/// 추천 카드에 보여줄 안내.
///
/// 서버가 계산한 사실을 짧은 한국어 문장으로 바꿔 준다. 시간과 순서는 모두 서버 계산값이며
/// 문장을 만드는 쪽(LLM)이 숫자를 더하거나 바꾸지 못한다 — 그래서 앱은 받은 값을 그대로
/// 보여주고 따로 계산하지 않는다.
class RecommendationGuide {
  const RecommendationGuide({
    required this.evaluationId,
    required this.headline,
    required this.routeSummary,
    required this.steps,
    required this.delay,
    this.minimumFareWon,
    this.maximumFareWon,
    this.departureAt,
    this.estimatedArrivalAt,
    this.durationSeconds,
    this.dataAsOf,
    this.validUntil,
  });

  /// 어느 계산에 대한 안내인지. 이 값이 바뀌었을 때만 다시 불러온다.
  final String? evaluationId;

  final String? headline;
  final String? routeSummary;
  final List<GuideStep> steps;
  final GuideDelay delay;
  final DateTime? departureAt;
  final DateTime? estimatedArrivalAt;
  final int? durationSeconds;

  /// 카카오 대중교통 경로가 제공한 예상 요금 범위다. null이면 앱이 0원으로 추측하지 않는다.
  final int? minimumFareWon;
  final int? maximumFareWon;

  /// 이 안내가 어느 시점의 교통정보를 본 것인지.
  final DateTime? dataAsOf;

  /// 이 시각이 지나면 최신 추천부터 다시 조회한다.
  final DateTime? validUntil;

  bool isExpiredAt(DateTime now) =>
      validUntil != null && !now.isBefore(validUntil!);

  factory RecommendationGuide.fromJson(
    Map<String, dynamic> json,
  ) => RecommendationGuide(
    evaluationId: json['evaluationId']?.toString(),
    headline: _text(json['headline']),
    routeSummary: _text(json['routeSummary']),
    steps: (json['routeSteps'] as List? ?? const [])
        .whereType<Map>()
        .map(
          (item) => GuideStep.fromJson(
            item.map((key, value) => MapEntry(key.toString(), value)),
          ),
        )
        .toList(growable: false),
    delay: GuideDelay.fromJson(
      (json['delay'] as Map?)?.map(
            (key, value) => MapEntry(key.toString(), value),
          ) ??
          const <String, dynamic>{},
    ),
    departureAt: _date(json['departureAt']),
    estimatedArrivalAt: _date(json['estimatedArrivalAt']),
    durationSeconds: (json['durationSeconds'] as num?)?.toInt(),
    minimumFareWon: ((json['fare'] as Map?)?['minimumWon'] as num?)?.toInt(),
    maximumFareWon: ((json['fare'] as Map?)?['maximumWon'] as num?)?.toInt(),
    dataAsOf: _date(json['dataAsOf']),
    validUntil: _date(json['validUntil']),
  );
}

class GuideStep {
  const GuideStep({
    required this.sequence,
    required this.mode,
    this.routeLabel,
    this.fromName,
    this.toName,
    this.durationSeconds,
    this.instruction,
  });

  final int sequence;
  final String mode;
  final String? routeLabel;
  final String? fromName;
  final String? toName;
  final int? durationSeconds;
  final String? instruction;

  factory GuideStep.fromJson(Map<String, dynamic> json) => GuideStep(
    sequence: (json['sequence'] as num?)?.toInt() ?? 0,
    mode: json['mode']?.toString() ?? 'UNKNOWN',
    routeLabel: _text(json['routeLabel']),
    fromName: _text(json['fromName']),
    toName: _text(json['toName']),
    durationSeconds: (json['durationSeconds'] as num?)?.toInt(),
    instruction: _text(json['instruction']),
  );
}

/// 지연 안내. 확인된 원인이 없으면 화면에 아무것도 내보내지 않는다.
class GuideDelay {
  const GuideDelay({
    required this.status,
    this.delaySeconds,
    this.reason,
    this.source,
    this.confirmed = false,
  });

  final String status;
  final int? delaySeconds;
  final String? reason;
  final String? source;
  final bool confirmed;

  static const GuideDelay none = GuideDelay(status: 'NO_CONFIRMED_DELAY');

  /// 보여줄 게 있는지. 이유가 없으면 숨긴다 — "0분 지연" 같은 말을 지어내지 않는다.
  bool get hasMessage =>
      status != 'NO_CONFIRMED_DELAY' && reason != null && reason!.isNotEmpty;

  factory GuideDelay.fromJson(Map<String, dynamic> json) => GuideDelay(
    status: json['status']?.toString() ?? 'NO_CONFIRMED_DELAY',
    delaySeconds: (json['delaySeconds'] as num?)?.toInt(),
    reason: _text(json['reason']),
    source: _text(json['source']),
    confirmed: json['confirmed'] == true,
  );
}

String? _text(Object? value) {
  final text = value?.toString().trim();
  return text == null || text.isEmpty ? null : text;
}

/// 화면에 보이는 시각은 시간대 표기가 없는 한국 시각으로, 운영 시각은 UTC 로 온다.
/// 읽는 자리에서 한 번 맞춰 둔다.
DateTime? _date(Object? value) =>
    DateTime.tryParse(value?.toString() ?? '')?.toLocal();
