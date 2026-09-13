import 'package:dio/dio.dart';

import '../core/constants/api_paths.dart';
import '../domain/models.dart';

class ScheduleApi {
  const ScheduleApi(this._dio);

  final Dio _dio;

  /// 인증 Dio로 현재 평가 상태를 읽는다. 실패 응답을 빈 정상 결과로 바꾸지 않는다.
  Future<Map<String, dynamic>> latestEvaluation(String id) async {
    final response = await _dio.get<Map<String, dynamic>>('${ApiPaths.schedule(id)}/recommendations/latest');
    final data = response.data?['data'];
    if (response.data?['success'] != true || data is! Map) {
      throw const FormatException('일정 평가 응답을 확인할 수 없습니다.');
    }
    return data.map((key, value) => MapEntry(key.toString(), value));
  }

  /// 서버에 저장된 평가 ID만 수락하고 사용자 입력 경로/ETA를 전송하지 않는다.
  Future<void> evaluationAction(String id, String action, [Map<String, dynamic>? body]) async {
    await _dio.post<Map<String, dynamic>>('${ApiPaths.schedule(id)}/$action', data: body ?? {});
  }

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

  Future<void> cancelSchedule(String id) async {
    await _dio.delete<void>(ApiPaths.schedule(id));
  }

  Future<List<ScheduleConflict>> getConflicts({
    required DateTime from,
    required DateTime to,
  }) async {
    final response = await _dio.get<Map<String, dynamic>>(
      ApiPaths.scheduleConflicts,
      queryParameters: {
        'from': from.toIso8601String(),
        'to': to.toIso8601String(),
      },
    );
    final data = response.data?['data'];
    final conflicts = data is Map ? data['conflicts'] : null;
    if (response.data?['success'] != true || conflicts is! List) {
      throw const FormatException('일정 충돌 응답 형식을 확인할 수 없습니다.');
    }
    return conflicts
        .whereType<Map>()
        .map((item) => ScheduleConflict.fromJson(_stringKeyed(item)))
        .toList(growable: false);
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
