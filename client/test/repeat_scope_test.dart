import 'package:ddubi/domain/models.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 반복 일정을 고치거나 중지할 때 "어디까지 적용할지" 를 묻는다.
///
/// 서버(!179)가 범위를 받을 수 있게 됐는데 앱이 묻지 않아 늘 기본값으로만 갔다.
/// 3일 뒤 일정을 보다가 규칙을 고쳤는데 오늘·내일 일정까지 바뀌면 사용자가 놀란다.
class _FakeRepeatRepository implements RepeatScheduleRepository {
  DateTime? lastEffectiveFrom;
  bool? lastCancelFutureInstances;
  var updateCalls = 0;
  var deactivateCalls = 0;

  @override
  Future<void> updateRepeatSchedule(
    RepeatScheduleRule rule, {
    DateTime? effectiveFrom,
  }) async {
    updateCalls++;
    lastEffectiveFrom = effectiveFrom;
  }

  @override
  Future<void> deactivateRepeatSchedule(
    String id, {
    bool cancelFutureInstances = false,
  }) async {
    deactivateCalls++;
    lastCancelFutureInstances = cancelFutureInstances;
  }

  @override
  Future<void> addRepeatSchedule(RepeatScheduleRule rule) async {}

  @override
  Future<RepeatScheduleRule> repeatScheduleById(String id) async =>
      throw UnimplementedError();

  @override
  Future<List<RepeatScheduleRule>> repeatSchedules({
    bool activeOnly = false,
  }) async => [_rule()];
}

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

RepeatScheduleRule _rule() => RepeatScheduleRule(
  id: 'r1',
  title: '평일 출근',
  origin: const PlaceLocation(name: '집', lat: 37.5, lng: 127.0),
  destination: const PlaceLocation(name: '회사', lat: 37.51, lng: 127.03),
  time: '09:00',
  departTime: '08:10',
  startDate: DateTime(2026, 9, 22),
  weekdays: const {1, 2, 3, 4, 5},
);

void main() {
  Future<_FakeRepeatRepository> openEdit(WidgetTester tester) async {
    tester.view.physicalSize = const Size(390 * 3, 2200 * 3);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);

    final repository = _FakeRepeatRepository();
    await tester.pumpWidget(
      MaterialApp(
        home: RepeatScheduleFormScreen(
          repository: repository,
          placeSearchService: const _FakePlaceSearchService(),
          rule: _rule(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return repository;
  }

  Future<void> save(WidgetTester tester) async {
    final button = find.text('반복 일정 수정하기');
    await tester.ensureVisible(button);
    await tester.tap(button);
    await tester.pumpAndSettle();
  }

  testWidgets('반복 규칙을 고치면 언제부터 적용할지 묻는다', (tester) async {
    await openEdit(tester);

    await save(tester);

    expect(find.byKey(const Key('repeatEffectiveFromDialog')), findsOneWidget);
    expect(find.text('언제부터 바꿀까요?'), findsOneWidget);
  });

  testWidgets('"지금부터" 를 고르면 날짜를 보내지 않는다 — 예전 동작 그대로', (tester) async {
    final repository = await openEdit(tester);
    await save(tester);

    await tester.tap(find.byKey(const Key('repeatEffectiveFromNow')));
    await tester.pumpAndSettle();

    expect(repository.updateCalls, 1);
    expect(repository.lastEffectiveFrom, isNull);
  });

  testWidgets('취소하면 아무것도 바꾸지 않는다', (tester) async {
    final repository = await openEdit(tester);
    await save(tester);

    await tester.tap(find.text('취소'));
    await tester.pumpAndSettle();

    expect(repository.updateCalls, 0);
  });
}
