import 'package:flutter/material.dart';
import 'package:kakao_flutter_sdk_user/kakao_flutter_sdk_user.dart';

import '../core/auth/kakao_auth_config.dart';

class KakaoLoginException implements Exception {
  const KakaoLoginException(this.message);

  final String message;
}

abstract interface class KakaoLoginService {
  Future<String> login(BuildContext context);
}

class KakaoSdkLoginService implements KakaoLoginService {
  const KakaoSdkLoginService();

  @override
  Future<String> login(BuildContext context) async {
    if (!KakaoAuthConfig.isMobileSupported) {
      throw const KakaoLoginException('카카오 로그인은 현재 Android 앱에서 확인할 수 있어요.');
    }
    if (!KakaoAuthConfig.canUseMobileLogin) {
      throw const KakaoLoginException('KAKAO_NATIVE_APP_KEY를 넣고 앱을 다시 실행해주세요.');
    }

    try {
      final token = await UserApi.instance.loginWithKakao(context);
      return token.accessToken;
    } on KakaoClientException catch (error) {
      if (error.reason == ClientErrorCause.cancelled) {
        throw const KakaoLoginException('카카오 로그인이 취소됐어요.');
      }
      throw const KakaoLoginException('카카오 인증을 완료하지 못했어요. 다시 시도해주세요.');
    } on Object {
      throw const KakaoLoginException('카카오 인증 중 문제가 발생했어요. 다시 시도해주세요.');
    }
  }
}
