import 'package:flutter/material.dart';

import '../core/theme.dart';
import '../domain/recommendation_guide.dart';
import '../domain/schedule_evaluation.dart';
import '../services/recommendation_api.dart';

/// 추천 카드 안내.
///
/// 서버가 계산한 사실을 짧은 한국어로 바꿔 보여준다. 숫자와 순서는 모두 서버 값이고
/// 앱은 계산하지 않는다.
///
/// <h3>언제 다시 부르나</h3>
/// 평가가 READY 이고 `evaluationId` 가 바뀌었을 때만 부른다. 화면이 다시 그려질 때마다
/// 부르면 서버가 매번 문장을 새로 만들게 되므로, 같은 계산에는 한 번만 부른다.
class RecommendationGuideCard extends StatefulWidget {
  const RecommendationGuideCard({
    super.key,
    required this.api,
    required this.scheduleId,
    required this.headline,
    this.evaluationRepository,
  });

  final RecommendationApi api;
  final String scheduleId;

  /// 버튼 줄(이 제안 적용·다시 계산·출발했어요)을 쓰려면 필요하다. 없으면 버튼을 그리지 않는다.
  ///
  /// 예전에는 이 버튼들이 "이동 예측" 카드에 있었다. 결론을 읽은 자리에서 바로 누를 수
  /// 있어야 해서 안내 카드로 옮겼다(2026-09-22).
  final ScheduleEvaluationRepository? evaluationRepository;

  /// 평가 결과를 나르는 통로. 여기서 상태와 evaluationId 를 본다.
  final ValueNotifier<ScheduleEvaluationV2?> headline;

  @override
  State<RecommendationGuideCard> createState() =>
      _RecommendationGuideCardState();
}

class _RecommendationGuideCardState extends State<RecommendationGuideCard> {
  RecommendationGuide? _guide;

  /// 마지막으로 불러온 계산의 id. 같은 값이면 다시 부르지 않는다.
  String? _loadedFor;

