import 'package:ddubi/domain/auth.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:ddubi/services/app_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 홈 날짜 화살표.
///
/// 예전에는 화살표가 그냥 그림이라 눌러도 아무 일이 없었다. 게다가 홈 요약은 오늘치만
/// 주기 때문에, 화살표만 살려도 보여줄 자료가 없었다. 이 검사는 두 가지를 지킨다.
///  - 화살표를 누르면 실제로 날짜가 넘어가고 그 날 일정이 보인다
///  - 홈을 열기만 할 때는 전체 일정을 받아오지 않는다(요청을 늘리지 않는다)
HomeApi _homeApi() {
  final dio = Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
    ..interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) => handler.resolve(
          Response<Map<String, dynamic>>(
            requestOptions: options,
            statusCode: 200,
            data: const {
              'success': true,
              'data': {
                'nextSchedule': null,
                'todaySchedules': <Object>[],
                'unreadNotificationCount': 0,
                'dataState': 'NORMAL',
              },
            },
          ),
        ),
      ),
    );
  final store = AuthSessionStore()
    ..save(
      const AuthSession(
        accessToken: 'test-access-token',
        refreshToken: 'test-refresh-token',
        tokenType: 'Bearer',
        deviceId: 'test-device',
        user: AuthUser(
          id: 1,
          provider: 'LOCAL',
          email: 'member@example.com',
          nickname: '뚜비',
        ),
      ),
    );
  return HomeApi(dio, store);
}

class _FakeScheduleRepository implements ScheduleRepository {
  _FakeScheduleRepository(this._schedules);

  final List<Schedule> _schedules;
  var calls = 0;

  @override
  Future<List<Schedule>> schedules() async {
    calls++;
    return _schedules;
  }

  @override
  Future<Schedule?> scheduleById(String id) async => null;

  @override
  Future<void> addSchedule(Schedule schedule) async {}

  @override
  Future<void> updateSchedule(Schedule schedule) async {}

  @override
  Future<void> cancelSchedule(String id) async {}

  @override
  Future<List<ScheduleConflict>> conflicts(DateTime from, DateTime to) async =>
      const [];
}

Schedule _scheduleOn(DateTime when, String title) => Schedule(
  id: title,
  title: title,
  origin: const PlaceLocation(name: '집', lat: 37.5, lng: 127.0),
  destination: const PlaceLocation(name: '회사', lat: 37.51, lng: 127.03),
  arriveBy: when,
);

/// 홈은 760px 미만에서만 모바일 화면을 쓴다. 검사 기본 크기(800)는 웹 화면이라
/// 날짜 화살표가 아예 없다. 세로도 넉넉히 줘서 스크롤 없이 한 화면에 담는다.
void _asPhone(WidgetTester tester) {
  tester.view.physicalSize = const Size(390 * 3, 1600 * 3);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
}

void main() {
  testWidgets('다음 날 화살표를 누르면 그 날 일정이 보인다', (tester) async {
    _asPhone(tester);
    final tomorrow = DateTime.now().add(const Duration(days: 1));
    final repository = _FakeScheduleRepository([
      _scheduleOn(
        DateTime(tomorrow.year, tomorrow.month, tomorrow.day, 9),
        '내일 병원',
      ),
    ]);

    await tester.pumpWidget(
      MaterialApp(
        home: HomeScreen(homeApi: _homeApi(), scheduleRepository: repository),
      ),
    );
    await tester.pumpAndSettle();

    // 홈을 열기만 해서는 전체 일정을 받아오지 않는다.
    expect(repository.calls, 0);
    expect(find.text('오늘 등록된 일정이 없어요.'), findsOneWidget);

    await tester.tap(find.byKey(const Key('homeNextDay')));
    await tester.pumpAndSettle();

    expect(repository.calls, 1);
    expect(find.text('내일 병원'), findsOneWidget);
    expect(find.text('${tomorrow.month}월 ${tomorrow.day}일 일정'), findsOneWidget);
  });

  testWidgets('날짜를 누르면 오늘로 돌아온다', (tester) async {
    _asPhone(tester);
    final repository = _FakeScheduleRepository(const []);

    await tester.pumpWidget(
      MaterialApp(
        home: HomeScreen(homeApi: _homeApi(), scheduleRepository: repository),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('homeNextDay')));
    await tester.pumpAndSettle();
    expect(find.text('오늘의 일정'), findsNothing);

    await tester.tap(find.byKey(const Key('homeDateLabel')));
    await tester.pumpAndSettle();
    expect(find.text('오늘의 일정'), findsOneWidget);
  });

  testWidgets('일정 저장소가 없으면 화살표는 꺼져 있다', (tester) async {
    _asPhone(tester);
    await tester.pumpWidget(MaterialApp(home: HomeScreen(homeApi: _homeApi())));
    await tester.pumpAndSettle();

    final next = tester.widget<IconButton>(
      find.descendant(
        of: find.byKey(const Key('homeNextDay')),
        matching: find.byType(IconButton),
      ),
    );
    expect(next.onPressed, isNull);
  });
}
