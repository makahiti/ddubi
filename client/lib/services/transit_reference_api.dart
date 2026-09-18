import 'package:dio/dio.dart';

import '../domain/transit_reference.dart';

class TransitReferenceApi {
  const TransitReferenceApi(this._dio);

  // Reuse the authenticated client. No internal API key belongs in the app.
  final Dio _dio;

  Future<TransitReferenceDetail> fetchDetail(
    String kind,
    String referenceId,
  ) async {
    if (!const ['subway', 'road'].contains(kind) ||
        referenceId.isEmpty ||
        referenceId.length > 200) {
      throw const TransitReferenceFailure('조회할 참고자료 식별자를 확인할 수 없습니다.');
    }
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/routes/reference-packages/$kind/${Uri.encodeComponent(referenceId)}',
      );
      final data = response.data?['data'];
      if (response.data?['success'] != true || data is! Map) {
        throw const TransitReferenceFailure('참고자료 상세 응답을 확인할 수 없습니다.');
      }
      return TransitReferenceDetail.fromJson(
        data.map((key, value) => MapEntry(key.toString(), value)),
        referenceId,
      );
    } on DioException {
      throw const TransitReferenceFailure(
        '참고자료를 불러오지 못했습니다. 로그인과 연결 상태를 확인해 주세요.',
      );
    }
  }

  Future<TransitReferenceStatus> fetchStatus() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/routes/data-status',
      );
      final data = response.data?['data'];
      if (response.data?['success'] != true || data is! Map) {
        throw const TransitReferenceFailure('참고자료 상태 응답을 확인할 수 없습니다.');
      }
      return TransitReferenceStatus.fromJson(
        data.map((key, value) => MapEntry(key.toString(), value)),
      );
    } on DioException catch (error) {
      throw TransitReferenceFailure(switch (error.response?.statusCode) {
        401 || 403 => '로그인 또는 자료 조회 권한을 확인해 주세요.',
        404 || 501 => '이 서버는 아직 참고자료 상태 조회를 지원하지 않습니다.',
        _ => '참고자료 상태를 불러오지 못했습니다. 잠시 후 다시 확인해 주세요.',
      });
    }
  }
}
