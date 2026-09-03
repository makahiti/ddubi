import 'package:flutter/foundation.dart';
import 'package:kakao_flutter_sdk_user/kakao_flutter_sdk_user.dart';

/// 카카오 앱 키는 커밋하지 않고 --dart-define-from-file=.env 로 주입한다.
class KakaoAuthConfig {
  const KakaoAuthConfig._();

  static const String nativeAppKey = String.fromEnvironment(
    'KAKAO_NATIVE_APP_KEY',
  );
  static const String javascriptKey = String.fromEnvironment(
    'KAKAO_JAVASCRIPT_KEY',
  );

  static bool get isMobileSupported =>
      !kIsWeb &&
      (defaultTargetPlatform == TargetPlatform.android ||
          defaultTargetPlatform == TargetPlatform.iOS);

  static bool get canUseMobileLogin =>
      isMobileSupported && nativeAppKey.isNotEmpty && _initialized;

  static bool _initialized = false;

  static Future<void> initialize() async {
    if (!isMobileSupported || nativeAppKey.isEmpty) return;
    try {
      await KakaoSdk.init(
        nativeAppKey: nativeAppKey,
        javaScriptAppKey: javascriptKey.isEmpty ? null : javascriptKey,
      );
      _initialized = true;
    } on Object catch (error, stackTrace) {
      debugPrint('Kakao login SDK initialization failed: $error');
      debugPrintStack(stackTrace: stackTrace);
    }
  }
}
