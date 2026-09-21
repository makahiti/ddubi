/// Translate only known historical evidence codes. Never expose opaque IDs as reasons.
String? historicalReferenceReasonLabel(String reason) {
  final parts = reason.split(':');
  // 버스 수식 fallback 계약의 코드는 HISTORICAL_REFERENCE_ 로 시작하지 않아 따로 찾는다.
  if (parts.contains('GTFS_FORMULA_APPLIED')) {
    return 'GTFS 시간표 기준 구간시간 적용 · 실측 아님';
  }
  if (parts.contains('BUS_REFERENCE_VERSION')) {
    return '사용한 버스 기준자료 버전 기록';
  }
  final kindIndex = parts.indexOf('BUS_REFERENCE_KIND');
  if (kindIndex >= 0) {
    final kind = kindIndex + 1 < parts.length ? parts[kindIndex + 1] : '';
    return switch (kind) {
      'GTFS_SCHEDULE_FORMULA' => '사용한 자료: GTFS 시간표 계산값',
      'HOURLY_SOURCE_MEAN' => '사용한 자료: 시간대별 실측 평균',
      'ACTUAL_TRAVERSAL_MEAN' => '사용한 자료: 실제 구간 통과 기록 평균',
      _ => '사용한 버스 기준자료 종류 기록',
    };
  }
  final index = parts.indexWhere(
    (part) => part.startsWith('HISTORICAL_REFERENCE_'),
  );
  if (index < 0) return null;
  return switch (parts[index]) {
    'HISTORICAL_REFERENCE_APPLIED' => '버스 과거 평균 소요시간 적용',
    'HISTORICAL_REFERENCE_WEATHER_UNKNOWN' => '과거 평균에 현재 날씨 미반영',
    'HISTORICAL_REFERENCE_VERSION' ||
    'HISTORICAL_REFERENCE_DATA_VERSION' ||
    'HISTORICAL_REFERENCE_SAMPLE_KIND' => '사용한 과거 자료의 버전·종류 기록',
    'HISTORICAL_REFERENCE_MIN_SAMPLE_COUNT' ||
    'HISTORICAL_REFERENCE_MIN_OBSERVED_DATES' => '사용한 과거 자료의 표본·관측일 수 기록',
    'HISTORICAL_REFERENCE_OLDEST_OBSERVATION_END' ||
    'HISTORICAL_REFERENCE_NEWEST_OBSERVATION_END' => '사용한 과거 자료의 관측 기간 기록',
    'HISTORICAL_REFERENCE_ENTRY_UNCERTAINTY_SECONDS' => '구간 진입시각 오차 범위 확인',
    _ => '과거 자료 적용 조건이 맞지 않아 기본 경로시간 유지',
  };
}

enum EvaluationState {
  pending,
  running,
  ready,
  failed,
  stopped,
  departureRequired,
  stale,
  unknown;

  static EvaluationState fromRaw(String? raw) => switch (raw) {
    'PENDING' => pending,
    'RUNNING' => running,
    'READY' => ready,
    'FAILED' => failed,
    'STOPPED' => stopped,
    'DEPARTURE_REQUIRED' => departureRequired,
    'STALE' => stale,
    _ => unknown,
  };
}

enum DepartureDecision {
  keepDeparture,
  advanceDeparture,
  departNow,
  marginUnavailable,
  lateExpected,
  dataInsufficient,
  routeUnavailable,
  searchIncomplete,
  unknown;

  static DepartureDecision fromRaw(String? raw) => switch (raw) {
    'KEEP_DEPARTURE' => keepDeparture,
    'ADVANCE_DEPARTURE' => advanceDeparture,
    'DEPART_NOW' => departNow,
    'MARGIN_UNAVAILABLE' => marginUnavailable,
    'LATE_EXPECTED' || 'ON_TIME_UNLIKELY' => lateExpected,
    'DATA_INSUFFICIENT' => dataInsufficient,
    'ROUTE_UNAVAILABLE' || 'NO_USABLE_ROUTE' => routeUnavailable,
    'SEARCH_INCOMPLETE' => searchIncomplete,
    _ => unknown,
  };
}

enum RouteLegRole {
  accessWalk,
  transferWalk,
  egressWalk,
  ride,
  wait,
  unknown;

