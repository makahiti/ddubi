import 'package:flutter/material.dart';

import '../domain/transit_reference.dart';

class TransitReferencePanel extends StatefulWidget {
  const TransitReferencePanel({super.key, this.repository});
  final TransitReferenceRepository? repository;

  @override
  State<TransitReferencePanel> createState() => _TransitReferencePanelState();
}

class _TransitReferencePanelState extends State<TransitReferencePanel> {
  TransitReferenceStatus? status;
  String? error;
  bool loading = false;
  bool requested = false;
  int generation = 0;

  @override
  void didUpdateWidget(covariant TransitReferencePanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.repository, widget.repository)) {
      generation++;
      status = null;
      error = null;
      loading = false;
      requested = false;
    }
  }

  Future<void> load() async {
    final repository = widget.repository;
    if (repository == null || loading) return;
    final request = ++generation;
    setState(() {
      requested = true;
      loading = true;
      status = null;
      error = null;
    });
    try {
      final result = await repository.transitReferenceStatus();
      if (mounted && request == generation) setState(() => status = result);
    } on TransitReferenceFailure catch (failure) {
      if (mounted && request == generation) {
        setState(() => error = failure.message);
      }
    } on Object {
      if (mounted && request == generation) {
        setState(() => error = '참고자료 상태를 확인할 수 없습니다. 다시 시도해 주세요.');
      }
    } finally {
      if (mounted && request == generation) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => Card(
    child: ExpansionTile(
      key: const Key('transitReferencePanel'),
      title: const Text('참고자료 준비 상태'),
      subtitle: const Text('서버 전체 자료 · 현재 경로 적용 여부와 별개'),
      onExpansionChanged: (expanded) {
        if (expanded && !requested) load();
      },
      childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      expandedCrossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          '자료를 보유하거나 조회를 설정한 것만으로 현재 경로에 반영된 것은 아닙니다. '
          '실제 적용 여부와 출발·도착 안내는 위 이동 예측 결과를 확인해 주세요.',
        ),
        const SizedBox(height: 12),
        if (widget.repository == null)
          const Text('현재 화면은 서버 참고자료 조회에 연결되지 않았습니다.'),
        if (loading) const LinearProgressIndicator(),
        if (error != null)
          Text(error!, key: const Key('transitReferenceError')),
        if (status case final value?) ...[
          _source('버스 과거 기록', value.bus),
          _source('지하철 예정 시간', value.subway, kind: 'subway'),
          _source('도로 연결 검토', value.road, kind: 'road'),
          const Text('참고자료는 실시간 도착정보나 운영 승인된 추천 결과가 아닙니다.'),
        ],
        if (widget.repository != null)
          TextButton(
            key: const Key('refreshTransitReference'),
            onPressed: loading ? null : load,
            child: const Text('준비 상태 다시 확인'),
          ),
      ],
    ),
  );

  Widget _source(String label, TransitReferenceState source, {String? kind}) =>
      Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('$label: ${_statusText(source.status)}'),
            for (final reason in source.limitations.map(_reasonText).toSet())
              Text('· $reason'),
            if (kind != null && source.status == 'STAGED_REFERENCE_AVAILABLE')
              for (final entry in source.sampleReferenceIds.indexed)
                TextButton(
                  key: ValueKey('reference-$kind-${entry.$1}'),
                  onPressed: () => _openDetail(kind, entry.$2),
                  child: Text('$label 보유자료 예시 ${entry.$1 + 1} 보기'),
                ),
          ],
        ),
      );

  void _openDetail(String kind, String referenceId) {
    final repository = widget.repository;
    if (repository == null) return;
    // IDs are taken only from the latest status response, not route names or app guesses.
    final future = repository.transitReferenceDetail(kind, referenceId);
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('보유 참고자료 예시', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 8),
              const Text('현재 일정·경로와 연결된 자료가 아닙니다. 추천이나 실시간 정보로 사용할 수 없습니다.'),
              const SizedBox(height: 16),
              FutureBuilder<TransitReferenceDetail>(
                future: future,
                builder: (context, snapshot) {
                  if (snapshot.hasError) {
                    return Text(
                      snapshot.error is TransitReferenceFailure
                          ? (snapshot.error as TransitReferenceFailure).message
                          : '참고자료 상세를 확인할 수 없습니다.',
                    );
                  }
                  if (!snapshot.hasData) return const LinearProgressIndicator();
                  return _detail(snapshot.data!);
                },
              ),
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('닫기'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _detail(TransitReferenceDetail detail) {
    final data = detail.data;
    final stops = data['stops'];
    final sourceType = switch (detail.recordType) {
      'SUBWAY_PATTERN' => '원천 예정 시간표의 운행 패턴',
      'SUBWAY_SEGMENT' => '예정 시간표 기반 구간 소요시간',
      'SUBWAY_HEADWAY' => '같은 운행 패턴의 예정 배차간격',
      'ROAD_OCCURRENCE' => '버스 원천 구간과 도로망 연결 검토',
      _ => '검토용 원천 자료',
    };
    final value = detail.recordType == 'SUBWAY_SEGMENT'
        ? data['scheduledMeanSeconds']
        : detail.recordType == 'SUBWAY_HEADWAY'
        ? data['scheduledMeanHeadwaySeconds']
        : null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(detail.label),
        Text('자료 종류: $sourceType'),
        const Text('자료 적용 기간: 현재 유효성 미확인'),
        if (stops is List) Text('이 원천 패턴의 정차 항목: ${stops.length}개'),
        if (value is num && value.isFinite && value > 0)
          Text(
            '예정 참고값: ${value.toStringAsFixed(1)}초 · 실제 관측 또는 현재 경로 예상시간이 아닙니다.',
          ),
        if (detail.sourceMasterVersion != null)
          Text('원천 기준자료 버전: ${detail.sourceMasterVersion}'),
        if (detail.sourceVersion != null)
          Text('참고자료 버전: ${detail.sourceVersion}'),
        for (final reason in detail.limitations.map(_reasonText).toSet())
          Text('· $reason'),
      ],
    );
  }

  String _statusText(String value) => switch (value) {
    'REFERENCE_NOT_CONFIGURED' => '서버 조회가 아직 설정되지 않았습니다.',
    'STAGED_REFERENCE_CONFIGURED' => '조회 설정됨 · 전체 적재와 경로 적용은 별도 확인이 필요합니다.',
    'STAGED_REFERENCE_NOT_APPROVED' => '검토용 자료 · 운영 적용 전입니다.',
    'STAGED_REFERENCE_AVAILABLE' => '검토용 자료 확인됨 · 현재 경로 적용을 뜻하지 않습니다.',
    'REFERENCE_PACKAGE_INVALID' => '참고자료 묶음 검증에 실패했습니다.',
    'REFERENCE_MISSING' => '조회할 참고자료가 없습니다.',
    'MASTER_VERSION_MISMATCH' => '기준 노선·정류장 자료의 버전이 맞지 않습니다.',
    'MASTER_DATA_NOT_CONFIGURED' => '기준 노선·정류장 자료가 설정되지 않았습니다.',
    'MASTER_LOOKUP_FAILED' => '기준 자료 상태를 확인하지 못했습니다.',
    _ => '준비 상태를 확인할 수 없습니다.',
  };

  String _reasonText(String value) => switch (value) {
    'WEATHER_NOT_JOINED' => '날씨 조건이 연결되지 않았습니다.',
    'SOURCE_AVAILABILITY_UNKNOWN' => '원천 자료의 가용 시각이 확인되지 않았습니다.',
    'MASTER_EFFECTIVE_DATE_UNVERIFIED' => '기준 노선·정류장 자료의 적용일이 검증되지 않았습니다.',
    'CONFIGURATION_DOES_NOT_PROVE_DATASET_UPLOAD_COMPLETE' =>
      '조회 설정과 전체 자료 적재 완료는 별도 확인이 필요합니다.',
    'CURRENT_TIMETABLE_VALIDITY_UNVERIFIED' => '현재 운행 시간표와의 일치 여부가 확인되지 않았습니다.',
    'PROVIDER_TRAIN_IDS_UNASSIGNED' => '실시간 열차와의 연결이 확인되지 않았습니다.',
    'PATTERN_SPECIFIC_HEADWAY_NOT_ALL_LINE_FREQUENCY' =>
      '배차 참고값은 같은 운행 패턴에만 해당합니다.',
    'NOT_REALTIME_OR_OBSERVED_DATA' => '실시간 또는 실제 운행 관측 자료가 아닙니다.',
    'REFERENCE_ID_IS_NOT_ROUTE_MAPPING' => '이 자료 식별자는 현재 경로와의 연결을 뜻하지 않습니다.',
    'NOT_APPROVED_FOR_RECOMMENDATION' => '운영 추천에 사용하도록 승인되지 않았습니다.',
    _ => '추가 데이터 검증이 필요합니다.',
  };
}
