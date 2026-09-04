import 'package:dio/dio.dart';

import '../core/constants/api_paths.dart';

class AuthApi {
  const AuthApi(this._dio);

  final Dio _dio;

  Future<void> requestEmailVerification(String email) async {
    await _dio.post<void>(ApiPaths.emailVerification, data: {'email': email});
  }

  Future<void> confirmEmailVerification({
    required String email,
    required String code,
  }) async {
    await _dio.post<void>(
      ApiPaths.emailVerificationConfirm,
      data: {'email': email, 'code': code},
    );
  }

  Future<Response<Map<String, dynamic>>> login({
    required String email,
    required String password,
    String? deviceId,
  }) {
    return _dio.post<Map<String, dynamic>>(
      ApiPaths.login,
      data: {'email': email, 'password': password, 'deviceId': ?deviceId},
    );
  }

  Future<Response<Map<String, dynamic>>> signUp({
    required String email,
    required String password,
    required String nickname,
    String? deviceId,
  }) {
    return _dio.post<Map<String, dynamic>>(
      ApiPaths.signUp,
      data: {
        'email': email,
        'password': password,
        'nickname': nickname,
        'deviceId': ?deviceId,
      },
    );
  }

  Future<Response<Map<String, dynamic>>> loginWithOAuthToken({
    required String provider,
    required String accessToken,
    String? deviceId,
  }) {
    return _dio.post<Map<String, dynamic>>(
      ApiPaths.oauthTokenLogin,
      data: {
        'provider': provider,
        'accessToken': accessToken,
        'deviceId': ?deviceId,
      },
    );
  }

  Future<Response<Map<String, dynamic>>> loginWithOAuthIdToken({
    required String provider,
    required String idToken,
    String? deviceId,
  }) {
    return _dio.post<Map<String, dynamic>>(
      ApiPaths.oauthIdTokenLogin,
      data: {'provider': provider, 'idToken': idToken, 'deviceId': ?deviceId},
    );
  }

  Future<void> logout({
    required String refreshToken,
    required String deviceId,
  }) async {
    await _dio.post<void>(
      ApiPaths.logout,
      data: {'refreshToken': refreshToken, 'deviceId': deviceId},
    );
  }
}
