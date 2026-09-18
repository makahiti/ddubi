import 'package:dio/dio.dart';

import '../core/constants/api_paths.dart';
import '../core/network/api_error_response.dart';
import '../domain/models.dart';
import '../domain/schedule_evaluation.dart';
import '../domain/transit_reference.dart';
import 'transit_reference_api.dart';

class ScheduleApi {
  const ScheduleApi(this._dio);

  final Dio _dio;

  Future<TransitReferenceStatus> transitReferenceStatus() =>
      TransitReferenceApi(_dio).fetchStatus();

  Future<TransitReferenceDetail> transitReferenceDetail(
    String kind,
    String referenceId,
  ) => TransitReferenceApi(_dio).fetchDetail(kind, referenceId);

  /// 인증 Dio로 현재 평가 상태를 읽는다. 실패 응답을 빈 정상 결과로 바꾸지 않는다.
  Future<ScheduleEvaluationV2> latestEvaluation(String id) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        ApiPaths.latestRecommendation(id),
      );
      final data = response.data?['data'];
      if (response.data?['success'] != true || data is! Map) {
        throw const FormatException('일정 평가 응답을 확인할 수 없습니다.');
      }
      return ScheduleEvaluationV2.fromJson(_stringKeyed(data));
    } on DioException catch (error) {
      throw _evaluationFailure(error);
    } on FormatException catch (error) {
      throw ScheduleEvaluationFailure(
        kind: EvaluationFailureKind.invalidResponse,
        message: error.message,
      );
    }
  }

  /// 서버에 저장된 평가 ID만 수락하고 사용자 입력 경로/ETA를 전송하지 않는다.
  Future<void> evaluationAction(
    String id,
    String action, [
    Map<String, dynamic>? body,
    Duration? receiveTimeout,
  ]) async {
    try {
      await _dio.post<Map<String, dynamic>>(
        '${ApiPaths.schedule(id)}/$action',
        data: body ?? {},
        // 수동 다시 계산은 서버가 동기로 계산한다(예산 10초 + 경로 조회). 기본 10초보다 길게 기다린다.
        options: receiveTimeout == null
            ? null
            : Options(receiveTimeout: receiveTimeout),
      );
    } on DioException catch (error) {
      throw _evaluationFailure(error);
    }
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

  /// 일정 취소(soft 삭제). 서버는 같은 요청이 다시 와도 204 로 답한다(멱등).
  /// 403(남의 일정)·404(없는 일정)·409(완료된 일정)·연결 실패를 구분해 던진다.
  Future<void> cancelSchedule(String id) async {
    try {
      await _dio.delete<void>(ApiPaths.schedule(id));
    } on DioException catch (error) {
      throw _commandFailure(error, '일정을 취소하지 못했습니다.');
    }
  }

  ScheduleCommandFailure _commandFailure(
    DioException error,
    String fallbackMessage,
  ) {
    final raw = error.response?.data;
    final apiError = raw is Map
        ? ApiErrorResponse.fromJson(_stringKeyed(raw))
        : null;
    final kind = switch (error.response?.statusCode) {
      401 => ScheduleCommandFailureKind.unauthenticated,
      403 => ScheduleCommandFailureKind.forbidden,
      404 => ScheduleCommandFailureKind.notFound,
      409 => ScheduleCommandFailureKind.conflict,
      _
          when error.type == DioExceptionType.connectionError ||
              error.type == DioExceptionType.connectionTimeout ||
              error.type == DioExceptionType.receiveTimeout ||
              error.type == DioExceptionType.sendTimeout =>
        ScheduleCommandFailureKind.network,
      _ => ScheduleCommandFailureKind.unknown,
    };
    return ScheduleCommandFailure(
      kind: kind,
      message: apiError?.message ?? fallbackMessage,
      code: apiError?.code,
    );
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

  ScheduleEvaluationFailure _evaluationFailure(DioException error) {
    final status = error.response?.statusCode;
    final raw = error.response?.data;
    final apiError = raw is Map
        ? ApiErrorResponse.fromJson(_stringKeyed(raw))
        : null;
    final kind = switch (status) {
      400 || 422 => EvaluationFailureKind.validation,
      401 || 403 => EvaluationFailureKind.authentication,
      404 => EvaluationFailureKind.notFound,
      409 => EvaluationFailureKind.staleEvaluation,
      429 => EvaluationFailureKind.rateLimited,
      503 => EvaluationFailureKind.dependencyUnavailable,
      _
          when error.type == DioExceptionType.connectionError ||
              error.type == DioExceptionType.connectionTimeout ||
              error.type == DioExceptionType.receiveTimeout ||
              error.type == DioExceptionType.sendTimeout =>
        EvaluationFailureKind.network,
      _ => EvaluationFailureKind.unknown,
    };
    return ScheduleEvaluationFailure(
      kind: kind,
      message: apiError?.message ?? '일정 평가 요청을 처리하지 못했습니다.',
      code: apiError?.code,
      fieldErrors: {
        for (final fieldError
            in apiError?.fieldErrors ?? const <ApiFieldError>[])
          fieldError.field: fieldError.reason,
      },
    );
  }
}
