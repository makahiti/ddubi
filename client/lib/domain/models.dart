enum RouteKind { candidate, recommended, fastest, safest }

enum ScheduleImportance {
  normal('NORMAL'),
  high('HIGH'),
  critical('CRITICAL');

  const ScheduleImportance(this.apiValue);

  final String apiValue;

  static ScheduleImportance fromApi(String? value) => values.firstWhere(
    (importance) => importance.apiValue == value,
    orElse: () => ScheduleImportance.normal,
  );
}

enum TransportPreferenceChoice {
  subwayFirst('지하철 우선'),
  balanced('버스 + 지하철'),
  busFirst('버스 우선');

  const TransportPreferenceChoice(this.label);

  final String label;

  List<Map<String, String>> get apiTransportPrefs => switch (this) {
    TransportPreferenceChoice.subwayFirst => const [
      {'mode': 'SUBWAY', 'preference': 'PREFER'},
    ],
    TransportPreferenceChoice.balanced => const [],
    TransportPreferenceChoice.busFirst => const [
      {'mode': 'BUS', 'preference': 'PREFER'},
    ],
  };
}

class PlaceLocation {
  const PlaceLocation({
    required this.name,
    required this.lat,
    required this.lng,
    this.address,
    this.providerPlaceId,
  });

  final String name;
  final double lat;
  final double lng;
  final String? address;
  final String? providerPlaceId;

  factory PlaceLocation.fromJson(Map<String, dynamic> json) => PlaceLocation(
    name: json['name'] as String? ?? '',
    lat: (json['lat'] as num).toDouble(),
    lng: (json['lng'] as num).toDouble(),
    address: json['address'] as String?,
    providerPlaceId: json['providerPlaceId']?.toString(),
  );

  Map<String, dynamic> toRequestJson() => {
    'name': name,
    'lat': lat,
    'lng': lng,
  };
}

class SavedPlace extends PlaceLocation {
  const SavedPlace({
    required this.id,
    required super.name,
    required super.lat,
    required super.lng,
    super.address,
  });

  final String id;

  factory SavedPlace.fromJson(Map<String, dynamic> json) => SavedPlace(
    id: json['id'].toString(),
    name: json['name'] as String? ?? '',
    address: json['address'] as String?,
    lat: (json['lat'] as num).toDouble(),
    lng: (json['lng'] as num).toDouble(),
  );
}

abstract interface class PlaceSearchService {
  Future<List<PlaceLocation>> search(
    String query, {
    int page = 0,
    int size = 10,
  });
}

class ScheduleConflictException implements Exception {
  const ScheduleConflictException();
}

class RouteSearchCondition {
  const RouteSearchCondition({
    required this.origin,
    required this.destination,
    required this.plannedDepartAt,
    required this.desiredArrivalAt,
  });

  final PlaceLocation origin;
  final PlaceLocation destination;
  final DateTime plannedDepartAt;
  final DateTime desiredArrivalAt;

  String get departurePlace => origin.name;
  String get destinationPlace => destination.name;
}

/// 서버 일정 상태(SCH-005). 삭제는 행을 지우지 않고 CANCELED 로 바꾸는 soft 취소다.
/// 목록 API 는 status 를 지정하지 않으면 취소 일정도 돌려주므로 화면에서 걸러야 한다.
enum ScheduleStatus {
  upcoming,
  done,
  canceled,
  unknown;

  static ScheduleStatus fromApi(String? raw) => switch (raw?.toUpperCase()) {
    null || '' || 'UPCOMING' => upcoming,
    'DONE' => done,
    'CANCELED' || 'CANCELLED' => canceled,
    _ => unknown,
  };
}

class Schedule {
  const Schedule({
    required this.id,
    required this.title,
    required this.origin,
    required this.destination,
    required this.arriveBy,
    this.plannedDepartAt,
    this.importance = ScheduleImportance.high,
    this.considerLastTrip = false,
    this.preferredTransport = TransportPreferenceChoice.balanced,
    this.repeatLabel,
    this.updatedAt,
    this.status = ScheduleStatus.upcoming,
  });

