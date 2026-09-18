import 'package:flutter/foundation.dart';
import 'package:kakao_maps_flutter/kakao_maps_flutter.dart';

import '../diagnostics/app_log.dart';

/// Kakao Maps keys are supplied at build/run time so they are never committed.
class KakaoMapConfig {
  const KakaoMapConfig._();

  static const String nativeAppKey = String.fromEnvironment(
    'KAKAO_NATIVE_APP_KEY',
  );
  static const String javascriptKey = String.fromEnvironment(
    'KAKAO_JAVASCRIPT_KEY',
  );

  static bool _initialized = false;
  static Object? _initializationError;

  static bool get isSupportedPlatform =>
      kIsWeb ||
      defaultTargetPlatform == TargetPlatform.android ||
      defaultTargetPlatform == TargetPlatform.iOS;

  static bool get hasRequiredKey =>
      kIsWeb ? javascriptKey.isNotEmpty : nativeAppKey.isNotEmpty;

  static bool get isReady =>
      isSupportedPlatform && hasRequiredKey && _initialized;

  static Object? get initializationError => _initializationError;

  static Future<void> initialize() async {
    if (!isSupportedPlatform || !hasRequiredKey) return;

    try {
      await KakaoMapsFlutter.init(
        kIsWeb ? null : nativeAppKey,
        webAPIKey: javascriptKey.isEmpty ? null : javascriptKey,
      ).timeout(const Duration(seconds: 12));
      _initialized = true;
    } catch (error, stackTrace) {
      _initializationError = error;
      AppLog.debug('Kakao Maps initialization failed: $error');
      AppLog.stack(stackTrace);
    }
  }
}
