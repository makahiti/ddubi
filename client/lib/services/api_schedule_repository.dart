import '../domain/models.dart';
import '../domain/schedule_evaluation.dart';
import 'schedule_api.dart';

class ApiScheduleRepository implements ScheduleRepository, ScheduleEvaluationRepository {
  const ApiScheduleRepository(this._api);

  final ScheduleApi _api;

  @override
  Future<Map<String, dynamic>> latestEvaluation(String id) => _api.latestEvaluation(id);
  @override
  Future<void> refreshEvaluation(String id) => _api.evaluationAction(id, 'recommendations');
  @override
  Future<void> acceptEvaluation(String id, String evaluationId) =>
      _api.evaluationAction(id, 'recommendations/accept', {'evaluationId': evaluationId});
  @override
  Future<void> confirmDeparture(String id) => _api.evaluationAction(id, 'departure');

  @override
  Future<List<Schedule>> schedules() {
    final now = DateTime.now();
    return _api.getSchedules(
      from: DateTime(now.year - 1, 1, 1),
      to: DateTime(now.year + 2, 12, 31, 23, 59, 59),
      size: 100,
      sort: 'arriveBy,asc',
    );
  }

  @override
  Future<Schedule?> scheduleById(String id) => _api.getSchedule(id);

  @override
  Future<void> addSchedule(Schedule schedule) async {
    await _api.createSchedule(schedule);
  }

  @override
  Future<void> updateSchedule(Schedule schedule) async {
    await _api.updateSchedule(schedule);
  }

  @override
  Future<void> cancelSchedule(String id) => _api.cancelSchedule(id);

  @override
  Future<List<ScheduleConflict>> conflicts(DateTime from, DateTime to) =>
      _api.getConflicts(from: from, to: to);
}
