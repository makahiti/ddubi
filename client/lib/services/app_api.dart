import 'package:dio/dio.dart';

import '../core/constants/api_paths.dart';
import '../domain/auth.dart';
import '../domain/models.dart';
import '../core/config/app_environment.dart';

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

  @override
  Future<List<RepeatScheduleRule>> repeatSchedules({
    bool activeOnly = false,
  }) async {
    final data = _successData(
      await _dio.get<Map<String, dynamic>>(
        ApiPaths.scheduleRepeats,
        queryParameters: {'activeOnly': activeOnly, 'page': 0, 'size': 100},
      ),
    );
    final content = data['content'];
    if (content is! List) return const [];
    return content
        .whereType<Map>()
        .map((item) => RepeatScheduleRule.fromJson(_stringKeyed(item)))
        .toList(growable: false);
  }

  @override
  Future<RepeatScheduleRule> repeatScheduleById(String id) async =>
      RepeatScheduleRule.fromJson(
        _successData(
          await _dio.get<Map<String, dynamic>>(ApiPaths.scheduleRepeat(id)),
        ),
      );

  @override
  Future<void> updateRepeatSchedule(
    RepeatScheduleRule rule, {
    DateTime? effectiveFrom,
  }) async {
    await _dio.patch<Map<String, dynamic>>(
      ApiPaths.scheduleRepeat(rule.id),
      data: {
        ...rule.toUpdateRequestJson(),
        // 안 보내면 서버가 지금부터로 본다. 예전 동작과 같다.
        if (effectiveFrom != null)
          'effectiveFrom': effectiveFrom.toIso8601String(),
      },
    );
  }

  @override
  Future<void> deactivateRepeatSchedule(
    String id, {
    bool cancelFutureInstances = false,
  }) async {
    await _dio.delete<void>(
      ApiPaths.scheduleRepeat(id),
      queryParameters: {'cancelFutureInstances': cancelFutureInstances},
    );
  }
}

class ArrivalApi {
  const ArrivalApi(this._dio);
  final Dio _dio;

  /// ARRIVAL-001 도착 보고.
  ///
  /// [actualArrival] 과 [locationConsent] 는 **함께** 보내야 뜻이 있다. 서버는 계정 동의와 이번
  /// 요청 동의가 둘 다 참일 때만 실제 도착 시각을 저장하고, 저장한 시각이 있어야 정시·빠듯·지연을
  /// 판정한다. 둘 중 하나라도 빠지면 일정만 완료되고 판정은 비어 있는 기록이 남는다.
  Future<ArrivalRecord> record(
    String scheduleId, {
    DateTime? actualArrival,
    bool locationConsent = false,
  }) async => ArrivalRecord.fromJson(
    _successData(
      await _dio.post<Map<String, dynamic>>(
        ApiPaths.scheduleArrival(scheduleId),
        data: {
          'actualArrival': actualArrival?.toIso8601String(),
          'locationConsent': locationConsent,
        },
      ),
    ),
  );
}

class ReportApi {
  const ReportApi(this._dio);
  final Dio _dio;

  Future<TravelHistory> history({
    required DateTime from,
    required DateTime to,
  }) async {
    final data = _successData(
      await _dio.get<Map<String, dynamic>>(
        ApiPaths.history,
        queryParameters: {
          'from': _dateOnly(from),
          'to': _dateOnly(to),
          'page': 0,
          'size': 100,
        },
      ),
    );
    final summary = data['summary'];
    final items = data['items'];
    final content = items is Map ? items['content'] : null;
    if (summary is! Map || content is! List) {
      throw const FormatException('이동 기록 응답 형식을 확인할 수 없습니다.');
    }
    return TravelHistory(
      summary: TravelHistorySummary.fromJson(_stringKeyed(summary)),
      items: content
          .whereType<Map>()
          .map((item) => TravelHistoryItem.fromJson(_stringKeyed(item)))
          .toList(growable: false),
    );
  }

  Future<List<WeeklyReport>> reports() async {
    final data = _successData(
      await _dio.get<Map<String, dynamic>>(
        ApiPaths.reports,
        queryParameters: {'page': 0, 'size': 100},
      ),
    );
    final content = data['content'];
    if (content is! List) return const [];
    return content
        .whereType<Map>()
        .map((item) => WeeklyReport.fromJson(_stringKeyed(item)))
        .toList(growable: false);
  }

