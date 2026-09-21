import 'package:ddubi/app.dart';
import 'package:ddubi/app/router.dart';
import 'package:ddubi/data/mock_repository.dart';
import 'package:ddubi/core/theme.dart';
import 'package:ddubi/domain/auth.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/presentation/auth_screens.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:ddubi/presentation/widgets.dart';
import 'package:ddubi/providers/app_providers.dart';
import 'package:ddubi/services/app_api.dart';
import 'package:ddubi/services/google_login_service.dart';
import 'package:ddubi/services/kakao_login_service.dart';
import 'package:ddubi/services/naver_login_service.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

Future<void> _skipIntro(WidgetTester tester) async {
  expect(find.text('건너뛰기'), findsOneWidget);
  await tester.tap(find.text('건너뛰기'));
  await tester.pumpAndSettle();
}

ReportApi _emptyReportApi() {
  final dio = Dio()
    ..interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) => handler.resolve(
          Response<Map<String, dynamic>>(
            requestOptions: options,
            statusCode: 200,
            data: {
              'success': true,
              'data': {
                'summary': {
                  'totalCount': 0,
                  'onTimeCount': 0,
                  'tightCount': 0,
                  'lateCount': 0,
                  'onTimeRate': 0,
                },
                'items': {'content': <Object>[]},
              },
            },
          ),
        ),
      ),
    );
  return ReportApi(dio);
}

