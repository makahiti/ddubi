import 'package:dio/dio.dart';

import '../core/constants/api_paths.dart';
import '../domain/models.dart';

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

  RouteOption toRouteOption(Schedule schedule) => RouteOption(
    kind: RouteKind.recommended,
    label: preview ? '최신 추천 · 검증용' : '최신 추천',
    transport: '${schedule.departurePlace} → ${schedule.place}',
    departure: _clock(departureAt),
    arrival: _clock(eta),
    margin: _sourceLabel(source),
    detail: durationMin == null ? '예상 이동시간 확인 중' : '약 $durationMin분',
    fare: confidence == null ? '신뢰도 확인 중' : '신뢰도 $confidence',
  );

  static String _clock(DateTime? value) => value == null
      ? '--:--'
      : '${value.hour.toString().padLeft(2, '0')}:'
            '${value.minute.toString().padLeft(2, '0')}';

  static String _sourceLabel(String? source) => switch (source) {
    'API_ONLY' => '경로 API 시간 기준',
    'ML_PREVIEW' => '버스 예측 포함',
    'SUBWAY_PREVIEW' => '지하철 시간표 포함',
    'MIXED_PREVIEW' => '복합 교통 데이터 기준',
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
    required this.selected,
    required this.baseDurationMin,
    required this.fromName,
    required this.toName,
    required this.segmentCount,
    required this.modes,
  });

  final String routeId;
  final int rankNo;
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
        selected: json['selected'] == true,
        baseDurationMin: (json['baseDurationMin'] as num?)?.toInt() ?? 0,
        fromName: json['fromName']?.toString(),
        toName: json['toName']?.toString(),
        segmentCount: (json['segmentCount'] as num?)?.toInt() ?? 0,
        modes: RecommendationApi._strings(json['modes']),
      );

  RouteOption toRouteOption(Schedule schedule) {
    final arrival = schedule.arriveBy;
    final departure = arrival.subtract(Duration(minutes: baseDurationMin));
    return RouteOption(
      kind: selected
          ? RouteKind.recommended
          : rankNo == 2
          ? RouteKind.fastest
          : RouteKind.safest,
      label: selected ? '선택된 추천 경로' : '${rankNo == 0 ? '-' : rankNo}순위 경로',
      transport: modes.isEmpty
          ? '이동수단 정보 없음'
          : modes.map(_transportLabel).join(' · '),
      departure: RecommendationApi._time(departure),
      arrival: RecommendationApi._time(arrival),
      margin: '$segmentCount개 이동 구간',
      detail: '약 $baseDurationMin분',
      fare: '요금 정보 없음',
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

class SavedRouteDetail {
  const SavedRouteDetail({
    required this.routeId,
    required this.scheduleId,
    required this.rankNo,
    required this.selected,
    required this.baseDurationMin,
    required this.segments,
    this.providerPathId,
  });

  final String routeId;
  final String scheduleId;
  final int rankNo;
  final bool selected;
  final int baseDurationMin;
  final String? providerPathId;
  final List<SavedRouteSegment> segments;

  factory SavedRouteDetail.fromJson(Map<String, dynamic> json) =>
      SavedRouteDetail(
        routeId: json['routeId'].toString(),
        scheduleId: json['scheduleId'].toString(),
        rankNo: (json['rankNo'] as num?)?.toInt() ?? 0,
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
    return candidates.indexed
        .map((entry) {
          final index = entry.$1;
          final raw = entry.$2;
          if (raw is! Map) {
            throw const FormatException('경로 후보 응답 형식이 올바르지 않습니다.');
          }
          final item = raw.map((key, value) => MapEntry(key.toString(), value));
          final durationMinutes =
              ((item['totalDurationSeconds'] as num).toDouble() / 60).ceil();
          final departureAt = condition.desiredArrivalAt.subtract(
            Duration(minutes: durationMinutes),
          );
          final fare = item['fare'] is Map ? item['fare'] as Map : const {};
          final minimumFare = (fare['minimumWon'] as num?)?.toInt();
          final maximumFare = (fare['maximumWon'] as num?)?.toInt();
          return RouteOption(
            kind: index == 0
                ? RouteKind.recommended
                : index == 1
                ? RouteKind.fastest
                : RouteKind.safest,
            label: index == 0 ? '추천 경로' : '경로 ${index + 1}',
            transport: switch (item['type']) {
              'BUS' => '버스',
              'SUBWAY' => '지하철',
              _ => '버스 + 지하철',
            },
            departure: _time(departureAt),
            arrival: _time(condition.desiredArrivalAt),
            margin: '환승 ${(item['transfers'] as num?)?.toInt() ?? 0}회',
            detail:
                '약 $durationMinutes분 · ${(item['totalDistanceMeters'] as num).toInt()}m',
            fare: _fare(minimumFare, maximumFare),
          );
        })
        .toList(growable: false);
  }

  static String _time(DateTime value) =>
      '${value.hour.toString().padLeft(2, '0')}:'
      '${value.minute.toString().padLeft(2, '0')}';

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
      final preview = _asMap(result['preview']);
      final previewFastest = _asMap(preview['fastest']);
      final selected = previewFastest.isNotEmpty
          ? previewFastest
          : _asMap(result['selected']);
      final proposed = _asMap(_asMap(result['suggestion'])['proposed']);
      final persistence = _asMap(result['persistence']);
      final departureAt = _dateTime(
        proposed['departureAt'] ??
            selected['departureAt'] ??
            result['plannedDepartAt'],
      );
      final eta = _dateTime(
        proposed['estimatedArrivalAt'] ?? selected['estimatedArrivalAt'],
      );
      final durationMin = departureAt == null || eta == null
          ? null
          : eta.difference(departureAt).inMinutes;
      return RecommendationOverview(
        state: state,
        recommendationId: result['recommendationId']?.toString(),
        departureAt: departureAt,
        eta: eta,
        durationMin: durationMin,
        confidence: persistence['confidence']?.toString(),
        preview: persistence['preview'] == true,
        source: persistence['source']?.toString(),
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

  static DateTime? _dateTime(Object? value) =>
      DateTime.tryParse(value?.toString() ?? '');

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
    final response = await getRoute(routeId);
    final data = response.data?['data'];
    if (response.data?['success'] != true || data is! Map) {
      throw const FormatException('경로 상세 응답 형식이 올바르지 않습니다.');
    }
    return SavedRouteDetail.fromJson(_stringKeyed(data));
  }

  Future<SavedRouteMap> getSavedRouteMap(String routeId) async {
    final response = await getRouteMap(routeId);
    final data = response.data?['data'];
    if (response.data?['success'] != true || data is! Map) {
      throw const FormatException('경로 지도 응답 형식이 올바르지 않습니다.');
    }
    return SavedRouteMap.fromJson(_stringKeyed(data));
  }

  Future<Response<Map<String, dynamic>>> getRoutes(String scheduleId) =>
      _dio.get<Map<String, dynamic>>(ApiPaths.scheduleRoutes(scheduleId));

  Future<Response<Map<String, dynamic>>> getRoute(String routeId) =>
      _dio.get<Map<String, dynamic>>(ApiPaths.route(routeId));

  Future<Response<Map<String, dynamic>>> getRouteMap(String routeId) =>
      _dio.get<Map<String, dynamic>>(ApiPaths.routeMap(routeId));
}
