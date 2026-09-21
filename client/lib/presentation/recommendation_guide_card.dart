import 'package:flutter/material.dart';

import '../core/theme.dart';
import '../domain/recommendation_guide.dart';
import '../domain/schedule_evaluation.dart';
import '../services/recommendation_api.dart';
import 'widgets.dart';

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
  });

  final RecommendationApi api;
  final String scheduleId;

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
    setState(() => _loading = true);
    try {
      final guide = await widget.api.getLatestGuide(widget.scheduleId);
      if (!mounted) return;
      setState(() {
        // 계산이 아직이면(409) null 이 온다. 이때는 지난 안내를 지우지 않는다.
        if (guide != null) {
          _guide = guide;
          _loadedFor = evaluationId;
        }
      });
    } on Object {
      // 안내를 못 받아도 화면의 다른 정보는 그대로 쓸 수 있다. 조용히 넘긴다.
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final guide = _guide;
    if (guide == null) return const SizedBox.shrink();
    return InfoCard(
      key: const Key('recommendationGuideCard'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 이 안내는 "지금 선택된 경로" 가 아니라 "가장 최근 계산 결과" 를 설명한다.
          // 바로 위 카드가 선택 경로라서, 말머리를 달지 않으면 같은 경로 이야기로 읽힌다.
          // 실제로 팀 안에서 그렇게 오해가 있었다(2026-09-22).
          Row(
            key: const Key('guideScopeLabel'),
            children: const [
              Icon(Icons.auto_awesome, size: 14, color: Color(0xFF52709B)),
              SizedBox(width: 5),
              Text(
                '방금 계산한 결과 기준',
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w800,
                  color: Color(0xFF52709B),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          if (guide.headline != null)
            Text(
              guide.headline!,
              key: const Key('guideHeadline'),
              style: const TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.w900,
                color: DdubiColors.navy,
              ),
            ),
          if (guide.routeSummary != null) ...[
            const SizedBox(height: 8),
            Text(
              guide.routeSummary!,
              key: const Key('guideRouteSummary'),
              style: const TextStyle(fontSize: 13, color: Color(0xFF52677B)),
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
                  color: Color(0xFF6E8193),
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
            const SizedBox(height: 12),
            for (final step in guide.steps) _stepRow(step),
          ],
          if (guide.delay.hasMessage) ...[
            const SizedBox(height: 12),
            _delayRow(guide.delay),
          ],
          if (guide.dataAsOf != null) ...[
            const SizedBox(height: 10),
            Text(
              '실시간 정보 기준 ${_clock(guide.dataAsOf!)}',
              key: const Key('guideDataAsOf'),
              style: const TextStyle(fontSize: 11, color: Color(0xFF8A99A8)),
            ),
          ],
        ],
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
