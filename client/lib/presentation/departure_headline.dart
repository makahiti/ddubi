import 'package:flutter/material.dart';

import '../core/theme.dart';
import '../domain/schedule_evaluation.dart';
import 'schedule_evaluation_card.dart';

/// 결론 한 줄의 성격. 색과 아이콘만 바꾸며 판단 기준은 [DepartureHeadlineContent] 에 있다.
enum DepartureHeadlineTone {
  /// 적용 가능한 확정 추천.
  ready,

  /// 과거 평균으로 계산한 참고값. 적용 대상이 아니다.
  reference,

  /// 아직 계산 중이거나 결과를 기다리는 중.
  waiting,

  /// 사용자가 무언가 확인하거나 입력해야 한다.
  attention,

  /// 이미 출발했다. 출발할 때 본 결과를 그대로 들고 있다.
  departed,

  /// 이대로면 일정 시작보다 늦는다. 여유가 아니라 부족을 말한다.
  late,
}

/// 일정 상세 맨 위와 홈에 함께 쓰는 "몇 시에 나가면 몇 시 도착" 한 줄.
///
/// 확정 추천과 과거 평균 참고값을 절대 같은 문구로 합치지 않는다.
/// 확정 조건([ScheduleEvaluationV2.canRecommendAt])을 통과하지 못하면
/// 제안 시각을 결론처럼 크게 쓰지 않는다.
class DepartureHeadlineContent {
  const DepartureHeadlineContent({
    required this.title,
    required this.tone,
    this.detail,
    this.departAt,
    this.arriveAt,
    this.spareMinutes,
    this.label = '추천 출발 시각',
    this.clockSuffix = '에 나가세요',
  });

  final String title;
  final String? detail;
  final DepartureHeadlineTone tone;

  /// 강조 모양에서 큰 숫자로 쓸 값들. 문장만으로는 칸을 나눌 수 없어 따로 담는다.
  /// 계산이 확정되지 않은 상태에서는 셋 다 null 이고, 그때는 문장만 보여준다.
  final DateTime? departAt;
  final DateTime? arriveAt;

  /// 일정 시작까지 남는 분. 늦으면 음수. 모르면 null.
  final int? spareMinutes;

  /// 강조 모양의 말머리. 출발 전에는 "추천 출발 시각", 출발 뒤에는 "출발 시각" 이다 —
  /// 이미 나간 사람에게 "나가세요" 라고 권하지 않는다.
  final String label;

  /// 큰 시각 뒤에 붙는 말.
  final String clockSuffix;

