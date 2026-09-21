import 'package:ddubi/domain/evaluation_diagnostics.dart';
import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/schedule_evaluation_card.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:flutter_test/flutter_test.dart';

/// 경기·인천·부산은 GTFS 시간표로 미리 계산한 값을 쓴다. 실측 과거 평균과 같은 화면에
/// 나오지만 의미가 다르므로, 파싱과 문구가 섞이지 않는지 확인한다.
Map<String, dynamic> evaluation({
  required String durationSource,
  required List<String> reasons,
  Map<String, dynamic>? versions,
}) => {
  'schemaVersion': 'schedule-evaluation-v2',
  'state': 'READY',
  'decision': 'KEEP_DEPARTURE',
  'proposedDepartAt': '2099-09-21T08:10:00+09:00',
  'estimatedArrivalAt': '2099-09-21T08:45:00+09:00',
  'validUntil': '2099-09-21T08:00:00+09:00',
  'search': {'complete': true},
  'versions': ?versions,
  'quality': {
    'preview': true,
    'productionEligible': false,
    'realtimeApplied': false,
    'costCoverage': 'COMPLETE',
    'fallbackReasons': reasons,
  },
  'segments': [
    {
      'sequence': 1,
      'mode': 'BUS',
      'legRole': 'RIDE',
      'durationSeconds': 420,
      'durationSource': durationSource,
    },
  ],
};

void main() {
  test('GTFS 계산값은 전용 출처로 읽는다', () {
    expect(
      DurationSource.fromRaw('BUS_GTFS_FORMULA'),
      DurationSource.busGtfsFormula,
    );
    expect(
      DurationSource.fromRaw('BUS_HISTORICAL_REFERENCE'),
      DurationSource.busHistoricalReference,
    );
  });

  test('실측 평균과 GTFS 계산값의 문구는 서로 다르다', () {
    final measured = durationSourceLabel(DurationSource.busHistoricalReference);
    final formula = durationSourceLabel(DurationSource.busGtfsFormula);
    expect(measured, isNot(formula));
    expect(formula, contains('GTFS'));
    // GTFS 는 실제 운행 기록이 아니므로 실측이라고 표시하면 안 된다.
    expect(formula, isNot(contains('실측')));
    expect(formula, contains('실시간 아님'));
    expect(measured, contains('실시간 아님'));
  });

  test('BUS_REFERENCE 후보 품질을 참고 추정으로 읽는다', () {
    const summary = RecommendationOverview(
      state: 'READY',
      source: 'BUS_REFERENCE',
    );
    expect(summary.historical, isTrue);
  });

  test('GTFS_FORMULA_APPLIED 사유를 실패로 읽지 않는다', () {
    final label = historicalReferenceReasonLabel(
      'step-1:GTFS_FORMULA_APPLIED',
    );
    expect(label, isNotNull);
    expect(label, isNot(contains('기본 경로시간 유지')));
    expect(label, contains('GTFS'));
  });

  test('버스 기준자료 버전·종류 사유를 실패로 읽지 않는다', () {
    for (final raw in [
      'step-1:BUS_REFERENCE_VERSION:bus-formula-20260920',
      'step-1:BUS_REFERENCE_KIND:GTFS_SCHEDULE_FORMULA',
      'step-1:BUS_REFERENCE_KIND:HOURLY_SOURCE_MEAN',
      'step-1:BUS_REFERENCE_KIND:ACTUAL_TRAVERSAL_MEAN',
    ]) {
      final label = historicalReferenceReasonLabel(raw);
      expect(label, isNotNull, reason: raw);
      expect(label, isNot(contains('기본 경로시간 유지')), reason: raw);
    }
  });

  test('versions 의 busReference 와 busReferenceKind 를 읽는다', () {
    final parsed = ScheduleEvaluationV2.fromJson(
      evaluation(
        durationSource: 'BUS_GTFS_FORMULA',
        reasons: ['step-1:GTFS_FORMULA_APPLIED'],
        versions: {
          'busReference': 'bus-formula-20260920',
          'busReferenceKind': 'GTFS_SCHEDULE_FORMULA',
        },
      ),
    );
    final diagnostics = EvaluationDiagnostics.from(parsed);
    expect(diagnostics.dataVersion, 'bus-formula-20260920');
    expect(diagnostics.referenceKind, 'GTFS_SCHEDULE_FORMULA');
  });

  test('GTFS 계산값이 적용되면 기준값 적용으로 판정한다', () {
    final parsed = ScheduleEvaluationV2.fromJson(
      evaluation(
        durationSource: 'BUS_GTFS_FORMULA',
        reasons: ['step-1:GTFS_FORMULA_APPLIED'],
      ),
    );
    expect(parsed.legs.single.durationSource, DurationSource.busGtfsFormula);
    // 확장하지 않으면 경기·인천·부산은 적용되고도 미적용으로 보인다.
    expect(parsed.hasHistoricalReference, isTrue);
    // 참고 추정일 뿐이므로 추천·수락으로는 올라가지 않는다.
    expect(parsed.canRecommendAt(DateTime(2026)), isFalse);
    expect(parsed.canAcceptAt(DateTime(2026)), isFalse);
    expect(parsed.quality.realtimeApplied, isFalse);
  });

  test('모르는 출처가 와도 화면이 통째로 실패하지 않는다', () {
    final parsed = ScheduleEvaluationV2.fromJson(
      evaluation(
        durationSource: 'BUS_SOMETHING_NEW',
        reasons: ['step-1:SOMETHING_NEW_APPLIED'],
      ),
    );
    expect(parsed.legs.single.durationSource, DurationSource.unknown);
    expect(durationSourceLabel(DurationSource.unknown), isNotEmpty);
    expect(parsed.hasHistoricalReference, isFalse);
  });
}
