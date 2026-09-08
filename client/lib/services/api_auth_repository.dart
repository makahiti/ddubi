import 'package:dio/dio.dart';

import '../core/network/api_error_response.dart';
import '../domain/auth.dart';
import 'auth_api.dart';
import 'device_token_service.dart';

class ApiAuthRepository implements AuthRepository {
  const ApiAuthRepository({
    required AuthApi api,
    required AuthSessionStore sessionStore,
    required DeviceTokenService deviceTokenService,
  }) : this._(api, sessionStore, deviceTokenService);

  const ApiAuthRepository._(
    this._api,
    this._sessionStore,
    this._deviceTokenService,
  );

  final AuthApi _api;
  final AuthSessionStore _sessionStore;
  final DeviceTokenService _deviceTokenService;

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
    _deviceTokenService.registerAfterAuthentication();
  }

  @override
  Future<void> requestEmailVerification(String email) async {
    try {
      await _api.requestEmailVerification(email);
    } on DioException catch (error) {
      throw _mapDioException(error);
    }
  }

  @override
  Future<void> confirmEmailVerification({
    required String email,
    required String code,
  }) async {
    try {
      await _api.confirmEmailVerification(email: email, code: code);
    } on DioException catch (error) {
      throw _mapDioException(error);
    }
  }

  @override
  Future<void> signUp({
    required String email,
    required String password,
    required String nickname,
  }) async {
    try {
      final response = await _api.signUp(
        email: email,
        password: password,
        nickname: nickname,
      );
      _saveSession(response.data);
    } on DioException catch (error) {
      throw _mapDioException(error);
    }
  }

  @override
  Future<void> logout() async {
    final session = _sessionStore.session;
    if (session == null) return;
    try {
      await _api.logout(
        refreshToken: session.refreshToken,
        deviceId: session.deviceId,
      );
    } on DioException catch (error) {
      throw _mapDioException(error);
    } finally {
      _sessionStore.clear();
    }
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

    if (error.response?.statusCode == 409) {
      return AuthException(
        AuthErrorCode.emailAlreadyExists,
        message: apiError?.message ?? '이미 사용 중인 이메일이에요.',
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