  /// [departedAt] 은 서버가 기록한 실제 출발 시각이다. 출발 뒤 화면에서 큰 숫자로 쓴다.
  static DepartureHeadlineContent of(
    ScheduleEvaluationV2? evaluation,
    DateTime now, {
    DateTime? departedAt,
  }) {
    if (evaluation == null) {
      return const DepartureHeadlineContent(
        title: '추천 출발 시간을 계산하고 있어요.',
        tone: DepartureHeadlineTone.waiting,
      );
    }
    switch (evaluation.state) {
      case EvaluationState.stopped:
        return _departed(evaluation, departedAt);
      case EvaluationState.departureRequired:
        return const DepartureHeadlineContent(
          title: '출발 예정시각을 입력해 주세요.',
          detail: '일정 수정에서 입력하면 추천 출발 시간을 계산해요.',
          tone: DepartureHeadlineTone.attention,
        );
      case EvaluationState.pending:
      case EvaluationState.running:
        return const DepartureHeadlineContent(
          title: '추천 출발 시간을 계산하고 있어요.',
          tone: DepartureHeadlineTone.waiting,
        );
      case EvaluationState.failed:
        return const DepartureHeadlineContent(
          title: '이번에는 추천 출발 시간을 계산하지 못했어요.',
          detail: '아래에서 다시 계산할 수 있어요.',
          tone: DepartureHeadlineTone.attention,
        );
      case EvaluationState.ready:
      case EvaluationState.stale:
      case EvaluationState.unknown:
        break;
    }
    if (evaluation.state == EvaluationState.stale) {
      return const DepartureHeadlineContent(
        title: '이전에 계산한 결과예요.',
        detail: '최신 결과를 다시 확인해 주세요.',
        tone: DepartureHeadlineTone.attention,
      );
    }
    final depart = evaluation.proposedDepartAt;
    final arrival = evaluation.estimatedArrivalAt;
    final computedAt = evaluation.computedAt;
    // 유효시간(약 1분)만 지난 결과는 고장이 아니다. 계산 당시 확정 가능했던 결론이면 조용히 그대로 보여준다.
    // 계산 당시에도 확정하지 못했던 결과는 아래 일반 규칙으로 내려가 이유를 보여준다.
    if (evaluation.isExpiredAt(now) &&
        computedAt != null &&
        depart != null &&
        arrival != null &&
        evaluation.canRecommendAt(computedAt)) {
      return DepartureHeadlineContent(
        title: '${formatClock(depart)}에 나가면 ${formatClock(arrival)} 도착',
        detail:
            '${formatClock(computedAt)}에 확인한 결과예요. 새 정보가 들어오면 자동으로 다시 계산해요.',
        tone: DepartureHeadlineTone.waiting,
        departAt: depart,
        arriveAt: arrival,
        spareMinutes: _spareMinutes(evaluation, arrival),
      );
    }
    // 출발 시각이 지났는데 아직 출발을 알리지 않았다. "나가세요" 를 그대로 두면 이미 지난 시각을
    // 권하는 꼴이다. 서버는 새 정보가 들어오면 지금 기준으로 다시 계산한다.
    if (evaluation.departureMissed && depart != null && arrival != null) {
      return DepartureHeadlineContent(
        title: '${formatClock(depart)} 출발 시각이 지났어요.',
        detail:
            '아래에서 다시 계산하면 지금 기준 출발 시각을 알려드려요. 이미 나섰다면 이동 안내에서 출발했어요를 눌러 주세요.',
        tone: DepartureHeadlineTone.attention,
        departAt: depart,
        arriveAt: arrival,
        spareMinutes: _spareMinutes(evaluation, arrival),
        label: '출발 시각이 지났어요',
        clockSuffix: '이 지났어요',
      );
    }
    if (evaluation.canAcceptAt(now) && depart != null && arrival != null) {
      final spare = _spareMinutes(evaluation, arrival);
      if (spare != null && spare < 0) return _late(depart, arrival, spare);
      final timing = _timing(now, depart);
      return DepartureHeadlineContent(
        title: timing.decorate(
          '${formatClock(depart)}에 나가면 ${formatClock(arrival)} 도착',
        ),
        detail: _marginDetail(evaluation, arrival),
        tone: DepartureHeadlineTone.ready,
        departAt: depart,
        arriveAt: arrival,
        spareMinutes: spare,
        label: timing.label,
        clockSuffix: timing.clockSuffix,
      );
    }
    if (evaluation.canShowHistoricalEstimateAt(now) &&
        depart != null &&
        arrival != null) {
      return DepartureHeadlineContent(
        title: '참고로는 ${formatClock(depart)} 출발이에요.',
        detail: '과거 평균으로 계산한 참고값이라 일정에 적용할 수 없어요.',
        tone: DepartureHeadlineTone.reference,
      );
    }
    // 확정은 아니지만 서버가 보여도 된다고 한 결과: 시각을 그대로 보여준다.
    // 색(파랑)과 문구로 확정과 구분한다. 적용 버튼은 서버 판단이 잠근다.
    if (evaluation.canDisplayEstimateAt(now) &&
        depart != null &&
        arrival != null) {
      final spare = _spareMinutes(evaluation, arrival);
      // 참고값이라도 늦는다는 사실은 같은 무게로 말한다. 파란 참고색 뒤에 숨기면 놓친다.
      if (spare != null && spare < 0) return _late(depart, arrival, spare);
      final reason = evaluationShortfallReason(evaluation);
      final timing = _timing(now, depart);
      return DepartureHeadlineContent(
        departAt: depart,
        arriveAt: arrival,
        spareMinutes: spare,
        title: timing.decorate(
          '${formatClock(depart)}에 나가면 ${formatClock(arrival)} 도착 예상',
        ),
        detail: evaluation.guidance.isProviderEstimate
            ? '기본 경로 기준 예상이에요. 실시간 정보가 들어오면 더 정확해져요.'
            : reason == null
            ? '아직 확정 전 참고값이에요.'
            : '아직 확정 전 참고값이에요. $reason',
        tone: DepartureHeadlineTone.reference,
        label: timing.label,
        clockSuffix: timing.clockSuffix,
      );
    }
    return DepartureHeadlineContent(
      title: '아직 출발 시각을 확정하지 못했어요.',
      detail: _shortfallDetail(evaluation),
      tone: DepartureHeadlineTone.attention,
    );
  }

