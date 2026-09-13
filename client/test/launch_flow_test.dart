import 'dart:async';

import 'package:ddubi/presentation/launch_flow.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('초기화가 끝나면 로고 스플래시에서 기존 인트로로 이동한다', (
    tester,
  ) async {
    final startup = Completer<void>();

    await tester.pumpWidget(
      AppLaunchFlow(
        splashDuration: const Duration(milliseconds: 10),
        initialize: (_) async => startup.future,
        child: const MaterialApp(home: Text('기존 인트로')),
      ),
    );

    expect(find.byKey(const Key('initialSplashScreen')), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 11));
    expect(find.text('기존 인트로'), findsNothing);

    startup.complete();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 230));
    expect(find.text('기존 인트로'), findsOneWidget);
    expect(find.byKey(const Key('startupLoadingScreen')), findsNothing);
    expect(find.byKey(const Key('startupCompleteScreen')), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('초기화가 빠른 경우에도 로고 스플래시만 거친다', (tester) async {
    await tester.pumpWidget(
      AppLaunchFlow(
        splashDuration: const Duration(milliseconds: 10),
        initialize: (_) async {},
        child: const MaterialApp(home: Text('기존 인트로')),
      ),
    );

    await tester.pump(const Duration(milliseconds: 11));
    await tester.pump(const Duration(milliseconds: 230));
    expect(find.text('기존 인트로'), findsOneWidget);
    expect(find.byKey(const Key('startupLoadingScreen')), findsNothing);
    expect(find.byKey(const Key('startupCompleteScreen')), findsNothing);
  });
}
