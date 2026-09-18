import 'schedule_evaluation.dart';

/// 구간(step)별 ML 적용 결과. 서버 reason 코드 `step-N:ML_*` 에서 읽는다.
enum MlApplication {
  /// `ML_APPLIED` — 운영 계약(PRODUCTION) 응답을 시간 계산에 사용.
  applied,

  /// `ML_TEST_APPLIED` — 시험 적용 모드에서 검증용 숫자를 사용. 운영 승인이 아니다.
  testApplied,

  /// `ML_SHADOW_RECORDED` — 숫자만 기록했고 시간 계산에는 쓰지 않았다.
  shadowRecorded,

  /// `ML_NOT_APPLIED:<원인>` — 시도했지만 적용하지 않았다.
  notApplied,

  /// ML 관련 코드가 하나도 없다(지원하지 않는 구간 등).
  notAttempted,
}

/// 구간별 과거 평균(historical reference) 적용 결과.
enum HistoricalApplication { applied, notApplied, notAttempted }

class StepDiagnostics {
  const StepDiagnostics({
    required this.step,
    required this.ml,
    required this.mlReasons,
    required this.historical,
    required this.historicalReasons,
    required this.apiFallback,
    this.modelVersion,
    this.minHistorySamples,
    this.maxMissingFeatures,
    this.rawCodes = const [],
  });

  final String step;
  final MlApplication ml;

  /// ML 미적용 원인 코드(예: ML_BUDGET_EXHAUSTED, ML_TIMEOUT). 원본 그대로 둔다.
  final List<String> mlReasons;
  final HistoricalApplication historical;

  /// 과거 평균 미적용 원인 코드(예: EXACT_KEY_UNAVAILABLE, BUDGET_EXHAUSTED).
  final List<String> historicalReasons;

  /// `API_FALLBACK` — 이 구간은 경로 API 원본 값을 그대로 사용했다.
  final bool apiFallback;
  final String? modelVersion;
  final int? minHistorySamples;
  final int? maxMissingFeatures;
  final List<String> rawCodes;
}

/// 개발자 진단 화면이 쓰는 파생 정보. 원본 코드는 번역하지 않고 그대로 보존한다.
class EvaluationDiagnostics {
  const EvaluationDiagnostics({
    required this.steps,
    required this.versions,
    required this.globalCodes,
    this.dataVersion,
  });

  final List<StepDiagnostics> steps;
  final Map<String, String> versions;

  /// step 접두어가 없는 코드(EGRESS_WALK_FALLBACK 등).
  final List<String> globalCodes;

  /// 과거 자료 버전. reason `HISTORICAL_REFERENCE_DATA_VERSION:<값>` 또는 versions 에서 읽는다.
  final String? dataVersion;

  static final RegExp _stepPrefix = RegExp(r'^(step-\d+|leg-\d+)$');