  /// 출발까지 남은 시간에 따라 말을 바꾼다.
  ///
  /// 출발이 몇 시간 뒤여도 "08:10에 나가세요" 라고만 쓰면 당장 나가라는 말처럼 읽힌다
  /// (2026-09-22 제보). 시각은 그대로 두고 말머리와 꼬리만 바꾼다.
  ///
  /// ```
  /// 1시간 넘게 남음   추천 출발 시각 · 아직 여유 있어요   HH:MM 에 나가면 돼요   · 아직 N시간 M분 남았어요
  /// 10분~1시간       추천 출발 시각                    HH:MM 에 나가세요
  /// 10분 안          곧 출발이에요                     HH:MM 에 나가세요       · N분 뒤 나가야 해요
  /// 지남             출발 시각이 지났어요               HH:MM 이 지났어요       · N분 지났어요
  /// ```
  @visibleForTesting
  static DepartureTiming timing(DateTime now, DateTime depart) =>
      _timing(now, depart);

  static DepartureTiming _timing(DateTime now, DateTime depart) {
    final left = depart.difference(now);
    final minutes = left.inMinutes;
    if (minutes > 60) {
      return DepartureTiming(
        label: '추천 출발 시각 · 아직 여유 있어요',
        clockSuffix: '에 나가면 돼요',
        tail: '아직 ${_leftLabel(left)} 남았어요',
      );
    }
    if (minutes > 10) {
      return const DepartureTiming(label: '추천 출발 시각', clockSuffix: '에 나가세요');
    }
    if (minutes >= 0) {
      return DepartureTiming(
        label: '곧 출발이에요',
        clockSuffix: '에 나가세요',
        tail: minutes <= 1 ? '지금 나가야 해요' : '$minutes분 뒤 나가야 해요',
      );
    }
    return DepartureTiming(
      label: '출발 시각이 지났어요',
      clockSuffix: '이 지났어요',
      tail: '${-minutes}분 지났어요',
    );
  }

  /// "2시간 15분" / "45분" 처럼. 시간이 0이면 시간을 쓰지 않는다.
  static String _leftLabel(Duration left) {
    final hours = left.inHours;
    final minutes = left.inMinutes % 60;
    if (hours == 0) return '$minutes분';
    return minutes == 0 ? '$hours시간' : '$hours시간 $minutes분';
  }