void main() {
  test('Mock 회원가입 계정으로 즉시 로그인할 수 있다', () async {
    final repository = MockAuthRepository();
    await repository.signUp(
      email: 'new@ddubi.app',
      password: 'new-password',
      nickname: '새 사용자',
    );
    await expectLater(
      repository.login(email: 'new@ddubi.app', password: 'new-password'),
      completes,
    );
    await expectLater(
      repository.signUp(
        email: 'new@ddubi.app',
        password: 'again',
        nickname: '중복 사용자',
      ),
      throwsA(isA<AuthException>()),
    );
  });

  test('회원가입 비밀번호는 영문과 숫자를 포함한 8자 이상이어야 한다', () {
    expect(validatePassword('short'), isNotNull);
    expect(validatePassword('abcdefgh'), isNotNull);
    expect(validatePassword('12345678'), isNotNull);
    expect(validatePassword('password123'), isNull);
  });

  testWidgets('인트로 첫 화면부터 진행 상태와 다음 버튼을 표시한다', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: DdubiApp()));

    expect(find.text('1 / 4'), findsOneWidget);
    expect(find.byIcon(Icons.arrow_forward), findsOneWidget);
    await tester.tap(find.byIcon(Icons.arrow_forward));
    await tester.pumpAndSettle();

    expect(find.text('2 / 4'), findsOneWidget);
    expect(find.byIcon(Icons.arrow_back), findsOneWidget);
  });

  testWidgets('로그인 화면과 핵심 진입 동작을 표시한다', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authRepositoryProvider.overrideWithValue(MockAuthRepository()),
        ],
        child: const DdubiApp(),
      ),
    );
    expect(find.text('뚜비'), findsOneWidget);
    await _skipIntro(tester);
    expect(find.text('K'), findsOneWidget);
    expect(find.bySemanticsLabel('G 로그인'), findsOneWidget);
    expect(find.text('N'), findsOneWidget);
    expect(find.text('Apple'), findsNothing);
    expect(find.text('이메일을 입력해주세요'), findsOneWidget);
    expect(find.byKey(const Key('loginEmailField')), findsOneWidget);
    expect(find.byKey(const Key('loginPasswordField')), findsOneWidget);
    expect(find.text('이메일로 로그인'), findsNothing);
    await tester.enterText(
      find.byType(TextFormField).at(0),
      'member@ddubi.app',
    );
    await tester.enterText(find.byType(TextFormField).at(1), 'password123');
    await tester.tap(find.text('로그인'));
    await tester.pumpAndSettle(const Duration(seconds: 1));
    expect(find.text('오늘의 일정'), findsOneWidget);
    expect(find.byKey(const Key('todayScheduleList')), findsOneWidget);
  });

  testWidgets('첫 로그인 화면에서 이메일과 비밀번호를 바로 입력한다', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: DdubiApp()));
    await _skipIntro(tester);
    expect(find.byType(TextFormField), findsNWidgets(2));
    await tester.enterText(
      find.byKey(const Key('loginEmailField')),
      'member@ddubi.app',
    );
    await tester.enterText(
      find.byKey(const Key('loginPasswordField')),
      'password123',
    );
    expect(find.text('이메일로 로그인'), findsNothing);
  });

  testWidgets('이메일 로그인은 잘못된 입력을 한국어로 안내한다', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: DdubiApp()));
    await _skipIntro(tester);
    await tester.enterText(find.byType(TextFormField).first, 'abc@');
    await tester.tap(find.text('로그인'));
    await tester.pump();
    expect(find.text('올바른 이메일 형식으로 입력해주세요.'), findsOneWidget);
    expect(find.text('비밀번호를 입력해주세요.'), findsOneWidget);
  });

  testWidgets('카카오 로그인은 공급자 인증 화면으로 이동한다', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authRepositoryProvider.overrideWithValue(MockAuthRepository()),
          kakaoLoginServiceProvider.overrideWithValue(
            const _FakeKakaoLoginService(),
          ),
        ],
        child: const DdubiApp(),
      ),
    );
    await _skipIntro(tester);
    await tester.ensureVisible(find.text('K'));
    await tester.tap(find.text('K'));
    await tester.pumpAndSettle();
    expect(find.text('카카오 로그인'), findsOneWidget);
    expect(find.text('카카오 계정으로 계속하기'), findsOneWidget);

    await tester.tap(find.text('카카오 계정으로 계속하기'));
    await tester.pumpAndSettle();
    expect(find.text('오늘의 일정'), findsOneWidget);
  });

  testWidgets('네이버 로그인은 공급자 인증 화면으로 이동한다', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authRepositoryProvider.overrideWithValue(MockAuthRepository()),
          naverLoginServiceProvider.overrideWithValue(
            const _FakeNaverLoginService(),
          ),
        ],
        child: const DdubiApp(),
      ),
    );
    await _skipIntro(tester);
    await tester.ensureVisible(find.text('N'));
    await tester.tap(find.text('N'));
    await tester.pumpAndSettle();
    expect(find.text('네이버 로그인'), findsOneWidget);
    expect(find.text('네이버 계정으로 계속하기'), findsOneWidget);

    await tester.tap(find.text('네이버 계정으로 계속하기'));
    await tester.pumpAndSettle();
    expect(find.text('오늘의 일정'), findsOneWidget);
  });

  testWidgets('Google 로그인은 ID 토큰으로 앱 로그인을 완료한다', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authRepositoryProvider.overrideWithValue(MockAuthRepository()),
          googleLoginServiceProvider.overrideWithValue(
            const _FakeGoogleLoginService(),
          ),
        ],
        child: const DdubiApp(),
      ),
    );
    await _skipIntro(tester);
    final googleLogin = find.bySemanticsLabel('G 로그인');
    await tester.ensureVisible(googleLogin);
    await tester.tap(googleLogin);
    await tester.pumpAndSettle();
    expect(find.text('Google 로그인'), findsOneWidget);
    expect(find.text('Google 계정으로 계속하기'), findsOneWidget);

    await tester.tap(find.text('Google 계정으로 계속하기'));
    await tester.pumpAndSettle();
    expect(find.text('오늘의 일정'), findsOneWidget);
  });

  testWidgets('기존 이메일 계정을 확인한 뒤 소셜 계정을 연동한다', (tester) async {
    final repository = MockAuthRepository();
    await tester.pumpWidget(
      MaterialApp(
        home: AccountLinkPromptScreen(
          provider: '카카오',
          email: 'member@ddubi.app',
          linkToken: 'link-token',
          repository: repository,
          onLinked: () {},
        ),
      ),
    );

    expect(find.text('이미 가입된 이메일이 있어요'), findsOneWidget);
    expect(find.text('member@ddubi.app'), findsOneWidget);

    await tester.tap(find.byKey(const Key('startEmailLinkButton')));
    await tester.pumpAndSettle();

    expect(find.text('이메일 계정 확인'), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('linkPasswordField')),
      'password123',
    );
    await tester.tap(find.byKey(const Key('completeEmailLinkButton')));
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();

    expect(find.text('계정 연동이 완료됐어요!'), findsOneWidget);
    expect(find.byKey(const Key('linkedAccountHomeButton')), findsOneWidget);
  });

  testWidgets('앱 전체에서 스크롤 끝 늘어남 효과를 사용하지 않는다', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: DdubiApp()));
    final app = tester.widget<MaterialApp>(find.byType(MaterialApp));
    expect(app.scrollBehavior, isA<DdubiScrollBehavior>());
  });

  testWidgets('Web 너비에서는 직접 경로와 NavigationRail을 사용한다', (tester) async {
    tester.view.physicalSize = const Size(1200, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final repository = MockDdubiRepository();
    final container = ProviderContainer(
      overrides: [
        ddubiRepositoryProvider.overrideWithValue(repository),
        scheduleRepositoryProvider.overrideWithValue(repository),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    container.read(appRouterProvider).go('/schedules/schedule-meeting');
    await tester.pumpAndSettle();

    expect(find.byType(NavigationRail), findsOneWidget);
    expect(
      tester.widget<NavigationRail>(find.byType(NavigationRail)).extended,
      isTrue,
    );
    expect(find.text('뚜비'), findsOneWidget);
    expect(find.text('팀 회의'), findsOneWidget);
    expect(find.textContaining('09:00'), findsWidgets);
    expect(find.text('강남역 인근'), findsOneWidget);
  });

  testWidgets('Web Home은 실제 API 실패 시 가짜 추천 경로를 표시하지 않는다', (tester) async {
    tester.view.physicalSize = const Size(1200, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final container = ProviderContainer();
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    container.read(appRouterProvider).go('/home');
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('webHomeDashboard')), findsOneWidget);
    expect(find.byKey(const Key('webHomeRouteGrid')), findsOneWidget);
    expect(find.byKey(const Key('webHomeMetrics')), findsOneWidget);
    expect(find.text('이동 대시보드'), findsOneWidget);
    expect(find.text('서비스 정상'), findsOneWidget);
    expect(find.text('도착 예정'), findsNWidgets(2));
    expect(find.byType(RouteCard), findsNothing);
  });

  testWidgets('일정 화면은 너비에 따라 모바일과 Web 구성을 나눈다', (tester) async {
    final repository = MockDdubiRepository();
    final container = ProviderContainer(
      overrides: [scheduleRepositoryProvider.overrideWithValue(repository)],
    );
    addTearDown(container.dispose);

    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );
    container.read(appRouterProvider).go('/schedules');
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('mobileScheduleLayout')), findsOneWidget);
    expect(find.byKey(const Key('webScheduleLayout')), findsNothing);

    tester.view.physicalSize = const Size(1200, 900);
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('webScheduleLayout')), findsOneWidget);
    expect(find.byKey(const Key('webScheduleMetrics')), findsOneWidget);
    expect(find.byKey(const Key('mobileScheduleLayout')), findsNothing);
    expect(find.text('날짜를 선택해 일정을 확인하세요.'), findsOneWidget);
  });

  testWidgets('일정 등록 화면의 입력값이 Mock 저장소에 저장된다', (tester) async {
    final repository = MockDdubiRepository();
    final container = ProviderContainer(
      overrides: [
        ddubiRepositoryProvider.overrideWithValue(repository),
        scheduleRepositoryProvider.overrideWithValue(repository),
        placeApiProvider.overrideWithValue(const _FakePlaceSearchService()),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    final router = container.read(appRouterProvider);
    router.go('/schedules');
    await tester.pumpAndSettle();
    bool? navigationResult;
    // 도착 기본값 19:30보다 현재 시각이 늦어도 출발 < 도착인 저장 시나리오를 유지한다.
    final navigation = router
        .push<bool>(
          '/schedules/new',
          extra: DateTime.now().add(const Duration(days: 2)),
        )
        .then((result) {
          navigationResult = result;
          return result;
        });
    await tester.pumpAndSettle();

    final fields = find.byType(TextFormField);
    await tester.enterText(fields.at(0), '병원 예약');
    expect(
      find.byKey(const Key('scheduleDepartureDateButton')),
      findsOneWidget,
    );
    expect(
      find.byKey(const Key('scheduleDepartureTimeButton')),
      findsOneWidget,
    );
    expect(find.byKey(const Key('scheduleDateButton')), findsOneWidget);
    expect(find.byKey(const Key('scheduleArrivalTimeButton')), findsOneWidget);
    await tester.tap(find.byKey(const Key('scheduleDepartureTimeButton')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    expect(find.text('중요도'), findsNothing);
    expect(find.text('교통수단 선호'), findsNothing);
    await tester.ensureVisible(find.byKey(const Key('destinationPlaceField')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('destinationPlaceField')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('placeSearchQuery')), '서울 병원');
    await tester.tap(find.byKey(const Key('placeSearchButton')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('placeSearchResult0')));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('originPlaceField')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('originPlaceField')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('placeSearchQuery')), '회사');
    await tester.tap(find.byKey(const Key('placeSearchButton')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('placeSearchResult0')));
    await tester.pumpAndSettle();
    await tester.drag(find.byType(ListView).last, const Offset(0, -700));
    await tester.pumpAndSettle();
    await tester.tap(find.text('저장하기'));
    await tester.pumpAndSettle();
    expect(find.text('필수 입력 항목입니다.'), findsNothing);
    // 저장 실패 시 미완료 Future를 기다려 CI가 장시간 멈추지 않도록 먼저 검사한다.
    expect(navigationResult, isTrue);
    expect(await navigation, isTrue);

    final saved = await repository.schedules();
    expect(saved.any((schedule) => schedule.title == '병원 예약'), isTrue);
    expect(saved.any((schedule) => schedule.place == '서울병원'), isTrue);
    expect(saved.any((schedule) => schedule.time == '19:30'), isTrue);
    expect(
      saved.any(
        (schedule) =>
            schedule.plannedDepartAt != null &&
            schedule.plannedDepartAt!.isBefore(schedule.arriveBy),
      ),
      isTrue,
    );
    expect(
      saved.any(
        (schedule) =>
            schedule.destination.lat == 37.492 &&
            schedule.destination.lng == 127.014,
      ),
      isTrue,
    );
  });

  testWidgets('일정 등록 화면은 Galaxy S10 크기에서 넘치지 않는다', (tester) async {
    tester.view.physicalSize = const Size(360, 760);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      MaterialApp(
        home: ScheduleFormScreen(
          repository: MockDdubiRepository(),
          placeSearchService: const _FakePlaceSearchService(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('어디서 출발해서\n어디로 가시나요?'), findsOneWidget);
    expect(
      find.byKey(const Key('scheduleDepartureDateButton')),
      findsOneWidget,
    );
    expect(find.byKey(const Key('scheduleArrivalTimeButton')), findsOneWidget);
    await tester.drag(
      find.byKey(const Key('scheduleFormScrollView')),
      const Offset(0, -900),
    );
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('considerLastTripSwitch')), findsOneWidget);
    expect(find.text('저장하기'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('일정을 저장하지 않아도 뒤로가기로 일정 목록에 돌아간다', (tester) async {
    final repository = MockDdubiRepository();
    final container = ProviderContainer(
      overrides: [
        ddubiRepositoryProvider.overrideWithValue(repository),
        scheduleRepositoryProvider.overrideWithValue(repository),
        placeApiProvider.overrideWithValue(const _FakePlaceSearchService()),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    final router = container.read(appRouterProvider);
    router.go('/schedules');
    await tester.pumpAndSettle();
    final navigation = router.push<bool>('/schedules/new');
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('scheduleFormBackButton')), findsOneWidget);
    await tester.tap(find.byKey(const Key('scheduleFormBackButton')));
    await tester.pumpAndSettle();

    expect(await navigation, isNull);
    expect(find.byKey(const Key('mobileScheduleLayout')), findsOneWidget);
  });

  test('일정 API 요청은 좌표와 문자열 중요도 및 updatedAt을 보존한다', () {
    final updatedAt = DateTime(2026, 9, 3, 9, 14, 22, 123, 456);
    final schedule = Schedule(
      id: '12',
      title: '병원 예약',
      origin: MockDdubiRepository.home,
      destination: const PlaceLocation(name: '서울병원', lat: 37.492, lng: 127.014),
      arriveBy: DateTime(2026, 9, 10, 19),
      importance: ScheduleImportance.critical,
      considerLastTrip: true,
      updatedAt: updatedAt,
    );

    expect(schedule.toCreateRequestJson()['importance'], 'CRITICAL');
    expect(schedule.toCreateRequestJson()['considerLastTrip'], isTrue);
    expect(
      (schedule.toCreateRequestJson()['destination'] as Map)['lat'],
      37.492,
    );
    expect(
      schedule.toUpdateRequestJson()['updatedAt'],
      updatedAt.toIso8601String(),
    );
  });

  testWidgets('일정 상세에서 updatedAt을 가진 일정을 수정한다', (tester) async {
    final repository = MockDdubiRepository();
    final container = ProviderContainer(
      overrides: [
        ddubiRepositoryProvider.overrideWithValue(repository),
        scheduleRepositoryProvider.overrideWithValue(repository),
        placeApiProvider.overrideWithValue(const _FakePlaceSearchService()),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    final router = container.read(appRouterProvider);
    router.go('/schedules/schedule-meeting');
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.byKey(const Key('editScheduleButton')),
      300,
      scrollable: find.byType(Scrollable).last,
    );
    await tester.drag(find.byType(Scrollable).last, const Offset(0, -80));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('editScheduleButton')));
    await tester.pumpAndSettle();

    expect(find.text('일정 수정'), findsWidgets);
    await tester.enterText(find.byType(TextFormField).first, '수정된 팀 회의');
    // 이전 Mock 일정에는 출발시각이 없으므로 자동 역산하지 않고 사용자가 직접 확인한다.
    await tester.tap(find.byKey(const Key('scheduleDepartureTimeButton')));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(TextButton).last);
    await tester.pumpAndSettle();
    await tester.fling(
      find.byType(ListView).last,
      const Offset(0, -1400),
      1200,
    );
    await tester.pumpAndSettle();
    expect(find.text('수정 완료'), findsOneWidget);
    await tester.tap(find.text('수정 완료'));
    await tester.pumpAndSettle();

    final updated = await repository.scheduleById('schedule-meeting');
    expect(updated?.title, '수정된 팀 회의');
    expect(updated?.updatedAt, isNotNull);
    expect(updated?.plannedDepartAt, isNotNull);
  });

  testWidgets('일정 상세의 저장 경로에서 실제 구간과 지도 좌표를 연다', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repository = MockDdubiRepository();
    final schedule = (await repository.schedules()).first;

    await tester.pumpWidget(
      MaterialApp(
        home: ScheduleDetailScreen(
          repository: repository,
          routeRepository: repository,
          recommendationApi: _SavedRouteRecommendationApi(),
          scheduleId: schedule.id,
          arrivalApi: ArrivalApi(Dio()),
          initialSchedule: schedule,
        ),
      ),
    );
    await tester.pumpAndSettle();

    final savedRoute = find.byKey(const Key('savedRoute-31'));
    await tester.scrollUntilVisible(savedRoute, 250);
    await tester.tap(savedRoute);
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('mobileSavedRouteLayout')), findsOneWidget);
    expect(find.byKey(const Key('savedRouteDetail')), findsOneWidget);
    expect(find.byKey(const Key('savedRoutePolyline')), findsOneWidget);
    expect(find.textContaining('버스 146'), findsOneWidget);
    expect(find.text('서버 경로 좌표 · 2개 점'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('낮은 데스크톱 Web 로그인 화면은 하단 영역만 스크롤한다', (tester) async {
    tester.view.physicalSize = const Size(1200, 560);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(const ProviderScope(child: DdubiApp()));
    await _skipIntro(tester);

    expect(find.byType(SingleChildScrollView), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('반복 일정 화면에서 요일 규칙을 저장해 일정들을 생성한다', (tester) async {
    final repository = MockDdubiRepository();
    final container = ProviderContainer(
      overrides: [
        ddubiRepositoryProvider.overrideWithValue(repository),
        repeatScheduleRepositoryProvider.overrideWithValue(repository),
        placeApiProvider.overrideWithValue(const _FakePlaceSearchService()),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    final router = container.read(appRouterProvider);
    final navigation = router.push<bool>('/schedules/repeat/new');
    await tester.pumpAndSettle();

    expect(find.text('반복 일정 등록'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('repeatTitleField')), '평일 출근');
    await tester.tap(find.byKey(const Key('repeatPlaceField')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('placeSearchQuery')),
      'SSAFY 서울캠퍼스',
    );
    await tester.tap(find.byKey(const Key('placeSearchButton')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('placeSearchResult0')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('repeatDepartureField')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('placeSearchQuery')), '회사');
    await tester.tap(find.byKey(const Key('placeSearchButton')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('placeSearchResult0')));
    await tester.pumpAndSettle();
    await tester.drag(find.byType(ListView).last, const Offset(0, -450));
    await tester.pumpAndSettle();
    expect(find.text('반복 요일'), findsOneWidget);
    expect(find.byKey(const Key('repeatWeekday1')), findsOneWidget);
    await tester.drag(find.byType(ListView).last, const Offset(0, -700));
    await tester.pumpAndSettle();
    await tester.tap(find.text('반복 일정 저장하기'));
    await tester.pumpAndSettle();

    expect(await navigation, isTrue);
    final generated = (await repository.schedules())
        .where((schedule) => schedule.title == '평일 출근')
        .toList();
    expect(generated, isNotEmpty);
    expect(
      generated.every((schedule) => schedule.repeatLabel == '평일 반복'),
      isTrue,
    );
    expect(generated.every((schedule) => schedule.date.weekday <= 5), isTrue);
  });

  testWidgets('경로 조건을 입력하면 Mock 추천 결과로 이동한다', (tester) async {
    final container = ProviderContainer(
      overrides: [
        placeApiProvider.overrideWithValue(const _FakePlaceSearchService()),
        recommendationApiProvider.overrideWithValue(_FakeRecommendationApi()),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    final router = container.read(appRouterProvider);
    router.go('/recommendations');
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('routeDepartureField')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('placeSearchQuery')), '회사');
    await tester.tap(find.byKey(const Key('placeSearchButton')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('placeSearchResult0')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('routeDestinationField')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('placeSearchQuery')), '여의도역');
    await tester.tap(find.byKey(const Key('placeSearchButton')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('placeSearchResult0')));
    await tester.pumpAndSettle();
    await tester.ensureVisible(
      find.byKey(const Key('routeDepartureTimeField')),
    );
    await tester.tap(find.byKey(const Key('routeDepartureTimeField')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('경로 평가하기'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('경로 평가하기'));
    await tester.pumpAndSettle();

    expect(find.text('추천 결과'), findsOneWidget);
    expect(find.textContaining('회사  →  서울병원'), findsOneWidget);
    expect(find.text('추천 경로'), findsOneWidget);
    expect(find.text('조건 수정'), findsOneWidget);
  });

  testWidgets('경로 화면은 Web에서 데스크톱 비교 레이아웃을 사용한다', (tester) async {
    tester.view.physicalSize = const Size(1200, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final container = ProviderContainer(
      overrides: [
        placeApiProvider.overrideWithValue(const _FakePlaceSearchService()),
        recommendationApiProvider.overrideWithValue(_FakeRecommendationApi()),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    final router = container.read(appRouterProvider);
    router.go('/recommendations');
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('webRouteSearchLayout')), findsOneWidget);
    expect(find.byKey(const Key('mobileRouteSearchLayout')), findsNothing);

    router.go(
      '/recommendations/mock-recommendation-1',
      extra: RouteSearchCondition(
        origin: const PlaceLocation(name: '강남역', lat: 37.49, lng: 127.02),
        destination: const PlaceLocation(name: '여의도역', lat: 37.52, lng: 126.92),
        plannedDepartAt: DateTime(2026, 9, 2, 9),
        desiredArrivalAt: DateTime(2026, 9, 2, 10),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('webRouteResultsLayout')), findsOneWidget);
    expect(find.byKey(const Key('webRouteResultsGrid')), findsOneWidget);
    expect(find.byKey(const Key('mobileRouteResultsLayout')), findsNothing);
    expect(
      find.byType(RouteCard),
      findsNWidgets(MockDdubiRepository.routeData.length),
    );
  });

  testWidgets('전체 지도는 Web에서 지도와 상세 정보를 좌우로 표시한다', (tester) async {
    tester.view.physicalSize = const Size(1200, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      MaterialApp(
        home: RouteMapScreen(
          repository: MockDdubiRepository(),
          selectedRoute: MockDdubiRepository.routeData.first,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('webRouteMapLayout')), findsOneWidget);
    expect(find.byKey(const Key('mobileRouteMapLayout')), findsNothing);
    expect(find.byType(ChoiceChip), findsNothing);
  });

  testWidgets('알림과 설정은 Web에서 데스크톱 정보 구조를 사용한다', (tester) async {
    tester.view.physicalSize = const Size(1200, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final container = ProviderContainer(
      overrides: [
        reportApiProvider.overrideWithValue(_emptyReportApi()),
        authRepositoryProvider.overrideWithValue(MockAuthRepository()),
      ],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    final router = container.read(appRouterProvider);
    router.go('/notifications');
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('webNotificationsLayout')), findsOneWidget);
    expect(find.byKey(const Key('webNotificationMetrics')), findsOneWidget);
    expect(find.byKey(const Key('mobileNotificationsLayout')), findsNothing);
    expect(find.text('출발과 일정 관련 알림을 한눈에 확인해요.'), findsWidgets);
    expect(find.byKey(const Key('notificationEmptyState')), findsOneWidget);
    expect(find.byKey(const Key('notificationFilter-all')), findsNothing);

    router.go('/settings');
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('webSettingsLayout')), findsOneWidget);
    expect(find.byKey(const Key('mobileSettingsLayout')), findsNothing);
    expect(find.text('뚜비와 더 편리한 이동을 위한 설정을 관리해요.'), findsOneWidget);
    expect(find.byKey(const Key('weeklyStampBoardCard')), findsOneWidget);
    expect(find.text('서비스 상태'), findsNothing);
    expect(find.text('회원 탈퇴'), findsNothing);
  });

  testWidgets('알림과 설정은 모바일에서 기존 세로형 구조를 유지한다', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final container = ProviderContainer(
      overrides: [reportApiProvider.overrideWithValue(_emptyReportApi())],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    final router = container.read(appRouterProvider);
    router.go('/notifications');
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('mobileNotificationsLayout')), findsOneWidget);
    expect(find.byKey(const Key('webNotificationsLayout')), findsNothing);
    expect(find.byKey(const Key('notificationEmptyState')), findsOneWidget);
    expect(find.text('아직 도착한 알림이 없어요.'), findsOneWidget);

    router.go('/settings');
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('mobileSettingsLayout')), findsOneWidget);
    expect(find.byKey(const Key('webSettingsLayout')), findsNothing);
    expect(find.text('위치 권한'), findsOneWidget);
    expect(find.text('알림 권한'), findsOneWidget);
    expect(find.text('지하철 우선'), findsNothing);
    expect(find.text('버스 + 지하철'), findsNothing);
    expect(find.text('버스 우선'), findsNothing);
    expect(find.text('도보'), findsNothing);
    expect(find.text('택시'), findsNothing);
    expect(find.text('더보기'), findsWidgets);
    expect(find.byKey(const Key('weeklyStampBoardCard')), findsOneWidget);
    expect(find.byKey(const Key('stampBoardPreparing')), findsOneWidget);
    expect(find.byKey(const Key('stampMissionPreviewRow')), findsNothing);
    expect(find.byKey(const Key('stampMissionGrid')), findsNothing);
    expect(find.byKey(const Key('stampBoardProgress')), findsNothing);
    expect(find.text('5/10개 달성했어요!'), findsNothing);
    expect(find.text('서비스 상태'), findsNothing);
    expect(find.text('회원 탈퇴'), findsNothing);
    expect(
      find.byKey(const Key('accountManagementMenuButton')),
      reason: '계정 관리는 눌러서 들어갈 수 있어야 한다 — 예전에는 준비 중 글자뿐이었다',
      findsOneWidget,
    );
    expect(find.byKey(const Key('savedPlacesMenuButton')), findsOneWidget);
    expect(find.byKey(const Key('travelRecordsMenuButton')), findsOneWidget);
    expect(find.byKey(const Key('helpInquiryButton')), findsOneWidget);

    await tester.drag(
      find.byKey(const Key('mobileSettingsLayout')),
      const Offset(0, 600),
    );
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('accountPasswordDialog')), findsNothing);

    await tester.drag(
      find.byKey(const Key('mobileSettingsLayout')),
      const Offset(0, -500),
    );
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('helpInquiryButton')));
    await tester.tap(find.byKey(const Key('helpInquiryButton')));
    await tester.pumpAndSettle();
    expect(find.text('무엇을 도와드릴까요?'), findsOneWidget);
    expect(find.byKey(const Key('inquiryMessageField')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('계정 관리 화면은 중복 로그아웃 없이 계정 변경 메뉴를 표시한다', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final container = ProviderContainer(
      overrides: [reportApiProvider.overrideWithValue(_emptyReportApi())],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    container.read(appRouterProvider).go('/settings/account');
    await tester.pumpAndSettle();
    expect(find.text('계정 관리'), findsOneWidget);
    expect(find.text('등록된 이메일이 없어요'), findsOneWidget);
    expect(find.byKey(const Key('changePasswordMenuButton')), findsOneWidget);
    expect(find.text('로그아웃'), findsNothing);
    expect(find.text('회원 탈퇴'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('회원 탈퇴 전에 탈퇴 확인 팝업을 표시한다', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final container = ProviderContainer(
      overrides: [reportApiProvider.overrideWithValue(_emptyReportApi())],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    container.read(appRouterProvider).go('/settings/account');
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('withdrawalMenuButton')));
    await tester.tap(find.byKey(const Key('withdrawalMenuButton')));
    await tester.pumpAndSettle();

    expect(
      find.byKey(const Key('withdrawalConfirmationDialog')),
      findsOneWidget,
    );
    expect(find.byKey(const Key('withdrawalCharacter')), findsOneWidget);
    expect(find.text('정말 탈퇴하시겠습니까?'), findsOneWidget);
    expect(find.byKey(const Key('withdrawalCancelButton')), findsOneWidget);
    expect(find.byKey(const Key('withdrawalConfirmButton')), findsOneWidget);
    expect(tester.takeException(), isNull);

    await tester.tap(find.byKey(const Key('withdrawalCancelButton')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('withdrawalConfirmationDialog')), findsNothing);
  });

  testWidgets('프로필 갤러리 선택 전에 사진 접근 권한 팝업을 표시한다', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final container = ProviderContainer(
      overrides: [reportApiProvider.overrideWithValue(_emptyReportApi())],
    );
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    container.read(appRouterProvider).go('/settings');
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('changeProfileImageButton')));
    await tester.pumpAndSettle();
    await tester.ensureVisible(
      find.byKey(const Key('pickProfileImageFromGallery')),
    );
    await tester.tap(find.byKey(const Key('pickProfileImageFromGallery')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('photoPermissionDialog')), findsOneWidget);
    expect(find.byKey(const Key('photoPermissionCharacter')), findsOneWidget);
    expect(find.byKey(const Key('photoPermissionLaterButton')), findsOneWidget);
    expect(find.byKey(const Key('photoPermissionAllowButton')), findsOneWidget);
    expect(find.text('나중에'), findsOneWidget);
    expect(find.text('허용하기'), findsOneWidget);
    expect(tester.takeException(), isNull);

    await tester.tap(find.byKey(const Key('photoPermissionLaterButton')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('photoPermissionDialog')), findsNothing);
  });

  testWidgets('Mock 지도에서 현재 위치와 출발지·도착지 및 후보를 구분한다', (tester) async {
    final repository = MockDdubiRepository();
    await tester.pumpWidget(
      MaterialApp(
        home: RouteMapScreen(
          repository: repository,
          selectedRoute: MockDdubiRepository.routeData.first,
          condition: RouteSearchCondition(
            origin: const PlaceLocation(name: '강남역', lat: 37.49, lng: 127.02),
            destination: const PlaceLocation(
              name: '여의도역',
              lat: 37.52,
              lng: 126.92,
            ),
            plannedDepartAt: DateTime(2026, 9, 1, 9),
            desiredArrivalAt: DateTime(2026, 9, 1, 10),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('mapCurrentMarker')), findsOneWidget);
    expect(find.byKey(const Key('mapDepartureMarker')), findsOneWidget);
    expect(find.byKey(const Key('mapDestinationMarker')), findsOneWidget);
    expect(find.text('Mock 지도 · Kakao 키 미설정'), findsOneWidget);
    expect(find.text('현재 위치 (Mock)'), findsOneWidget);
    expect(find.text('출발 · 강남역'), findsOneWidget);
    expect(find.text('도착 · 여의도역'), findsOneWidget);
    expect(find.byType(ChoiceChip), findsNothing);
    expect(find.byKey(const Key('mapEntireRouteButton')), findsOneWidget);
    expect(find.byKey(const Key('mapCurrentLocationButton')), findsOneWidget);

    expect(find.text('17:57  출발 · 강남역'), findsOneWidget);
  });

  testWidgets('경로 이탈 시 인앱 배너를 표시하고 닫는다', (tester) async {
    tester.view.physicalSize = const Size(360, 760);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        home: RouteMapScreen(
          repository: MockDdubiRepository(),
          selectedRoute: MockDdubiRepository.routeData.first,
          initialRouteDeviation: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('mobileRouteMapLayout')), findsOneWidget);
    expect(find.byKey(const Key('routeDeviationBanner')), findsOneWidget);
    expect(find.textContaining('추천 경로에서 벗어났어요.'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.tap(find.byKey(const Key('dismissRouteDeviationBanner')));
    await tester.pump();
    expect(find.byKey(const Key('routeDeviationBanner')), findsNothing);
  });

  testWidgets('출발 안내 후 이동 중 상태로 전환한다', (tester) async {
    final repository = MockDdubiRepository();
    await tester.pumpWidget(
      MaterialApp(
        home: MovingScreen(
          repository: repository,
          route: MockDdubiRepository.routeData.first,
        ),
      ),
    );
    await tester.pump();

    expect(find.text('출발 안내'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('출발했어요'), 300);
    expect(find.text('출발했어요'), findsOneWidget);

    await tester.tap(find.text('출발했어요'));
    await tester.pump();

    expect(find.text('이동 중'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('도착했어요'), 300);
    expect(find.text('도착했어요'), findsOneWidget);
  });
}

class _FakeKakaoLoginService implements KakaoLoginService {
  const _FakeKakaoLoginService();

  @override
  Future<String> login(BuildContext context) async => 'test-kakao-access-token';
}

class _FakeGoogleLoginService implements GoogleLoginService {
  const _FakeGoogleLoginService();

  @override
  Future<String> login(BuildContext context) async => 'test-google-id-token';
}

class _FakeNaverLoginService implements NaverLoginService {
  const _FakeNaverLoginService();

  @override
  Future<NaverAuthorizationResult> login(BuildContext context) async =>
      const NaverAuthorizationResult(
        authorizationCode: 'test-naver-authorization-code',
        state: 'test-state',
        redirectUri: naverRedirectUri,
      );
}

class _FakePlaceSearchService implements PlaceSearchService {
  const _FakePlaceSearchService();

  @override
  Future<List<PlaceLocation>> search(
    String query, {
    int page = 0,
    int size = 10,
  }) async => [
    if (query == '회사')
      const PlaceLocation(
        providerPlaceId: 'office-1',
        name: '회사',
        address: '서울 강남구 테헤란로 212',
        lat: 37.501,
        lng: 127.039,
      )
    else
      const PlaceLocation(
        providerPlaceId: 'hospital-1',
        name: '서울병원',
        address: '서울 서초구 테스트로 1',
        lat: 37.492,
        lng: 127.014,
      ),
  ];
}

class _FakeRecommendationApi extends RecommendationApi {
  _FakeRecommendationApi() : super(Dio());

  @override
  Future<RouteEvaluationResult> evaluateOrSearch(
    RouteSearchCondition condition,
  ) async => RouteEvaluationResult.candidates(MockDdubiRepository.routeData);

  @override
  Future<List<RouteOption>> searchRoutes(
    RouteSearchCondition condition,
  ) async => MockDdubiRepository.routeData;
}

class _SavedRouteRecommendationApi extends RecommendationApi {
  _SavedRouteRecommendationApi() : super(Dio());

  @override
  Future<RecommendationOverview?> getLatestOverview(String scheduleId) async =>
      null;

  @override
  Future<List<SavedRouteSummary>> getSavedRoutes(String scheduleId) async => [
    const SavedRouteSummary(
      routeId: '31',
      rankNo: 1,
      selected: true,
      baseDurationMin: 25,
      fromName: '집',
      toName: '역삼역',
      segmentCount: 2,
      modes: ['WALK', 'BUS'],
    ),
  ];

  @override
  Future<SavedRouteDetail> getSavedRouteDetail(String routeId) async =>
      const SavedRouteDetail(
        routeId: '31',
        scheduleId: '12',
        rankNo: 1,
        selected: true,
        baseDurationMin: 25,
        providerPathId: 'path-1',
        segments: [
          SavedRouteSegment(
            seq: 1,
            mode: 'WALK',
            fromName: '집',
            toName: '강남역',
            baseDurationMin: 5,
          ),
          SavedRouteSegment(
            seq: 2,
            mode: 'BUS',
            providerRouteKey: '146',
            fromName: '강남역',
            toName: '역삼역',
            baseDurationMin: 20,
          ),
        ],
      );

  @override
  Future<SavedRouteMap> getSavedRouteMap(String routeId) async =>
      const SavedRouteMap(
        routeId: '31',
        bounds: RouteMapBounds(
          minLat: 37.4979,
          minLng: 127.0276,
          maxLat: 37.5006,
          maxLng: 127.0364,
        ),
        segments: [
          RouteMapSegment(
            seq: 1,
            mode: 'WALK',
            fromName: '집',
            toName: '강남역',
            coordinates: [],
          ),
          RouteMapSegment(
            seq: 2,
            mode: 'BUS',
            fromName: '강남역',
            toName: '역삼역',
            coordinates: [
              RouteCoordinate(latitude: 37.4979, longitude: 127.0276),
              RouteCoordinate(latitude: 37.5006, longitude: 127.0364),
            ],
          ),
        ],
      );
}
