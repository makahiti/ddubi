import 'package:dio/dio.dart';

import '../core/constants/api_paths.dart';
import '../domain/models.dart';
import '../domain/recommendation_guide.dart';
import '../domain/schedule_evaluation.dart';

class RecommendationOverview {
  const RecommendationOverview({
    required this.state,
    this.recommendationId,
    this.departureAt,
    this.eta,
    this.durationMin,
    this.confidence,
    this.preview = false,
    this.source,
    this.fallbackUsed = false,
    this.reasonCodes = const [],
  });

  final String state;
  final String? recommendationId;
  final DateTime? departureAt;
  final DateTime? eta;
  final int? durationMin;
  final String? confidence;
  final bool preview;
  final String? source;
  final bool fallbackUsed;
  final List<String> reasonCodes;

  bool get hasEstimate => departureAt != null && eta != null;

  /// 과거 평균으로 계산한 결과인지. 실시간이 아니므로 화면에서 "참고 추정" 으로 말한다.
  ///
  /// 서버가 쓰는 이름이 여럿이다(2026-09-21 운영 응답 확인). 하나라도 빠지면 과거 평균
  /// 결과가 "최신 추천" 으로 잘못 표시된다.
  bool get historical => const {
    'HISTORICAL_REFERENCE',
    'MIXED_HISTORICAL_REFERENCE',
    'BUS_REFERENCE',
    'BUS_HISTORICAL_REFERENCE',
  }.contains(source);

  RouteOption toRouteOption(Schedule schedule) => RouteOption(
    kind: historical ? RouteKind.candidate : RouteKind.recommended,
    label: historical
        ? '과거 평균 기반 참고 추정'
        : preview
        ? '최신 추천 · 검증용'
        : '최신 추천',
    transport: '${schedule.departurePlace} → ${schedule.place}',
    departure: _clock(departureAt),
    arrival: _clock(eta),
    margin: _sourceLabel(source),
    detail: durationMin == null ? '예상 이동시간 확인 중' : '약 $durationMin분',
    fare: confidence == null ? '신뢰도 확인 중' : '신뢰도 $confidence',
  );

  /// 서버 시각을 사람이 읽는 시:분으로 바꾼다.
  ///
  /// 반드시 [DateTime.toLocal] 을 거친다. 이 값의 출처가 섞여 있기 때문이다 — 평가 결과의
  /// `departureAt`·`estimatedArrivalAt` 은 UTC(`...Z`)로 오고, 추천 근거 API 의 `departAt`
  /// 은 시간대 표기가 없는 한국 시각으로 온다. 앞의 것을 그대로 찍으면 9시간 이른 시각이
  /// 화면에 뜬다(같은 화면 아래 평가 카드는 맞는 시각을 보여주므로 두 값이 어긋나 보였다).
  /// 시간대 표기가 없는 값에 toLocal 을 불러도 값은 바뀌지 않으므로 두 출처 모두 안전하다.
  static String _clock(DateTime? value) {
    if (value == null) return '--:--';
    final local = value.toLocal();
    return '${local.hour.toString().padLeft(2, '0')}:'
        '${local.minute.toString().padLeft(2, '0')}';
  }

  static String _sourceLabel(String? source) => switch (source) {
    'API_ONLY' => '경로 API 시간 기준',
    'ML_PREVIEW' => '버스 예측 포함 · 시험 중',
    'ML_PRODUCTION' => '버스 예측 포함',
    'SUBWAY_PREVIEW' => '지하철 시간표 포함',
    'MIXED_PREVIEW' => '복합 교통 데이터 기준',
    'HISTORICAL_REFERENCE' ||
    'MIXED_HISTORICAL_REFERENCE' => '과거 버스 평균 포함 · 실시간 아님',
    'BUS_REFERENCE' || 'BUS_HISTORICAL_REFERENCE' => '버스 기준시간 포함 · 실시간 아님',
    'FALLBACK' => '경로 API 시간으로 대체',
    'UNAVAILABLE' => '교통정보를 확인하지 못함',
    _ => '최신 평가 결과',
  };
}

