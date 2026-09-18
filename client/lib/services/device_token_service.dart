import 'dart:async';

import 'package:dio/dio.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';

import '../core/constants/api_paths.dart';
import '../domain/auth.dart';
import '../core/diagnostics/app_log.dart';

const expectedFirebaseProjectId = 'ddubi-85d2b';

typedef NotificationOpenHandler = void Function(String location);

String notificationLocationFromData(Map<String, dynamic> data) {
  final notificationId = data['notificationId']?.toString().trim();
  if (notificationId != null && notificationId.isNotEmpty) {
    return '/notifications/$notificationId';
  }

  final scheduleId = data['scheduleId']?.toString().trim();
  if (scheduleId != null && scheduleId.isNotEmpty) {
    return '/schedules/$scheduleId';
  }

  return '/notifications';
}

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
    AppLog.debug('Firebase initialization skipped: $error');
    AppLog.stack(stackTrace);
  }
}

class DeviceTokenService {
  DeviceTokenService(this._dio, this._sessionStore);

  final Dio _dio;
  final AuthSessionStore _sessionStore;

  StreamSubscription<String>? _tokenRefreshSubscription;
  StreamSubscription<RemoteMessage>? _notificationOpenSubscription;
  NotificationOpenHandler? _notificationOpenHandler;
  String? _pendingNotificationLocation;
  bool _started = false;

  Future<void> start() async {
    if (_started || !_hasExpectedFirebaseApp()) return;
    _started = true;

    _tokenRefreshSubscription = FirebaseMessaging.instance.onTokenRefresh
        .listen(
          (token) => unawaited(_registerToken(token)),
          onError: (Object error, StackTrace stackTrace) {
            AppLog.debug('FCM token refresh failed: $error');
            AppLog.stack(stackTrace);
          },
        );

    _notificationOpenSubscription = FirebaseMessaging.onMessageOpenedApp.listen(
      _handleNotificationOpened,
      onError: (Object error, StackTrace stackTrace) {
        AppLog.debug('FCM notification open handling failed: $error');
        AppLog.stack(stackTrace);
      },
    );

    final initialMessage = await FirebaseMessaging.instance.getInitialMessage();
    if (initialMessage != null) {
      _handleNotificationOpened(initialMessage);
    }
  }

  void registerAfterAuthentication() {
    unawaited(_registerCurrentToken());
    _flushPendingNotificationLocation();
  }

  void setNotificationOpenHandler(NotificationOpenHandler handler) {
    _notificationOpenHandler = handler;
    _flushPendingNotificationLocation();
  }

  Future<void> unregisterBeforeLogout() async {
    if (!_hasExpectedFirebaseApp()) return;

    final token = await FirebaseMessaging.instance.getToken();
    if (token == null || token.isEmpty) return;

    await _dio.delete<void>(ApiPaths.deviceToken, data: {'fcmToken': token});
    await FirebaseMessaging.instance.deleteToken();
  }

  Future<void> _registerCurrentToken() async {
    if (!_hasExpectedFirebaseApp()) return;

    try {
      await FirebaseMessaging.instance.requestPermission();
      final token = await FirebaseMessaging.instance.getToken();
      if (token == null || token.isEmpty) {
        AppLog.debug('FCM did not return a registration token.');
        return;
      }
      await _registerToken(token);
    } on Object catch (error, stackTrace) {
      AppLog.debug('FCM token registration failed: $error');
      AppLog.stack(stackTrace);
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
      AppLog.debug(
        'FCM token API failed (${error.response?.statusCode ?? error.type}): '
        '${error.message}',
      );
      AppLog.stack(stackTrace);
    }
  }

  void _handleNotificationOpened(RemoteMessage message) {
    _pendingNotificationLocation = notificationLocationFromData(message.data);
    _flushPendingNotificationLocation();
  }

  void _flushPendingNotificationLocation() {
    final location = _pendingNotificationLocation;
    final handler = _notificationOpenHandler;
    if (location == null ||
        handler == null ||
        _sessionStore.accessToken == null) {
      return;
    }

    _pendingNotificationLocation = null;
    handler(location);
  }

  bool _hasExpectedFirebaseApp() {
    if (!_supportsFirebaseMessaging || Firebase.apps.isEmpty) return false;

    final actualProjectId = Firebase.app().options.projectId;
    if (actualProjectId == expectedFirebaseProjectId) return true;

    AppLog.debug(
      'FCM disabled: expected Firebase project $expectedFirebaseProjectId, '
      'got $actualProjectId.',
    );
    return false;
  }

  void dispose() {
    unawaited(_tokenRefreshSubscription?.cancel());
    unawaited(_notificationOpenSubscription?.cancel());
  }
}
