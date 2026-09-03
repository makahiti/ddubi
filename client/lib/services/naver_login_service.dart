import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class NaverLoginException implements Exception {
  const NaverLoginException(this.message);

  final String message;
}

abstract interface class NaverLoginService {
  Future<String> login(BuildContext context);
}

class NaverSdkLoginService implements NaverLoginService {
  const NaverSdkLoginService();

  static const _channel = MethodChannel('com.ssafy.ddubi/naver_login');

  @override
  Future<String> login(BuildContext context) async {
    final isAndroid =
        !kIsWeb && defaultTargetPlatform == TargetPlatform.android;
    if (!isAndroid) {
      throw const NaverLoginException('네이버 로그인은 현재 Android 앱에서 확인할 수 있어요.');
    }

    try {
      final accessToken = await _channel.invokeMethod<String>('login');
      if (accessToken == null || accessToken.isEmpty) {
        throw const NaverLoginException('네이버 인증 토큰을 받지 못했어요. 설정을 확인해주세요.');
      }
      return accessToken;
    } on PlatformException catch (error) {
      if (error.code == 'NOT_CONFIGURED') {
        throw const NaverLoginException(
          'NAVER_CLIENT_ID와 NAVER_CLIENT_SECRET을 설정하고 앱을 다시 실행해주세요.',
        );
      }
      if (error.code == 'CANCELED') {
        throw const NaverLoginException('네이버 로그인이 취소되었어요.');
      }
      throw const NaverLoginException('네이버 인증을 완료하지 못했어요. 다시 시도해주세요.');
    } on NaverLoginException {
      rethrow;
    } on Object {
      throw const NaverLoginException('네이버 인증 중 문제가 발생했어요. 다시 시도해주세요.');
    }
  }
}
