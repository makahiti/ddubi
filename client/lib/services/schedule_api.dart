import 'package:dio/dio.dart';

import '../core/constants/api_paths.dart';
import '../domain/models.dart';

class ScheduleApi {
  const ScheduleApi(this._dio);

  final Dio _dio;

  Future<List<Schedule>> getSchedules({
    required DateTime from,
    required DateTime to,
    String? status,
    int page = 0,
    int size = 20,
    String? sort,
  }) async {
    final response = await _dio.get<Map<String, dynamic>>(
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
    final data = response.data?['data'];
    if (response.data?['success'] != true || data is! Map<String, dynamic>) {
      throw const FormatException('일정 목록 응답 형식을 확인할 수 없습니다.');
    }
    final content = data['content'];
    if (content is! List) return const [];
    return content
        .whereType<Map>()
        .map((item) => Schedule.fromJson(_stringKeyed(item)))
        .toList(growable: false);
  }

  Future<Schedule> getSchedule(String id) async {
    final response = await _dio.get<Map<String, dynamic>>(
      ApiPaths.schedule(id),
    );
    return _scheduleFromResponse(response);
  }

  Future<Schedule> createSchedule(Schedule schedule) async {
    final response = await _dio.post<Map<String, dynamic>>(
      ApiPaths.schedules,
      data: schedule.toCreateRequestJson(),
    );
    return _scheduleFromResponse(response);
  }

  Future<Schedule> updateSchedule(Schedule schedule) async {
    if (schedule.updatedAt == null) {
      throw ArgumentError('일정 수정에는 조회 응답의 updatedAt이 필요합니다.');
    }
    try {
      final response = await _dio.patch<Map<String, dynamic>>(
        ApiPaths.schedule(schedule.id),
        data: schedule.toUpdateRequestJson(),
      );
      return _scheduleFromResponse(response);
    } on DioException catch (error) {
      if (error.response?.statusCode == 409) {
        throw const ScheduleConflictException();
      }
      rethrow;
    }
  }

  Schedule _scheduleFromResponse(Response<Map<String, dynamic>> response) {
    final data = response.data?['data'];
    if (response.data?['success'] != true || data is! Map) {
      throw const FormatException('일정 응답 형식을 확인할 수 없습니다.');
    }
    return Schedule.fromJson(_stringKeyed(data));
  }

  static Map<String, dynamic> _stringKeyed(Map<dynamic, dynamic> source) =>
      source.map((key, value) => MapEntry(key.toString(), value));
}
