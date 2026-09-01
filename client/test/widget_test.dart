import 'package:ddubi/app.dart';
import 'package:ddubi/app/router.dart';
import 'package:ddubi/data/mock_repository.dart';
import 'package:ddubi/domain/auth.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:ddubi/providers/app_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Mock 회원가입 계정으로 즉시 로그인할 수 있다', () async {
    final repository = MockAuthRepository();
    await repository.signUp(email: 'new@ddubi.app', password: 'new-password');
    await expectLater(
      repository.login(email: 'new@ddubi.app', password: 'new-password'),
      completes,
    );
    await expectLater(
      repository.signUp(email: 'new@ddubi.app', password: 'again'),
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
    await tester.pumpWidget(const ProviderScope(child: DdubiApp()));
    expect(find.text('뚜비'), findsOneWidget);
    expect(find.textContaining('카카오로 시작하기'), findsOneWidget);
    expect(find.textContaining('이메일로 시작하기'), findsOneWidget);
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
    await tester.pumpWidget(const ProviderScope(child: DdubiApp()));
    await tester.tap(find.textContaining('카카오로 시작하기'));
    await tester.pumpAndSettle();
    expect(find.text('카카오 로그인'), findsOneWidget);
    expect(find.text('개발용 카카오 인증 완료'), findsOneWidget);
  });

  testWidgets('Web 너비에서는 직접 경로와 NavigationRail을 사용한다', (tester) async {
    tester.view.physicalSize = const Size(1200, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final container = ProviderContainer();
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    container.read(appRouterProvider).go('/schedules/schedule-meeting');
    await tester.pumpAndSettle();

    expect(find.byType(NavigationRail), findsOneWidget);
    expect(find.text('09:00 팀 회의'), findsOneWidget);
    expect(find.text('강남역 인근'), findsOneWidget);
  });

  testWidgets('일정 등록 화면의 입력값이 Mock 저장소에 저장된다', (tester) async {
    final repository = MockDdubiRepository();
    final container = ProviderContainer(
      overrides: [ddubiRepositoryProvider.overrideWithValue(repository)],
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
    await tester.enterText(fields.at(1), '서울 병원');
    await tester.enterText(fields.at(2), '14:30');
    await tester.enterText(fields.at(3), '회사');
    await tester.drag(find.byType(ListView).last, const Offset(0, -700));
    await tester.pumpAndSettle();
    await tester.tap(find.text('저장하기'));
    await tester.pumpAndSettle();
    expect(await navigation, isTrue);

    final saved = await repository.schedules();
    expect(saved.any((schedule) => schedule.title == '병원 예약'), isTrue);
    expect(saved.any((schedule) => schedule.place == '서울 병원'), isTrue);
    expect(saved.any((schedule) => schedule.time == '14:30'), isTrue);
    expect(saved.any((schedule) => schedule.departurePlace == '회사'), isTrue);
  });

  testWidgets('경로 조건을 입력하면 Mock 추천 결과로 이동한다', (tester) async {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(container: container, child: const DdubiApp()),
    );

    final router = container.read(appRouterProvider);
    router.go('/recommendations');
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('routeDepartureField')), '강남역');
    await tester.enterText(
      find.byKey(const Key('routeDestinationField')),
      '여의도역',
    );
    await tester.tap(find.text('추천 경로 찾기'));
    await tester.pumpAndSettle();

    expect(find.text('추천 결과'), findsOneWidget);
    expect(find.textContaining('강남역  →  여의도역'), findsOneWidget);
    expect(find.text('추천 경로'), findsOneWidget);
    expect(find.text('조건 수정'), findsOneWidget);
  });

  testWidgets('Mock 지도에서 현재 위치와 출발지·도착지 및 후보를 구분한다', (tester) async {
    final repository = MockDdubiRepository();
    await tester.pumpWidget(
      MaterialApp(
        home: RouteMapScreen(
          repository: repository,
          selectedRoute: MockDdubiRepository.routeData.first,
          condition: RouteSearchCondition(
            departurePlace: '강남역',
            destinationPlace: '여의도역',
            desiredArrivalAt: DateTime(2026, 9, 1, 10),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('mapCurrentMarker')), findsOneWidget);
    expect(find.byKey(const Key('mapDepartureMarker')), findsOneWidget);
    expect(find.byKey(const Key('mapDestinationMarker')), findsOneWidget);
    expect(find.text('현재 위치 (Mock)'), findsOneWidget);
    expect(find.text('출발 · 강남역'), findsOneWidget);
    expect(find.text('도착 · 여의도역'), findsOneWidget);
    expect(find.byType(ChoiceChip), findsNWidgets(3));

    await tester.tap(find.text('가장 빠른 경로'));
    await tester.pump();
    expect(find.text('17:51 출발'), findsOneWidget);
  });
}