  /// 출발한 뒤의 결론.
  ///
  /// 예전에는 "이 일정의 출발 안내가 끝났어요." 한 줄이었다. 서버가 멈춘 일정에는 상태 한 단어만
  /// 돌려줘서 쓸 수 있는 값이 없었기 때문이다. 이제 서버가 마지막 계산 결과를 함께 주므로
  /// (2026-09-22), 출발 시각·도착 예정·여유를 그대로 보여준다. 결과가 없는 예전 일정에는 옛 문구가
  /// 그대로 남는다.
  ///
  /// 출발 시각은 서버가 기록한 실제 출발([departedAt])이 먼저다. 그것이 없으면 제안 시각을 쓴다.
  static DepartureHeadlineContent _departed(
    ScheduleEvaluationV2 evaluation,
    DateTime? departedAt,
  ) {
    final arrival = evaluation.estimatedArrivalAt;
    final depart =
        departedAt ?? evaluation.actualDepartAt ?? evaluation.proposedDepartAt;
    if (arrival == null || depart == null) {
      return const DepartureHeadlineContent(
        title: '이 일정의 출발 안내가 끝났어요.',
        tone: DepartureHeadlineTone.waiting,
      );
    }
    final spare = _spareMinutes(evaluation, arrival);
    return DepartureHeadlineContent(
      title: '${formatClock(depart)}에 출발해 ${formatClock(arrival)} 도착 예정',
      detail: spare != null && spare < 0
          ? '출발할 때 계산으로는 일정 시작보다 ${-spare}분 늦어요.'
          : '출발할 때 계산한 결과예요. 도착하면 아래 "도착 완료" 로 알려 주세요.',
      tone: spare != null && spare < 0
          ? DepartureHeadlineTone.late
          : DepartureHeadlineTone.departed,
      departAt: depart,
      arriveAt: arrival,
      spareMinutes: spare,
      label: '출발 시각',
      clockSuffix: '에 출발했어요',
    );
  }

  /// 이대로면 늦는다는 결론.
  ///
  /// 여유가 음수인데 초록·파란 카드에 "늦음 3분" 이 작게 붙어 있으면 못 간다는 사실이 읽히지 않는다
  /// (2026-09-22 제보). 제목부터 늦는다고 말하고 카드 전체를 붉게 한다.
  static DepartureHeadlineContent _late(
    DateTime depart,
    DateTime arrival,
    int spare,
  ) => DepartureHeadlineContent(
    title:
        '${formatClock(depart)}에 나가도 ${formatClock(arrival)} 도착 · ${-spare}분 늦어요',
    detail: '지금 경로로는 시간 안에 도착하기 어려워요. 더 일찍 나가거나 다른 경로가 필요해요.',
    tone: DepartureHeadlineTone.late,
    departAt: depart,
    arriveAt: arrival,
    spareMinutes: spare,
  );

  /// 일정 시작까지 얼마나 여유가 있는지 한 줄로. 늦는 경우를 여유처럼 쓰지 않는다.
  static String? _marginDetail(
    ScheduleEvaluationV2 evaluation,
    DateTime arrival,
  ) {
    final lateness = evaluation.latenessSeconds;
    if (lateness != null && lateness > 0) {
      return '일정 시작보다 ${_minutes(lateness)}분 늦게 도착할 것 같아요.';
    }
    final scheduled = evaluation.arriveBy ?? evaluation.targetArriveAt;
    if (scheduled == null) return null;
    final spare = scheduled.difference(arrival).inSeconds;
    if (spare <= 0) return '일정 시작에 딱 맞춰 도착할 것 같아요.';
    return '일정 시작 ${_minutes(spare)}분 전 도착이에요.';
  }

  /// 확정하지 못한 이유를 한 줄로. 이유가 여럿이어도 대표 하나만 말하고, 카드와 같은 문장을 쓴다.
  /// 서버는 새 정보가 들어오면 스스로 다시 계산하므로 그 사실을 덧붙여 "고장"으로 읽히지 않게 한다.
  static String? _shortfallDetail(ScheduleEvaluationV2 evaluation) {
    final reason = evaluationShortfallReason(evaluation);
    if (reason == null) return null;
    if (evaluation.decision == DepartureDecision.searchIncomplete ||
        evaluation.decision == DepartureDecision.routeUnavailable) {
      return reason;
    }
    return '$reason 새 정보가 들어오면 자동으로 다시 계산해요.';
  }

  static int _minutes(int seconds) => (seconds / 60).round().clamp(1, 1 << 30);

  /// 일정 시작까지 남는 분. 늦으면 음수. 기준 시각을 모르면 null.
  static int? _spareMinutes(ScheduleEvaluationV2 evaluation, DateTime arrival) {
    final lateness = evaluation.latenessSeconds;
    if (lateness != null && lateness > 0) return -_minutes(lateness);
    final scheduled = evaluation.arriveBy ?? evaluation.targetArriveAt;
    if (scheduled == null) return null;
    final spare = scheduled.difference(arrival).inSeconds;
    return spare <= 0 ? 0 : _minutes(spare);
  }
}

