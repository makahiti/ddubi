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
      await _saveSession(response.data);
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
      await _handleOAuthResponse(response.data, provider);
    } on DioException catch (error) {
      final linkRequired = _accountLinkRequired(error, provider);
      if (linkRequired != null) throw linkRequired;
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
  Future<void> loginWithOAuthCode({
    required String provider,
    required String authorizationCode,
    required String redirectUri,
    required String state,
  }) async {
    try {
      final response = await _api.loginWithOAuthCode(
        provider: provider,
        authorizationCode: authorizationCode,
        redirectUri: redirectUri,
        state: state,
      );
      await _handleOAuthResponse(response.data, provider);
    } on DioException catch (error) {
      final linkRequired = _accountLinkRequired(error, provider);
      if (linkRequired != null) throw linkRequired;
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
  Future<void> linkOAuthAccount({
    required String linkToken,
    required String password,
  }) async {
    try {
      final response = await _api.linkOAuthAccount(
        linkToken: linkToken,
        password: password,
      );
      await _saveSession(response.data);
    } on DioException catch (error) {
      throw _mapDioException(error);
    } on AuthException {
      rethrow;
    } on Object {
      throw const AuthException(
        AuthErrorCode.serverError,
        message: '계정 연동 처리 중 문제가 발생했습니다.',
      );
    }
  }

  @override
  Future<List<String>> linkSocialAccount({
    required String provider,
    String? accessToken,
    String? idToken,
  }) async {
    try {
      final response = await _api.linkSocialAccount(
        provider: provider,
        accessToken: accessToken,
        idToken: idToken,
      );
      return _socialProviders(response.data);
    } on DioException catch (error) {
      throw _mapDioException(error);
    }
  }

  @override
  Future<List<String>> unlinkSocialAccount(String provider) async {
    try {
      final response = await _api.unlinkSocialAccount(provider);
      return _socialProviders(response.data);
    } on DioException catch (error) {
      throw _mapDioException(error);
    }
  }

  List<String> _socialProviders(Map<String, dynamic>? body) {
    final rawData = body?['data'];
    final data = rawData is Map
        ? rawData.map((key, value) => MapEntry(key.toString(), value))
        : const <String, dynamic>{};
    final providers = data['providers'];
    if (body?['success'] != true || providers is! List) {
      throw const AuthException(
        AuthErrorCode.serverError,
        message: '소셜 계정 연결 응답을 확인할 수 없습니다.',
      );
    }
    return providers.map((item) => item.toString()).toList(growable: false);
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
      await _handleOAuthResponse(response.data, provider);
    } on DioException catch (error) {
      final linkRequired = _accountLinkRequired(error, provider);
      if (linkRequired != null) throw linkRequired;
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

  Future<void> _saveSession(Map<String, dynamic>? body) async {
    final data = body?['data'];
    if (body?['success'] != true || data is! Map<String, dynamic>) {
      throw const AuthException(
        AuthErrorCode.serverError,
        message: '로그인 응답 형식을 확인할 수 없습니다.',
      );
    }
    final tokens = data['tokens'];
    final sessionData = tokens is Map
        ? tokens.map((key, value) => MapEntry(key.toString(), value))
        : data;
    try {
      await _sessionStore.save(AuthSession.fromJson(sessionData));
    } on Object {
      await _sessionStore.clear();
      rethrow;
    }
    _deviceTokenService.registerAfterAuthentication();
  }

  Future<void> _handleOAuthResponse(
    Map<String, dynamic>? body,
    String fallbackProvider,
  ) async {
    final rawData = body?['data'];
    if (body?['success'] != true || rawData is! Map) {
      throw const AuthException(
        AuthErrorCode.serverError,
        message: '로그인 응답 형식을 확인할 수 없습니다.',
      );
    }
    final data = rawData.map((key, value) => MapEntry(key.toString(), value));
    final status = data['status']?.toString();
    if (status == 'ACCOUNT_LINK_REQUIRED') {
      final linkToken = data['linkToken']?.toString() ?? '';
      if (linkToken.isEmpty) {
        throw const AuthException(
          AuthErrorCode.serverError,
          message: '계정 연동 정보를 확인할 수 없습니다.',
        );
      }
      throw AccountLinkRequiredException(
        linkToken: linkToken,
        provider: data['provider']?.toString() ?? fallbackProvider,
        email: data['email']?.toString() ?? '',
      );
    }
    if (status != null && status != 'AUTHENTICATED') {
      throw const AuthException(
        AuthErrorCode.serverError,
        message: '지원하지 않는 로그인 상태입니다.',
      );
    }
    await _saveSession(body);
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
      await _saveSession(response.data);
    } on DioException catch (error) {
      throw _mapDioException(error);
    }
  }

  @override
  Future<void> logout() async {
    final session = _sessionStore.session;
    if (session == null) return;
    try {
      try {
        await _deviceTokenService.unregisterBeforeLogout();
      } finally {
        await _api.logout(
          refreshToken: session.refreshToken,
          deviceId: session.deviceId,
        );
      }
    } on DioException catch (error) {
      throw _mapDioException(error);
    } finally {
      await _sessionStore.clear();
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
        message: '서버에 연결할 수 없습니다.',
      );
    }

    return AuthException(
      AuthErrorCode.serverError,
      message: apiError?.message ?? '잠시 후 다시 시도해주세요.',
    );
  }

  AccountLinkRequiredException? _accountLinkRequired(
    DioException error,
    String fallbackProvider,
  ) {
    final raw = error.response?.data;
    if (raw is! Map) return null;
    final body = raw.map((key, value) => MapEntry(key.toString(), value));
    final rawError = body['error'];
    final errorBody = rawError is Map
        ? rawError.map((key, value) => MapEntry(key.toString(), value))
        : const <String, dynamic>{};
    final code = errorBody['code']?.toString() ?? body['code']?.toString();
    if (code != 'ACCOUNT_LINK_REQUIRED') return null;

    final rawData = body['data'] ?? errorBody['data'];
    if (rawData is! Map) return null;
    final data = rawData.map((key, value) => MapEntry(key.toString(), value));
    final linkToken = data['linkToken']?.toString() ?? '';
    if (linkToken.isEmpty) return null;
    return AccountLinkRequiredException(
      linkToken: linkToken,
      provider: data['provider']?.toString() ?? fallbackProvider,
      email: data['email']?.toString() ?? '',
    );
  }
}
