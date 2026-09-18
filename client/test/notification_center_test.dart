import 'package:ddubi/domain/auth.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:ddubi/services/app_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('알림함은 실제 응답 데이터로 필터와 선택 모드를 제공한다', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      MaterialApp(home: NotificationsScreen(api: _notificationApi())),
    );
    await tester.pumpAndSettle();

    expect(find.text('알림함'), findsOneWidget);
    expect(find.text('3건'), findsOneWidget);
    expect(find.text('17:35에 출발하면 좋아요'), findsOneWidget);
    expect(find.text('지하철 2호선 일부 지연'), findsOneWidget);
    expect(find.byKey(const Key('notificationEmptyState')), findsNothing);

    await tester.tap(find.byKey(const Key('notificationSelectionButton')));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const Key('notificationSelectionControls')),
      findsOneWidget,
    );

    await tester.tap(find.byKey(const Key('notificationCard-1')));
    await tester.pumpAndSettle();
    expect(find.text('선택 1개'), findsOneWidget);

    await tester.tap(find.byKey(const Key('notificationSelectionButton')));
    await tester.pumpAndSettle();
    await tester.ensureVisible(
      find.byKey(const Key('notificationFilter-delay')),
    );
    await tester.tap(find.byKey(const Key('notificationFilter-delay')));
    await tester.pumpAndSettle();

    expect(find.text('지하철 2호선 일부 지연'), findsOneWidget);
    expect(find.text('17:35에 출발하면 좋아요'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('알림이 없으면 캐릭터와 안내 문구를 표시한다', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      MaterialApp(
        home: NotificationsScreen(api: _notificationApi(empty: true)),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('notificationEmptyState')), findsOneWidget);
    expect(find.text('아직 도착한 알림이 없어요.'), findsOneWidget);
    expect(find.text('중요한 일정과 교통 정보를\n가장 먼저 알려드릴게요!'), findsOneWidget);
    expect(find.text('최근 알림'), findsOneWidget);
    expect(find.text('안 읽음 0건'), findsNothing);
    expect(find.byKey(const Key('notificationUnreadCount')), findsNothing);
    expect(find.byKey(const Key('notificationEmptyCharacter')), findsOneWidget);
    expect(find.byKey(const Key('notificationSettingsButton')), findsOneWidget);
    expect(find.byKey(const Key('notificationFilter-all')), findsNothing);

    await tester.tap(find.byKey(const Key('notificationSelectionButton')));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const Key('notificationSelectionControls')),
      findsNothing,
    );
    expect(find.text('완료'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

NotificationApi _notificationApi({bool empty = false}) {
  final sessionStore = AuthSessionStore()
    ..save(
      const AuthSession(
        accessToken: 'test-token',
        refreshToken: 'test-refresh-token',
        tokenType: 'Bearer',
        deviceId: 'test-device',
        user: AuthUser(
          id: 1,
          provider: 'EMAIL',
          email: 'tester@ddubi.app',
          nickname: '테스터',
        ),
      ),
    );
  final dio = Dio(BaseOptions(baseUrl: 'https://example.test'))
    ..interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          handler.resolve(
            Response<Map<String, dynamic>>(
              requestOptions: options,
              statusCode: 200,
              data: {
                'success': true,
                'data': {
                  'unreadCount': empty ? 0 : 3,
                  'content': empty
                      ? <Map<String, dynamic>>[]
                      : <Map<String, dynamic>>[
                          {
                            'id': 1,
                            'type': 'DEPARTURE_RECOMMENDATION',
                            'title': '17:35에 출발하면 좋아요',
                            'body': '면접 일정 기준 · 예상 지연 10분 반영',
                            'status': 'SENT',
                            'createdAt': DateTime.now()
                                .subtract(const Duration(minutes: 5))
                                .toIso8601String(),
                            'readAt': null,
                          },
                          {
                            'id': 2,
                            'type': 'DELAY',
                            'title': '지하철 2호선 일부 지연',
                            'body': '추천 출발 시간이 17:25 → 17:15로 변경되었어요.',
                            'status': 'SENT',
                            'createdAt': DateTime.now()
                                .subtract(const Duration(seconds: 30))
                                .toIso8601String(),
                            'readAt': null,
                          },
                          {
                            'id': 3,
                            'type': 'SCHEDULE_REMINDER',
                            'title': '오늘 18:00 병원 예약',
                            'body': '출발 준비를 확인해보세요.',
                            'status': 'SENT',
                            'createdAt': DateTime.now()
                                .subtract(const Duration(minutes: 12))
                                .toIso8601String(),
                            'readAt': null,
                          },
                          {
                            'id': 4,
                            'type': 'INFO',
                            'title': '교통 데이터가 업데이트됐어요',
                            'body': '더 정확한 추천을 위해 최신 정보가 반영되었어요.',
                            'status': 'SENT',
                            'createdAt': DateTime.now()
                                .subtract(const Duration(hours: 1))
                                .toIso8601String(),
                            'readAt': DateTime.now().toIso8601String(),
                          },
                        ],
                },
              },
            ),
          );
        },
      ),
    );
  return NotificationApi(dio, sessionStore);
}
