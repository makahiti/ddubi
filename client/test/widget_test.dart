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
import 'package:ddubi/services/kakao_login_service.dart';
import 'package:ddubi/services/google_login_service.dart';
import 'package:ddubi/services/naver_login_service.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

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
    expect(find.textContaining('카카오로 시작하기'), findsOneWidget);
    expect(find.textContaining('네이버로 시작하기'), findsOneWidget);
    expect(find.textContaining('Apple로 시작하기'), findsNothing);
    expect(find.textContaining('이메일로 시작하기'), findsOneWidget);
    expect(find.byKey(const Key('debugHomeButton')), findsOneWidget);
    await tester.tap(find.textContaining('이메일로 시작하기'));
    await tester.pumpAndSettle();
    expect(find.text('이메일로 로그인'), findsOneWidget);
    expect(find.text('이메일을 잊으셨나요?'), findsOneWidget);
    expect(find.text('비밀번호를 잊으셨나요?'), findsOneWidget);
    await tester.enterText(
      find.byType(TextFormField).at(0),
      'member@ddubi.app',
    );
    await tester.enterText(find.byType(TextFormField).at(1), 'password123');
    await tester.tap(find.text('로그인'));
    await tester.pumpAndSettle(const Duration(seconds: 1));
    expect(find.text('다음 중요 일정'), findsOneWidget);
    expect(find.text('추천 출발 시간'), findsOneWidget);
  });

  testWidgets('이메일 로그인은 잘못된 입력을 한국어로 안내한다', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: DdubiApp()));
    await tester.tap(find.textContaining('이메일로 시작하기'));
    await tester.pumpAndSettle();
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
    await tester.tap(find.textContaining('카카오로 시작하기'));
    await tester.pumpAndSettle();
    expect(find.text('카카오 로그인'), findsOneWidget);
    expect(find.text('카카오 계정으로 계속하기'), findsOneWidget);

    await tester.tap(find.text('카카오 계정으로 계속하기'));
    await tester.pumpAndSettle();
    expect(find.text('다음 중요 일정'), findsOneWidget);
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
    await tester.tap(find.textContaining('네이버로 시작하기'));
    await tester.pumpAndSettle();
    expect(find.text('네이버 로그인'), findsOneWidget);
    expect(find.text('네이버 계정으로 계속하기'), findsOneWidget);

    await tester.tap(find.text('네이버 계정으로 계속하기'));
    await tester.pumpAndSettle();
    expect(find.text('다음 중요 일정'), findsOneWidget);
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
    await tester.tap(find.textContaining('Google로 시작하기'));
    await tester.pumpAndSettle();
    expect(find.text('Google 로그인'), findsOneWidget);
    expect(find.text('Google 계정으로 계속하기'), findsOneWidget);

    await tester.tap(find.text('Google 계정으로 계속하기'));
    await tester.pumpAndSettle();
    expect(find.text('다음 중요 일정'), findsOneWidget);
  });

  testWidgets('기존 이메일 계정을 확인한 뒤 소셜 계정을 연동한다', (tester) async {
    final repository = MockAuthRepository();
    await tester.pumpWidget(
      MaterialApp(
        home: AccountLinkPromptScreen(
          provider: '카카오',
          email: 'member@ddubi.app',
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
      overrides: [scheduleRepositoryProvider.overrideWithValue(repository)],
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
    expect(find.text('09:00 팀 회의'), findsOneWidget);
    expect(find.text('강남역 인근'), findsOneWidget);
  });

  testWidgets('Web Home은 대시보드와 3열 추천 경로를 표시한다', (tester) async {
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
    expect(find.byType(RouteCard), findsNWidgets(3));
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
    final navigation = router.push<bool>('/schedules/new');
    await tester.pumpAndSettle();

    final fields = find.byType(TextFormField);
    await tester.enterText(fields.at(0), '병원 예약');
    await tester.enterText(fields.at(1), '14:30');
    await tester.tap(find.byKey(const Key('destinationPlaceField')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('placeSearchQuery')), '서울 병원');
    await tester.tap(find.byKey(const Key('placeSearchButton')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('placeSearchResult0')));
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
    expect(await navigation, isTrue);

    final saved = await repository.schedules();
    expect(saved.any((schedule) => schedule.title == '병원 예약'), isTrue);
    expect(saved.any((schedule) => schedule.place == '서울병원'), isTrue);
    expect(saved.any((schedule) => schedule.time == '14:30'), isTrue);
    expect(
      saved.any(
        (schedule) =>
            schedule.destination.lat == 37.492 &&
            schedule.destination.lng == 127.014,
      ),
      isTrue,
    );
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
    await tester.tap(find.byKey(const Key('editScheduleButton')));
    await tester.pumpAndSettle();

    expect(find.text('일정 수정'), findsWidgets);
    await tester.enterText(find.byType(TextFormField).first, '수정된 팀 회의');
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
  });

  testWidgets('낮은 데스크톱 Web 로그인 화면도 스크롤하며 넘치지 않는다', (tester) async {
    tester.view.physicalSize = const Size(1200, 560);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(const ProviderScope(child: DdubiApp()));
    await tester.pump();

    expect(find.byType(SingleChildScrollView), findsWidgets);
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
    await tester.tap(find.text('추천 경로 찾기'));
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
        desiredArrivalAt: DateTime(2026, 9, 2, 10),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('webRouteResultsLayout')), findsOneWidget);
    expect(find.byKey(const Key('webRouteResultsGrid')), findsOneWidget);
    expect(find.byKey(const Key('mobileRouteResultsLayout')), findsNothing);
    expect(find.byType(RouteCard), findsNWidgets(3));
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
    expect(find.byType(ChoiceChip), findsNWidgets(3));
  });

  testWidgets('알림과 설정은 Web에서 데스크톱 정보 구조를 사용한다', (tester) async {
    tester.view.physicalSize = const Size(1200, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final container = ProviderContainer();
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
    expect(find.text('최근 알림'), findsOneWidget);

    router.go('/settings');
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('webSettingsLayout')), findsOneWidget);
    expect(find.byKey(const Key('mobileSettingsLayout')), findsNothing);
    expect(find.text('경로 추천에 사용할 교통수단과 앱 권한을 관리하세요.'), findsOneWidget);
  });

  testWidgets('알림과 설정은 모바일에서 기존 세로형 구조를 유지한다', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final container = ProviderContainer();
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    final router = container.read(appRouterProvider);
    router.go('/notifications');
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('mobileNotificationsLayout')), findsOneWidget);
    expect(find.byKey(const Key('webNotificationsLayout')), findsNothing);

    router.go('/settings');
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('mobileSettingsLayout')), findsOneWidget);
    expect(find.byKey(const Key('webSettingsLayout')), findsNothing);
    expect(find.text('지하철 우선'), findsOneWidget);
    expect(find.text('버스 + 지하철'), findsOneWidget);
    expect(find.text('버스 우선'), findsOneWidget);
    expect(find.text('도보'), findsNothing);
    expect(find.text('택시'), findsNothing);
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
    expect(find.byType(ChoiceChip), findsNWidgets(3));
    expect(find.byKey(const Key('mapEntireRouteButton')), findsOneWidget);
    expect(find.byKey(const Key('mapCurrentLocationButton')), findsOneWidget);

    await tester.tap(find.text('가장 빠른 경로'));
    await tester.pump();
    expect(find.text('17:51 출발'), findsOneWidget);
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
  Future<String> login(BuildContext context) async => 'test-naver-access-token';
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
  Future<List<RouteOption>> searchRoutes(
    RouteSearchCondition condition,
  ) async => MockDdubiRepository.routeData;
}
