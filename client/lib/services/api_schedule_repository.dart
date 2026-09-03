import '../domain/models.dart';
import 'schedule_api.dart';

class ApiScheduleRepository implements ScheduleRepository {
  const ApiScheduleRepository(this._api);

  final ScheduleApi _api;

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
}