  static RouteLegRole fromRaw(String? raw) => switch (raw) {
    'ACCESS_WALK' => accessWalk,
    'TRANSFER_WALK' => transferWalk,
    'EGRESS_WALK' => egressWalk,
    'RIDE' => ride,
    'WAIT' => wait,
    _ => unknown,
  };
}

enum DurationSource {
  kakaoWalkingApi,
  publicTransitStep,
  providerTotalFallback,
  realtimeArrival,
  timetable,
  mlPreview,

  /// 운영 계약(PRODUCTION)을 만족한 ML 예측. 현재 서버 기본 모드(SHADOW)에서는 나오지 않는다.
  mlProduction,
  busHistoricalReference,

  /// 경기·인천·부산 GTFS 시간표로 미리 계산한 구간시간. 실제 운행 기록이 아니다.
  busGtfsFormula,
  unknown;

  static DurationSource fromRaw(String? raw) => switch (raw) {
    'KAKAO_WALKING_API' => kakaoWalkingApi,
    'PUBLIC_TRANSIT_STEP' => publicTransitStep,
    'PROVIDER_TOTAL_FALLBACK' || 'PROVIDER_TOTAL' => providerTotalFallback,
    'REALTIME_ARRIVAL' => realtimeArrival,
    'TIMETABLE' || 'SUBWAY_TIMETABLE' => timetable,
    'ML_PREVIEW' => mlPreview,
    'ML_PRODUCTION' => mlProduction,
    'BUS_HISTORICAL_REFERENCE' => busHistoricalReference,
    'BUS_GTFS_FORMULA' => busGtfsFormula,
    _ => unknown,
  };
}

class EndpointEvidence {
  const EndpointEvidence({
    this.verified,
    this.source,
    this.mappingVersion,
    this.policyVersion,
    this.distanceMeters,
    this.judgment,
    this.reason,
    this.values = const {},
  });

  final bool? verified;
  final String? source;
  final String? mappingVersion;
  final String? policyVersion;
  final int? distanceMeters;
  final String? judgment;
  final String? reason;
  final Map<String, dynamic> values;

  factory EndpointEvidence.fromJson(Map<String, dynamic> json) =>
      EndpointEvidence(
        verified: json['verified'] as bool?,
        source: json['source']?.toString(),
        mappingVersion: json['mappingVersion']?.toString(),
        policyVersion: json['policyVersion']?.toString(),
        distanceMeters: (json['distanceMeters'] as num?)?.toInt(),
        judgment: json['judgment']?.toString(),
        reason: json['reason']?.toString(),
        values: Map.unmodifiable(Map<String, dynamic>.from(json)),
      );
}

class EvaluationRouteLeg {
  const EvaluationRouteLeg({
    required this.sequence,
    required this.mode,
    required this.role,
    required this.roleRaw,
    required this.durationSource,
    required this.durationSourceRaw,
    this.fromName,
    this.toName,
    this.durationSeconds,
    this.endpointEvidence,
  });

  final int sequence;
  final String mode;
  final RouteLegRole role;
  final String? roleRaw;
  final DurationSource durationSource;
  final String? durationSourceRaw;
  final String? fromName;
  final String? toName;
  final int? durationSeconds;
  final EndpointEvidence? endpointEvidence;

  factory EvaluationRouteLeg.fromJson(Map<String, dynamic> json) {
    final roleRaw = json['legRole']?.toString();
    final durationSourceRaw = json['durationSource']?.toString();
    final evidence = _map(json['endpointEvidence']);
    return EvaluationRouteLeg(
      sequence:
          (json['sequence'] as num?)?.toInt() ??
          (json['seq'] as num?)?.toInt() ??
          0,
      mode: json['mode']?.toString() ?? 'UNKNOWN',
      role: RouteLegRole.fromRaw(roleRaw),
      roleRaw: roleRaw,
      durationSource: DurationSource.fromRaw(durationSourceRaw),
      durationSourceRaw: durationSourceRaw,
      fromName: json['fromName']?.toString(),
      toName: json['toName']?.toString(),
      durationSeconds: (json['durationSeconds'] as num?)?.toInt(),
      endpointEvidence: evidence.isEmpty
          ? null
          : EndpointEvidence.fromJson(evidence),
    );
  }
}

