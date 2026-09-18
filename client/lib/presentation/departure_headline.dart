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
  });

  final String title;
  final String? detail;
  final DepartureHeadlineTone tone;

  static DepartureHeadlineContent of(
    ScheduleEvaluationV2? evaluation,
    DateTime now,
  ) {
    if (evaluation == null) {
      return const DepartureHeadlineContent(
        title: '추천 출발 시간을 계산하고 있어요.',
        tone: DepartureHeadlineTone.waiting,
      );
    }
    switch (evaluation.state) {
      case EvaluationState.stopped:
        return const DepartureHeadlineContent(
          title: '이 일정의 출발 안내가 끝났어요.',
          tone: DepartureHeadlineTone.waiting,
        );
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
      );
    }
    if (evaluation.canRecommendAt(now) && depart != null && arrival != null) {
      return DepartureHeadlineContent(
        title: '${formatClock(depart)}에 나가면 ${formatClock(arrival)} 도착',
        detail: _marginDetail(evaluation, arrival),
        tone: DepartureHeadlineTone.ready,
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
    // 확정 조건은 못 넘겼지만 계산이 끝난 결과: 시각을 참고값으로 보여준다(2026-09-18 결정).
    // 색(파랑)과 "참고"라는 말로 확정과 구분한다. 적용 버튼은 카드의 규칙이 그대로 잠근다.
    if (evaluationHasReferenceTimes(evaluation) &&
        depart != null &&
        arrival != null) {
      final reason = evaluationShortfallReason(evaluation);
      return DepartureHeadlineContent(
        title: '${formatClock(depart)}에 나가면 ${formatClock(arrival)} 도착 예상',
        detail: reason == null ? '아직 확정 전 참고값이에요.' : '아직 확정 전 참고값이에요. $reason',
        tone: DepartureHeadlineTone.reference,
      );
    }
    return DepartureHeadlineContent(
      title: '아직 출발 시각을 확정하지 못했어요.',
      detail: _shortfallDetail(evaluation),
      tone: DepartureHeadlineTone.attention,
    );
  }

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
}

/// "오전 8:12" 형식. 날짜는 결론 문장에 넣지 않는다.
String formatClock(DateTime value) {
  final local = value.toLocal();
  final meridiem = local.hour < 12 ? '오전' : '오후';
  final hour = local.hour % 12 == 0 ? 12 : local.hour % 12;
  return '$meridiem $hour:${local.minute.toString().padLeft(2, '0')}';
}

/// 결론 한 줄 상자. 일정 상세 상단과 홈에서 같은 문구를 쓴다.
class DepartureHeadline extends StatelessWidget {
  const DepartureHeadline({
    super.key,
    required this.evaluation,
    this.compact = false,
    this.onTap,
  });

  final ScheduleEvaluationV2? evaluation;

  /// 홈처럼 좁은 자리에서는 글자를 줄이고 보조 문구를 한 줄로 유지한다.
  final bool compact;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final content = DepartureHeadlineContent.of(evaluation, DateTime.now());
    final palette = _palette(content.tone);
    final body = Padding(
      padding: EdgeInsets.fromLTRB(
        16,
        compact ? 13 : 16,
        16,
        compact ? 13 : 16,
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(palette.icon, size: compact ? 20 : 22, color: palette.accent),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  content.title,
                  key: const Key('departureHeadlineTitle'),
                  style: TextStyle(
                    fontSize: compact ? 16 : 19,
                    height: 1.3,
                    fontWeight: FontWeight.w900,
                    color: DdubiColors.navy,
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
      decoration: BoxDecoration(
        color: palette.background,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: palette.border),
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

  static _HeadlinePalette _palette(DepartureHeadlineTone tone) =>
      switch (tone) {
        DepartureHeadlineTone.ready => const _HeadlinePalette(
          background: Color(0xFFE9F8F1),
          border: Color(0xFFBFE6D4),
          accent: DdubiColors.green,
          icon: Icons.directions_run_rounded,
        ),
        DepartureHeadlineTone.reference => const _HeadlinePalette(
          background: Color(0xFFF2F5FB),
          border: Color(0xFFD5DEEE),
          accent: Color(0xFF4D80E8),
          icon: Icons.history_rounded,
        ),
        DepartureHeadlineTone.waiting => const _HeadlinePalette(
          background: Color(0xFFF4F6FA),
          border: DdubiColors.border,
          accent: Color(0xFF6C7D96),
          icon: Icons.hourglass_bottom_rounded,
        ),
        DepartureHeadlineTone.attention => const _HeadlinePalette(
          background: Color(0xFFFFF6E9),
          border: Color(0xFFF3DCBB),
          accent: DdubiColors.orange,
          icon: Icons.info_outline_rounded,
        ),
      };
}

class _HeadlinePalette {
  const _HeadlinePalette({
    required this.background,
    required this.border,
    required this.accent,
    required this.icon,
  });

  final Color background;
  final Color border;
  final Color accent;
  final IconData icon;
}