  Future<List<TravelHistoryItem>> reportDetails(String id) async {
    final data = _successData(
      await _dio.get<Map<String, dynamic>>(ApiPaths.report(id)),
    );
    final details = data['reportDetails'];
    if (details is! List) return const [];
    return details
        .whereType<Map>()
        .map((item) => TravelHistoryItem.fromJson(_stringKeyed(item)))
        .toList(growable: false);
  }

  static String _dateOnly(DateTime value) =>
      '${value.year.toString().padLeft(4, '0')}-'
      '${value.month.toString().padLeft(2, '0')}-'
      '${value.day.toString().padLeft(2, '0')}';
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
    final parsed = content is List
        ? content
              .whereType<Map>()
              .map((item) => AppNotification.fromJson(_stringKeyed(item)))
              .toList(growable: false)
        : const <AppNotification>[];
    final unique = <String, AppNotification>{};
    for (final item in parsed) {
      unique.putIfAbsent(item.id, () => item);
    }
    return NotificationInbox(
      unreadCount: (data['unreadCount'] as num?)?.toInt() ?? 0,
      items: unique.values.toList(growable: false),
    );
  }

  Future<AppNotification> getNotification(String id) async {
    final data = _successData(
      await _dio.get<Map<String, dynamic>>(ApiPaths.notification(id)),
    );
    return AppNotification.fromJson(data);
  }

  Future<void> markRead(String id) async {
    if (_sessionStore.accessToken == null) return;
    await _dio.patch<Map<String, dynamic>>(ApiPaths.notificationRead(id));
  }

  Future<void> markAllRead() async {
    if (_sessionStore.accessToken == null) return;
    await _dio.patch<Map<String, dynamic>>(ApiPaths.notificationsReadAll);
  }

  /// 고른 알림을 알림함에서 지운다.
  ///
  /// 서버는 발송 이력을 남기고 화면에서만 감춘다. 하나라도 없거나 남의 것이면 아무것도
  /// 지우지 않으므로, 실패하면 목록을 그대로 두고 다시 불러오면 된다.
  ///
  /// 지운 뒤의 안읽음 뱃지 숫자를 돌려준다. 안읽은 알림을 지우면 뱃지가 줄어든다.
  Future<int> deleteNotifications(List<String> ids) async {
    if (_sessionStore.accessToken == null || ids.isEmpty) return 0;
    final data = _successData(
      await _dio.delete<Map<String, dynamic>>(
        ApiPaths.notifications,
        queryParameters: {'ids': ids},
      ),
    );
    return (data['unreadCount'] as num?)?.toInt() ?? 0;
  }
}

class UserApi {
  const UserApi(this._dio, this._sessionStore);

  final Dio _dio;
  final AuthSessionStore _sessionStore;

  Future<UserProfile> getMe() async {
    final session = _sessionStore.session;
    if (session == null) {
      // 운영 빌드는 가짜 사용자로 대체하지 않는다. 화면은 오류 상태를 보여주고 로그인으로 유도한다.
      if (!AppEnvironment.allowFakeAuth) {
        throw StateError('로그인 세션이 없어 계정 정보를 불러올 수 없습니다.');
      }
      return const UserProfile(
        id: 'debug',
        email: null,
        nickname: '뚜비 사용자',
        notifyLeadMin: 20,
        alarmEnabled: true,
        briefingTime: null,
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
    // "07:00" 형식. 빈 문자열을 보내면 서버가 "브리핑 안 받음" 으로 지운다.
    String? briefingTime,
    // 밤 브리핑 시각. 규칙은 briefingTime 과 같다.
    String? nightBriefingTime,
    // 이동 기록(실제 도착 시각) 저장 동의. 서버가 동의 시각을 찍거나 지운다.
    bool? historyConsent,
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
            if (briefingTime != null)
              'briefingTime': briefingTime.isEmpty ? null : briefingTime,
            if (nightBriefingTime != null)
              'nightBriefingTime': nightBriefingTime.isEmpty
                  ? null
                  : nightBriefingTime,
            'historyConsent': ?historyConsent,
          },
        ),
      ),
    );
  }

  Future<void> withdraw({String? password, String? reason}) async {
    await _dio.delete<void>(
      ApiPaths.me,
      data: {
        'password': ?password,
        'confirmation': 'WITHDRAW',
        'reason': ?reason,
      },
    );
    _sessionStore.clear();
  }
}
