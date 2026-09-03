import '../domain/models.dart';

class MockDdubiRepository implements DdubiRepository {
  final List<Schedule> _addedSchedules = [];
  final List<RepeatScheduleRule> _repeatRules = [];
  static const _weekdays = ['월', '화', '수', '목', '금', '토', '일'];
  static String _label(DateTime date, DateTime today) {
    final difference = DateTime(
      date.year,
      date.month,
      date.day,
    ).difference(DateTime(today.year, today.month, today.day)).inDays;
    if (difference == 0) return '오늘';
    if (difference == 1) return '내일';
    return '${date.month}.${date.day} (${_weekdays[date.weekday - 1]})';
  }

  static List<Schedule> get scheduleData {
    final now = DateTime.now();
    return [
      Schedule(
        id: 'schedule-baseball',
        title: '잠실야구장',
        place: '잠실야구장',
        departurePlace: '집 (서울 강남구 역삼동)',
        time: '19:30',
        dateLabel: _label(now, now),
        date: now,
      ),
      Schedule(
        id: 'schedule-meeting',
        title: '팀 회의',
        place: '강남역 인근',
        departurePlace: '집 (서울 강남구 역삼동)',
        time: '09:00',
        dateLabel: _label(now.add(const Duration(days: 1)), now),
        date: now.add(const Duration(days: 1)),
      ),
      Schedule(
        id: 'schedule-dinner',
        title: '저녁 약속',
        place: '홍대입구역 인근',
        departurePlace: '집 (서울 강남구 역삼동)',
        time: '18:00',
        dateLabel: _label(now.add(const Duration(days: 2)), now),
        date: now.add(const Duration(days: 2)),
      ),
    ];
  }

  static const routeData = [
    RouteOption(
      kind: RouteKind.recommended,
      label: '추천 경로',
      transport: '지하철 2호선',
      departure: '17:57',
      arrival: '18:45',
      margin: '15분 여유',
      detail: '환승 1회 · 약 48분',
      fare: '1,450원',
    ),
    RouteOption(
      kind: RouteKind.fastest,
      label: '가장 빠른 경로',
      transport: '공항 + 지하철',
      departure: '17:51',
      arrival: '18:33',
      margin: '3분 여유',
      detail: '환승 1회 · 약 42분',
      fare: '4,750원',
    ),
    RouteOption(
      kind: RouteKind.safest,
      label: '가장 안전한 경로',
      transport: '지하철 9호선',
      departure: '18:02',
      arrival: '18:55',
      margin: '25분 여유',
      detail: '환승 0회 · 약 53분',
      fare: '1,450원',
    ),
  ];
  @override
  Future<List<Schedule>> schedules() async =>
      [...scheduleData, ..._addedSchedules]
        ..sort((a, b) => a.date.compareTo(b.date));
  @override
  Future<Schedule?> scheduleById(String id) async {
    for (final schedule in await schedules()) {
      if (schedule.id == id) return schedule;
    }
    return null;
  }

  @override
  Future<void> addSchedule(Schedule schedule) async =>
      _addedSchedules.add(schedule);

  @override
  Future<void> addRepeatSchedule(RepeatScheduleRule rule) async {
    _repeatRules.add(rule);
    final today = DateTime.now();
    final lastDate =
        rule.untilDate ?? rule.startDate.add(const Duration(days: 84));
    for (
      var date = DateTime(
        rule.startDate.year,
        rule.startDate.month,
        rule.startDate.day,
      );
      !date.isAfter(lastDate);
      date = date.add(const Duration(days: 1))
    ) {
      if (!rule.weekdays.contains(date.weekday)) continue;
      final dateId =
          '${date.year}${date.month.toString().padLeft(2, '0')}${date.day.toString().padLeft(2, '0')}';
      _addedSchedules.add(
        Schedule(
          id: '${rule.id}-$dateId',
          title: rule.title,
          place: rule.place,
          departurePlace: rule.departurePlace,
          time: rule.time,
          dateLabel: _label(date, today),
          date: date,
          importance: rule.importance,
          preferredTransport: rule.preferredTransport,
          repeatLabel: _repeatLabel(rule.weekdays),
        ),
      );
    }
  }

  static String _repeatLabel(Set<int> weekdays) {
    if (weekdays.length == 7) return '매일';
    if (weekdays.length == 5 && weekdays.containsAll({1, 2, 3, 4, 5})) {
      return '평일 반복';
    }
    final sorted = weekdays.toList()..sort();
    return '매주 ${sorted.map((day) => _weekdays[day - 1]).join('·')}';
  }

  @override
  Future<List<RouteOption>> routes({RouteSearchCondition? condition}) async =>
      routeData;
}
