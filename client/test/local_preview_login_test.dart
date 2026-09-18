import 'package:ddubi/app.dart';
import 'package:ddubi/core/network/dio_provider.dart';
import 'package:ddubi/domain/auth.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:ddubi/services/google_login_service.dart';
import 'package:ddubi/services/kakao_login_service.dart';
import 'package:ddubi/services/naver_login_service.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _RecordingAuthRepository extends MockAuthRepository {
  final calls = <(String, String)>[];

  @override
  Future<void> login({required String email, required String password}) async {
    calls.add((email, password));
  }
}

Future<void> _enterCredentials(
  WidgetTester tester,
  String id,
  String password,
) async {
  await tester.enterText(find.byType(TextFormField).at(0), id);
  await tester.enterText(find.byType(TextFormField).at(1), password);
  await tester.ensureVisible(find.widgetWithText(FilledButton, '로그인'));
  await tester.tap(find.widgetWithText(FilledButton, '로그인'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'local preview reaches real home without HTTP or a fake session',
    (tester) async {
      tester.view.physicalSize = const Size(1440, 3040);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final requests = <String>[];
      final dio = Dio();
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            requests.add(options.path);
            handler.reject(DioException(requestOptions: options));
          },
        ),
      );
      final container = ProviderContainer(
        overrides: [dioProvider.overrideWithValue(dio)],
      );
      addTearDown(container.dispose);
      addTearDown(dio.close);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const DdubiApp(),
        ),
      );
      await tester.tap(find.text('건너뛰기'));
      await tester.pumpAndSettle();
      await _enterCredentials(tester, 'localadmin', 'local1234');
      expect(find.text('오늘의 일정'), findsOneWidget);
      expect(container.read(authSessionStoreProvider).session, isNull);
      expect(requests, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('ordinary credentials still reach the existing repository', (
    tester,
  ) async {
    final repository = _RecordingAuthRepository();
    var authenticated = false;
    await tester.pumpWidget(
      MaterialApp(
        home: LoginScreen(
          authRepository: repository,
          kakaoLoginService: const KakaoSdkLoginService(),
          googleLoginService: const GoogleSdkLoginService(),
          naverLoginService: const NaverWebLoginService(),
          onAuthenticated: () => authenticated = true,
        ),
      ),
    );
    await _enterCredentials(tester, 'member@example.com', 'password123');
    expect(repository.calls, [('member@example.com', 'password123')]);
    expect(authenticated, isTrue);
  });

  for (final credentials in [
    ('localadmin', 'wrong1234'),
    ('Localadmin', 'local1234'),
    (' localadmin', 'local1234'),
    ('localadmin', 'local1234 '),
  ]) {
    testWidgets('preview requires exact credentials: $credentials', (
      tester,
    ) async {
      final repository = _RecordingAuthRepository();
      var authenticated = false;
      await tester.pumpWidget(
        MaterialApp(
          home: LoginScreen(
            authRepository: repository,
            kakaoLoginService: const KakaoSdkLoginService(),
            googleLoginService: const GoogleSdkLoginService(),
            naverLoginService: const NaverWebLoginService(),
            onAuthenticated: () => authenticated = true,
          ),
        ),
      );
      await _enterCredentials(tester, credentials.$1, credentials.$2);
      expect(authenticated, isFalse);
      expect(repository.calls, isEmpty);
      expect(find.text('올바른 이메일 형식으로 입력해주세요.'), findsOneWidget);
    });
  }
}
