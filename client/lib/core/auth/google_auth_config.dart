import 'package:flutter/foundation.dart';
import 'package:google_sign_in/google_sign_in.dart';

class GoogleAuthConfig {
  const GoogleAuthConfig._();

  static const String webClientId = String.fromEnvironment(
    'GOOGLE_WEB_CLIENT_ID',
  );

  static bool _initialized = false;

  static bool get isAndroidSupported =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  static bool get canUseAndroidLogin =>
      isAndroidSupported && webClientId.isNotEmpty && _initialized;

  static Future<void> initialize() async {
    if (!isAndroidSupported || webClientId.isEmpty) return;
    try {
      await GoogleSignIn.instance.initialize(serverClientId: webClientId);
      _initialized = true;
    } on Object catch (error, stackTrace) {
      debugPrint('Google Sign-In SDK initialization failed: $error');
      debugPrintStack(stackTrace: stackTrace);
    }
  }
}
