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

Schedule _scheduleOn(
  DateTime when,
  String title, {
  ScheduleStatus status = ScheduleStatus.upcoming,
}) => Schedule(
  status: status,
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

  // 제보: "일정을 고치고 홈에 오면 한 번에 안 뜰 때가 있다. 날짜를 바꿨다 돌아오면
  // 뜰 때도 있다"(2026-09-22). 다른 날 목록을 처음 한 번만 읽고 계속 재사용한 탓이다.
  testWidgets('날짜를 옮길 때마다 일정을 다시 읽는다', (tester) async {
    _asPhone(tester);
    final repository = _FakeScheduleRepository(const []);

    await tester.pumpWidget(
      MaterialApp(
        home: HomeScreen(homeApi: _homeApi(), scheduleRepository: repository),
      ),
    );
    await tester.pumpAndSettle();
    expect(repository.calls, 0);

    await tester.tap(find.byKey(const Key('homeNextDay')));
    await tester.pumpAndSettle();
    expect(repository.calls, 1);

    // 두 번째부터도 다시 읽어야 한다. 예전에는 여기서 1 에 머물렀다.
    await tester.tap(find.byKey(const Key('homeNextDay')));
    await tester.pumpAndSettle();
    expect(repository.calls, 2);

    await tester.tap(find.byKey(const Key('homePreviousDay')));
    await tester.pumpAndSettle();
    expect(repository.calls, 3);
  });

  testWidgets('아래로 당기면 홈을 다시 읽는다', (tester) async {
    _asPhone(tester);
    var summaryCalls = 0;
    final dio = Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
      ..interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            summaryCalls++;
            handler.resolve(
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
            );
          },
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

    await tester.pumpWidget(
      MaterialApp(home: HomeScreen(homeApi: HomeApi(dio, store))),
    );
    await tester.pumpAndSettle();
    expect(summaryCalls, 1);

    expect(find.byType(RefreshIndicator), findsOneWidget);

    // RefreshIndicator 는 화면 높이의 25% 넘게 당겨야 걸린다. 이 검사 화면은 1600 이라
    // 400 을 넘겨야 한다. 300 으로 당기면 아무 일도 일어나지 않는다.
    await tester.drag(
      find.byKey(const Key('todayScheduleList')),
      const Offset(0, 600),
      touchSlopY: 0,
    );
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();

    expect(summaryCalls, 2);
  });

  // 취소한 일정이 날짜를 옮기면 다시 나타났다. 서버 목록 API 는 상태를 안 주면
  // 취소분까지 돌려주는데 앱이 거르지 않아서였다(2026-09-22 제보).
  testWidgets('취소한 일정은 다른 날 목록에도 안 보인다', (tester) async {
    _asPhone(tester);
    final tomorrow = DateTime.now().add(const Duration(days: 1));
    final day = DateTime(tomorrow.year, tomorrow.month, tomorrow.day, 9);
    final repository = _FakeScheduleRepository([
      _scheduleOn(day, '취소한 병원', status: ScheduleStatus.canceled),
      _scheduleOn(day.add(const Duration(hours: 2)), '남아 있는 회의'),
    ]);

    await tester.pumpWidget(
      MaterialApp(
        home: HomeScreen(homeApi: _homeApi(), scheduleRepository: repository),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('homeNextDay')));
    await tester.pumpAndSettle();

    expect(find.text('취소한 병원'), findsNothing);
    expect(find.text('남아 있는 회의'), findsOneWidget);
  });

  testWidgets('완료한 일정은 목록에 남고 완료 표시가 붙는다', (tester) async {
    _asPhone(tester);
    final tomorrow = DateTime.now().add(const Duration(days: 1));
    final repository = _FakeScheduleRepository([
      _scheduleOn(
        DateTime(tomorrow.year, tomorrow.month, tomorrow.day, 9),
        '다녀온 회의',
        status: ScheduleStatus.done,
      ),
    ]);

    await tester.pumpWidget(
      MaterialApp(
        home: HomeScreen(homeApi: _homeApi(), scheduleRepository: repository),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('homeNextDay')));
    await tester.pumpAndSettle();

    expect(find.text('다녀온 회의'), findsOneWidget);
    expect(find.byKey(const Key('scheduleDoneBadge')), findsOneWidget);
    // 끝난 일정 카드 오른쪽에 "예정" 이 함께 붙어 두 상태가 동시에 보였다(2026-09-22 제보).
    expect(
      find.descendant(
        of: find.ancestor(
          of: find.text('다녀온 회의'),
          matching: find.byType(Opacity),
        ),
        matching: find.byKey(const Key('scheduleUpcomingBadge')),
      ),
      findsNothing,
    );
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
