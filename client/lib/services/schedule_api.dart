import 'package:dio/dio.dart';

import '../core/constants/api_paths.dart';

class ScheduleApi {
  const ScheduleApi(this._dio);

  final Dio _dio;

  Future<Response<Map<String, dynamic>>> getSchedules({
    required DateTime from,
    required DateTime to,
    String? status,
    int page = 0,
    int size = 20,
    String? sort,
  }) => _dio.get<Map<String, dynamic>>(
    ApiPaths.schedules,
    queryParameters: {
      'from': from.toIso8601String(),
      'to': to.toIso8601String(),
      'status': ?status,
      'page': page,
      'size': size,
      'sort': ?sort,
    },
  );

  Future<Response<Map<String, dynamic>>> getSchedule(String id) =>
      _dio.get<Map<String, dynamic>>(ApiPaths.schedule(id));

  Future<Response<Map<String, dynamic>>> createSchedule(
    Map<String, dynamic> request,
  ) => _dio.post<Map<String, dynamic>>(ApiPaths.schedules, data: request);
}
