import 'dart:async';

import 'package:dio/dio.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';

import '../core/constants/api_paths.dart';
import '../domain/auth.dart';

const expectedFirebaseProjectId = 'ddubi-85d2b';

bool get _supportsFirebaseMessaging =>
    !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

Future<void> initializeFirebaseForAndroid() async {
  if (!_supportsFirebaseMessaging) return;

  try {
    final app = await Firebase.initializeApp();
    if (app.options.projectId != expectedFirebaseProjectId) {
      throw StateError(
        'FCM project mismatch: expected $expectedFirebaseProjectId, '
        'got ${app.options.projectId}',
      );
    }
  } on Object catch (error, stackTrace) {
    debugPrint('Firebase initialization skipped: $error');
    debugPrintStack(stackTrace: stackTrace);
  }
}

class DeviceTokenService {
  DeviceTokenService(this._dio, this._sessionStore);

  final Dio _dio;
  final AuthSessionStore _sessionStore;

  StreamSubscription<String>? _tokenRefreshSubscription;
  bool _started = false;

  Future<void> start() async {
    if (_started || !_hasExpectedFirebaseApp()) return;
    _started = true;

    _tokenRefreshSubscription = FirebaseMessaging.instance.onTokenRefresh
        .listen(
          (token) => unawaited(_registerToken(token)),
          onError: (Object error, StackTrace stackTrace) {
            debugPrint('FCM token refresh failed: $error');
            debugPrintStack(stackTrace: stackTrace);
          },
        );
  }

  void registerAfterAuthentication() {
    unawaited(_registerCurrentToken());
  }

  Future<void> _registerCurrentToken() async {
    if (!_hasExpectedFirebaseApp()) return;

    try {
      await FirebaseMessaging.instance.requestPermission();
      final token = await FirebaseMessaging.instance.getToken();
      if (token == null || token.isEmpty) {
        debugPrint('FCM did not return a registration token.');
        return;
      }
      await _registerToken(token);
    } on Object catch (error, stackTrace) {
      debugPrint('FCM token registration failed: $error');
      debugPrintStack(stackTrace: stackTrace);
    }
  }

  Future<void> _registerToken(String token) async {
    if (_sessionStore.accessToken == null || !_hasExpectedFirebaseApp()) return;

    try {
      await _dio.put<void>(
        ApiPaths.deviceToken,
        data: {'fcmToken': token, 'platform': 'ANDROID'},
      );
    } on DioException catch (error, stackTrace) {
      debugPrint(
        'FCM token API failed (${error.response?.statusCode ?? error.type}): '
        '${error.message}',
      );
      debugPrintStack(stackTrace: stackTrace);
    }
  }

  bool _hasExpectedFirebaseApp() {
    if (!_supportsFirebaseMessaging || Firebase.apps.isEmpty) return false;

    final actualProjectId = Firebase.app().options.projectId;
    if (actualProjectId == expectedFirebaseProjectId) return true;

    debugPrint(
      'FCM disabled: expected Firebase project $expectedFirebaseProjectId, '
      'got $actualProjectId.',
    );
    return false;
  }

  void dispose() {
    unawaited(_tokenRefreshSubscription?.cancel());
  }
}
