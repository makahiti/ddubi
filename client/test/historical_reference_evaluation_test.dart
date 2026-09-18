import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/schedule_evaluation_card.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> historical({bool applied = true}) => {
  'schemaVersion': 'schedule-evaluation-v2',
  'state': 'READY',
  'decision': 'KEEP_DEPARTURE',
  'proposedDepartAt': '2099-09-18T08:10:00+09:00',
  'estimatedArrivalAt': '2099-09-18T08:45:00+09:00',
  'validUntil': '2099-09-18T08:00:00+09:00',
  'search': {'complete': true},
  'quality': {
    'preview': true,
    'productionEligible': false,
    'realtimeApplied': false,
    'costCoverage': 'COMPLETE',
    'fallbackReasons': applied
        ? [
            'step-1:HISTORICAL_REFERENCE_APPLIED',
            'step-1:HISTORICAL_REFERENCE_WEATHER_UNKNOWN',
          ]
        : ['step-1:HISTORICAL_REFERENCE_MASTER_MISMATCH'],
  },
  'segments': [
    {
      'sequence': 1,
      'mode': 'BUS',
      'legRole': 'RIDE',
      'durationSeconds': 360,
      'durationSource': applied
          ? 'BUS_HISTORICAL_REFERENCE'
          : 'PUBLIC_TRANSIT_STEP',
    },
  ],
};

void main() {
  test(
    'actual backend historical provenance reasons are not fallback failures',
    () {
      for (final code in [
        'VERSION:v1',
        'SAMPLE_KIND:hourly',
        'MIN_SAMPLE_COUNT:20',
        'MIN_OBSERVED_DATES:4',
        'OLDEST_OBSERVATION_END:2026-06-01',
        'NEWEST_OBSERVATION_END:2026-08-23',
        'ENTRY_UNCERTAINTY_SECONDS:30',
      ]) {
        final label = historicalReferenceReasonLabel(
          'step-1:HISTORICAL_REFERENCE_$code',
        );
        expect(label, isNotNull);
        expect(label, isNot(contains('기본 경로시간 유지')));
      }
    },
  );
  test('applied historical source remains non-realtime and non-acceptable', () {
    final evaluation = ScheduleEvaluationV2.fromJson(historical());
    expect(
      evaluation.legs.single.durationSource,
      DurationSource.busHistoricalReference,
    );
    expect(evaluation.hasHistoricalReference, isTrue);
    expect(evaluation.canShowHistoricalEstimateAt(DateTime(2026)), isTrue);
    expect(evaluation.canRecommendAt(DateTime(2026)), isFalse);
    expect(evaluation.canAcceptAt(DateTime(2026)), isFalse);
    expect(evaluation.quality.realtimeApplied, isFalse);
  });
  test('failed fallback is not misidentified as applied historical data', () {
    final evaluation = ScheduleEvaluationV2.fromJson(
      historical(applied: false),
    );
    expect(evaluation.hasHistoricalReference, isFalse);
    expect(evaluation.canShowHistoricalEstimateAt(DateTime(2026)), isFalse);
  });
  test('expired incomplete and data-insufficient historical estimates remain hidden', () {
    for (final patch in <Map<String, dynamic>>[
      {'validUntil': '2020-01-01T00:00:00Z'},
      {
        'search': {'complete': false},
      },
      {'decision': 'DATA_INSUFFICIENT'},
      {'decision': 'ROUTE_UNAVAILABLE'},
      {'decision': 'SEARCH_INCOMPLETE'},
    ]) {
      expect(
        ScheduleEvaluationV2.fromJson({...historical(), ...patch})
            .canShowHistoricalEstimateAt(DateTime(2026)),
        isFalse,
      );
    }
  });
  testWidgets(
    'historical calculation is visible with reference wording without promoting recommendation',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: ScheduleEvaluationSummary(
                evaluation: ScheduleEvaluationV2.fromJson(historical()),
              ),
            ),
          ),
        ),
      );
      expect(find.text('일부 구간은 과거 평균으로 계산했어요.'), findsOneWidget);
      expect(find.textContaining('과거 평균 기반 참고 출발'), findsOneWidget);
      expect(find.textContaining('과거 평균 기반 참고 도착'), findsOneWidget);
      expect(find.textContaining('현재 날씨 미반영'), findsOneWidget);
      expect(find.textContaining('과거 버스 평균 기준 · 실시간 아님'), findsOneWidget);
      expect(find.textContaining('제안 출발'), findsNothing);
      expect(find.textContaining('실시간 정보 미적용'), findsOneWidget);
    },
  );
  test(
    'legacy historical source stays distinguishable from ML and realtime',
    () {
      const overview = RecommendationOverview(
        state: 'READY',
        source: 'MIXED_HISTORICAL_REFERENCE',
      );
      expect(overview.historical, isTrue);
    },
  );
}