/// "오전 8:12" 형식. 날짜는 결론 문장에 넣지 않는다.
String formatClock(DateTime value) {
  final local = value.toLocal();
  final meridiem = local.hour < 12 ? '오전' : '오후';
  final hour = local.hour % 12 == 0 ? 12 : local.hour % 12;
  return '$meridiem $hour:${local.minute.toString().padLeft(2, '0')}';
}

/// "08:20" 형식. 큰 숫자로 쓸 때는 "오전/오후" 가 붙으면 줄이 길어져 잘린다.
String formatClockCompact(DateTime value) {
  final local = value.toLocal();
  return '${local.hour.toString().padLeft(2, '0')}:'
      '${local.minute.toString().padLeft(2, '0')}';
}

/// 결론 한 줄 상자. 일정 상세 상단과 홈에서 같은 문구를 쓴다.
class DepartureHeadline extends StatelessWidget {
  const DepartureHeadline({
    super.key,
    required this.evaluation,
    this.compact = false,
    this.emphasized = false,
    this.onTap,
    this.departedAt,
  });

  final ScheduleEvaluationV2? evaluation;

  /// 홈처럼 좁은 자리에서는 글자를 줄이고 보조 문구를 한 줄로 유지한다.
  final bool compact;

  /// 일정 상세 맨 위에서 쓰는 강조 모양. 글자를 키우고 말머리를 붙인다.
  final bool emphasized;
  final VoidCallback? onTap;

  /// 서버가 기록한 실제 출발 시각. 출발 뒤 화면에서 큰 숫자로 쓴다.
  final DateTime? departedAt;