  final String id;
  final String title;
  final PlaceLocation origin;
  final PlaceLocation destination;
  final DateTime arriveBy;
  // 사용자 입력 출발시각. 서버 추천값과 구분하며 구버전 null을 임의로 역산하지 않는다.
  final DateTime? plannedDepartAt;
  final ScheduleImportance importance;
  final bool considerLastTrip;
  final TransportPreferenceChoice preferredTransport;
  final String? repeatLabel;
  final DateTime? updatedAt;
  final ScheduleStatus status;

  bool get isCanceled => status == ScheduleStatus.canceled;

  /// 목록·홈에 보여줄 일정만 남긴다. 취소된 일정은 서버에 남아 있어도 화면에서는 제외한다.
  static List<Schedule> visible(Iterable<Schedule> schedules) =>
      schedules.where((schedule) => !schedule.isCanceled).toList();

  String get place => destination.name;
  String get departurePlace => origin.name;
  String get time =>
      '${arriveBy.hour.toString().padLeft(2, '0')}:${arriveBy.minute.toString().padLeft(2, '0')}';
  DateTime get date => arriveBy;
  String get dateLabel {
    final now = DateTime.now();
    final dateOnly = DateTime(arriveBy.year, arriveBy.month, arriveBy.day);
    final today = DateTime(now.year, now.month, now.day);
    final difference = dateOnly.difference(today).inDays;
    if (difference == 0) return '오늘';
    if (difference == 1) return '내일';
    const weekdays = ['월', '화', '수', '목', '금', '토', '일'];
    return '${arriveBy.month}.${arriveBy.day} (${weekdays[arriveBy.weekday - 1]})';
  }

  factory Schedule.fromJson(Map<String, dynamic> json) => Schedule(
    id: json['id'].toString(),
    title: json['title'] as String? ?? '',
    origin: PlaceLocation.fromJson(json['origin'] as Map<String, dynamic>),
    destination: PlaceLocation.fromJson(
      json['destination'] as Map<String, dynamic>,
    ),
    arriveBy: DateTime.parse(json['arriveBy'] as String),
    plannedDepartAt: DateTime.tryParse(
      json['plannedDepartAt'] as String? ?? '',
    ),
    importance: ScheduleImportance.fromApi(json['importance'] as String?),
    considerLastTrip: json['considerLastTrip'] as bool? ?? false,
    updatedAt: DateTime.tryParse(json['updatedAt'] as String? ?? ''),
    status: ScheduleStatus.fromApi(json['status']?.toString()),
  );

  Map<String, dynamic> toCreateRequestJson() => {
    'title': title,
    'origin': origin.toRequestJson(),
    'destination': destination.toRequestJson(),
    'arriveBy': arriveBy.toIso8601String(),
    if (plannedDepartAt != null)
      'plannedDepartAt': plannedDepartAt!.toIso8601String(),
    'importance': importance.apiValue,
    'considerLastTrip': considerLastTrip,
  };

  Map<String, dynamic> toUpdateRequestJson() => {
    ...toCreateRequestJson(),
    'updatedAt': updatedAt?.toIso8601String(),
  };

  Schedule copyWith({
    String? title,
    PlaceLocation? origin,
    PlaceLocation? destination,
    DateTime? arriveBy,
    DateTime? plannedDepartAt,
    ScheduleImportance? importance,
    bool? considerLastTrip,
    DateTime? updatedAt,
  }) => Schedule(
    id: id,
    title: title ?? this.title,
    origin: origin ?? this.origin,
    destination: destination ?? this.destination,
    arriveBy: arriveBy ?? this.arriveBy,
    plannedDepartAt: plannedDepartAt ?? this.plannedDepartAt,
    importance: importance ?? this.importance,
    considerLastTrip: considerLastTrip ?? this.considerLastTrip,
    preferredTransport: preferredTransport,
    repeatLabel: repeatLabel,
    updatedAt: updatedAt ?? this.updatedAt,
    status: status,
  );
}

class RepeatScheduleRule {
  const RepeatScheduleRule({
    required this.id,
    required this.title,
    required this.origin,
    required this.destination,
    required this.time,
    required this.startDate,
    required this.weekdays,
    this.untilDate,
    this.importance = ScheduleImportance.high,
    this.considerLastTrip = false,
    this.preferredTransport = TransportPreferenceChoice.balanced,
    this.active = true,
  });