class EvaluationQuality {
  const EvaluationQuality({
    required this.preview,
    required this.productionEligible,
    required this.realtimeApplied,
    required this.walkingApiApplied,
    required this.walkingApiCoverage,
    required this.costCoverage,
    required this.missingInputs,
    required this.fallbackReasons,
  });

  final bool preview;
  final bool productionEligible;
  final bool realtimeApplied;
  final bool walkingApiApplied;
  final String walkingApiCoverage;
  final String costCoverage;
  final List<String> missingInputs;
  final List<String> fallbackReasons;

  factory EvaluationQuality.fromJson(Map<String, dynamic> json) =>
      EvaluationQuality(
        preview: json['preview'] == true,
        productionEligible: json['productionEligible'] == true,
        realtimeApplied: json['realtimeApplied'] == true,
        walkingApiApplied: json['walkingApiApplied'] == true,
        walkingApiCoverage: json['walkingApiCoverage']?.toString() ?? 'NONE',
        costCoverage: json['costCoverage']?.toString() ?? 'UNKNOWN',
        missingInputs: _strings(json['missingInputs']),
        fallbackReasons: _strings(json['fallbackReasons']),
      );

  static const unavailable = EvaluationQuality(
    preview: false,
    productionEligible: false,
    realtimeApplied: false,
    walkingApiApplied: false,
    walkingApiCoverage: 'NONE',
    costCoverage: 'UNKNOWN',
    missingInputs: [],
    fallbackReasons: [],
  );
}

/// 서버가 내린 "보여줘도 되는가 / 적용해도 되는가" 판단.
///
/// 앱이 조건을 따로 따지지 않는다. 같은 근거로 서버가 수락 검증도 하므로, 앱에서 다르게 판단하면
/// 화면과 실제 동작이 어긋난다(2026-09-18 백엔드 guidance 도입).
class EvaluationGuidance {
  const EvaluationGuidance({
    required this.canDisplayEstimate,
    required this.canAccept,
    required this.source,
    this.reasons = const [],
  });

  /// 예상 출발·도착 시각을 화면에 보여도 되는지.
  final bool canDisplayEstimate;

  /// "이 제안 적용" 을 눌러도 되는지.
  final bool canAccept;

  /// 그 시각이 무엇으로 계산됐는지.
  /// `PROVIDER_ESTIMATE`(기본 경로 기준) · `MIXED_ESTIMATE` · `EXPERIMENTAL_ESTIMATE`(시험 예측 포함) · `UNAVAILABLE`.
  final String source;

  /// 적용을 막은 이유 코드. 사용자 화면에는 그대로 쓰지 않는다.
  final List<String> reasons;

  /// guidance 가 없는 예전 서버 응답. 시각을 보여주지도, 적용하지도 않는다.
  static const EvaluationGuidance absent = EvaluationGuidance(
    canDisplayEstimate: false,
    canAccept: false,
    source: 'UNAVAILABLE',
  );

  /// 전부 기본 경로 소요시간으로 계산한 값인지.
  bool get isProviderEstimate => source == 'PROVIDER_ESTIMATE';

  /// 시험 중인 예측이 섞였는지.
  bool get isExperimental => source == 'EXPERIMENTAL_ESTIMATE';

  factory EvaluationGuidance.fromJson(Map<String, dynamic> json) =>
      EvaluationGuidance(
        canDisplayEstimate: json['canDisplayEstimate'] == true,
        canAccept: json['canAccept'] == true,
        source: json['source']?.toString() ?? 'UNAVAILABLE',
        reasons: _strings(json['reasons']),
      );
}

/// 같은 평가에서 후보 경로들을 같은 기준으로 비교한 결과.
///
/// `route.baseDurationMin`(경로 API 원본)과 다른 값이다. 목록의 순위·소요시간은 이 값을 쓴다.
class EvaluationCandidateEstimate {
  const EvaluationCandidateEstimate({
    required this.rank,
    this.candidateKey,
    this.routeId,
    this.assessment,
    this.departAt,
    this.estimatedArrivalAt,
    this.durationSeconds,
    this.selected = false,
    this.proposed = false,
  });

  final int rank;
  final String? candidateKey;

  /// 저장된 경로 식별자. 목록 카드와 이어 붙일 때 쓴다.
  final String? routeId;
  final String? assessment;
  final DateTime? departAt;
  final DateTime? estimatedArrivalAt;
  final int? durationSeconds;