class RecommendationExplanation {
  const RecommendationExplanation({
    required this.recommendationId,
    this.departureAt,
    this.eta,
    this.durationMin,
    this.confidence,
    this.dataCheckedAt,
    this.preview,
    this.source,
    this.fallbackUsed,
    this.reasonCodes = const [],
  });

  final String recommendationId;
  final DateTime? departureAt;
  final DateTime? eta;
  final int? durationMin;
  final String? confidence;
  final DateTime? dataCheckedAt;
  final bool? preview;
  final String? source;
  final bool? fallbackUsed;
  final List<String> reasonCodes;
}

class SavedRouteSummary {
  const SavedRouteSummary({
    required this.routeId,
    required this.rankNo,
    this.displayRank,
    required this.selected,
    required this.baseDurationMin,
    required this.fromName,
    required this.toName,
    required this.segmentCount,
    required this.modes,
  });

  final String routeId;

  /// 경로 제공자의 원본 순위 또는 내부 저장 순위. 사용자에게 보여주는 번호가 아니다 —
  /// 선택된 추천의 rankNo 가 20 일 수 있다(소요시간 기준이라 그렇다).
  final int rankNo;

  /// 서버가 정한 화면 표시용 순위. 선택된 경로가 1 이다. 없으면(구버전 응답) 화면 순번을
  /// 앱이 직접 매긴다.
  final int? displayRank;

  final bool selected;
  final int baseDurationMin;
  final String? fromName;
  final String? toName;
  final int segmentCount;
  final List<String> modes;

  factory SavedRouteSummary.fromJson(Map<String, dynamic> json) =>
      SavedRouteSummary(
        routeId: json['routeId'].toString(),
        rankNo: (json['rankNo'] as num?)?.toInt() ?? 0,
        displayRank: (json['displayRank'] as num?)?.toInt(),
        selected: json['selected'] == true,
        baseDurationMin: (json['baseDurationMin'] as num?)?.toInt() ?? 0,
        fromName: json['fromName']?.toString(),
        toName: json['toName']?.toString(),
        segmentCount: (json['segmentCount'] as num?)?.toInt() ?? 0,
        modes: RecommendationApi._strings(json['modes']),
      );

  /// 요약 카드에 쓸 값.
  ///
  /// [evaluatedMinutes] 와 [fareLabel] 은 아래 추천 목록·안내 카드가 실제로 보여주는 값이다.
  /// 같은 화면에서 이 카드만 경로 API 원본을 쓰는 바람에, 같은 경로가 위에서는 42분,
  /// 아래에서는 51분으로 보였다(2026-09-22 확인). 값이 있으면 그것을 따른다.
  RouteOption toRouteOption(
    Schedule schedule, {
    int? evaluatedMinutes,
    String? fareLabel,
  }) {
    return RouteOption(
      kind: selected ? RouteKind.recommended : RouteKind.candidate,
      label: selected ? '선택된 경로' : '경로 ${rankNo == 0 ? '-' : rankNo}',
      transport: modes.isEmpty
          ? '이동수단 정보 없음'
          : modes.map(_transportLabel).join(' · '),
      departure: schedule.plannedDepartAt == null
          ? '확인 필요'
          : RecommendationApi._time(schedule.plannedDepartAt!),
      arrival: '평가 필요',
      margin: '$segmentCount개 이동 구간',
      detail: '약 ${evaluatedMinutes ?? baseDurationMin}분',
      fare: fareLabel ?? '요금 정보 없음',
    );
  }
}

class SavedRouteSegment {
  const SavedRouteSegment({
    required this.seq,
    required this.mode,
    required this.fromName,
    required this.toName,
    required this.baseDurationMin,
    this.providerRouteKey,
  });

  final int seq;
  final String mode;
  final String? providerRouteKey;

