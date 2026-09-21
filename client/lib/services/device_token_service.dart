import 'dart:async';

import 'package:dio/dio.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

import '../core/constants/api_paths.dart';
import '../domain/auth.dart';
import '../core/diagnostics/app_log.dart';

const expectedFirebaseProjectId = 'ddubi-85d2b';

/// 앱이 띄우는 알림이 들어갈 채널.
///
/// 안드로이드 8부터 모든 알림은 채널에 속해야 한다. 지금까지 앱이 알림을 한 번도
/// 직접 띄우지 않아 채널이 아예 없었다(2026-09-21 기기 확인).
///
/// 백그라운드에서 FCM 이 자동으로 띄우는 알림도 같은 채널을 쓰도록 AndroidManifest 의
/// `default_notification_channel_id` 에 같은 값을 적어 둔다. 그래야 사용자가
/// 설정에서 끄고 켜는 스위치가 하나로 보인다.
const ddubiNotificationChannelId = 'ddubi_default';
const _channelName = '뚜비 알림';
const _channelDescription = '출발 안내와 일정 브리핑을 알려드려요.';

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
  StreamSubscription<RemoteMessage>? _foregroundSubscription;
  NotificationOpenHandler? _notificationOpenHandler;
  String? _pendingNotificationLocation;
  bool _started = false;

  /// 앱이 켜져 있는 동안 푸시가 도착하면 알린다. 안 읽은 알림 개수를 바로 갱신하는 데 쓴다.
  final _foregroundMessages = StreamController<void>.broadcast();

  /// 앱이 떠 있을 때 도착한 알림. 화면이 이 신호로 종 아이콘 숫자를 즉시 고친다.
  Stream<void> get onForegroundMessage => _foregroundMessages.stream;

  final _localNotifications = FlutterLocalNotificationsPlugin();
  var _localNotificationsReady = false;

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

    await _prepareLocalNotifications();

    // 앱이 떠 있는 동안 도착하는 알림. 여기를 듣지 않으면 화면이 새 알림을 전혀 모른다.
    // 안드로이드는 앱이 화면에 보이는 동안에는 FCM 알림을 대신 띄워 주지 않으므로
    // (2026-09-21 기기 로그로 확인) 우리가 직접 띄운다.
    _foregroundSubscription = FirebaseMessaging.onMessage.listen(
      (message) {
        if (!_foregroundMessages.isClosed) _foregroundMessages.add(null);
        unawaited(_showLocalNotification(message));
      },
      onError: (Object error, StackTrace stackTrace) {
        AppLog.debug('FCM foreground message handling failed: $error');
        AppLog.stack(stackTrace);
      },
    );

    // 이미 로그인된 채로 앱을 다시 깔면 로그인을 거치지 않아 토큰이 서버로 가지 않는다.
    // 그러면 서버는 죽은 토큰으로 계속 보내고 폰에는 아무것도 오지 않는다
    // (2026-09-21 운영 확인 — UNREGISTERED). 세션이 있으면 켤 때마다 한 번 보낸다.
    if (_sessionStore.session != null) {
      unawaited(_registerCurrentToken());
    }

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

  /// 알림 채널을 만들고, 앱이 띄운 알림을 눌렀을 때 갈 곳을 연결한다.
  Future<void> _prepareLocalNotifications() async {
    if (_localNotificationsReady) return;
    try {
      await _localNotifications.initialize(
        const InitializationSettings(
          android: AndroidInitializationSettings('@mipmap/ic_launcher'),
          iOS: DarwinInitializationSettings(),
        ),
        onDidReceiveNotificationResponse: (response) {
          final payload = response.payload;
          if (payload == null || payload.isEmpty) return;
          _pendingNotificationLocation = payload;
          _flushPendingNotificationLocation();
        },
      );
      await _localNotifications
          .resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin
          >()
          ?.createNotificationChannel(
            const AndroidNotificationChannel(
              ddubiNotificationChannelId,
              _channelName,
              description: _channelDescription,
              importance: Importance.high,
            ),
          );
      _localNotificationsReady = true;
    } on Object catch (error, stackTrace) {
      // 알림을 못 띄워도 앱은 돌아가야 한다. 푸시가 안 보일 뿐이다.
      AppLog.debug('로컬 알림 준비 실패: $error');
      AppLog.stack(stackTrace);
    }
  }

  /// 앱이 켜져 있을 때 받은 알림을 화면에 띄운다.
  Future<void> _showLocalNotification(RemoteMessage message) async {
    if (!_localNotificationsReady) return;
    final notification = message.notification;
    final title = notification?.title;
    final body = notification?.body;
    // 제목도 내용도 없는 메시지는 띄울 것이 없다. 빈 알림을 띄우면 그게 더 혼란스럽다.
    if ((title == null || title.isEmpty) && (body == null || body.isEmpty)) {
      return;
    }
    try {
      await _localNotifications.show(
        message.messageId?.hashCode ?? DateTime.now().millisecondsSinceEpoch,
        title,
        body,
        const NotificationDetails(
          android: AndroidNotificationDetails(
            ddubiNotificationChannelId,
            _channelName,
            channelDescription: _channelDescription,
            importance: Importance.high,
            priority: Priority.high,
          ),
          iOS: DarwinNotificationDetails(),
        ),
        payload: notificationLocationFromData(message.data),
      );
    } on Object catch (error) {
      AppLog.debug('로컬 알림 표시 실패: $error');
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
    unawaited(_foregroundSubscription?.cancel());
    unawaited(_foregroundMessages.close());
  }
}