  /// 지금 저장된 선택.
  final bool selected;

  /// 이번 평가가 새로 제안한 경로. 적용 전에는 [selected] 와 다를 수 있다.
  final bool proposed;

  factory EvaluationCandidateEstimate.fromJson(Map<String, dynamic> json) =>
      EvaluationCandidateEstimate(
        rank: (json['rank'] as num?)?.toInt() ?? 0,
        candidateKey: json['candidateKey']?.toString(),
        routeId: json['routeId']?.toString(),
        assessment: json['assessment']?.toString(),
        departAt: _date(json['departAt']),
        estimatedArrivalAt: _date(json['estimatedArrivalAt']),
        durationSeconds: (json['durationSeconds'] as num?)?.toInt(),
        selected: json['selected'] == true,
        proposed: json['proposed'] == true,
      );
}

class EvaluationSearch {
  const EvaluationSearch({
    this.domain,
    this.evaluatedCount,
    this.complete,
    this.incompleteReason,
  });

  final String? domain;
  final int? evaluatedCount;
  final bool? complete;
  final String? incompleteReason;

  factory EvaluationSearch.fromJson(Map<String, dynamic> json) =>
      EvaluationSearch(
        domain: json['domain']?.toString(),
        evaluatedCount: (json['evaluatedCount'] as num?)?.toInt(),
        complete: json['complete'] as bool?,
        incompleteReason: json['incompleteReason']?.toString(),
      );
}

class EvaluationCause {
  const EvaluationCause({
    this.incidentKey,
    this.revision,
    this.source,
    this.officialNoticeAt,
    this.affectedSegmentIds = const [],
    this.reasonCode,
    this.description,
    this.evidenceLevel,
    this.observedAt,
    this.effect,
    this.confirmed,
  });

  final String? incidentKey;
  final int? revision;
  final String? source;
  final DateTime? officialNoticeAt;
  final List<String> affectedSegmentIds;
  final String? reasonCode;
  final String? description;
  final String? evidenceLevel;
  final DateTime? observedAt;
  final String? effect;
  final bool? confirmed;

  /// Transitional alias for callers that used the pre-v2 cause model.
  String? get code => reasonCode;

  factory EvaluationCause.fromJson(Map<String, dynamic> json) =>
      EvaluationCause(
        incidentKey: (json['incidentKey'] ?? json['eventId'])?.toString(),
        revision: (json['revision'] as num?)?.toInt(),
        source: json['source']?.toString(),
        officialNoticeAt: _date(json['officialNoticeAt']),
        affectedSegmentIds: _strings(
          json['affectedSegmentIds'] ?? json['segmentIds'],
        ),
        reasonCode: (json['reasonCode'] ?? json['code'] ?? json['type'])
            ?.toString(),
        description: (json['description'] ?? json['message'])?.toString(),
        evidenceLevel: json['evidenceLevel']?.toString(),
        observedAt: _date(json['observedAt']),
        effect: json['effect']?.toString(),
        confirmed: json['confirmed'] as bool?,
      );
}

class EvaluationVersions {
  const EvaluationVersions(this.values);
  final Map<String, String> values;

  factory EvaluationVersions.fromJson(Map<String, dynamic> json) =>
      EvaluationVersions(
        json.map((key, value) => MapEntry(key, value.toString())),
      );
}

class ScheduleEvaluationV2 {
  const ScheduleEvaluationV2({
    required this.schemaVersion,
    required this.evaluationId,
    required this.scheduleId,
    required this.state,
    required this.stateRaw,
    required this.decision,
    required this.decisionRaw,
    required this.quality,
    required this.causes,
    required this.legs,
    this.inputVersion,
    this.eventRevision,
    this.triggerReason,
    this.userInputDepartAt,
    this.plannedDepartAt,
    this.effectiveDepartAt,
    this.actualDepartAt,
    this.arriveBy,
    this.originalEstimatedArrivalAt,
    this.targetArriveAt,
    this.appliedBufferSeconds,
    this.proposedDepartAt,
    this.estimatedArrivalAt,
    this.durationSeconds,
    this.latenessSeconds,
    this.arrivesBeforeSchedule,
    this.meetsAdjustedTarget,
    this.departureMissed = false,
    this.selectedRouteId,
    this.proposedRouteId,
    this.search,
    this.bestKnownDecision,
    this.bestKnownDecisionRaw,
    this.computedAt,
    this.asOf,
    this.validUntil,
    this.nextEvaluationAt,
    this.versions,
    this.guidance = EvaluationGuidance.absent,
    this.candidateEstimates = const [],
  });

