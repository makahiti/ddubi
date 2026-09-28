import 'package:ddubi/data/mock_repository.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 반복 일정의 시간 입력.
///
/// 예전에는 사용자가 `08:10` 을 직접 쳐야 했는데, 이 칸이 띄우는 숫자 키패드에는 콜론이
/// 없어서 입력 자체가 불가능했다(2026-09-22 기기 확인). 숫자만 치면 콜론을 대신 넣어
/// 주고, 시계 단추로 골라도 되게 고쳤다.
class _FakePlaceSearchService implements PlaceSearchService {
  const _FakePlaceSearchService();

  @override
  Future<List<PlaceLocation>> search(
    String query, {
    int page = 0,
    int size = 10,
  }) async => const [];

  @override
  Future<PlaceSearchPage> searchPage(
    String query, {
    int page = 0,
    int size = 10,
  }) async =>
      const PlaceSearchPage(items: [], hasNext: false, totalElements: 0);
}

void main() {
  Future<void> openRepeatForm(WidgetTester tester) async {
    tester.view.physicalSize = const Size(390 * 3, 1600 * 3);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        home: RepeatScheduleFormScreen(
          repository: MockDdubiRepository(),
          placeSearchService: const _FakePlaceSearchService(),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('숫자만 눌러도 HH:mm 으로 채워진다', (tester) async {
    await openRepeatForm(tester);
    final field = find.byKey(const Key('repeatDepartTimeField'));
    expect(field, findsOneWidget);

    await tester.enterText(field, '0810');
    await tester.pump();

    expect(find.text('08:10'), findsOneWidget);
  });

  testWidgets('이미 형식에 맞게 들어온 값은 그대로 둔다', (tester) async {
    await openRepeatForm(tester);

    await tester.enterText(
      find.byKey(const Key('repeatDepartTimeField')),
      '08:10',
    );
    await tester.pump();

    expect(find.text('08:10'), findsOneWidget);
  });

  testWidgets('네 자리를 넘겨 눌러도 잘라서 받는다', (tester) async {
    await openRepeatForm(tester);

    await tester.enterText(
      find.byKey(const Key('repeatDepartTimeField')),
      '081099',
    );
    await tester.pump();

    expect(find.text('08:10'), findsOneWidget);
  });
}
