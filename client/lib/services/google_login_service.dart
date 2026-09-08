import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';

import '../core/auth/google_auth_config.dart';

class GoogleLoginException implements Exception {
  const GoogleLoginException(this.message);

  final String message;
}

abstract interface class GoogleLoginService {
  Future<String> login(BuildContext context);
}

class GoogleSdkLoginService implements GoogleLoginService {
  const GoogleSdkLoginService();

  @override
  Future<String> login(BuildContext context) async {
    if (!GoogleAuthConfig.isAndroidSupported) {
      throw const GoogleLoginException('Google 로그인은 현재 Android 앱에서 확인할 수 있어요.');
    }
    if (!GoogleAuthConfig.canUseAndroidLogin) {
      throw const GoogleLoginException(
        'GOOGLE_WEB_CLIENT_ID를 설정하고 앱을 다시 실행해주세요.',
      );
    }

    try {
      final account = await GoogleSignIn.instance.authenticate();
      final idToken = account.authentication.idToken;
      if (idToken == null || idToken.isEmpty) {
        throw const GoogleLoginException('Google 인증 토큰을 받지 못했어요. 설정을 확인해주세요.');
      }
      return idToken;
    } on GoogleSignInException catch (error) {
      if (error.code == GoogleSignInExceptionCode.canceled) {
        throw const GoogleLoginException('Google 로그인이 취소되었어요.');
      }
      if (error.code == GoogleSignInExceptionCode.clientConfigurationError ||
          error.code == GoogleSignInExceptionCode.providerConfigurationError) {
        throw const GoogleLoginException('Google 로그인 앱 설정을 확인해주세요.');
      }
      throw const GoogleLoginException('Google 인증을 완료하지 못했어요. 다시 시도해주세요.');
    } on GoogleLoginException {
      rethrow;
    } on Object {
      throw const GoogleLoginException('Google 인증 중 문제가 발생했어요. 다시 시도해주세요.');
    }
  }
}
