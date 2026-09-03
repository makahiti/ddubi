enum RouteKind { recommended, fastest, safest }

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
    required this.place,
    required this.departurePlace,
    required this.time,
    required this.dateLabel,
    required this.date,
    this.importance = 1,
    this.preferredTransport = 0,
    this.repeatLabel,
  });
  final String id, title, place, departurePlace, time, dateLabel;
  final DateTime date;
  final int importance;
  final int preferredTransport;
  final String? repeatLabel;
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
    this.importance = 1,
    this.preferredTransport = 0,
  });

  final String id;
  final String title;
  final String place;
  final String departurePlace;
  final String time;
  final DateTime startDate;
  final Set<int> weekdays;
  final DateTime? untilDate;
  final int importance;
  final int preferredTransport;
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

abstract interface class DdubiRepository {
  Future<List<Schedule>> schedules();
  Future<Schedule?> scheduleById(String id);
  Future<void> addSchedule(Schedule schedule);
  Future<void> addRepeatSchedule(RepeatScheduleRule rule);
  Future<List<RouteOption>> routes({RouteSearchCondition? condition});
}