  @override
  Widget build(BuildContext context) {
    final content = DepartureHeadlineContent.of(
      evaluation,
      DateTime.now(),
      departedAt: departedAt,
    );
    final palette = paletteOf(content);
    // 강조 모양은 시각을 큰 숫자로 쓰고 도착·여유를 칸으로 나눈다. 값이 없는 상태
    // (계산 중·입력 필요)에서는 나눌 것이 없으므로 평소 모양으로 돌아간다.
    final bigClock = emphasized && content.departAt != null;
    final body = Padding(
      padding: EdgeInsets.fromLTRB(
        16,
        compact ? 13 : (emphasized ? 18 : 16),
        16,
        compact ? 13 : (emphasized ? 18 : 16),
      ),
      child: bigClock
          ? _emphasized(content, palette)
          : Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  palette.icon,
                  size: compact ? 20 : (emphasized ? 26 : 22),
                  color: palette.accent,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (emphasized) ...[
                        Text(
                          content.label,
                          key: const Key('departureHeadlineLabel'),
                          style: TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.w800,
                            letterSpacing: 0.3,
                            color: palette.accent,
                          ),
                        ),
                        const SizedBox(height: 6),
                      ],
                      Text(
                        content.title,
                        key: const Key('departureHeadlineTitle'),
                        style: TextStyle(
                          fontSize: compact ? 16 : (emphasized ? 22 : 19),
                          height: 1.25,
                          fontWeight: FontWeight.w900,
                          color: palette.title,
                        ),
                      ),
                      if (content.detail != null) ...[
                        const SizedBox(height: 4),
                        Text(
                          content.detail!,
                          key: const Key('departureHeadlineDetail'),
                          maxLines: compact ? 2 : 3,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 12,
                            height: 1.35,
                            color: Color(0xFF5A6B85),
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
                if (onTap != null)
                  const Padding(
                    padding: EdgeInsets.only(left: 6, top: 2),
                    child: Icon(
                      Icons.chevron_right_rounded,
                      size: 20,
                      color: Color(0xFF8394AC),
                    ),
                  ),
              ],
            ),
    );
    return Container(
      key: Key('departureHeadline_${palette.name}'),
      decoration: BoxDecoration(
        color: palette.background,
        borderRadius: BorderRadius.circular(emphasized ? 20 : 18),
        border: Border.all(color: palette.border, width: emphasized ? 1.6 : 1),
      ),
      clipBehavior: Clip.antiAlias,
      child: onTap == null
          ? body
          : InkWell(
              key: const Key('departureHeadlineTap'),
              onTap: onTap,
              child: body,
            ),
    );
  }

  /// 시안 배치: 말머리 → 큰 출발 시각 → 도착·여유 두 칸.
  Widget _emphasized(
    DepartureHeadlineContent content,
    HeadlinePalette palette,
  ) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Row(
        children: [
          Icon(palette.icon, size: 16, color: palette.accent),
          const SizedBox(width: 6),
          Text(
            content.label,
            key: const Key('departureHeadlineLabel'),
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w800,
              letterSpacing: 0.3,
              color: palette.accent,
            ),
          ),
        ],
      ),
      const SizedBox(height: 10),
      Row(
        crossAxisAlignment: CrossAxisAlignment.baseline,
        textBaseline: TextBaseline.alphabetic,
        children: [
          Text(
            formatClockCompact(content.departAt!),
            key: const Key('departureHeadlineClock'),
            style: TextStyle(
              fontSize: 38,
              height: 1,
              fontWeight: FontWeight.w900,
              color: palette.title,
            ),
          ),
          const SizedBox(width: 7),
          Text(
            content.clockSuffix,
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w700,
              color: palette.title,
            ),
          ),
        ],
      ),
      const SizedBox(height: 12),
      Row(
        children: [
          if (content.arriveAt != null)
            Expanded(
              child: _metric(
                '도착 예정',
                formatClockCompact(content.arriveAt!),
                DdubiColors.navy,
              ),
            ),
          if (content.arriveAt != null && content.spareMinutes != null)
            const SizedBox(width: 8),
          if (content.spareMinutes != null)
            Expanded(
              child: _metric(
                content.spareMinutes! < 0 ? '늦음' : '여유',
                _spareLabel(content.spareMinutes!),
                content.spareMinutes! < 0 ? DdubiColors.red : palette.accent,
              ),
            ),
        ],
      ),
      if (content.detail != null) ...[
        const SizedBox(height: 10),
        Text(
          content.detail!,
          key: const Key('departureHeadlineDetail'),
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 12,
            height: 1.35,
            // 늦는 카드에서는 이유도 같은 붉은 계열로 읽혀야 한다. 회색이면 카드가 붉은데 글은 태연하다.
            color: content.tone == DepartureHeadlineTone.late
                ? const Color(0xFF9B2C2C)
                : const Color(0xFF5A6B85),
          ),
        ),
      ],
    ],
  );

  /// 여유 칸에 쓸 말. 0 분은 "0분" 이 아니라 딱 맞는다는 뜻이다.
  static String _spareLabel(int minutes) {
    if (minutes < 0) return '${-minutes}분';
    if (minutes == 0) return '딱 맞음';
    return '$minutes분';
  }

  /// 도착·여유 한 칸. 바탕은 흰색이라 카드 바탕색이 무엇이든 읽힌다.
  static Widget _metric(String label, String value, Color valueColor) =>
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.75),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              label,
              style: const TextStyle(
                fontSize: 10,
                fontWeight: FontWeight.w700,
                color: Color(0xFF5C6B7F),
              ),
            ),
            const SizedBox(height: 2),
            Text(
              value,
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w800,
                color: valueColor,
              ),
            ),
          ],
        ),
      );

  /// 카드 색.
  ///
  /// 확정·출발 뒤 카드는 여유에 따라 색이 달라진다. 안전할수록 지금의 초록, 빠듯할수록 붉어진다.
  /// 늦는 카드는 여유와 관계없이 붉다. 그 밖의 상태(참고·대기·주의)는 성격별 고정 색이다.
  ///
  /// ```
  /// 여유 10분 이상  초록      평소와 같다
  /// 여유 5~9분     노랑      슬슬 서둘러야 한다
  /// 여유 1~4분     주황      빠듯하다
  /// 딱 맞음(0분)   주황      한 대만 놓쳐도 늦는다
  /// 늦음           붉음      이대로는 못 간다
  /// ```
  @visibleForTesting
  static HeadlinePalette paletteOf(DepartureHeadlineContent content) {
    final spare = content.spareMinutes;
    switch (content.tone) {
      case DepartureHeadlineTone.late:
        return HeadlinePalette.late;
      case DepartureHeadlineTone.ready:
      case DepartureHeadlineTone.departed:
        final icon = content.tone == DepartureHeadlineTone.departed
            ? Icons.directions_walk_rounded
            : Icons.directions_run_rounded;
        if (spare == null || spare >= 10) return HeadlinePalette.safe(icon);
        if (spare >= 5) return HeadlinePalette.hurry(icon);
        return HeadlinePalette.tight(icon);
      case DepartureHeadlineTone.reference:
        return HeadlinePalette.reference;
      case DepartureHeadlineTone.waiting:
        return HeadlinePalette.waiting;
      case DepartureHeadlineTone.attention:
        return HeadlinePalette.attention;
    }
  }
}

