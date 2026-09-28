import 'package:ddubi/app.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 웹에서 앱을 폰 크기 틀에 담는다.
///
/// 이 앱은 화면이 넓으면 데스크톱 전용 화면으로 갈라지는데, 그쪽에는 모바일에만 들어간
/// 것들이 빠져 있다(완료·취소 표시, 아래로 당겨 새로 고침, 일정 상세 개편). 웹을 공개하는
/// 동안에는 "앱을 웹에서 그대로 보는" 모양으로 둔다.
///
/// 검사는 앱(웹이 아닌 곳)에서 돌기 때문에 kIsWeb 이 거짓이다. 그래서 여기서는 틀을
/// 씌우지 않고 그대로 통과시키는지를 본다 — 폰과 태블릿에서 화면이 좁아지면 안 된다.
void main() {
  testWidgets('앱에서는 틀을 씌우지 않는다 — 화면을 그대로 쓴다', (tester) async {
    tester.view.physicalSize = const Size(1280 * 2, 800 * 2);
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      const MaterialApp(
        home: WebPhoneFrame(
          child: Scaffold(body: Center(child: Text('내용'))),
        ),
      ),
    );

    expect(find.text('내용'), findsOneWidget);
    // 틀을 씌웠다면 폭이 430 으로 좁아진다. 앱에서는 그러면 안 된다.
    final box = tester.getSize(find.byType(Scaffold));
    expect(box.width, greaterThan(WebPhoneFrame.phoneWidth));
  });

  testWidgets('자식이 없어도 깨지지 않는다', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(home: WebPhoneFrame(child: null)),
    );
    expect(tester.takeException(), isNull);
  });

  test('폰 틀 너비는 요즘 폰에 맞춘 값이다', () {
    // 이 값이 화면 갈림 기준(760)보다 작아야 모바일 화면이 선택된다.
    expect(WebPhoneFrame.phoneWidth, lessThan(760));
  });
}