  final String schemaVersion;
  final String? evaluationId;
  final String? scheduleId;
  final int? inputVersion;
  final int? eventRevision;
  final EvaluationState state;
  final String stateRaw;
  final DepartureDecision decision;
  final String? decisionRaw;
  final String? triggerReason;
  final DateTime? userInputDepartAt;
  final DateTime? plannedDepartAt;
  final DateTime? effectiveDepartAt;
  final DateTime? actualDepartAt;
  final DateTime? arriveBy;
  final DateTime? originalEstimatedArrivalAt;
  final DateTime? targetArriveAt;
  final int? appliedBufferSeconds;
  final DateTime? proposedDepartAt;
  final DateTime? estimatedArrivalAt;
  final int? durationSeconds;
  final int? latenessSeconds;
  final bool? arrivesBeforeSchedule;
  final bool? meetsAdjustedTarget;
  final bool departureMissed;
  final String? selectedRouteId;
  final String? proposedRouteId;
  final EvaluationQuality quality;
  final EvaluationSearch? search;

  /// 표시·적용 판단. 서버가 없으면 [EvaluationGuidance.absent] 라 아무것도 보여주지 않는다.
  final EvaluationGuidance guidance;

  /// 후보 경로별 평가 결과. 목록 카드와 `routeId` 로 이어 붙인다.
  final List<EvaluationCandidateEstimate> candidateEstimates;
  final DepartureDecision? bestKnownDecision;
  final String? bestKnownDecisionRaw;
  final List<EvaluationCause> causes;
  final List<EvaluationRouteLeg> legs;
  final DateTime? computedAt;
  final DateTime? asOf;
  final DateTime? validUntil;
  final DateTime? nextEvaluationAt;
  final EvaluationVersions? versions;

  bool isExpiredAt(DateTime now) =>
      validUntil != null && !validUntil!.isAfter(now);

  bool get isLegacy => schemaVersion != 'schedule-evaluation-v2';

  bool get hasHistoricalReference =>
      legs.any(
        (leg) =>
            leg.durationSource == DurationSource.busHistoricalReference ||
            leg.durationSource == DurationSource.busGtfsFormula,
      ) ||
      quality.fallbackReasons.any(
        (reason) =>
            reason.split(':').contains('HISTORICAL_REFERENCE_APPLIED') ||
            reason.split(':').contains('GTFS_FORMULA_APPLIED'),
      );

  /// A separately labelled estimate is not permission to accept a recommendation.
  bool canShowHistoricalEstimateAt(DateTime now) =>
      hasHistoricalReference &&
      !isLegacy &&
      state == EvaluationState.ready &&
      estimatedArrivalAt != null &&
      proposedDepartAt != null &&
      validUntil != null &&
      !isExpiredAt(now) &&
      quality.costCoverage == 'COMPLETE' &&
      search?.complete != false &&
      const {
        DepartureDecision.keepDeparture,
        DepartureDecision.advanceDeparture,
        DepartureDecision.departNow,
        DepartureDecision.marginUnavailable,
        DepartureDecision.lateExpected,
      }.contains(decision);

  /// 시각을 화면에 보여도 되는지. 판단은 서버(`guidance`)가 하고, 앱은 결과가 아직 유효한지만 본다.
  ///
  /// 앱이 품질 조건을 따로 따지지 않는다. 서버가 같은 근거로 수락 검증도 하므로 기준이 갈라지면 안 된다.
  bool canDisplayEstimateAt(DateTime now) =>
      !isLegacy &&
      state == EvaluationState.ready &&
      guidance.canDisplayEstimate &&
      estimatedArrivalAt != null &&
      validUntil != null &&
      !isExpiredAt(now);

