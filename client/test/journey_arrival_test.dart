import 'package:ddubi/data/mock_repository.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

/// 이동 안내 화면의 "예상 도착".
///
/// 저장된 경로의 요약([RouteOption.arrival])은 도착 칸에 "평가 필요" 라는 자리표시자를 들고 있다.
/// 화면이 그 칸을 그대로 보여줘서, 이동 중에 예상 도착이 늘 "평가 필요" 로 보였다(2026-09-22 제보).
/// 출발 전에는 평가 시각과 유효한 경로 요약을 순서대로 쓰고, 실제 출발 뒤에는
/// 둘 다 재사용하지 않는다. 자리표시자는 어느 단계에서도 시각으로 다루지 않는다.
///
/// 출발한 뒤에는 규칙이 하나 더 있다(2026-09-23, S15P21E202-83). 서버가 **실제 출발 시각 기준**으로
/// 다시 계산해 주기 전까지는 출발 전에 제안했던 도착 시각도, 경로 요약의 시각도 쓰지 않는다.
/// 지난 제안을 지금 도착 예정처럼 보여주면 안 되기 때문이다.
RouteOption _savedRoute({String arrival = '평가 필요'}) => RouteOption(
  kind: RouteKind.recommended,
  label: '선택된 경로',
  transport: '지하철',
  departure: '21:44',
  arrival: arrival,
  margin: '5개 이동 구간',
  detail: '약 44분',
  fare: '1,650원',
);

Future<void> _pump(
  WidgetTester tester, {
  required RouteOption route,
  DateTime? estimatedArrivalAt,
  bool departedAlready = false,
}) async {
  await tester.pumpWidget(
    ProviderScope(
      child: MaterialApp(
        home: MovingScreen(
          repository: MockDdubiRepository(),
          route: route,
          estimatedArrivalAt: estimatedArrivalAt,
          departedAlready: departedAlready,
        ),
      ),
    ),
  );
  // 화면 위 뚜비 캐릭터가 계속 움직여서 pumpAndSettle 은 끝나지 않는다. 한 프레임만 그린다.
  await tester.pump();
}

void main() {
  testWidgets('출발 전에는 평가가 준 도착 예정을 보여준다', (tester) async {
    await _pump(
      tester,
      route: _savedRoute(),
      estimatedArrivalAt: DateTime(2026, 9, 22, 22, 28),
    );

    expect(find.byKey(const Key('journeyArrivalValue')), findsOneWidget);
    expect(find.text('오후 10:28'), findsOneWidget);
    expect(find.text('평가 필요'), findsNothing);
  });

  testWidgets('"평가 필요" 자리표시자는 시각이 아니므로 모른다고 적는다', (tester) async {
    await _pump(tester, route: _savedRoute());

    expect(find.text('도착 예정 시간을 확인할 수 없어요'), findsOneWidget);
    expect(find.text('평가 필요'), findsNothing);
  });

  testWidgets('평가값이 없고 경로 요약에 진짜 시각이 있으면 그것을 쓴다', (tester) async {
    await _pump(tester, route: _savedRoute(arrival: '22:30'));

    expect(find.text('22:30'), findsOneWidget);
  });

  testWidgets('출발한 뒤에는 서버 재계산 전까지 옛 제안 시각을 쓰지 않는다', (tester) async {
    await _pump(
      tester,
      route: _savedRoute(arrival: '22:30'),
      estimatedArrivalAt: DateTime(2026, 9, 22, 22, 28),
      departedAlready: true,
    );
    // 재계산 조회가 끝나는 프레임까지 그린다(조회할 일정이 없으면 곧바로 "모름" 으로 확정된다).
    await tester.pump();

    expect(find.text('오후 10:28'), findsNothing);
    expect(find.text('22:30'), findsNothing);
    expect(find.text('도착 예정 시간을 확인할 수 없어요'), findsOneWidget);
  });
}