  final String id;
  final String title;
  final PlaceLocation origin;
  final PlaceLocation destination;
  final String time;
  final DateTime startDate;
  final Set<int> weekdays;
  final DateTime? untilDate;
  final ScheduleImportance importance;
  final bool considerLastTrip;
  final TransportPreferenceChoice preferredTransport;
  final bool active;

  String get place => destination.name;
  String get departurePlace => origin.name;

  Map<String, dynamic> toCreateRequestJson() {
    const byDay = ['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU'];
    final orderedDays = weekdays.toList()..sort();
    return {
      'title': title,
      'origin': origin.toRequestJson(),
      'destination': destination.toRequestJson(),
      'localTime': time,
      'startDate': _dateOnly(startDate),
      'rrule':
          'FREQ=WEEKLY;BYDAY=${orderedDays.map((day) => byDay[day - 1]).join(',')}',
      'endDate': untilDate == null ? null : _dateOnly(untilDate!),
      'importance': importance.apiValue,
      'considerLastTrip': considerLastTrip,
    };
  }

  Map<String, dynamic> toUpdateRequestJson() => toCreateRequestJson();

  factory RepeatScheduleRule.fromJson(Map<String, dynamic> json) {
    final rrule = json['rrule'] as String? ?? '';
    const apiDays = ['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU'];
    final byDay = rrule
        .split(';')
        .where((part) => part.startsWith('BYDAY='))
        .map((part) => part.substring(6))
        .expand((part) => part.split(','))
        .toSet();
    return RepeatScheduleRule(
      id: json['id'].toString(),
      title: json['title'] as String? ?? '',
      origin: PlaceLocation.fromJson(
        (json['origin'] as Map).map(
          (key, value) => MapEntry(key.toString(), value),
        ),
      ),
      destination: PlaceLocation.fromJson(
        (json['destination'] as Map).map(
          (key, value) => MapEntry(key.toString(), value),
        ),
      ),
      time: _hourMinute(json['localTime'] as String?),
      startDate: DateTime.parse(json['startDate'] as String),
      untilDate: DateTime.tryParse(json['endDate'] as String? ?? ''),
      weekdays: {
        for (var index = 0; index < apiDays.length; index++)
          if (byDay.contains(apiDays[index])) index + 1,
      },
      importance: ScheduleImportance.fromApi(json['importance'] as String?),
      considerLastTrip: json['considerLastTrip'] as bool? ?? false,
      active: json['active'] as bool? ?? true,
    );
  }

  static String _dateOnly(DateTime value) =>
      '${value.year.toString().padLeft(4, '0')}-'
      '${value.month.toString().padLeft(2, '0')}-'
      '${value.day.toString().padLeft(2, '0')}';

  static String _hourMinute(String? value) {
    if (value == null || value.length < 5) return '00:00';
    return value.substring(0, 5);
  }
}

class ScheduleConflict {
  const ScheduleConflict({
    required this.previousScheduleId,
    required this.nextScheduleId,
    required this.shortageMin,
    required this.severity,
  });

  final String previousScheduleId;
  final String nextScheduleId;
  final int shortageMin;
  final String severity;

  factory ScheduleConflict.fromJson(Map<String, dynamic> json) =>
      ScheduleConflict(
        previousScheduleId: json['previousScheduleId'].toString(),
        nextScheduleId: json['nextScheduleId'].toString(),
        shortageMin: (json['shortageMin'] as num).toInt(),
        severity: json['severity'] as String? ?? 'WARNING',
      );
}

class HomeSummary {
  const HomeSummary({
    required this.todaySchedules,
    required this.unreadNotificationCount,
    required this.dataState,
    this.nextSchedule,
  });

  final Schedule? nextSchedule;
  final List<Schedule> todaySchedules;
  final int unreadNotificationCount;
  final String dataState;
}

class AppNotification {
  const AppNotification({
    required this.id,
    required this.type,
    required this.title,
    required this.body,
    required this.status,
    required this.createdAt,
    this.scheduleId,
    this.evaluationId,
    this.decision,
    this.validUntil,
    this.readAt,
  });

