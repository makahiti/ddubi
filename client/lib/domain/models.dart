enum RouteKind { recommended, fastest, safest }

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
    required this.departurePlace,
    required this.destinationPlace,
    required this.desiredArrivalAt,
  });

  final String departurePlace;
  final String destinationPlace;
  final DateTime desiredArrivalAt;
}

class Schedule {
  const Schedule({
    required this.id,
    required this.title,
    required this.origin,
    required this.destination,
    required this.arriveBy,
    this.importance = ScheduleImportance.high,
    this.considerLastTrip = false,
    this.preferredTransport = TransportPreferenceChoice.balanced,
    this.repeatLabel,
    this.updatedAt,
  });

  final String id;
  final String title;
  final PlaceLocation origin;
  final PlaceLocation destination;
  final DateTime arriveBy;
  final ScheduleImportance importance;
  final bool considerLastTrip;
  final TransportPreferenceChoice preferredTransport;
  final String? repeatLabel;
  final DateTime? updatedAt;

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
    importance: ScheduleImportance.fromApi(json['importance'] as String?),
    considerLastTrip: json['considerLastTrip'] as bool? ?? false,
    updatedAt: DateTime.tryParse(json['updatedAt'] as String? ?? ''),
  );

  Map<String, dynamic> toCreateRequestJson() => {
    'title': title,
    'origin': origin.toRequestJson(),
    'destination': destination.toRequestJson(),
    'arriveBy': arriveBy.toIso8601String(),
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
    ScheduleImportance? importance,
    bool? considerLastTrip,
    DateTime? updatedAt,
  }) => Schedule(
    id: id,
    title: title ?? this.title,
    origin: origin ?? this.origin,
    destination: destination ?? this.destination,
    arriveBy: arriveBy ?? this.arriveBy,
    importance: importance ?? this.importance,
    considerLastTrip: considerLastTrip ?? this.considerLastTrip,
    preferredTransport: preferredTransport,
    repeatLabel: repeatLabel,
    updatedAt: updatedAt ?? this.updatedAt,
  );
}

class RepeatScheduleRule {
  const RepeatScheduleRule({
    required this.id,
    required this.title,
    required this.place,
    required this.departurePlace,
    required this.time,
    required this.startDate,
    required this.weekdays,
    this.untilDate,
    this.importance = ScheduleImportance.high,
    this.considerLastTrip = false,
    this.preferredTransport = TransportPreferenceChoice.balanced,
  });

  final String id;
  final String title;
  final String place;
  final String departurePlace;
  final String time;
  final DateTime startDate;
  final Set<int> weekdays;
  final DateTime? untilDate;
  final ScheduleImportance importance;
  final bool considerLastTrip;
  final TransportPreferenceChoice preferredTransport;
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

abstract interface class ScheduleRepository {
  Future<List<Schedule>> schedules();
  Future<Schedule?> scheduleById(String id);
  Future<void> addSchedule(Schedule schedule);
  Future<void> updateSchedule(Schedule schedule);
}

abstract interface class DdubiRepository implements ScheduleRepository {
  Future<void> addRepeatSchedule(RepeatScheduleRule rule);
  Future<List<RouteOption>> routes({RouteSearchCondition? condition});
}
