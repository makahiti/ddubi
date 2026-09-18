import '../domain/models.dart';
import '../domain/schedule_evaluation.dart';
import '../domain/transit_reference.dart';
import 'schedule_api.dart';

class ApiScheduleRepository
    implements
        ScheduleRepository,
        ScheduleEvaluationRepository,
        TransitReferenceRepository {
  const ApiScheduleRepository(this._api);

  final ScheduleApi _api;

  @override
  Future<TransitReferenceStatus> transitReferenceStatus() =>
      _api.transitReferenceStatus();

  @override
  Future<TransitReferenceDetail> transitReferenceDetail(
    String kind,
    String referenceId,
  ) => _api.transitReferenceDetail(kind, referenceId);

  @override
  Future<ScheduleEvaluationV2> latestEvaluation(String id) =>
      _api.latestEvaluation(id);

  /// 수동 다시 계산. 서버 예산(10초)과 경로 조회 시간을 합쳐 최대 30초까지 기다린다.
  static const Duration manualRefreshTimeout = Duration(seconds: 30);

  @override
  Future<void> refreshEvaluation(String id) =>
      _api.evaluationAction(id, 'recommendations', null, manualRefreshTimeout);
  @override
  Future<void> acceptEvaluation(String id, String evaluationId) =>
      _api.evaluationAction(id, 'recommendations/accept', {
        'evaluationId': evaluationId,
      });
  @override
  Future<void> confirmDeparture(String id) =>
      _api.evaluationAction(id, 'departure');

  @override
  Future<List<Schedule>> schedules() async {
    final now = DateTime.now();
    final all = await _api.getSchedules(
      from: DateTime(now.year - 1, 1, 1),
      to: DateTime(now.year + 2, 12, 31, 23, 59, 59),
      size: 100,
      sort: 'arriveBy,asc',
    );
    // 서버는 status 미지정 시 취소(CANCELED) 일정도 돌려준다. 취소한 일정이 목록·홈에 다시 보이지 않게 건다.
    return Schedule.visible(all);
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
