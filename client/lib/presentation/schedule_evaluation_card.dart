import 'dart:async';
import 'package:flutter/material.dart';
import '../domain/schedule_evaluation.dart';

/// 서버 평가 상태를 보여준다. 임의 신뢰도·정시 도착 보장을 표시하지 않고,
/// 결과 시각/잠정 여부/실패를 구분한다. 수락과 실제 출발 확인은 사용자 동작에서만 보낸다.
class ScheduleEvaluationCard extends StatefulWidget {
  const ScheduleEvaluationCard({super.key, required this.repository, required this.scheduleId});
  final ScheduleEvaluationRepository repository;
  final String scheduleId;
  @override
  State<ScheduleEvaluationCard> createState() => _ScheduleEvaluationCardState();
}

class _ScheduleEvaluationCardState extends State<ScheduleEvaluationCard> {
  Map<String, dynamic>? data;
  String? error;
  bool busy = false;
  Timer? timer;

  @override
  void initState() {
    super.initState();
    _load();
    timer = Timer.periodic(const Duration(seconds: 30), (_) {
      if (!busy && data?['state'] != 'STOPPED') _load();
    });
  }

  @override
  void dispose() { timer?.cancel(); super.dispose(); }

  Future<void> _load() async {
    if (busy) return;
    setState(() => busy = true);
    try {
      final latest = await widget.repository.latestEvaluation(widget.scheduleId);
      if (mounted) setState(() { data = latest; error = null; });
    } catch (_) {
      if (mounted) setState(() => error = '예측을 불러오지 못했습니다. 다시 확인해 주세요.');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> _action(Future<void> Function() action) async {
    if (busy) return;
    setState(() => busy = true);
    try {
      await action();
      final latest = await widget.repository.latestEvaluation(widget.scheduleId);
      if (mounted) setState(() { data = latest; error = null; });
    } catch (_) {
      if (mounted) setState(() => error = '처리하지 못했습니다. 최신 예측을 다시 확인해 주세요.');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Map _map(dynamic value) => value is Map ? value : const {};
  String _time(dynamic value) {
    final instant = DateTime.tryParse(value?.toString() ?? '');
    if (instant == null) return '확인 불가';
    final kst = instant.toUtc().add(const Duration(hours: 9));
    return '${kst.month}/${kst.day} ${kst.hour.toString().padLeft(2, '0')}:${kst.minute.toString().padLeft(2, '0')}';
  }

  String _stepLabel(Map step) {
    final mode = switch (step['mode']) { 'BUS' => '버스', 'SUBWAY' => '지하철', 'WALK' => '도보', _ => '이동' };
    final vehicles = (step['vehicles'] as List? ?? const []).join(', ');
    return '$mode $vehicles · ${step['fromName']} → ${step['toName']}';
  }

  @override
  Widget build(BuildContext context) {
    final state = data?['state'];
    final result = _map(data?['result']);
    final preview = _map(result['preview']);
    final isPreview = preview.isNotEmpty;
    final selected = isPreview ? _map(preview['fastest']) : _map(result['selected']);
    final roadAlternative = _map(preview['roadAlternative']);
    final roadReasons = (selected['reasons'] as List? ?? const []).map((r) => r.toString());
    final suggestion = _map(result['suggestion']);
    final proposed = _map(suggestion['proposed']);
    final hasChange = proposed.isNotEmpty && (proposed['journeyKey'] != result['selectedKey'] ||
        proposed['departureAt'] != result['plannedDepartAt']);
    final candidates = result['candidates'] is List ? result['candidates'] as List : const [];
    final proposedInfo = candidates.map(_map).where((c) => c['key'] == proposed['journeyKey']);
    final routeSteps = proposedInfo.isEmpty ? const [] : (proposedInfo.first['steps'] as List? ?? const []);
    final label = switch (state) {
      'PENDING' => '예측을 준비하고 있습니다.',
      'STALE' => '갱신을 기다리고 있습니다. 아래 값은 이전 예측입니다.',
      'DEPARTURE_REQUIRED' => '일정 수정에서 출발 예정시각을 입력해 주세요.',
      'STOPPED' => '이 일정의 출발 전 감시가 종료됐습니다.',
      'FAILED' => '최신 예측을 계산하지 못했습니다. 잠시 후 다시 확인해 주세요.',
      'READY' => switch (selected['assessment']) {
        'ON_TIME_ESTIMATE' => '목표시간 이내 도착 예상',
        'LATE_ESTIMATE' => '목표시간보다 늦게 도착할 것으로 예상됩니다.',
        'BLOCKED' => '현재 경로에 이용 불가 구간이 있습니다.',
        _ => '도착 예측에 필요한 정보가 부족합니다.',
      },
      _ => '예측을 확인하고 있습니다.',
    };
    return Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('이동 예측', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8), Text(label),
        if (error != null) Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
        if (result.isNotEmpty) ...[
          if (isPreview) const Text('검증용 최소시간 후보 · 구간별 계산 방식은 아래 표시'),
          if (isPreview && selected.isEmpty) const Text('계산 가능하고 통제되지 않은 후보가 없습니다.'),
          Text('입력 출발: ${_time(result['plannedDepartAt'])}'),
          Text('예상 도착: ${_time(selected['estimatedArrivalAt'])}'),
          Text('계산: ${_time(result['checkedAt'])}'),
          const Text('배차와 실제 도착은 달라질 수 있습니다.'),
          if (selected['tentative'] == true) const Text('잠정 예측입니다. 배차와 모델 정확도 검증이 필요합니다.'),
          if (roadReasons.any((r) => r.startsWith('ROAD_POTENTIAL_IMPACT:')))
            const Text('예상 통과 구간에 사고·통제 영향이 있을 수 있습니다.'),
          if (roadReasons.any((r) => r.startsWith('ROAD_') && !r.startsWith('ROAD_POTENTIAL_IMPACT:')))
            const Text('일부 도로의 영향 여부는 확인되지 않았습니다.'),
          if (roadAlternative.isNotEmpty)
            Text('도로 영향 검토용 대체 후보 · 예상 도착 ${_time(roadAlternative['estimatedArrivalAt'])}'),
          if (isPreview) ...[
            for (final candidate in (preview['candidates'] as List? ?? const []))
              Text('${_map(_map(candidate)['result'])['journeyKey']}: 버스 ML ${_map(candidate)['mlSteps']}개, 지하철 시간표·실시간 ${_map(candidate)['subwaySteps'] ?? 0}개, API ${_map(candidate)['apiSteps']}개'),
            // 지하철 별도 계산이 있어도 전체 시간에 미반영된 상태를 적용 성공으로 표시하지 않는다.
            for (final value in (preview['subwayPredictions'] as List? ?? const []))
              Text(_subwayLabel(_map(value))),
            for (final value in (preview['waitPredictions'] as List? ?? const []))
              Text(_waitLabel(_map(value))),
          ],
          if (hasChange || isPreview && proposed.isNotEmpty) ...[
            const Divider(),
            Text('제안 출발: ${_time(proposed['departureAt'])}'),
            Text('제안 도착: ${_time(proposed['estimatedArrivalAt'])}'),
            for (final value in routeSteps) Text(_stepLabel(_map(value))),
            if (!isPreview) FilledButton(onPressed: busy || error != null || state != 'READY' ? null : () => _action(() =>
                widget.repository.acceptEvaluation(widget.scheduleId, result['evaluationId'] as String)),
                child: const Text('이 제안 적용')),
          ],
        ],
        if (busy) const LinearProgressIndicator(),
        if (state != 'STOPPED' && state != 'DEPARTURE_REQUIRED') Wrap(spacing: 8, children: [
          OutlinedButton(onPressed: busy ? null : () => _action(() => widget.repository.refreshEvaluation(widget.scheduleId)),
              child: const Text('다시 계산')),
          TextButton(onPressed: busy ? null : () => _action(() => widget.repository.confirmDeparture(widget.scheduleId)),
              child: const Text('출발했어요')),
        ]),
      ],
    )));
  }