  bool canRecommendAt(DateTime now) =>
      !isLegacy &&
      state == EvaluationState.ready &&
      const {
        DepartureDecision.keepDeparture,
        DepartureDecision.advanceDeparture,
        DepartureDecision.departNow,
        DepartureDecision.marginUnavailable,
        DepartureDecision.lateExpected,
      }.contains(decision) &&
      !quality.preview &&
      quality.productionEligible &&
      quality.costCoverage == 'COMPLETE' &&
      search?.complete != false &&
      estimatedArrivalAt != null &&
      validUntil != null &&
      !isExpiredAt(now) &&
      !legs.any(
        (leg) =>
            (leg.role == RouteLegRole.ride || leg.role == RouteLegRole.wait) &&
            (leg.durationSource == DurationSource.providerTotalFallback ||
                leg.durationSource == DurationSource.unknown),
      );

  /// "이 제안 적용" 을 눌러도 되는지.
  ///
  /// 서버 판단(`guidance.canAccept`)에 더해, 앱은 이미 출발했거나 제안이 만료된 경우만 추가로 막는다.
  /// 최종 수락 검증은 서버가 다시 한다.
  bool canAcceptAt(DateTime now) =>
      canDisplayEstimateAt(now) &&
      guidance.canAccept &&
      actualDepartAt == null &&
      !departureMissed &&
      proposedDepartAt != null;

  factory ScheduleEvaluationV2.fromJson(Map<String, dynamic> json) {
    if (json['schemaVersion'] != 'schedule-evaluation-v2') {
      return ScheduleEvaluationV2.fromLegacyJson(json);
    }
    final stateRaw = _requiredString(json, 'state');
    final decisionRaw = json['decision']?.toString();
    final quality = _map(json['quality']);
    final search = _map(json['search']);
    final guidance = _map(json['guidance']);
    final versions = _map(json['versions']);
    return ScheduleEvaluationV2(
      schemaVersion: 'schedule-evaluation-v2',
      evaluationId: json['evaluationId']?.toString(),
      scheduleId: json['scheduleId']?.toString(),
      inputVersion: (json['inputVersion'] as num?)?.toInt(),
      eventRevision: (json['eventRevision'] as num?)?.toInt(),
      state: EvaluationState.fromRaw(stateRaw),
      stateRaw: stateRaw,
      decision: DepartureDecision.fromRaw(decisionRaw),
      decisionRaw: decisionRaw,
      triggerReason: json['triggerReason']?.toString(),
      userInputDepartAt: _date(json['userInputDepartAt']),
      plannedDepartAt: _date(json['plannedDepartAt']),
      effectiveDepartAt: _date(json['effectiveDepartAt']),
      actualDepartAt: _date(json['actualDepartAt']),
      arriveBy: _date(json['arriveBy']),
      originalEstimatedArrivalAt: _date(json['originalEstimatedArrivalAt']),
      targetArriveAt: _date(json['targetArriveAt']),
      appliedBufferSeconds: (json['appliedBufferSeconds'] as num?)?.toInt(),
      proposedDepartAt: _date(json['proposedDepartAt']),
      estimatedArrivalAt: _date(json['estimatedArrivalAt']),
      durationSeconds: (json['durationSeconds'] as num?)?.toInt(),
      latenessSeconds: (json['latenessSeconds'] as num?)?.toInt(),
      arrivesBeforeSchedule: json['arrivesBeforeSchedule'] as bool?,
      meetsAdjustedTarget: json['meetsAdjustedTarget'] as bool?,
      departureMissed: json['departureMissed'] == true,
      selectedRouteId: json['selectedRouteId']?.toString(),
      proposedRouteId: json['proposedRouteId']?.toString(),
      quality: quality.isEmpty
          ? EvaluationQuality.unavailable
          : EvaluationQuality.fromJson(quality),
      search: search.isEmpty ? null : EvaluationSearch.fromJson(search),
      guidance: guidance.isEmpty
          ? EvaluationGuidance.absent
          : EvaluationGuidance.fromJson(guidance),
      candidateEstimates: _maps(json['candidateEstimates'])
          .map(EvaluationCandidateEstimate.fromJson)
          .toList(growable: false),
      bestKnownDecision: json['bestKnownDecision'] == null
          ? null
          : DepartureDecision.fromRaw(json['bestKnownDecision']?.toString()),
      bestKnownDecisionRaw: json['bestKnownDecision']?.toString(),
      causes: _maps(json['causes']).map(EvaluationCause.fromJson).toList(),
      legs: _maps(json['legs'] ?? json['segments'])
          .map(EvaluationRouteLeg.fromJson)
          .toList(),
      computedAt: _date(json['computedAt']),
      asOf: _date(json['asOf']),
      validUntil: _date(json['validUntil']),
      nextEvaluationAt: _date(json['nextEvaluationAt']),
      versions: versions.isEmpty ? null : EvaluationVersions.fromJson(versions),
    );
  }

