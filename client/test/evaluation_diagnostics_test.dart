import 'package:ddubi/domain/evaluation_diagnostics.dart';
import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/evaluation_diagnostics_panel.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

ScheduleEvaluationV2 _evaluation(
  List<String> fallbackReasons, {
  Map<String, String> versions = const {},
}) => ScheduleEvaluationV2.fromJson({
  'schemaVersion': 'schedule-evaluation-v2',
  'state': 'READY',
  'decision': 'KEEP_DEPARTURE',
  'quality': {
    'preview': true,
    'productionEligible': false,
    'realtimeApplied': false,
    'walkingApiCoverage': 'BOTH',
    'costCoverage': 'COMPLETE',
    'missingInputs': <String>[],
    'fallbackReasons': fallbackReasons,
  },
  if (versions.isNotEmpty) 'versions': versions,
});

void main() {
  test(
    '예산 부족(ML_BUDGET_EXHAUSTED)과 과거 자료 없음(EXACT_KEY_UNAVAILABLE)을 다른 진단으로 나눈다',
    () {
      final diagnostics = EvaluationDiagnostics.from(
        _evaluation([
          'step-1:ML_BUDGET_EXHAUSTED',
          'step-1:ML_NOT_APPLIED:ML_BUDGET_EXHAUSTED',
          'step-1:HISTORICAL_REFERENCE_NOT_APPLIED:EXACT_KEY_UNAVAILABLE',
          'step-1:API_FALLBACK',
          'step-2:ML_NOT_APPLIED:ML_TIMEOUT',
          'step-2:HISTORICAL_REFERENCE_APPLIED',
          'step-2:HISTORICAL_REFERENCE_DATA_VERSION:bus-ref-20260917',
        ]),
      );

      expect(diagnostics.steps.map((s) => s.step), ['step-1', 'step-2']);
      final first = diagnostics.steps.first;
      expect(first.ml, MlApplication.notApplied);
      expect(first.mlReasons, ['ML_BUDGET_EXHAUSTED']);
      expect(first.historical, HistoricalApplication.notApplied);
      expect(first.historicalReasons, ['EXACT_KEY_UNAVAILABLE']);
      expect(first.apiFallback, isTrue);
      final second = diagnostics.steps.last;
      expect(second.mlReasons, ['ML_TIMEOUT']);
      expect(second.historical, HistoricalApplication.applied);
      expect(second.apiFallback, isFalse);
      expect(diagnostics.dataVersion, 'bus-ref-20260917');
      expect(diagnostics.mlSummary, MlApplication.notApplied);
      expect(diagnostics.historicalSummary, HistoricalApplication.applied);
    },
  );

  test('시험 적용·운영 적용·기록만 을 구분하고 모델 버전·표본·누락 특성 수를 읽는다', () {
    final testApplied = EvaluationDiagnostics.from(
      _evaluation([
        // 시험 적용은 이력이 있는 구간에서만 나온다. 표본 0건이면 서버가 ML_NOT_APPLIED:ML_HISTORY_INSUFFICIENT 를 보낸다.
        'step-1:ML_TEST_APPLIED',
        'step-1:ML_MODEL_VERSION:lgbm-v12',
        'step-1:ML_MIN_HISTORY_SAMPLES:7',
        'step-1:ML_MAX_MISSING_FEATURES:15',
      ]),
    );
    expect(testApplied.mlSummary, MlApplication.testApplied);
    expect(testApplied.steps.single.modelVersion, 'lgbm-v12');
    expect(testApplied.steps.single.minHistorySamples, 7);
    expect(testApplied.steps.single.maxMissingFeatures, 15);
    expect(testApplied.modelVersion, 'lgbm-v12');

    final applied = EvaluationDiagnostics.from(
      _evaluation(['step-1:ML_APPLIED', 'step-1:ML_MODEL_VERSION:prod-1']),
    );
    expect(applied.mlSummary, MlApplication.applied);

    final shadow = EvaluationDiagnostics.from(
      _evaluation([
        'step-1:ML_SHADOW_RECORDED',
        'step-1:ML_NOT_APPLIED:SHADOW_MODE',
        'step-1:API_FALLBACK',
      ]),
    );
    // 그림자 모드는 "미적용"이지만 원인(SHADOW_MODE)을 함께 남긴다.
    expect(shadow.mlSummary, MlApplication.notApplied);
    expect(shadow.steps.single.mlReasons, ['SHADOW_MODE']);
    expect(shadow.steps.single.apiFallback, isTrue);
  });

  test('step 접두어가 없는 코드는 전체 코드로 보관하고 versions 에서 모델·자료 버전을 읽는다', () {
    final diagnostics = EvaluationDiagnostics.from(
      _evaluation(
        ['EGRESS_WALK_FALLBACK'],
        versions: {'model': 'v12', 'dataVersion': 'metro-20260910'},
      ),
    );
    expect(diagnostics.steps, isEmpty);
    expect(diagnostics.globalCodes, ['EGRESS_WALK_FALLBACK']);
    expect(diagnostics.mlSummary, MlApplication.notAttempted);
    expect(diagnostics.historicalSummary, HistoricalApplication.notAttempted);
    expect(diagnostics.modelVersion, 'v12');
    expect(diagnostics.dataVersion, 'metro-20260910');
  });

  testWidgets('진단 패널은 원본 코드가 많아도 화면을 덮지 않게 줄여 보여준다', (tester) async {
    // 실기기에서 부족한 정보 174건이 그대로 뿌려져 화면을 덮었다.
    final many = [
      for (var i = 0; i < 174; i++)
        'BUS:10010060$i:101000082:101000084:MISSING',
    ];
    final evaluation = ScheduleEvaluationV2.fromJson({
      'schemaVersion': 'schedule-evaluation-v2',
      'state': 'READY',
      'decision': 'KEEP_DEPARTURE',
      'quality': {
        'preview': true,
        'productionEligible': false,
        'realtimeApplied': false,
        'walkingApiCoverage': 'BOTH',
        'costCoverage': 'COMPLETE',
        'missingInputs': many,
        'fallbackReasons': <String>[],
      },
    });

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: EvaluationDiagnosticsPanel(evaluation: evaluation),
          ),
        ),
      ),
    );

    expect(find.textContaining('missingInputs(174)'), findsOneWidget);
    expect(find.textContaining('외 154건'), findsOneWidget);
    expect(find.textContaining('BUS:100100600:'), findsOneWidget);
    expect(find.textContaining('BUS:100100173:'), findsNothing);
  });
}
