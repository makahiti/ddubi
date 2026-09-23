import 'schedule_evaluation.dart';

/// 저장된 경로 하나에 대해 <b>이번 평가가 계산한 숫자</b>와 그 출처를 모아 둔 표.
///
/// <h3>왜 한 곳에 모으나</h3>
/// 같은 경로인데 목록은 우리 계산값, 지도 상세는 경로 API 원본을 보여주던 문제가 있었다.
/// 화면마다 "무엇을 보여줄지"를 각자 정하면 반드시 갈라진다. 그래서 규칙을 여기 한 곳에만 둔다.
///
/// 규칙은 하나다. <b>평가한 값이 있으면 그 값을, 없으면 경로 API 원본을 쓰고 원본이라고 밝힌다.</b>
/// 두 값을 섞어 더하거나 평균 내지 않는다 — 출처가 다른 숫자를 합치면 어느 쪽도 설명할 수 없다.
class RouteEvaluatedNumbers {
  const RouteEvaluatedNumbers({this.totalSeconds, this.legs = const {}});

  /// 평가가 계산한 전체 소요시간(초). 아직 계산 전이면 null 이다.
  final int? totalSeconds;

  /// 구간 순번(seq) → 그 구간에 실제로 적용된 시간과 출처.
  final Map<int, EvaluationRouteLeg> legs;

  /// 평가 결과가 없을 때 쓰는 빈 표. 화면은 이때 경로 API 원본을 그대로 보여준다.
  static const RouteEvaluatedNumbers empty = RouteEvaluatedNumbers();

  bool get isEmpty => totalSeconds == null && legs.isEmpty;

  /// 올림한다 — 90초를 1분으로 깎으면 그만큼 늦게 나가게 된다. 서버 계산과 같은 방향이다.
  int? get totalMinutes =>
      totalSeconds == null ? null : (totalSeconds! / 60).ceil();

  /// 구간 하나에 적용된 시간(분). 평가값이 없으면 null 이고 화면은 원본을 쓴다.
  int? minutesOf(int seq) {
    final seconds = legs[seq]?.durationSeconds;
    return seconds == null ? null : (seconds / 60).ceil();
  }

  /// 구간 하나의 시간 출처. 평가값이 없으면 null 이다.
  DurationSource? sourceOf(int seq) => legs[seq]?.durationSource;

  /// 이 경로(routeId)에 해당하는 값만 골라 담는다.
  ///
  /// 평가 결과는 후보 여러 개의 구간을 한 배열에 담아 준다. 후보 키가 맞는 구간만 가져오는
  /// 이유는, 다른 후보의 구간 시간을 이 경로 카드에 얹으면 합이 맞지 않기 때문이다.
  factory RouteEvaluatedNumbers.of(
    ScheduleEvaluationV2? evaluation,
    String routeId,
  ) {
    if (evaluation == null || routeId.trim().isEmpty) {
      return RouteEvaluatedNumbers.empty;
    }
    String? candidateKey;
    int? totalSeconds;
    for (final estimate in evaluation.candidateEstimates) {
      if (estimate.routeId != routeId) continue;
      candidateKey = estimate.candidateKey;
      totalSeconds = estimate.durationSeconds;
      break;
    }
    if (evaluation.state == EvaluationState.stopped &&
        evaluation.selectedRouteId == routeId) {
      // 출발 시각에 다시 계산한 전체 소요시간과 출발 전 구간별 값을 섞지 않는다.
      // 새 구간별 근거가 없는 동안은 총시간만 표시하고 구간은 원본 표기를 쓴다.
      return RouteEvaluatedNumbers(totalSeconds: evaluation.durationSeconds);
    }
    if (candidateKey == null) return RouteEvaluatedNumbers.empty;
    final legs = <int, EvaluationRouteLeg>{};
    for (final leg in evaluation.legs) {
      // 후보 키가 비어 있는 예전 응답은 어느 경로의 값인지 알 수 없다. 쓰지 않는다.
      if (leg.candidateKey != candidateKey) continue;
      if (leg.durationSeconds == null) continue;
      legs[leg.sequence] = leg;
    }
    return RouteEvaluatedNumbers(totalSeconds: totalSeconds, legs: legs);
  }
}