  /// 조회에 실패한 계산의 id. 값이 있으면 "다시 불러오기" 를 띄운다.
  String? _retryFor;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    widget.headline.addListener(_onEvaluation);
    _onEvaluation();
  }

  @override
  void dispose() {
    widget.headline.removeListener(_onEvaluation);
    super.dispose();
  }

  void _onEvaluation() {
    final value = widget.headline.value;
    if (value == null || value.state != EvaluationState.ready) return;
    final id = value.evaluationId;
    if (id == null || id.isEmpty || id == _loadedFor || _loading) return;
    _load(id);
  }

  Future<void> _load(String evaluationId) async {
    // 계산이 바뀐 순간 지난 안내를 내린다.
    //
    // 예전에는 조회가 실패해도 _guide 를 그대로 뒀다. 그 결과 새 계산 화면에 지난
    // 계산의 경로·출발시각·요금이 계속 보였고, 사용자는 그게 방금 결과라고 믿었다
    // (BE 지적, 2026-09-22). 빈 카드가 틀린 카드보다 낫다.
    setState(() {
      _loading = true;
      _guide = null;
      _loadedFor = null;
    });
    try {
      final guide = await widget.api.getLatestGuide(widget.scheduleId);
      if (!mounted) return;
      setState(() {
        if (guide != null) {
          _guide = guide;
          _loadedFor = evaluationId;
          _retryFor = null;
        } else {
          // 계산이 아직이면(409) null 이 온다. 곧 다시 부를 수 있게 열어 둔다.
          _retryFor = evaluationId;
        }
      });
    } on Object {
      // 안내를 못 받아도 화면의 다른 정보는 그대로 쓸 수 있다. 오류 화면은 띄우지 않는다.
      if (mounted) setState(() => _retryFor = evaluationId);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  /// 다시 불러본다. 실패했던 계산에 대해서만 동작한다.
  ///
  /// 안내는 화면의 곁가지라 자동으로 계속 조르지 않는다. 사용자가 원할 때만 부른다.
  Future<void> _retry() async {
    final id = _retryFor;
    if (id == null || _loading) return;
    await _load(id);
  }

  @override
  Widget build(BuildContext context) {
    final guide = _guide;
    return Container(
      key: const Key('recommendationGuideCard'),
      padding: const EdgeInsets.fromLTRB(14, 14, 14, 14),
      decoration: BoxDecoration(
        // 예전 "이 일정에 맞춰" 카드의 모양을 그대로 쓴다. 두 카드가 나란히 있으면서
        // 하나는 비어 있고 하나는 흰 카드라 따로 놀았다. 한 장으로 합친다.
        gradient: const LinearGradient(
          colors: [Color(0xFFE1FAF5), Color(0xFFF1FBE8)],
        ),
        borderRadius: BorderRadius.circular(19),
        border: Border.all(color: const Color(0xFFC7EEDC)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Image.asset(
                'assets/images/ttubee/running.png',
                width: 62,
                height: 56,
                fit: BoxFit.contain,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // 이 안내는 "지금 선택된 경로" 가 아니라 "가장 최근 계산 결과" 를 설명한다.
                    // 말머리를 달지 않으면 바로 위 경로 카드와 같은 이야기로 읽힌다.
                    Container(
                      key: const Key('guideScopeLabel'),
                      padding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 4,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.white,
                        borderRadius: BorderRadius.circular(14),
                      ),
                      child: const Text(
                        '이 일정에 맞춰',
                        style: TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.w800,
                          color: Color(0xFF2F4A7D),
                        ),
                      ),
                    ),
                    const SizedBox(height: 8),
                    ..._summary(guide),
                  ],
                ),
              ),
            ],
          ),
          if (guide != null) ...[const SizedBox(height: 12), _detailBox(guide)],
          if (widget.evaluationRepository != null) ...[
            const SizedBox(height: 12),
            const Divider(height: 1, color: Color(0xFFC7EEDC)),
            const SizedBox(height: 12),
            _GuideActions(
              repository: widget.evaluationRepository!,
              scheduleId: widget.scheduleId,
              headline: widget.headline,
            ),
          ],
        ],
      ),
    );
  }

  /// 카드 위쪽 한 문단. 안내가 없어도 카드를 비우지 않는다.
  ///
  /// 예전에는 안내를 못 받으면 카드가 통째로 사라졌다. 이제 같은 카드에 버튼이 함께
  /// 있어서, 사라지면 누를 자리까지 없어진다.
  List<Widget> _summary(RecommendationGuide? guide) {
    if (guide == null) {
      return [
        Text(
          _loading ? '추천 안내를 불러오고 있어요.' : '추천 안내를 아직 불러오지 못했어요.',
          style: const TextStyle(
            fontSize: 15,
            height: 1.35,
            fontWeight: FontWeight.w900,
            color: DdubiColors.navy,
          ),
        ),
        if (!_loading && _retryFor != null) ...[
          const SizedBox(height: 6),
          TextButton(
            key: const Key('guideRetryButton'),
            onPressed: _retry,
            style: TextButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              minimumSize: const Size(0, 36),
              backgroundColor: Colors.white,
              foregroundColor: const Color(0xFF00665F),
            ),
            child: const Text('다시 불러오기'),
          ),
        ],
      ];
    }
    return [
      if (_changeLabel(guide.changeType) != null) ...[
        _changeBadge(guide.changeType!),
        const SizedBox(height: 8),
      ],
      // 사유가 없으면 지어내지 않는다. 서버가 준 결론(headline)을 대신 크게 쓰고,
      // 아래 상자에서는 같은 문장을 되풀이하지 않는다.
      Text(
        guide.recommendationReason ?? guide.headline ?? '최신 계산 결과예요.',
        key: guide.recommendationReason != null
            ? const Key('guideRecommendationReason')
            : const Key('guideFallbackLine'),
        style: const TextStyle(
          fontSize: 16,
          height: 1.35,
          fontWeight: FontWeight.w900,
          color: DdubiColors.navy,
        ),
      ),
    ];
  }

  /// 카드 안쪽 흰 상자. 경로·시각·요금·구간·지연을 담는다.
  Widget _detailBox(RecommendationGuide guide) => Container(
    padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: 0.72),
      borderRadius: BorderRadius.circular(14),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (guide.routeSummary != null)
          Text(
            guide.routeSummary!,
            key: const Key('guideRouteSummary'),
            style: const TextStyle(
              fontSize: 13,
              height: 1.45,
              color: Color(0xFF344054),
            ),
          ),
        if (guide.headline != null && guide.recommendationReason != null) ...[
          const SizedBox(height: 10),
          Text(
            guide.headline!,
            key: const Key('guideHeadline'),
            style: const TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w900,
              color: DdubiColors.navy,
            ),
          ),
        ],
        if (guide.minimumFareWon != null) ...[
          const SizedBox(height: 8),
          Row(
            key: const Key('guideFare'),
            children: [
              const Icon(
                Icons.payments_outlined,
                size: 16,
                color: Color(0xFF5C6B7F),
              ),
              const SizedBox(width: 6),
              Text(
                '예상 요금 ${_fare(guide.minimumFareWon!, guide.maximumFareWon)}',
                style: const TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w700,
                  color: DdubiColors.navy,
                ),
              ),
            ],
          ),
        ],
        if (guide.steps.isNotEmpty) ...[
          const SizedBox(height: 10),
          for (final step in guide.steps) _stepRow(step),
        ],
        if (guide.delay.hasMessage) ...[
          const SizedBox(height: 10),
          _delayRow(guide.delay),
        ],
        if (guide.dataAsOf != null) ...[
          const SizedBox(height: 10),
          Text(
            '실시간 정보 기준 ${_clock(guide.dataAsOf!)}',
            key: const Key('guideDataAsOf'),
            style: const TextStyle(fontSize: 11, color: Color(0xFF6B7A8C)),
          ),
        ],
      ],
    ),
  );

  /// 서버가 확정한 변경 유형을 사람 말로 바꾼다.
  ///
  /// 모르는 값이면 null 을 돌려주고 아무것도 그리지 않는다. 서버가 나중에 유형을
  /// 늘렸을 때 앱이 영어 코드를 그대로 보여주는 일이 없어야 한다.
  static String? _changeLabel(String? changeType) => switch (changeType) {
    'ROUTE_CHANGED' => '다른 경로 제안',
    'DEPARTURE_TIME_CHANGED' => '출발시각 변경 제안',
    'ETA_UPDATED' => '도착 예상 변경',
    'NO_CHANGE' => '지금 경로 유지',
    _ => null,
  };

  /// 유형별 색. 바꾸라는 제안은 눈에 띄게, 유지는 차분하게.
  static (Color, Color) _changeColors(String changeType) =>
      switch (changeType) {
        'ROUTE_CHANGED' || 'DEPARTURE_TIME_CHANGED' => (
          const Color(0xFFFFF3E6),
          const Color(0xFFB45309),
        ),
        'NO_CHANGE' => (const Color(0xFFE9F7EF), const Color(0xFF13703F)),
        _ => (const Color(0xFFEEF3FA), const Color(0xFF3A5A87)),
      };

  Widget _changeBadge(String changeType) {
    final (background, foreground) = _changeColors(changeType);
    return Container(
      key: const Key('guideChangeType'),
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        _changeLabel(changeType)!,
        style: TextStyle(
          fontSize: 11.5,
          fontWeight: FontWeight.w800,
          color: foreground,
        ),
      ),
    );
  }

  Widget _stepRow(GuideStep step) => Padding(
    padding: const EdgeInsets.only(bottom: 6),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(_stepIcon(step.mode), size: 17, color: _stepTone(step.mode)),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            _stepText(step),
            style: const TextStyle(fontSize: 13, color: DdubiColors.navy),
          ),
        ),
        if (step.durationSeconds != null)
          Text(
            '약 ${(step.durationSeconds! / 60).ceil()}분',
            style: const TextStyle(fontSize: 12, color: Color(0xFF6E8193)),
          ),
      ],
    ),
  );

  /// 지연 안내. 확인된 이유가 있을 때만 그린다 — 분 단위 숫자가 없으면 만들어내지 않는다.
  Widget _delayRow(GuideDelay delay) {
    final tone = _delayTone(delay.status);
    return Container(
      key: const Key('guideDelay'),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: tone.withValues(alpha: .10),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.warning_amber_rounded, size: 17, color: tone),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  delay.reason!,
                  style: TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w700,
                    color: tone,
                  ),
                ),
                if (delay.source != null)
                  Text(
                    delay.source!,
                    style: const TextStyle(
                      fontSize: 11,
                      color: Color(0xFF8A99A8),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  static String _stepText(GuideStep step) {
    if (step.instruction != null) return step.instruction!;
    final label = _modeLabel(step.mode);
    final route = step.routeLabel == null ? label : '$label ${step.routeLabel}';
    final from = step.fromName ?? '';
    final to = step.toName ?? '';
    if (from.isEmpty && to.isEmpty) return route;
    return '$route  $from → $to';
  }

  static String _modeLabel(String mode) => switch (mode) {
    'WALK' => '도보',
    'BUS' => '버스',
    'SUBWAY' => '지하철',
    'TAXI' => '택시',
    _ => mode,
  };

  static IconData _stepIcon(String mode) => switch (mode) {
    'WALK' => Icons.directions_walk_rounded,
    'BUS' => Icons.directions_bus_rounded,
    'SUBWAY' => Icons.directions_subway_rounded,
    _ => Icons.place_outlined,
  };

  static Color _stepTone(String mode) => switch (mode) {
    'WALK' => const Color(0xFF7F91A2),
    'BUS' => DdubiColors.green,
    'SUBWAY' => DdubiColors.blue,
    _ => DdubiColors.navy,
  };

  static Color _delayTone(String status) => switch (status) {
    'POSSIBLE' => const Color(0xFFE0A100),
    'DELAYED' => const Color(0xFFFF6B35),
    'DISRUPTED' => DdubiColors.red,
    _ => const Color(0xFF7F91A2),
  };

  static String _clock(DateTime value) {
    final local = value.toLocal();
    return '${local.hour.toString().padLeft(2, '0')}:'
        '${local.minute.toString().padLeft(2, '0')}';
  }

  /// 단일 요금과 범위 요금을 구분한다. 최대값이 없으면 확인된 최소값만 표시한다.
  static String _fare(int minimum, int? maximum) {
    final value = maximum == null || maximum == minimum
        ? _number(minimum)
        : '${_number(minimum)}~${_number(maximum)}';
    return '$value원';
  }

  static String _number(int value) => value.toString().replaceAllMapped(
    RegExp(r'\B(?=(\d{3})+(?!\d))'),
    (_) => ',',
  );
}

/// 안내 카드 아래쪽 버튼 줄.
///
/// 예전에는 "이동 예측" 카드에 있었다. 사용자가 결론을 읽는 자리와 누르는 자리가 멀어서
/// 안내 카드 안으로 옮겼다(2026-09-22). 이동 예측 카드는 나중에 없앨 예정이다.
///
/// 계산 상태는 [headline] 이 나르는 평가 결과로 판단한다. 따로 조회하지 않는다 —
/// 같은 값을 두 곳에서 읽으면 화면 안에서 서로 다른 상태가 보인다.
class _GuideActions extends StatefulWidget {
  const _GuideActions({
    required this.repository,
    required this.scheduleId,
    required this.headline,
  });

  final ScheduleEvaluationRepository repository;
  final String scheduleId;
  final ValueNotifier<ScheduleEvaluationV2?> headline;

  @override
  State<_GuideActions> createState() => _GuideActionsState();
}

class _GuideActionsState extends State<_GuideActions> {
  bool _busy = false;
  String? _notice;

  Future<void> _run(Future<void> Function() action, {String? done}) async {
    setState(() {
      _busy = true;
      _notice = null;
    });
    try {
      await action();
      if (!mounted) return;
      setState(() => _notice = done);
    } on Object {
      if (!mounted) return;
      setState(() => _notice = '요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(
    BuildContext context,
  ) => ValueListenableBuilder<ScheduleEvaluationV2?>(
    valueListenable: widget.headline,
    builder: (context, evaluation, _) {
      // 계산이 끝나기 전에는 제안을 적용할 수 없다. 끝난 안내·입력 대기 상태에서는
      // 다시 계산과 출발 보고도 의미가 없다.
      final active =
          evaluation != null &&
          evaluation.state != EvaluationState.stopped &&
          evaluation.state != EvaluationState.departureRequired;
      final computing =
          evaluation?.state == EvaluationState.pending ||
          evaluation?.state == EvaluationState.running;
      final evaluationId = evaluation?.evaluationId;
      final canAccept =
          evaluation != null &&
          evaluation.proposedDepartAt != null &&
          evaluation.canAcceptAt(DateTime.now()) &&
          evaluationId != null &&
          evaluationId.isNotEmpty;

      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (evaluation?.proposedDepartAt != null)
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                key: const Key('acceptEvaluationButton'),
                onPressed: _busy || !canAccept
                    ? null
                    : () => _run(
                        () => widget.repository.acceptEvaluation(
                          widget.scheduleId,
                          evaluationId,
                        ),
                        done: '제안한 출발 시각을 적용했어요.',
                      ),
                icon: const Icon(Icons.check_rounded, size: 18),
                label: const Text('이 제안 적용'),
              ),
            ),
          if (active) ...[
            if (evaluation.proposedDepartAt != null) const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    key: const Key('refreshEvaluationButton'),
                    onPressed: _busy || computing
                        ? null
                        : () => _run(
                            () => widget.repository.refreshEvaluation(
                              widget.scheduleId,
                            ),
                          ),
                    style: OutlinedButton.styleFrom(
                      backgroundColor: Colors.white.withValues(alpha: 0.85),
                    ),
                    icon: const Icon(Icons.refresh_rounded, size: 18),
                    label: const Text('다시 계산'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: OutlinedButton.icon(
                    key: const Key('confirmDepartureButton'),
                    onPressed: _busy
                        ? null
                        : () => _run(
                            () => widget.repository.confirmDeparture(
                              widget.scheduleId,
                            ),
                            done: '출발을 기록했어요.',
                          ),
                    style: OutlinedButton.styleFrom(
                      backgroundColor: Colors.white.withValues(alpha: 0.85),
                      foregroundColor: const Color(0xFF2F4A7D),
                      side: const BorderSide(color: Color(0xFFB9CBE8)),
                    ),
                    icon: const Icon(Icons.directions_run_rounded, size: 18),
                    label: const Text('출발했어요'),
                  ),
                ),
              ],
            ),
          ],
          if (computing && !_busy) ...[
            const SizedBox(height: 6),
            const Text(
              '지금 계산 중이라 끝난 뒤에 다시 계산할 수 있어요.',
              key: Key('guideRefreshLocked'),
              style: TextStyle(fontSize: 12, color: Color(0xFF5C6B7F)),
            ),
          ],
          if (_notice != null) ...[
            const SizedBox(height: 6),
            Text(
              _notice!,
              key: const Key('guideActionNotice'),
              style: const TextStyle(fontSize: 12, color: Color(0xFF2F4A7D)),
            ),
          ],
        ],
      );
    },
  );
}