/// 결론 카드 색 묶음. [name] 은 검사와 진단에서 어느 색인지 알아보는 데 쓴다.
@visibleForTesting
class HeadlinePalette {
  const HeadlinePalette({
    required this.name,
    required this.background,
    required this.border,
    required this.accent,
    required this.icon,
    this.title = DdubiColors.navy,
  });

  final String name;
  final Color background;
  final Color border;
  final Color accent;
  final IconData icon;

  /// 제목과 큰 숫자 색. 늦는 카드만 붉게 쓴다.
  final Color title;

  static HeadlinePalette safe(IconData icon) => HeadlinePalette(
    name: 'safe',
    background: const Color(0xFFE9F8F1),
    border: const Color(0xFFBFE6D4),
    accent: DdubiColors.green,
    icon: icon,
  );

  static HeadlinePalette hurry(IconData icon) => HeadlinePalette(
    name: 'hurry',
    background: const Color(0xFFFFF8E1),
    border: const Color(0xFFF1DFA3),
    accent: const Color(0xFFB7791F),
    icon: icon,
  );

  static HeadlinePalette tight(IconData icon) => HeadlinePalette(
    name: 'tight',
    background: const Color(0xFFFFF0E4),
    border: const Color(0xFFF5C9A4),
    accent: DdubiColors.orange,
    icon: icon,
  );

  static const HeadlinePalette late = HeadlinePalette(
    name: 'late',
    background: Color(0xFFFDECEC),
    border: Color(0xFFF2BDBD),
    accent: DdubiColors.red,
    icon: Icons.warning_amber_rounded,
    title: Color(0xFF8E1B1B),
  );

  static const HeadlinePalette reference = HeadlinePalette(
    name: 'reference',
    background: Color(0xFFF2F5FB),
    border: Color(0xFFD5DEEE),
    accent: Color(0xFF4D80E8),
    icon: Icons.history_rounded,
  );

  static const HeadlinePalette waiting = HeadlinePalette(
    name: 'waiting',
    background: Color(0xFFF4F6FA),
    border: DdubiColors.border,
    accent: Color(0xFF6C7D96),
    icon: Icons.hourglass_bottom_rounded,
  );

  static const HeadlinePalette attention = HeadlinePalette(
    name: 'attention',
    background: Color(0xFFFFF6E9),
    border: Color(0xFFF3DCBB),
    accent: DdubiColors.orange,
    icon: Icons.info_outline_rounded,
  );
}

/// 남은 시간에 따른 말머리·꼬리. [DepartureHeadlineContent] 가 그대로 받아 쓴다.
class DepartureTiming {
  const DepartureTiming({
    required this.label,
    required this.clockSuffix,
    this.tail,
  });

  final String label;
  final String clockSuffix;

  /// 제목 뒤에 " · " 로 붙는 말. 없으면 제목을 그대로 둔다.
  final String? tail;

  String decorate(String title) => tail == null ? title : '$title · $tail';
}