  factory ScheduleEvaluationV2.fromLegacyJson(Map<String, dynamic> json) {
    final result = _map(json['result']);
    final preview = _map(result['preview']);
    final selected = _map(preview['fastest']).isNotEmpty
        ? _map(preview['fastest'])
        : _map(result['selected']);
    final proposed = _map(_map(result['suggestion'])['proposed']);
    final persistence = _map(result['persistence']);
    final stateRaw = json['state']?.toString() ?? 'UNKNOWN';
    final candidateList = _maps(result['candidates']);
    final proposedKey = proposed['journeyKey']?.toString();
    final proposedCandidate = candidateList.where(
      (candidate) => candidate['key']?.toString() == proposedKey,
    );
    final rawLegs = proposedCandidate.isNotEmpty
        ? proposedCandidate.first['steps']
        : selected['steps'];
    final isPreview = persistence['preview'] == true || preview.isNotEmpty;
    return ScheduleEvaluationV2(
      schemaVersion: 'schedule-evaluation-v1',
      evaluationId: (result['evaluationId'] ?? result['recommendationId'] ?? '')
          .toString(),
      scheduleId: (result['scheduleId'] ?? json['scheduleId'] ?? '').toString(),
      state: EvaluationState.fromRaw(stateRaw),
      stateRaw: stateRaw,
      decision: DepartureDecision.unknown,
      decisionRaw: null,
      plannedDepartAt: _date(result['plannedDepartAt']),
      proposedDepartAt: _date(
        proposed['departureAt'] ?? selected['departureAt'],
      ),
      estimatedArrivalAt: _date(
        proposed['estimatedArrivalAt'] ?? selected['estimatedArrivalAt'],
      ),
      quality: EvaluationQuality(
        preview: isPreview,
        productionEligible: false,
        realtimeApplied: false,
        walkingApiApplied: false,
        walkingApiCoverage: 'NONE',
        costCoverage: 'UNKNOWN',
        missingInputs: const [],
        fallbackReasons: _strings(persistence['reasons']),
      ),
      causes: const [],
      legs: _maps(rawLegs).map(EvaluationRouteLeg.fromJson).toList(),
      computedAt: _date(result['checkedAt']),
    );
  }
}

enum EvaluationFailureKind {
  validation,
  authentication,
  notFound,
  staleEvaluation,
  rateLimited,
  dependencyUnavailable,
  network,
  invalidResponse,
  unknown,
}

class ScheduleEvaluationFailure implements Exception {
  const ScheduleEvaluationFailure({
    required this.kind,
    required this.message,
    this.code,
    this.fieldErrors = const {},
  });

  final EvaluationFailureKind kind;
  final String message;
  final String? code;
  final Map<String, String> fieldErrors;

  @override
  String toString() => message;
}

abstract interface class ScheduleEvaluationRepository {
  Future<ScheduleEvaluationV2> latestEvaluation(String scheduleId);
  Future<void> refreshEvaluation(String scheduleId);
  Future<void> acceptEvaluation(String scheduleId, String evaluationId);
  Future<void> confirmDeparture(String scheduleId);
}

Map<String, dynamic> _map(Object? value) => value is Map
    ? value.map((key, item) => MapEntry(key.toString(), item))
    : const <String, dynamic>{};

List<Map<String, dynamic>> _maps(Object? value) => value is List
    ? value.whereType<Map>().map(_map).toList(growable: false)
    : const [];

List<String> _strings(Object? value) => value is List
    ? value.map((item) => item.toString()).toList(growable: false)
    : const [];

DateTime? _date(Object? value) {
  if (value == null) return null;
  final parsed = DateTime.tryParse(value.toString());
  if (parsed == null) {
    throw FormatException('올바르지 않은 date-time 값입니다: $value');
  }
  return parsed;
}

String _requiredString(Map<String, dynamic> json, String key) {
  final value = json[key]?.toString();
  if (value == null || value.isEmpty) {
    throw FormatException('$key 필드가 필요합니다.');
  }
  return value;
}