  factory EvaluationDiagnostics.from(ScheduleEvaluationV2 evaluation) {
    final byStep = <String, List<List<String>>>{};
    final global = <String>[];
    String? dataVersion;
    for (final raw in evaluation.quality.fallbackReasons) {
      final parts = raw.split(':');
      if (parts.length >= 2 && _stepPrefix.hasMatch(parts.first)) {
        byStep.putIfAbsent(parts.first, () => []).add(parts.sublist(1));
      } else {
        global.add(raw);
      }
      final versionIndex = parts.indexOf('HISTORICAL_REFERENCE_DATA_VERSION');
      if (versionIndex >= 0 && versionIndex + 1 < parts.length) {
        dataVersion ??= parts.sublist(versionIndex + 1).join(':');
      }
    }
    final versions = evaluation.versions?.values ?? const <String, String>{};
    dataVersion ??= versions['dataVersion'] ?? versions['data'];

    final steps = <StepDiagnostics>[];
    for (final entry in byStep.entries) {
      var ml = MlApplication.notAttempted;
      var historical = HistoricalApplication.notAttempted;
      final mlReasons = <String>[];
      final historicalReasons = <String>[];
      var apiFallback = false;
      String? modelVersion;
      int? minSamples;
      int? maxMissing;
      for (final codeParts in entry.value) {
        final code = codeParts.first;
        final value = codeParts.length > 1
            ? codeParts.sublist(1).join(':')
            : null;
        switch (code) {
          case 'ML_APPLIED':
            ml = MlApplication.applied;
          case 'ML_TEST_APPLIED':
            ml = MlApplication.testApplied;
          case 'ML_SHADOW_RECORDED':
            if (ml == MlApplication.notAttempted) {
              ml = MlApplication.shadowRecorded;
            }
          case 'ML_NOT_APPLIED':
            if (ml != MlApplication.applied &&
                ml != MlApplication.testApplied) {
              ml = MlApplication.notApplied;
            }
            if (value != null && value.isNotEmpty) mlReasons.add(value);
          case 'ML_MODEL_VERSION':
            modelVersion = value;
          case 'ML_MIN_HISTORY_SAMPLES':
            minSamples = int.tryParse(value ?? '');
          case 'ML_MAX_MISSING_FEATURES':
            maxMissing = int.tryParse(value ?? '');
          case 'HISTORICAL_REFERENCE_APPLIED':
            historical = HistoricalApplication.applied;
          case 'HISTORICAL_REFERENCE_NOT_APPLIED':
            if (historical != HistoricalApplication.applied) {
              historical = HistoricalApplication.notApplied;
            }
            if (value != null && value.isNotEmpty) historicalReasons.add(value);
          case 'API_FALLBACK':
            apiFallback = true;
          default:
            break;
        }
      }
      steps.add(
        StepDiagnostics(
          step: entry.key,
          ml: ml,
          mlReasons: List.unmodifiable(mlReasons),
          historical: historical,
          historicalReasons: List.unmodifiable(historicalReasons),
          apiFallback: apiFallback,
          modelVersion: modelVersion,
          minHistorySamples: minSamples,
          maxMissingFeatures: maxMissing,
          rawCodes: List.unmodifiable(
            entry.value.map((parts) => parts.join(':')),
          ),
        ),
      );
    }
    steps.sort((a, b) => _stepNumber(a.step).compareTo(_stepNumber(b.step)));
    return EvaluationDiagnostics(
      steps: List.unmodifiable(steps),
      versions: Map.unmodifiable(versions),
      globalCodes: List.unmodifiable(global),
      dataVersion: dataVersion,
    );
  }

  static int _stepNumber(String step) =>
      int.tryParse(step.split('-').last) ?? 0;

  /// 전체 평가의 ML 적용 요약. 구간마다 다르면 가장 강한 적용 단계를 돌려준다.
  MlApplication get mlSummary {
    if (steps.any((s) => s.ml == MlApplication.applied)) {
      return MlApplication.applied;
    }
    if (steps.any((s) => s.ml == MlApplication.testApplied)) {
      return MlApplication.testApplied;
    }
    if (steps.any((s) => s.ml == MlApplication.notApplied)) {
      return MlApplication.notApplied;
    }
    if (steps.any((s) => s.ml == MlApplication.shadowRecorded)) {
      return MlApplication.shadowRecorded;
    }
    return MlApplication.notAttempted;
  }

  HistoricalApplication get historicalSummary {
    if (steps.any((s) => s.historical == HistoricalApplication.applied)) {
      return HistoricalApplication.applied;
    }
    if (steps.any((s) => s.historical == HistoricalApplication.notApplied)) {
      return HistoricalApplication.notApplied;
    }
    return HistoricalApplication.notAttempted;
  }

  String? get modelVersion =>
      steps.map((s) => s.modelVersion).whereType<String>().firstOrNull ??
      versions['model'] ??
      versions['modelVersion'];
}

String mlApplicationLabel(MlApplication value) => switch (value) {
  MlApplication.applied => 'ML 적용(운영 계약)',
  MlApplication.testApplied => 'ML 시험 적용(검증용 숫자 사용)',
  MlApplication.shadowRecorded => 'ML 기록만(시간 계산 미사용)',
  MlApplication.notApplied => 'ML 미적용',
  MlApplication.notAttempted => 'ML 시도 없음',
};

String historicalApplicationLabel(HistoricalApplication value) =>
    switch (value) {
      HistoricalApplication.applied => '과거 평균 적용',
      HistoricalApplication.notApplied => '과거 평균 미적용',
      HistoricalApplication.notAttempted => '과거 평균 해당 없음',
    };
