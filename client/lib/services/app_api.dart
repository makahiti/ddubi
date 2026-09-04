import 'package:dio/dio.dart';

import '../core/constants/api_paths.dart';
import '../domain/auth.dart';
import '../domain/models.dart';

Map<String, dynamic> _stringKeyed(Map<dynamic, dynamic> source) =>
    source.map((key, value) => MapEntry(key.toString(), value));

Map<String, dynamic> _successData(Response<Map<String, dynamic>> response) {
  final data = response.data?['data'];
  if (response.data?['success'] != true || data is! Map) {
    throw const FormatException('API 응답 형식을 확인할 수 없습니다.');
  }
  return _stringKeyed(data);
}

class HomeApi {
  const HomeApi(this._dio, this._sessionStore);

  final Dio _dio;
  final AuthSessionStore _sessionStore;

  Future<HomeSummary> getSummary() async {
    if (_sessionStore.accessToken == null) {
      return const HomeSummary(
        todaySchedules: [],
        unreadNotificationCount: 0,
        dataState: 'UNKNOWN',
      );
    }
    final data = _successData(
      await _dio.get<Map<String, dynamic>>(ApiPaths.home),
    );
    final next = data['nextSchedule'];
    final today = data['todaySchedules'];
    return HomeSummary(
      nextSchedule: next is Map ? Schedule.fromJson(_stringKeyed(next)) : null,
      todaySchedules: today is List
          ? today
                .whereType<Map>()
                .map((item) => Schedule.fromJson(_stringKeyed(item)))
                .toList(growable: false)
          : const [],
      unreadNotificationCount:
          (data['unreadNotificationCount'] as num?)?.toInt() ?? 0,
      dataState: data['dataState'] as String? ?? 'UNKNOWN',
    );
  }
}

class RepeatScheduleApi implements RepeatScheduleRepository {
  const RepeatScheduleApi(this._dio);

  final Dio _dio;

  @override
  Future<void> addRepeatSchedule(RepeatScheduleRule rule) async {
    await _dio.post<Map<String, dynamic>>(
      ApiPaths.scheduleRepeats,
      data: rule.toCreateRequestJson(),
    );
  }
}

class NotificationApi {
  const NotificationApi(this._dio, this._sessionStore);

  final Dio _dio;
  final AuthSessionStore _sessionStore;

  Future<NotificationInbox> getInbox({bool unreadOnly = false}) async {
    if (_sessionStore.accessToken == null) {
      return const NotificationInbox(unreadCount: 0, items: []);
    }
    final data = _successData(
      await _dio.get<Map<String, dynamic>>(
        ApiPaths.notifications,
        queryParameters: {'unreadOnly': unreadOnly, 'page': 0, 'size': 50},
      ),
    );
    final content = data['content'];
    return NotificationInbox(
      unreadCount: (data['unreadCount'] as num?)?.toInt() ?? 0,
      items: content is List
          ? content
                .whereType<Map>()
                .map((item) => AppNotification.fromJson(_stringKeyed(item)))
                .toList(growable: false)
          : const [],
    );
  }

  Future<void> markRead(String id) async {
    if (_sessionStore.accessToken == null) return;
    await _dio.patch<Map<String, dynamic>>(ApiPaths.notificationRead(id));
  }

  Future<void> markAllRead() async {
    if (_sessionStore.accessToken == null) return;
    await _dio.patch<Map<String, dynamic>>(ApiPaths.notificationsReadAll);
  }
}

class UserApi {
  const UserApi(this._dio, this._sessionStore);

  final Dio _dio;
  final AuthSessionStore _sessionStore;

  Future<UserProfile> getMe() async {
    final session = _sessionStore.session;
    if (session == null) {
      return const UserProfile(
        id: 'debug',
        email: null,
        nickname: '뚜비 사용자',
        notifyLeadMin: 20,
        alarmEnabled: true,
        transportPrefs: [],
      );
    }
    return UserProfile.fromJson(
      _successData(await _dio.get<Map<String, dynamic>>(ApiPaths.me)),
    );
  }

  Future<UserProfile> update({
    String? nickname,
    int? notifyLeadMin,
    bool? alarmEnabled,
    List<Map<String, String>>? transportPrefs,
  }) async {
    if (_sessionStore.session == null) return getMe();
    return UserProfile.fromJson(
      _successData(
        await _dio.patch<Map<String, dynamic>>(
          ApiPaths.me,
          data: {
            'nickname': ?nickname,
            'notifyLeadMin': ?notifyLeadMin,
            'alarmEnabled': ?alarmEnabled,
            'transportPrefs': ?transportPrefs,
          },
        ),
      ),
    );
  }
}
