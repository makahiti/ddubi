import 'package:ddubi/domain/route_evaluated_numbers.dart';
import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:flutter_test/flutter_test.dart';

/// 후보 두 개를 담은 평가 응답. 서버 계약(schedule-evaluation-v2)과 같은 모양이다.
ScheduleEvaluationV2 evaluation() => ScheduleEvaluationV2.fromJson({
  'schemaVersion': 'schedule-evaluation-v2',
  'evaluationId': 'eval-1',
  'state': 'READY',
  'decision': 'KEEP',
  'candidateEstimates': [
    {
      'candidateKey': 'cand-A',
      'routeId': 101,
      'rank': 1,
      'durationSeconds': 2_460,
      'selected': true,
      'proposed': false,
    },
    {
      'candidateKey': 'cand-B',
      'routeId': 202,
      'rank': 2,
      'durationSeconds': 3_000,
      'selected': false,
      'proposed': true,
    },
  ],
  'segments': [
    {
      'candidateKey': 'cand-A',
      'sequence': 1,
      'mode': 'WALK',
      'legRole': 'ACCESS_WALK',
      'durationSeconds': 240,
      'durationSource': 'KAKAO_WALKING_API',
    },
    {
      'candidateKey': 'cand-A',
      'sequence': 2,
      'mode': 'BUS',
      'legRole': 'TRANSIT',
      'durationSeconds': 1_500,
      'durationSource': 'BUS_HISTORICAL_REFERENCE',
    },
    {
      'candidateKey': 'cand-B',
      'sequence': 1,
      'mode': 'SUBWAY',
      'legRole': 'TRANSIT',
      'durationSeconds': 900,
      'durationSource': 'TIMETABLE',
    },
  ],
});

void main() {
  test('경로 ID로 그 경로의 전체 시간과 구간 시간만 가져온다', () {
    final numbers = RouteEvaluatedNumbers.of(evaluation(), '101');

    expect(numbers.totalMinutes, 41); // 2460초 → 올림 41분
    expect(numbers.minutesOf(1), 4);
    expect(numbers.sourceOf(1), DurationSource.kakaoWalkingApi);
    expect(numbers.minutesOf(2), 25);
    expect(numbers.sourceOf(2), DurationSource.busHistoricalReference);
  });

  test('다른 후보의 구간 시간을 이 경로 값으로 쓰지 않는다', () {
    final numbers = RouteEvaluatedNumbers.of(evaluation(), '202');

    expect(numbers.totalMinutes, 50);
    // cand-B 의 1번 구간은 지하철 15분이다. cand-A 의 도보 4분이 섞이면 안 된다.
    expect(numbers.minutesOf(1), 15);
    expect(numbers.sourceOf(1), DurationSource.timetable);
    expect(numbers.minutesOf(2), isNull);
  });

  test('평가에 없는 경로거나 평가 전이면 비어 있다 — 화면은 원본을 쓴다', () {
    expect(RouteEvaluatedNumbers.of(evaluation(), '999').isEmpty, isTrue);
    expect(RouteEvaluatedNumbers.of(null, '101').isEmpty, isTrue);
    expect(RouteEvaluatedNumbers.of(evaluation(), '   ').isEmpty, isTrue);
    expect(RouteEvaluatedNumbers.empty.totalMinutes, isNull);
    expect(RouteEvaluatedNumbers.empty.minutesOf(1), isNull);
  });

  test('후보 키가 없는 예전 응답의 구간은 쓰지 않는다', () {
    final legacy = ScheduleEvaluationV2.fromJson({
      'schemaVersion': 'schedule-evaluation-v2',
      'evaluationId': 'eval-old',
      'state': 'READY',
      'decision': 'KEEP',
      'candidateEstimates': [
        {'candidateKey': 'cand-A', 'routeId': 101, 'durationSeconds': 600},
      ],
      'segments': [
        {
          'sequence': 1,
          'mode': 'BUS',
          'legRole': 'TRANSIT',
          'durationSeconds': 600,
          'durationSource': 'PUBLIC_TRANSIT_STEP',
        },
      ],
    });

    final numbers = RouteEvaluatedNumbers.of(legacy, '101');
    expect(numbers.totalMinutes, 10);
    expect(numbers.minutesOf(1), isNull);
  });
}
