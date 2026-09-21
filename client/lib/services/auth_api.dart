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

  /// 마이페이지 소셜 연결.
  ///
  /// 카카오는 액세스 토큰, 구글은 ID 토큰, 네이버는 인가 코드를 보낸다. 네이버가 인가 코드인
  /// 이유는 앱이 SDK 가 아니라 웹 창으로 로그인해서 토큰이 아니라 코드만 돌아오기 때문이다.
  Future<Response<Map<String, dynamic>>> linkSocialAccount({
    required String provider,
    String? accessToken,
    String? idToken,
    String? authorizationCode,
    String? redirectUri,
    String? state,
  }) => _dio.post<Map<String, dynamic>>(
    ApiPaths.socialLinks,
    data: {
      'provider': provider,
      'accessToken': ?accessToken,
      'idToken': ?idToken,
      'authorizationCode': ?authorizationCode,
      'redirectUri': ?redirectUri,
      'state': ?state,
    },
  );

  /// 비밀번호 변경. 성공하면 다른 기기는 모두 로그아웃되고 이 기기용 새 토큰이 온다.
  Future<Response<Map<String, dynamic>>> changePassword({
    required String currentPassword,
    required String newPassword,
    String? deviceId,
  }) => _dio.patch<Map<String, dynamic>>(
    ApiPaths.passwordChange,
    data: {
      'currentPassword': currentPassword,
      'newPassword': newPassword,
      'deviceId': ?deviceId,
    },
  );

  /// 이메일 변경. 새 주소의 인증(코드 발송·확인)을 먼저 마쳐야 한다.
  Future<Response<Map<String, dynamic>>> changeEmail({
    required String newEmail,
    String? password,
  }) => _dio.patch<Map<String, dynamic>>(
    ApiPaths.emailChange,
    data: {'newEmail': newEmail, 'password': ?password},
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
