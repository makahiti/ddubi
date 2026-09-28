import 'package:flutter/material.dart';

import '../core/theme.dart';
import '../domain/models.dart';
import '../domain/schedule_evaluation.dart';
import '../services/app_api.dart';
import 'departure_headline.dart' show formatClockCompact;
import 'screens/shared.dart' show arrivalResultText;
import 'widgets.dart';

/// 끝난 일정의 기록 카드.
///
/// 예전에는 "도착을 기록한 일정이에요. 출발 안내는 끝났어요." 한 줄이었다. 다녀온 일정을 열면
/// 언제 나가서 언제 도착했는지, 예상과 얼마나 달랐는지가 보여야 한다(2026-09-22 제보).
///
/// 두 곳에서 값을 모은다.
/// - 출발할 때의 계산(마지막 평가): 예상 도착·예상 소요. 서버가 멈춘 일정에도 마지막 결과를
///   돌려주므로(2026-09-22) 그대로 읽는다. 읽은 값은 [headline] 에도 넣어 아래 AI 안내 카드가
///   같은 계산의 문장을 불러오게 한다.
/// - 도착 기록(이동 기록 API): 실제 도착 시각·판정. 일정별 조회 API 가 없어 그 날 앞뒤 하루를
///   받아 이 일정 것만 고른다.
///
/// 어느 쪽이 없어도 카드는 남는다. 있는 값만 적고 없는 칸은 "기록 없음" 이라고 쓴다. 지어내지 않는다.
class FinishedScheduleRecordCard extends StatefulWidget {
  const FinishedScheduleRecordCard({
    super.key,
    required this.schedule,
    required this.headline,
    this.evaluationRepository,
    this.reportApi,
  });

  final Schedule schedule;
  final ValueNotifier<ScheduleEvaluationV2?> headline;
  final ScheduleEvaluationRepository? evaluationRepository;
  final ReportApi? reportApi;

  @override
  State<FinishedScheduleRecordCard> createState() =>
      _FinishedScheduleRecordCardState();
}

class _FinishedScheduleRecordCardState
    extends State<FinishedScheduleRecordCard> {
  ScheduleEvaluationV2? _evaluation;
  TravelHistoryItem? _record;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final repository = widget.evaluationRepository;
    final reportApi = widget.reportApi;
    ScheduleEvaluationV2? evaluation;
    TravelHistoryItem? record;
    if (repository != null) {
      try {
        evaluation = await repository.latestEvaluation(widget.schedule.id);
      } on Object {
        evaluation = null;
      }
    }
    if (reportApi != null) {
      try {
        final day = widget.schedule.arriveBy;
        final history = await reportApi.history(
          from: day.subtract(const Duration(days: 1)),
          to: day.add(const Duration(days: 1)),
        );
        for (final item in history.items) {
          if (item.scheduleId == widget.schedule.id) {
            record = item;
            break;
          }
        }
      } on Object {
        record = null;
      }
    }
    if (!mounted) return;
    setState(() {
      _evaluation = evaluation;
      _record = record;
      _loading = false;
    });
    // 아래 AI 안내 카드가 같은 계산의 문장을 불러오게 한다.
    if (evaluation != null) widget.headline.value = evaluation;
  }

  @override
  Widget build(BuildContext context) {
    final schedule = widget.schedule;
    final departed = schedule.actualDepartAt;
    final expectedArrival = _evaluation?.estimatedArrivalAt;
    final expectedSeconds = _evaluation?.durationSeconds;
    final actualArrival = _record?.actualArrival;
    final actualSeconds = departed != null && actualArrival != null
        ? actualArrival.difference(departed).inSeconds
        : null;
    final result = _record?.result;
    final tone = switch (result) {
      'ON_TIME' => DdubiColors.green,
      'TIGHT' => DdubiColors.orange,
      'LATE_RISK' => DdubiColors.red,
      _ => const Color(0xFF6E8193),
    };
    return InfoCard(
      key: const Key('finishedScheduleRecord'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              ScheduleStatusBadge(schedule.status),
              const SizedBox(width: 8),
              const Expanded(
                child: Text(
                  '이렇게 다녀왔어요',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w900,
                    color: DdubiColors.navy,
                  ),
                ),
              ),
              if (_loading)
                const SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              else if (_record != null)
                Container(
                  key: const Key('finishedResultBadge'),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 4,
                  ),
                  decoration: BoxDecoration(
                    color: tone.withValues(alpha: .12),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    arrivalResultText(result),
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w800,
                      color: tone,
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: _cell(
                  '출발',
                  departed == null ? '기록 없음' : formatClockCompact(departed),
                  key: const Key('finishedDepartedAt'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _cell(
                  '실제 도착',
                  actualArrival == null
                      ? '기록 없음'
                      : formatClockCompact(actualArrival),
                  key: const Key('finishedActualArrival'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _cell(
                  '도착 목표',
                  formatClockCompact(schedule.arriveBy),
                  key: const Key('finishedArriveBy'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: _cell(
                  '예상 도착',
                  expectedArrival == null
                      ? '기록 없음'
                      : formatClockCompact(expectedArrival),
                  key: const Key('finishedExpectedArrival'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _cell(
                  '예상 소요',
                  expectedSeconds == null ? '기록 없음' : _minutes(expectedSeconds),
                  key: const Key('finishedExpectedDuration'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _cell(
                  '실제 소요',
                  actualSeconds == null ? '기록 없음' : _minutes(actualSeconds),
                  key: const Key('finishedActualDuration'),
                ),
              ),
            ],
          ),
          if (!_loading && _record == null) ...[
            const SizedBox(height: 10),
            const Text(
              // 도착 시각을 남기지 않았으면 판정도 없다. 저장 동의는 더보기에서 켠다.
              '도착 시각 기록이 없어 실제 도착·판정은 비어 있어요. 더보기 > 이동 기록 저장을 켜면 다음 도착부터 남아요.',
              key: Key('finishedNoRecordNotice'),
              style: TextStyle(fontSize: 12, color: Color(0xFF6E8193)),
            ),
          ],
        ],
      ),
    );
  }

  static String _minutes(int seconds) {
    // 초를 버리지 않고 올린다. 실제 소요를 깎아 말하면 다음 계획이 빠듯해진다.
    final minutes = (seconds / 60).ceil();
    if (minutes < 60) return '약 $minutes분';
    final hours = minutes ~/ 60;
    final rest = minutes % 60;
    return rest == 0 ? '약 $hours시간' : '약 $hours시간 $rest분';
  }

  static Widget _cell(String label, String value, {Key? key}) => Container(
    key: key,
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
    decoration: BoxDecoration(
      color: const Color(0xFFF4F6FA),
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
            fontSize: 14,
            fontWeight: FontWeight.w800,
            color: value == '기록 없음'
                ? const Color(0xFF8A96A8)
                : DdubiColors.navy,
          ),
        ),
      ],
    ),
  );
}
