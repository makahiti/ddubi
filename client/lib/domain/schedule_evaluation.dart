/// 실제 서버 일정의 평가 조회/재계산/수락 경계. Mock 일정은 이 인터페이스를 구현하지 않아
/// 가짜 ETA나 신뢰도를 정상 서버 결과처럼 표시하지 않는다.
abstract interface class ScheduleEvaluationRepository {
  Future<Map<String, dynamic>> latestEvaluation(String scheduleId);
  Future<void> refreshEvaluation(String scheduleId);
  Future<void> acceptEvaluation(String scheduleId, String evaluationId);
  Future<void> confirmDeparture(String scheduleId);
}
