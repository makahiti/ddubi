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

  Future<Response<Map<String, dynamic>>> loginWithOAuthCode({
    required String provider,
    required String authorizationCode,
    required String redirectUri,
    required String state,
    String? deviceId,
  }) {
    return _dio.post<Map<String, dynamic>>(
      ApiPaths.oauthLogin,
      data: {
        'provider': provider,
        'authorizationCode': authorizationCode,
        'redirectUri': redirectUri,
        'state': state,
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

  Future<Response<Map<String, dynamic>>> linkOAuthAccount({
    required String linkToken,
    required String password,
    String? deviceId,
  }) {
    return _dio.post<Map<String, dynamic>>(
      ApiPaths.oauthLink,
      data: {
        'linkToken': linkToken,
        'password': password,
        'deviceId': ?deviceId,
      },
    );
  }

  Future<Response<Map<String, dynamic>>> linkSocialAccount({
    required String provider,
    String? accessToken,
    String? idToken,
  }) => _dio.post<Map<String, dynamic>>(
    ApiPaths.socialLinks,
    data: {
      'provider': provider,
      'accessToken': ?accessToken,
      'idToken': ?idToken,
    },
  );

  Future<Response<Map<String, dynamic>>> unlinkSocialAccount(String provider) =>
      _dio.delete<Map<String, dynamic>>(ApiPaths.socialLink(provider));

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
