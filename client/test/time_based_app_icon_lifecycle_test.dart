import 'package:ddubi/services/time_based_app_icon_service.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

/// 아이콘 교체 시점을 고정한다.
///
/// 화면이 떠 있는 동안 별칭을 끄면 방금 누른 아이콘이 사라져 안드로이드가 실행 중인
/// 작업을 끝낸다 — 사용자 눈에는 "켜졌다 바로 꺼짐" 이다. 그래서 앱이 화면에서
/// 내려간 뒤에만 바꾼다. 이 테스트가 그 규칙을 지킨다.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('com.ssafy.ddubi/app_icon');
  late List<String> calls;

  setUp(() {
    calls = <String>[];
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call.method);
          // 지금 낮 아이콘이 걸려 있다고 답해, 밤 시각이면 교체가 필요하게 만든다.
          if (call.method == 'getActiveIcon') return 'day';
          return null;
        });
  });

  tearDown(() {
    debugDefaultTargetPlatformOverride = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('앱이 켜질 때는 아이콘을 바꾸지 않는다', () async {
    TimeBasedAppIconLifecycle().start();
    await Future<void>.delayed(Duration.zero);

    expect(calls, isEmpty);
  });

  test('화면이 떠 있는 동안(resumed)에는 바꾸지 않는다', () async {
    final lifecycle = TimeBasedAppIconLifecycle()..start();
    lifecycle.didChangeAppLifecycleState(AppLifecycleState.resumed);
    await Future<void>.delayed(Duration.zero);

    expect(calls, isEmpty);
  });

  test('앱이 화면에서 내려가면(paused) 그때 바꾼다', () async {
    final lifecycle = TimeBasedAppIconLifecycle()..start();
    lifecycle.didChangeAppLifecycleState(AppLifecycleState.paused);
    await Future<void>.delayed(Duration.zero);

    expect(calls, contains('getActiveIcon'));
  });
}
