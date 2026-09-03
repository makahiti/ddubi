import 'package:dio/dio.dart';

import '../core/network/api_error_response.dart';
import '../domain/auth.dart';
import 'auth_api.dart';

class ApiAuthRepository implements AuthRepository {
  const ApiAuthRepository({
    required AuthApi api,
    required AuthSessionStore sessionStore,
  }) : this._(api, sessionStore);

  const ApiAuthRepository._(this._api, this._sessionStore);

  final AuthApi _api;
  final AuthSessionStore _sessionStore;

  @override
  Future<void> login({required String email, required String password}) async {
    try {
      final response = await _api.login(email: email, password: password);
      _saveSession(response.data);
    } on DioException catch (error) {
      throw _mapDioException(error);
    } on AuthException {
      rethrow;
    } on Object {
      throw const AuthException(
        AuthErrorCode.serverError,
        message: '로그인 처리 중 문제가 발생했습니다.',
      );
    }
  }

  @override
  Future<void> loginWithOAuthToken({
    required String provider,
    required String accessToken,
  }) async {
    try {
      final response = await _api.loginWithOAuthToken(
        provider: provider,
        accessToken: accessToken,
      );
      _saveSession(response.data);
    } on DioException catch (error) {
      throw _mapDioException(error);
    } on AuthException {
      rethrow;
    } on Object {
      throw const AuthException(
        AuthErrorCode.serverError,
        message: '소셜 로그인 처리 중 문제가 발생했습니다.',
      );
    }
  }

  @override
  Future<void> loginWithOAuthIdToken({
    required String provider,
    required String idToken,
  }) async {
    try {
      final response = await _api.loginWithOAuthIdToken(
        provider: provider,
        idToken: idToken,
      );
      _saveSession(response.data);
    } on DioException catch (error) {
      throw _mapDioException(error);
    } on AuthException {
      rethrow;
    } on Object {
      throw const AuthException(
        AuthErrorCode.serverError,
        message: '소셜 로그인 처리 중 문제가 발생했습니다.',
      );
    }
  }

  void _saveSession(Map<String, dynamic>? body) {
    final data = body?['data'];
    if (body?['success'] != true || data is! Map<String, dynamic>) {
      throw const AuthException(
        AuthErrorCode.serverError,
        message: '로그인 응답 형식을 확인할 수 없습니다.',
      );
    }
    _sessionStore.save(AuthSession.fromJson(data));
  }

  @override
  Future<void> signUp({required String email, required String password}) {
    throw const AuthException(
      AuthErrorCode.registrationRequiresVerification,
      message: '실제 회원가입은 이메일 인증 화면 연결 후 사용할 수 있어요.',
    );
  }

  AuthException _mapDioException(DioException error) {
    final responseBody = error.response?.data;
    ApiErrorResponse? apiError;
    if (responseBody is Map<String, dynamic>) {
      apiError = ApiErrorResponse.fromJson(responseBody);
    } else if (responseBody is Map) {
      apiError = ApiErrorResponse.fromJson(
        responseBody.map((key, value) => MapEntry(key.toString(), value)),
      );
    }

    if (error.response?.statusCode == 401) {
      return AuthException(
        AuthErrorCode.invalidCredentials,
        message: apiError?.message ?? '이메일 또는 비밀번호를 확인해주세요.',
      );
    }

    if (error.type == DioExceptionType.connectionError ||
        error.type == DioExceptionType.connectionTimeout ||
        error.type == DioExceptionType.receiveTimeout ||
        error.type == DioExceptionType.sendTimeout) {
      return const AuthException(
        AuthErrorCode.connectionFailed,
        message: '로컬 백엔드 서버에 연결할 수 없습니다.',
      );
    }

    return AuthException(
      AuthErrorCode.serverError,
      message: apiError?.message ?? '잠시 후 다시 시도해주세요.',
    );
  }
}