  /// 화면에 보여도 되는 노선 이름.
  ///
  /// `providerRouteKey` 는 버스 번호·지하철 호선을 담는 자리인데, 서버가 양 끝 도보를
  /// 만들어 넣을 때 `EVALUATION_ENDPOINT_WALK` 라는 내부 표시를 같이 넣는다. 그대로
  /// 찍으면 사용자에게 "도보 EVALUATION_ENDPOINT_WALK" 로 보인다(2026-09-22 확인).
  /// 도보에는 노선이 없으므로 보여줄 것이 없다.
  String? get displayRouteName {
    final key = providerRouteKey?.trim();
    if (key == null || key.isEmpty) return null;
    if (mode == 'WALK') return null;
    if (key == 'EVALUATION_ENDPOINT_WALK') return null;
    return key;
  }

  final String? fromName;
  final String? toName;
  final int baseDurationMin;

  factory SavedRouteSegment.fromJson(Map<String, dynamic> json) =>
      SavedRouteSegment(
        seq: (json['seq'] as num?)?.toInt() ?? 0,
        mode: json['mode']?.toString() ?? 'UNKNOWN',
        providerRouteKey: json['providerRouteKey']?.toString(),
        fromName: json['fromName']?.toString(),
        toName: json['toName']?.toString(),
        baseDurationMin: (json['baseDurationMin'] as num?)?.toInt() ?? 0,
      );
}

/// 저장된 후보가 서버에서 이미 정리돼 없는 경우.
///
/// 재계산이 끝나면 옛 후보는 서버가 지운다. 그래서 404 는 서버 장애가 아니라 "화면이 든 목록이
/// 오래됐다"는 신호다. 화면은 이 예외를 보고 목록을 다시 읽도록 안내한다.
class SavedRouteGoneException implements Exception {
  const SavedRouteGoneException(this.routeId);

  final String routeId;

  @override
  String toString() => '저장된 경로 $routeId 가 서버에 없습니다.';
}

class SavedRouteDetail {
  const SavedRouteDetail({
    required this.routeId,
    required this.scheduleId,
    required this.rankNo,
    this.displayRank,
    required this.selected,
    required this.baseDurationMin,
    required this.segments,
    this.providerPathId,
  });

  final String routeId;
  final String scheduleId;
  final int rankNo;

  /// 서버가 정한 화면 표시용 순위. 목록과 같은 값이어야 한다.
  final int? displayRank;

  final bool selected;
  final int baseDurationMin;
  final String? providerPathId;
  final List<SavedRouteSegment> segments;

  factory SavedRouteDetail.fromJson(Map<String, dynamic> json) =>
      SavedRouteDetail(
        routeId: json['routeId'].toString(),
        scheduleId: json['scheduleId'].toString(),
        rankNo: (json['rankNo'] as num?)?.toInt() ?? 0,
        displayRank: (json['displayRank'] as num?)?.toInt(),
        selected: json['selected'] == true,
        baseDurationMin: (json['baseDurationMin'] as num?)?.toInt() ?? 0,
        providerPathId: json['providerPathId']?.toString(),
        segments: RecommendationApi._maps(json['segments'])
            .map(SavedRouteSegment.fromJson)
            .toList(growable: false),
      );
}

class RouteCoordinate {
  const RouteCoordinate({required this.latitude, required this.longitude});

  final double latitude;
  final double longitude;

  factory RouteCoordinate.fromJson(Map<String, dynamic> json) =>
      RouteCoordinate(
        latitude: (json['latitude'] as num).toDouble(),
        longitude: (json['longitude'] as num).toDouble(),
      );
}

class RouteMapBounds {
  const RouteMapBounds({
    required this.minLat,
    required this.minLng,
    required this.maxLat,
    required this.maxLng,
  });

  final double minLat;
  final double minLng;
  final double maxLat;
  final double maxLng;

  factory RouteMapBounds.fromJson(Map<String, dynamic> json) => RouteMapBounds(
    minLat: (json['minLat'] as num).toDouble(),
    minLng: (json['minLng'] as num).toDouble(),
    maxLat: (json['maxLat'] as num).toDouble(),
    maxLng: (json['maxLng'] as num).toDouble(),
  );
}

class RouteEvaluationResult {
  const RouteEvaluationResult._({
    this.evaluation,
    this.candidates = const [],
    this.searchFallback = false,
  });

