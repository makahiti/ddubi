import 'package:dio/dio.dart';

import '../core/constants/api_paths.dart';

class RecommendationApi {
  const RecommendationApi(this._dio);

  final Dio _dio;

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