  final String id;
  final String? scheduleId;
  final String? evaluationId;
  final String? decision;
  final DateTime? validUntil;
  final String type;
  final String title;
  final String body;
  final String status;
  final DateTime createdAt;
  final DateTime? readAt;

  bool get isUnread => readAt == null;

  factory AppNotification.fromJson(Map<String, dynamic> json) =>
      AppNotification(
        id: json['id'].toString(),
        scheduleId: json['scheduleId']?.toString(),
        evaluationId: json['evaluationId']?.toString(),
        decision: json['decision']?.toString(),
        validUntil: DateTime.tryParse(json['validUntil'] as String? ?? ''),
        type: json['type'] as String? ?? 'INFO',
        title: json['title'] as String? ?? '',
        body: json['body'] as String? ?? '',
        status: json['status'] as String? ?? '',
        createdAt: DateTime.parse(json['createdAt'] as String),
        readAt: DateTime.tryParse(json['readAt'] as String? ?? ''),
      );
}

class NotificationInbox {
  const NotificationInbox({required this.unreadCount, required this.items});

  final int unreadCount;
  final List<AppNotification> items;
}

class UserProfile {
  const UserProfile({
    required this.id,
    required this.email,
    required this.nickname,
    required this.notifyLeadMin,
    required this.alarmEnabled,
    required this.transportPrefs,
    this.provider,
  });

  final String id;
  final String? email;
  final String nickname;
  final int notifyLeadMin;
  final bool alarmEnabled;
  final List<Map<String, String>> transportPrefs;
  final String? provider;

  factory UserProfile.fromJson(Map<String, dynamic> json) => UserProfile(
    id: json['id'].toString(),
    email: json['email'] as String?,
    nickname: json['nickname'] as String? ?? '',
    notifyLeadMin: (json['notifyLeadMin'] as num?)?.toInt() ?? 20,
    alarmEnabled: json['alarmEnabled'] as bool? ?? true,
    transportPrefs: (json['transportPrefs'] as List? ?? const [])
        .whereType<Map>()
        .map(
          (item) => item.map(
            (key, value) => MapEntry(key.toString(), value.toString()),
          ),
        )
        .toList(growable: false),
    provider: json['provider']?.toString(),
  );
}

class ArrivalRecord {
  const ArrivalRecord({
    required this.scheduleId,
    required this.status,
    required this.reportDetailId,
    this.actualArrival,
    this.result,
  });

  final String scheduleId;
  final String status;
  final String reportDetailId;
  final DateTime? actualArrival;
  final String? result;

  factory ArrivalRecord.fromJson(Map<String, dynamic> json) => ArrivalRecord(
    scheduleId: json['scheduleId'].toString(),
    status: json['status'] as String? ?? 'DONE',
    reportDetailId: json['reportDetailId'].toString(),
    actualArrival: DateTime.tryParse(json['actualArrival'] as String? ?? ''),
    result: json['result'] as String?,
  );
}

class TravelHistorySummary {
  const TravelHistorySummary({
    required this.totalCount,
    required this.onTimeCount,
    required this.tightCount,
    required this.lateCount,
    required this.onTimeRate,
    this.avgDurationMin,
  });

  final int totalCount, onTimeCount, tightCount, lateCount;
  final double onTimeRate;
  final double? avgDurationMin;

  factory TravelHistorySummary.fromJson(Map<String, dynamic> json) =>
      TravelHistorySummary(
        totalCount: (json['totalCount'] as num?)?.toInt() ?? 0,
        onTimeCount: (json['onTimeCount'] as num?)?.toInt() ?? 0,
        tightCount: (json['tightCount'] as num?)?.toInt() ?? 0,
        lateCount: (json['lateCount'] as num?)?.toInt() ?? 0,
        onTimeRate: (json['onTimeRate'] as num?)?.toDouble() ?? 0,
        avgDurationMin: (json['avgDurationMin'] as num?)?.toDouble(),
      );
}