  const RouteEvaluationResult.evaluated(ScheduleEvaluationV2 value)
    : this._(evaluation: value);

  const RouteEvaluationResult.candidates(List<RouteOption> values)
    : this._(candidates: values, searchFallback: true);

  final ScheduleEvaluationV2? evaluation;
  final List<RouteOption> candidates;
  final bool searchFallback;
}

class RouteMapSegment {
  const RouteMapSegment({
    required this.seq,
    required this.mode,
    required this.fromName,
    required this.toName,
    required this.coordinates,
  });

  final int seq;
  final String mode;
  final String? fromName;
  final String? toName;
  final List<RouteCoordinate> coordinates;

  factory RouteMapSegment.fromJson(Map<String, dynamic> json) =>
      RouteMapSegment(
        seq: (json['seq'] as num?)?.toInt() ?? 0,
        mode: json['mode']?.toString() ?? 'UNKNOWN',
        fromName: json['fromName']?.toString(),
        toName: json['toName']?.toString(),
        coordinates: RecommendationApi._maps(json['coordinates'])
            .map(RouteCoordinate.fromJson)
            .toList(growable: false),
      );
}

class SavedRouteMap {
  const SavedRouteMap({
    required this.routeId,
    required this.segments,
    this.bounds,
  });

  final String routeId;
  final RouteMapBounds? bounds;
  final List<RouteMapSegment> segments;

  List<RouteCoordinate> get coordinates =>
      segments.expand((segment) => segment.coordinates).toList(growable: false);

  factory SavedRouteMap.fromJson(Map<String, dynamic> json) {
    final rawBounds = RecommendationApi._asMap(json['bounds']);
    return SavedRouteMap(
      routeId: json['routeId'].toString(),
      bounds: rawBounds.isEmpty ? null : RouteMapBounds.fromJson(rawBounds),
      segments: RecommendationApi._maps(json['segments'])
          .map(RouteMapSegment.fromJson)
          .toList(growable: false),
    );
  }
}

String _transportLabel(String mode) => switch (mode) {
  'WALK' => '도보',
  'BUS' => '버스',
  'SUBWAY' => '지하철',
  'TAXI' => '택시',
  _ => mode,
};

class RecommendationApi {
  const RecommendationApi(this._dio);

  final Dio _dio;

  Future<RouteEvaluationResult> evaluateOrSearch(
    RouteSearchCondition condition,
  ) async {
    try {
      return RouteEvaluationResult.evaluated(await evaluateRoute(condition));
    } on DioException catch (error) {
      if (!const {404, 405, 501}.contains(error.response?.statusCode)) rethrow;
      return RouteEvaluationResult.candidates(await searchRoutes(condition));
    }
  }

  Future<ScheduleEvaluationV2> evaluateRoute(
    RouteSearchCondition condition,
  ) async {
    final response = await _dio.post<Map<String, dynamic>>(
      ApiPaths.routeEvaluate,
      data: {
        'origin': {
          'longitude': condition.origin.lng,
          'latitude': condition.origin.lat,
        },
        'destination': {
          'longitude': condition.destination.lng,
          'latitude': condition.destination.lat,
        },
        'plannedDepartAt': _isoWithOffset(condition.plannedDepartAt),
        'arriveBy': _isoWithOffset(condition.desiredArrivalAt),
      },
    );
    final data = response.data?['data'];
    if (response.data?['success'] != true || data is! Map) {
      throw const FormatException('경로 평가 응답 형식을 확인할 수 없습니다.');
    }
    return ScheduleEvaluationV2.fromJson(_stringKeyed(data));
  }

