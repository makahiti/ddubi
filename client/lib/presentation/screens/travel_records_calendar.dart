import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/theme.dart';
import '../../domain/models.dart';
import '../departure_headline.dart' show formatClockCompact;
import '../widgets.dart';
import 'shared.dart';

/// 이동 기록을 달력으로 본다.
///
/// 목록 화면(이동 기록 · 리포트)은 그대로 두고, 같은 자료를 날짜별로 훑어볼 자리를 하나 더 둔다.
/// 더 자세히 보여주지는 않는다 — 목록에 있는 것(제목·도착 목표 시각·실제 도착·판정)을 고른 날짜로
/// 걸러 보여줄 뿐이다. 일정 달력과 같은 위젯을 써서 두 달력이 다르게 보이지 않게 한다.
///
/// 자료는 목록 화면이 이미 받아 둔 것을 그대로 받는다. 같은 흐름에서 같은 숫자를 두 번 받지 않는다.
/// 그래서 볼 수 있는 범위도 목록과 같다(최근 석 달 안팎). 그 밖의 달은 비어 보인다.
class TravelRecordsCalendarScreen extends StatefulWidget {
  const TravelRecordsCalendarScreen({
    super.key,
    required this.items,
    this.initialDate,
  });

  final List<TravelHistoryItem> items;

  /// 처음 고른 날. 없으면 가장 최근 기록의 날, 그것도 없으면 오늘이다.
  final DateTime? initialDate;

  @override
  State<TravelRecordsCalendarScreen> createState() =>
      _TravelRecordsCalendarScreenState();
}

class _TravelRecordsCalendarScreenState
    extends State<TravelRecordsCalendarScreen> {
  late DateTime _selected = widget.initialDate ?? _latestOrToday();

  DateTime _latestOrToday() {
    if (widget.items.isEmpty) return DateTime.now();
    return widget.items
        .map((item) => item.arriveBy)
        .reduce((a, b) => a.isAfter(b) ? a : b);
  }

  static bool _sameDay(DateTime a, DateTime b) =>
      a.year == b.year && a.month == b.month && a.day == b.day;

  List<TravelHistoryItem> get _onSelectedDay =>
      widget.items.where((item) => _sameDay(item.arriveBy, _selected)).toList()
        ..sort((a, b) => a.arriveBy.compareTo(b.arriveBy));

  @override
  Widget build(BuildContext context) => AppPage(
    title: '이동 기록 달력',
    child: ListView(
      key: const Key('travelRecordsCalendar'),
      padding: screenPadding,
      children: [
        MonthlyCalendar(
          selectedDate: _selected,
          markedDates: widget.items
              .map((item) => item.arriveBy)
              .toList(growable: false),
          markerLabel: '기록',
          onSelected: (date) => setState(() => _selected = date),
        ),
        const SizedBox(height: 16),
        SectionHeader('${_selected.month}월 ${_selected.day}일 이동 기록'),
        const SizedBox(height: 8),
        if (_onSelectedDay.isEmpty)
          const InfoCard(
            child: Text(
              '이 날의 이동 기록이 없어요.',
              key: Key('travelRecordsCalendarEmpty'),
            ),
          )
        else
          ..._onSelectedDay.map(
            (item) => Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: _RecordCard(item: item),
            ),
          ),
      ],
    ),
  );
}

/// 하루 기록 한 장. 목록 화면의 한 줄과 같은 정보를 조금 더 읽기 쉽게 펼친 것이다.
class _RecordCard extends StatelessWidget {
  const _RecordCard({required this.item});

  final TravelHistoryItem item;

  @override
  Widget build(BuildContext context) {
    final result = arrivalResultText(item.result);
    final tone = switch (item.result) {
      'ON_TIME' => DdubiColors.green,
      'TIGHT' => DdubiColors.orange,
      'LATE_RISK' => DdubiColors.red,
      _ => const Color(0xFF6E8193),
    };
    final actual = item.actualArrival;
    // 누르면 그 일정의 상세로 간다. 끝난 일정 상세가 출발·도착 기록과 당시 안내를 보여준다.
    return InkWell(
      key: Key('recordCard-${item.reportDetailId}'),
      borderRadius: BorderRadius.circular(12),
      onTap: item.scheduleId.isEmpty
          ? null
          : () => context.push('/schedules/${item.scheduleId}'),
      child: InfoCard(
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.title,
                    style: const TextStyle(
                      fontWeight: FontWeight.w800,
                      fontSize: 15,
                      color: DdubiColors.navy,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    // 실제 도착이 없으면 시각을 만들어 넣지 않는다. 판정도 그래서 "판정 없음" 이다.
                    actual == null
                        ? '도착 목표 ${formatClockCompact(item.arriveBy)} · 도착 시각 없음'
                        : '도착 목표 ${formatClockCompact(item.arriveBy)} · 실제 ${formatClockCompact(actual)}',
                    style: const TextStyle(
                      fontSize: 12,
                      color: Color(0xFF6E8193),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 10),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
              decoration: BoxDecoration(
                color: tone.withValues(alpha: .12),
                borderRadius: BorderRadius.circular(999),
              ),
              child: Text(
                result,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w800,
                  color: tone,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