class TravelHistoryItem {
  const TravelHistoryItem({
    required this.reportDetailId,
    required this.scheduleId,
    required this.title,
    required this.arriveBy,
    this.actualArrival,
    this.result,
  });

  final String reportDetailId, scheduleId, title;
  final DateTime arriveBy;
  final DateTime? actualArrival;
  final String? result;

  factory TravelHistoryItem.fromJson(Map<String, dynamic> json) =>
      TravelHistoryItem(
        reportDetailId: json['reportDetailId'].toString(),
        scheduleId: json['scheduleId'].toString(),
        title: json['title'] as String? ?? '',
        arriveBy: DateTime.parse(json['arriveBy'] as String),
        actualArrival: DateTime.tryParse(
          json['actualArrival'] as String? ?? '',
        ),
        result: json['result'] as String?,
      );
}

class TravelHistory {
  const TravelHistory({required this.summary, required this.items});
  final TravelHistorySummary summary;
  final List<TravelHistoryItem> items;
}

class WeeklyReport {
  const WeeklyReport({
    required this.id,
    required this.periodStart,
    required this.periodEnd,
    required this.totalSchedules,
    required this.onTimeEstimated,
    required this.delayedEstimated,
  });

  final String id;
  final DateTime periodStart, periodEnd;
  final int totalSchedules, onTimeEstimated, delayedEstimated;
  int get tightEstimated => totalSchedules - onTimeEstimated - delayedEstimated;

  factory WeeklyReport.fromJson(Map<String, dynamic> json) => WeeklyReport(
    id: json['id'].toString(),
    periodStart: DateTime.parse(json['periodStart'] as String),
    periodEnd: DateTime.parse(json['periodEnd'] as String),
    totalSchedules: (json['totalSchedules'] as num?)?.toInt() ?? 0,
    onTimeEstimated: (json['onTimeEstimated'] as num?)?.toInt() ?? 0,
    delayedEstimated: (json['delayedEstimated'] as num?)?.toInt() ?? 0,
  );
}

class RouteOption {
  const RouteOption({
    required this.kind,
    required this.label,
    required this.transport,
    required this.departure,
    required this.arrival,
    required this.margin,
    required this.detail,
    required this.fare,
  });
  final RouteKind kind;
  final String label, transport, departure, arrival, margin, detail, fare;
}

/// 일정 변경 요청(취소 등) 실패 종류. 서버 상태 코드를 화면 문구로 옮기기 위한 최소 구분이다.
enum ScheduleCommandFailureKind {
  /// 401 — 로그인이 만료됐거나 인증되지 않음. 남의 일정(403)과 다른 안내가 필요하다.
  unauthenticated,

  /// 403 — 다른 사용자의 일정.
  forbidden,

  /// 404 — 이미 지워졌거나 존재하지 않는 일정.
  notFound,

  /// 409 — 완료된 일정 등 지금 상태에서는 처리할 수 없는 요청.
  conflict,

  /// 연결·시간 초과.
  network,
  unknown,
}

class ScheduleCommandFailure implements Exception {
  const ScheduleCommandFailure({
    required this.kind,
    required this.message,
    this.code,
  });

  final ScheduleCommandFailureKind kind;
  final String message;
  final String? code;

  @override
  String toString() => message;
}

abstract interface class ScheduleRepository {
  Future<List<Schedule>> schedules();
  Future<Schedule?> scheduleById(String id);
  Future<void> addSchedule(Schedule schedule);
  Future<void> updateSchedule(Schedule schedule);
  Future<void> cancelSchedule(String id);
  Future<List<ScheduleConflict>> conflicts(DateTime from, DateTime to);
}

abstract interface class RepeatScheduleRepository {
  Future<void> addRepeatSchedule(RepeatScheduleRule rule);
  Future<List<RepeatScheduleRule>> repeatSchedules({bool activeOnly = false});
  Future<RepeatScheduleRule> repeatScheduleById(String id);
  Future<void> updateRepeatSchedule(RepeatScheduleRule rule);
  Future<void> deactivateRepeatSchedule(String id);
}

abstract interface class DdubiRepository
    implements ScheduleRepository, RepeatScheduleRepository {
  Future<List<RouteOption>> routes({RouteSearchCondition? condition});
}