  String _subwayLabel(Map value) {
    final estimate = _map(value['estimate']);
    final prefix = '${value['candidateKey']} 지하철';
    if (estimate['arrivalAt'] == null) return '$prefix: 시간표·도착 정보 부족으로 API 시간 사용';
    if (value['applied'] != true) return '$prefix: 대기·환승 시간 확인이 필요해 API 시간 유지';
    final source = estimate['source'] == 'SUBWAY_REALTIME_ARRIVAL_AND_TIMETABLE'
        ? '실시간 도착 + 시간표' : '시간표';
    return '$prefix: $source · 탑승 예상 ${_time(estimate['boardingAt'])} · 하차 예상 ${_time(estimate['arrivalAt'])}';
  }

  /// 결측은 0분으로 표시하지 않는다. 계산했지만 시간 중복 방지로 미반영된 대기도 구별한다.
  String _waitLabel(Map value) {
    final mode = value['mode'] == 'BUS' ? '버스' : '지하철';
    final prefix = '${value['candidateKey']} ${value['stepKey']} $mode 대기';
    final seconds = value['seconds'];
    if (seconds is! num) return '$prefix: 다음 차량 정보 부족';
    final duration = '${seconds ~/ 60}분 ${seconds.toInt() % 60}초';
    final source = value['source'] == 'SUBWAY_TIMETABLE' ? '시간표 기준' : '실시간 도착예측 기준';
    final applied = value['applied'] == true ? '전체 시간에 반영' : '전체 시간에는 미반영';
    return '$prefix: $duration · $source · $applied';
  }
}
