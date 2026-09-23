import '../domain/models.dart';
import 'recommendation_api.dart';

/// 기존 화면이 사용하는 통합 저장소 인터페이스를 실제 API 구현으로 연결한다.
/// 조건 없는 경로 조회는 백엔드 계약에 없으므로 가짜 경로를 만들지 않고 빈 목록을 반환한다.
class ApiDdubiRepository implements DdubiRepository {
  const ApiDdubiRepository(
    this._schedules,
    this._repeats,
    this._recommendations,
  );

  final ScheduleRepository _schedules;
  final RepeatScheduleRepository _repeats;
  final RecommendationApi _recommendations;

  @override
  Future<List<Schedule>> schedules() => _schedules.schedules();

  @override
  Future<Schedule?> scheduleById(String id) => _schedules.scheduleById(id);

  @override
  Future<Schedule> addSchedule(Schedule schedule) =>
      _schedules.addSchedule(schedule);

  @override
  Future<void> updateSchedule(Schedule schedule) =>
      _schedules.updateSchedule(schedule);

  @override
  Future<void> cancelSchedule(String id) => _schedules.cancelSchedule(id);

  @override
  Future<List<ScheduleConflict>> conflicts(DateTime from, DateTime to) =>
      _schedules.conflicts(from, to);

  @override
  Future<void> addRepeatSchedule(RepeatScheduleRule rule) =>
      _repeats.addRepeatSchedule(rule);

  @override
  Future<List<RepeatScheduleRule>> repeatSchedules({bool activeOnly = false}) =>
      _repeats.repeatSchedules(activeOnly: activeOnly);

  @override
  Future<RepeatScheduleRule> repeatScheduleById(String id) =>
      _repeats.repeatScheduleById(id);

  @override
  Future<void> updateRepeatSchedule(
    RepeatScheduleRule rule, {
    DateTime? effectiveFrom,
  }) => _repeats.updateRepeatSchedule(rule, effectiveFrom: effectiveFrom);

  @override
  Future<void> deactivateRepeatSchedule(
    String id, {
    bool cancelFutureInstances = false,
  }) => _repeats.deactivateRepeatSchedule(
    id,
    cancelFutureInstances: cancelFutureInstances,
  );

  @override
  Future<List<RouteOption>> routes({RouteSearchCondition? condition}) {
    if (condition == null) return Future.value(const []);
    return _recommendations.searchRoutes(condition);
  }
}