  Future<List<RouteOption>> searchRoutes(RouteSearchCondition condition) async {
    final response = await _dio.post<Map<String, dynamic>>(
      ApiPaths.routeSearch,
      data: {
        'startLongitude': condition.origin.lng,
        'startLatitude': condition.origin.lat,
        'endLongitude': condition.destination.lng,
        'endLatitude': condition.destination.lat,
      },
    );
    final data = response.data?['data'];
    final candidates = data is Map ? data['candidates'] : null;
    if (response.data?['success'] != true || candidates is! List) {
      throw const FormatException('경로 검색 응답 형식을 확인할 수 없습니다.');
    }
    // 카카오 배열 순서는 빠른 순이 아니다. 평가 전에는 비교할 우리 값이 없으므로
    // 경로 API 가 말한 전체 시간이 짧은 순으로만 세운다. 이 순서는 우리 계산이 아니며
    // 화면도 그렇게 밝힌다.
    final items = <Map<String, dynamic>>[];
    for (final raw in candidates) {
      if (raw is! Map) {
        throw const FormatException('경로 후보 응답 형식이 올바르지 않습니다.');
      }
      items.add(raw.map((key, value) => MapEntry(key.toString(), value)));
    }
    items.sort(
      (a, b) => (a['totalDurationSeconds'] as num).compareTo(
        b['totalDurationSeconds'] as num,
      ),
    );
    return items
        .map((item) {
          final durationMinutes =
              ((item['totalDurationSeconds'] as num).toDouble() / 60).ceil();
          final fare = item['fare'] is Map ? item['fare'] as Map : const {};
          final minimumFare = (fare['minimumWon'] as num?)?.toInt();
          final maximumFare = (fare['maximumWon'] as num?)?.toInt();
          return RouteOption(
            kind: RouteKind.candidate,
            label: '원본 경로 기준 · 가장 빠른 후보',
            transport: switch (item['type']) {
              'BUS' => '버스',
              'SUBWAY' => '지하철',
              _ => '버스 + 지하철',
            },
            departure: '평가 필요',
            arrival: '평가 필요',
            margin: '환승 ${(item['transfers'] as num?)?.toInt() ?? 0}회',
            detail:
                '약 $durationMinutes분 · ${(item['totalDistanceMeters'] as num).toInt()}m',
            fare: _fare(minimumFare, maximumFare),
          );
        })
        .toList(growable: false);
  }

  /// 같은 이유로 여기서도 지역 시각으로 맞춘다. 지금 인자는 일정 API 값(시간대 표기 없음)
  /// 뿐이라 값이 바뀌지 않지만, 나중에 UTC 값이 들어와도 틀리지 않게 둔다.
  static String _time(DateTime value) {
    final local = value.toLocal();
    return '${local.hour.toString().padLeft(2, '0')}:'
        '${local.minute.toString().padLeft(2, '0')}';
  }

  static String _isoWithOffset(DateTime value) {
    if (value.isUtc) return value.toIso8601String();
    final offset = value.timeZoneOffset;
    final totalMinutes = offset.inMinutes.abs();
    final sign = offset.isNegative ? '-' : '+';
    final hours = (totalMinutes ~/ 60).toString().padLeft(2, '0');
    final minutes = (totalMinutes % 60).toString().padLeft(2, '0');
    return '${value.toIso8601String()}$sign$hours:$minutes';
  }

  static String _fare(int? minimum, int? maximum) {
    if (minimum == null) return '요금 정보 없음';
    if (maximum == null || maximum == minimum) return '$minimum원';
    return '$minimum~$maximum원';
  }

  Future<Response<Map<String, dynamic>>> createRecommendation({
    required String scheduleId,
    String? trigger,
    bool? forceRealtime,
  }) => _dio.post<Map<String, dynamic>>(
    ApiPaths.scheduleRecommendations(scheduleId),
    data: {'trigger': ?trigger, 'forceRealtime': ?forceRealtime},
  );

  Future<Response<Map<String, dynamic>>> getLatestRecommendation(
    String scheduleId,
  ) =>
      _dio.get<Map<String, dynamic>>(ApiPaths.latestRecommendation(scheduleId));

