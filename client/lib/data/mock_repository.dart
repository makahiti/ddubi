import '../domain/models.dart';

class MockDdubiRepository implements DdubiRepository {
  final List<Schedule> _addedSchedules = [];
  final Map<String, Schedule> _updatedSchedules = {};
  final List<RepeatScheduleRule> _repeatRules = [];
  static const home = PlaceLocation(
    name: '집 (서울 강남구 역삼동)',
    address: '서울 강남구 역삼동',
    lat: 37.5001,
    lng: 127.0364,
  );
  static const baseballStadium = PlaceLocation(
    name: '잠실야구장',
    address: '서울 송파구 올림픽로 25',
    providerPlaceId: '18577297',
    lat: 37.5121,
    lng: 127.0719,
  );
  static const _weekdays = ['월', '화', '수', '목', '금', '토', '일'];

  static List<Schedule> get scheduleData {
    final now = DateTime.now();
    return [
      Schedule(
        id: 'schedule-baseball',
        title: '잠실야구장',
        origin: home,
        destination: baseballStadium,
        arriveBy: DateTime(now.year, now.month, now.day, 19, 30),
        updatedAt: now.subtract(const Duration(days: 1)),
      ),
      Schedule(
        id: 'schedule-meeting',
        title: '팀 회의',
        origin: home,
        destination: const PlaceLocation(
          name: '강남역 인근',
          address: '서울 강남구 강남대로 396',
          lat: 37.4979,
          lng: 127.0276,
        ),
        arriveBy: DateTime(now.year, now.month, now.day + 1, 9),
        updatedAt: now.subtract(const Duration(hours: 12)),
      ),
      Schedule(
        id: 'schedule-dinner',
        title: '저녁 약속',
        origin: home,
        destination: const PlaceLocation(
          name: '홍대입구역 인근',
          address: '서울 마포구 양화로 188',
          lat: 37.5572,
          lng: 126.9254,
        ),
        arriveBy: DateTime(now.year, now.month, now.day + 2, 18),
        updatedAt: now.subtract(const Duration(hours: 6)),
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
  Future<List<Schedule>> schedules() async {
    final schedules = [
      ...scheduleData,
      ..._addedSchedules,
    ].map((schedule) => _updatedSchedules[schedule.id] ?? schedule).toList();
    schedules.sort((a, b) => a.date.compareTo(b.date));
    return schedules;
  }

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
  Future<void> updateSchedule(Schedule schedule) async {
    _updatedSchedules[schedule.id] = schedule.copyWith(
      updatedAt: DateTime.now(),
    );
  }

  @override
  Future<void> addRepeatSchedule(RepeatScheduleRule rule) async {
    _repeatRules.add(rule);
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
          origin: home,
          destination: PlaceLocation(
            name: rule.place,
            lat: baseballStadium.lat,
            lng: baseballStadium.lng,
          ),
          arriveBy: DateTime(
            date.year,
            date.month,
            date.day,
            int.parse(rule.time.split(':').first),
            int.parse(rule.time.split(':').last),
          ),
          importance: rule.importance,
          considerLastTrip: rule.considerLastTrip,
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

class MockPlaceSearchService implements PlaceSearchService {
  const MockPlaceSearchService();

  static const _places = [
    MockDdubiRepository.home,
    MockDdubiRepository.baseballStadium,
    PlaceLocation(
      providerPlaceId: 'mock-gangnam-station',
      name: '강남역',
      address: '서울 강남구 강남대로 396',
      lat: 37.4979,
      lng: 127.0276,
    ),
    PlaceLocation(
      providerPlaceId: 'mock-ssafy-seoul',
      name: 'SSAFY 서울캠퍼스',
      address: '서울 강남구 테헤란로 212',
      lat: 37.5012,
      lng: 127.0396,
    ),
  ];

  @override
  Future<List<PlaceLocation>> search(
    String query, {
    int page = 0,
    int size = 10,
  }) async {
    final normalized = query.replaceAll(' ', '').toLowerCase();
    return _places
        .where(
          (place) =>
              place.name
                  .replaceAll(' ', '')
                  .toLowerCase()
                  .contains(normalized) ||
              (place.address ?? '')
                  .replaceAll(' ', '')
                  .toLowerCase()
                  .contains(normalized),
        )
        .take(size)
        .toList(growable: false);
  }
}
