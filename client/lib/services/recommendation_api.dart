import 'package:dio/dio.dart';

import '../core/constants/api_paths.dart';
import '../domain/models.dart';

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

  Future<Response<Map<String, dynamic>>> getRoutes(
    String scheduleId, {
    String? sort,
    bool includeStale = false,
  }) => _dio.get<Map<String, dynamic>>(
    ApiPaths.scheduleRoutes(scheduleId),
    queryParameters: {'sort': ?sort, 'includeStale': includeStale},
  );

  Future<Response<Map<String, dynamic>>> getRoute(String routeId) =>
      _dio.get<Map<String, dynamic>>(ApiPaths.route(routeId));

  Future<Response<Map<String, dynamic>>> getRouteMap(String routeId) =>
      _dio.get<Map<String, dynamic>>(ApiPaths.routeMap(routeId));
}