  /// 추천 카드 안내를 가져온다.
  ///
  /// 최신 추천이 READY 일 때만 부르고, `evaluationId` 가 바뀐 경우에만 다시 부른다.
  /// 계산이 아직 안 끝났으면 서버가 409 로 답하는데, 그때는 오류가 아니라 "계산 중" 이므로
  /// null 을 돌려주고 화면은 기존 계산 중 안내를 그대로 둔다.
  Future<RecommendationGuide?> getLatestGuide(String scheduleId) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        ApiPaths.latestRecommendationGuide(scheduleId),
      );
      final data = response.data?['data'];
      if (response.data?['success'] != true || data is! Map) return null;
      return RecommendationGuide.fromJson(_stringKeyed(data));
    } on DioException catch (error) {
      final status = error.response?.statusCode;
      // 409 = 아직 계산 중, 404 = 안내가 아직 없음. 둘 다 오류로 다루지 않는다.
      if (status == 409 || status == 404) return null;
      rethrow;
    }
  }

  Future<Response<Map<String, dynamic>>> getRecommendationExplanation(
    String recommendationId,
  ) => _dio.get<Map<String, dynamic>>(
    ApiPaths.recommendationExplanation(recommendationId),
  );

  Future<RecommendationOverview?> getLatestOverview(String scheduleId) async {
    try {
      final response = await getLatestRecommendation(scheduleId);
      final rawData = response.data?['data'];
      if (response.data?['success'] != true || rawData is! Map) {
        throw const FormatException('최신 추천 응답 형식을 확인할 수 없습니다.');
      }
      final data = _stringKeyed(rawData);
      final state = data['state']?.toString() ?? 'UNKNOWN';
      final result = _asMap(data['result']);
      if (result.isEmpty) return RecommendationOverview(state: state);
      // "현재 선택 경로" 칸에 쓰는 값이다. 그래서 <b>지금 선택된 경로</b>만 본다.
      //
      // 예전에는 제안 경로와 "가장 빠른 후보"를 먼저 집었다. 그러면 제목은 현재 선택인데
      // 숫자는 다른 경로 것이 되어, 아래 추천 경로 카드와 20분 넘게 벌어졌다
      // (2026-09-21 운영 자료: 일정 23 은 22분 / 69분).
      // 제안 경로는 아래 "새로 제안된 경로" 카드에서 따로 보여준다.
      final selected = _asMap(result['selected']);
      // 옛 형식 응답에는 selected 가 없고 preview.fastest 만 있다. 그때만 쓴다.
      final fallback = _asMap(_asMap(result['preview'])['fastest']);
      final chosen = selected.isNotEmpty ? selected : fallback;
      final persistence = _asMap(result['persistence']);
      final departureAt = _dateTime(
        chosen['departureAt'] ?? result['plannedDepartAt'],
      );
      final eta = _dateTime(chosen['estimatedArrivalAt']);
      // 올림한다 — 추천 경로 카드·서버 저장값과 같은 규칙이다. 29분 53초를 29분으로
      // 깎으면 같은 경로인데 카드마다 1분씩 어긋난다.
      final durationMin = departureAt == null || eta == null
          ? null
          : (eta.difference(departureAt).inSeconds / 60).ceil();
      return RecommendationOverview(
        state: state,
        recommendationId: result['recommendationId']?.toString(),
        departureAt: departureAt,
        eta: eta,
        durationMin: durationMin,
        confidence: persistence['confidence']?.toString(),
        preview: persistence['preview'] == true,
        source:
            const {
              'HISTORICAL_REFERENCE',
              'MIXED_HISTORICAL_REFERENCE',
            }.contains(chosen['quality'])
            ? chosen['quality'] as String
            : persistence['source']?.toString(),
        fallbackUsed: persistence['fallbackUsed'] == true,
        reasonCodes: _strings(persistence['reasons']),
      );
    } on DioException catch (error) {
      if (error.response?.statusCode == 404) return null;
      rethrow;
    }
  }

  Future<RecommendationExplanation> getExplanation(String id) async {
    final response = await getRecommendationExplanation(id);
    final rawData = response.data?['data'];
    if (response.data?['success'] != true || rawData is! Map) {
      throw const FormatException('추천 근거 응답 형식을 확인할 수 없습니다.');
    }
    final data = _stringKeyed(rawData);
    return RecommendationExplanation(
      recommendationId: data['recommendationId']?.toString() ?? id,
      departureAt: _dateTime(data['departAt']),
      eta: _dateTime(data['eta']),
      durationMin: (data['durationMin'] as num?)?.toInt(),
      confidence: data['confidence']?.toString(),
      dataCheckedAt: _dateTime(data['dataCheckedAt']),
      preview: data['preview'] as bool?,
      source: data['source']?.toString(),
      fallbackUsed: data['fallbackUsed'] as bool?,
      reasonCodes: _strings(data['reasonCodes']),
    );
  }

  static Map<String, dynamic> _asMap(Object? value) =>
      value is Map ? _stringKeyed(value) : const <String, dynamic>{};

  static Map<String, dynamic> _stringKeyed(Map<dynamic, dynamic> value) =>
      value.map((key, item) => MapEntry(key.toString(), item));

  /// 서버 시각 문자열을 읽는다. 읽자마자 지역 시각으로 맞춘다.
  ///
  /// 지금 계약은 두 갈래다 — 화면에 보이는 일정 시각(plannedDepartAt, arriveBy,
  /// proposedDepartAt 등)은 시간대 표기가 없는 KST 이고, 운영·만료 시각(computedAt,
  /// validUntil 등)은 UTC(`...Z`) 다. 여기서 한 번 맞춰 두면 뒤에서 쓰는 곳마다 따로
  /// 신경 쓰지 않아도 되고, 캐시에 남은 예전 UTC 응답도 제대로 보인다.
  /// 시간대 표기가 없는 값에 toLocal 을 불러도 값은 바뀌지 않는다.
  static DateTime? _dateTime(Object? value) =>
      DateTime.tryParse(value?.toString() ?? '')?.toLocal();

  static List<String> _strings(Object? value) => value is List
      ? value.map((item) => item.toString()).toList(growable: false)
      : const [];

  static List<Map<String, dynamic>> _maps(Object? value) => value is List
      ? value.whereType<Map>().map(_stringKeyed).toList(growable: false)
      : const [];

  Future<List<SavedRouteSummary>> getSavedRoutes(String scheduleId) async {
    final response = await getRoutes(scheduleId);
    final data = response.data?['data'];
    if (response.data?['success'] != true || data is! List) {
      throw const FormatException('저장된 경로 목록 응답 형식이 올바르지 않습니다.');
    }
    return _maps(data).map(SavedRouteSummary.fromJson).toList(growable: false);
  }

  Future<SavedRouteDetail> getSavedRouteDetail(String routeId) async {
    final response = await _routeOrGone(() => getRoute(routeId), routeId);
    final data = response.data?['data'];
    if (response.data?['success'] != true || data is! Map) {
      throw const FormatException('경로 상세 응답 형식이 올바르지 않습니다.');
    }
    return SavedRouteDetail.fromJson(_stringKeyed(data));
  }

  Future<SavedRouteMap> getSavedRouteMap(String routeId) async {
    final response = await _routeOrGone(() => getRouteMap(routeId), routeId);
    final data = response.data?['data'];
    if (response.data?['success'] != true || data is! Map) {
      throw const FormatException('경로 지도 응답 형식이 올바르지 않습니다.');
    }
    return SavedRouteMap.fromJson(_stringKeyed(data));
  }

  /// 404 만 [SavedRouteGoneException] 으로 바꾼다. 다른 실패는 그대로 올린다.
  Future<Response<Map<String, dynamic>>> _routeOrGone(
    Future<Response<Map<String, dynamic>>> Function() request,
    String routeId,
  ) async {
    try {
      return await request();
    } on DioException catch (error) {
      if (error.response?.statusCode == 404) {
        throw SavedRouteGoneException(routeId);
      }
      rethrow;
    }
  }

  Future<Response<Map<String, dynamic>>> getRoutes(String scheduleId) =>
      _dio.get<Map<String, dynamic>>(ApiPaths.scheduleRoutes(scheduleId));

  Future<Response<Map<String, dynamic>>> getRoute(String routeId) =>
      _dio.get<Map<String, dynamic>>(ApiPaths.route(routeId));

  Future<Response<Map<String, dynamic>>> getRouteMap(String routeId) =>
      _dio.get<Map<String, dynamic>>(ApiPaths.routeMap(routeId));
}
