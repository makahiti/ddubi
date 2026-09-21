import 'package:ddubi/data/mock_repository.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/domain/saved_route_display.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:ddubi/services/app_api.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'saved_route_display_test.dart' show fixture33, route;

/// 저장된 경로 목록·상세를 흉내 내고 어떤 경로 ID로 상세를 열었는지 기록한다.
class _FakeRecommendationApi extends RecommendationApi {
  _FakeRecommendationApi(
    this.routes, {
    this.failRouteIds = const {},
    this.goneRouteIds = const {},
  }) : super(Dio());

  final List<SavedRouteSummary> routes;
  final Set<String> failRouteIds;

  /// 서버가 재계산 뒤 정리해 이미 없는 경로. 실제 API 층은 404 를 이 예외로 바꿔 준다.
  final Set<String> goneRouteIds;
  final detailCalls = <String>[];
  var listCalls = 0;

  @override
  Future<RecommendationOverview?> getLatestOverview(String scheduleId) async =>
      null;

  @override
  Future<List<SavedRouteSummary>> getSavedRoutes(String scheduleId) async {
    listCalls++;
    return routes;
  }

  @override
  Future<SavedRouteDetail> getSavedRouteDetail(String routeId) async {
    detailCalls.add(routeId);
    if (goneRouteIds.contains(routeId)) throw SavedRouteGoneException(routeId);
    if (failRouteIds.contains(routeId)) {
      throw DioException(
        requestOptions: RequestOptions(path: '/routes/$routeId'),
        type: DioExceptionType.receiveTimeout,
      );
    }
    return SavedRouteDetail(
      routeId: routeId,
      scheduleId: '12',
      rankNo: 1,
      selected: false,
      baseDurationMin: 25,
      providerPathId: 'path-$routeId',
      segments: const [
        SavedRouteSegment(
          seq: 1,
          mode: 'WALK',
          fromName: '집',
          toName: '강남역',
          baseDurationMin: 5,
        ),
      ],
    );
  }

  @override
  Future<SavedRouteMap> getSavedRouteMap(String routeId) async {
    if (goneRouteIds.contains(routeId)) throw SavedRouteGoneException(routeId);
    if (failRouteIds.contains(routeId)) {
      throw DioException(
        requestOptions: RequestOptions(path: '/routes/$routeId/map'),
        type: DioExceptionType.receiveTimeout,
      );
    }
    return SavedRouteMap(
      routeId: routeId,
      bounds: const RouteMapBounds(
        minLat: 37.4979,
        minLng: 127.0276,
        maxLat: 37.5006,
        maxLng: 127.0364,
      ),
      segments: const [
        RouteMapSegment(
          seq: 1,
          mode: 'WALK',
          fromName: '집',
          toName: '강남역',
          coordinates: [],
        ),
      ],
    );
  }
}

Future<(_FakeRecommendationApi, Schedule)> _pump(
  WidgetTester tester,
  List<SavedRouteSummary> routes, {
  Set<String> failRouteIds = const {},
  Set<String> goneRouteIds = const {},
}) async {
  tester.view.physicalSize = const Size(390, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final repository = MockDdubiRepository();
  final schedule = (await repository.schedules()).first;
  final api = _FakeRecommendationApi(
    routes,
    failRouteIds: failRouteIds,
    goneRouteIds: goneRouteIds,
  );
  await tester.pumpWidget(
    MaterialApp(
      home: ScheduleDetailScreen(
        repository: repository,
        routeRepository: repository,
        recommendationApi: api,
        scheduleId: schedule.id,
        arrivalApi: ArrivalApi(Dio()),
        initialSchedule: schedule,
      ),
    ),
  );
  await tester.pumpAndSettle();
  return (api, schedule);
}

Finder _cards() => find.byWidgetPredicate(
  (widget) =>
      widget is InkWell &&
      widget.key is ValueKey<String> &&
      (widget.key as ValueKey<String>).value.startsWith('savedRoute-'),
);

void main() {
  testWidgets('후보 33개여도 카드는 하나만 나오고 그것이 선택 추천이다', (tester) async {
    final (api, _) = await _pump(tester, fixture33());

    expect(api.detailCalls, isEmpty);
    expect(_cards(), findsOneWidget);
    final firstKey =
        (tester.widget<InkWell>(_cards().first).key as ValueKey<String>).value;
    expect(firstKey, 'savedRoute-sel');
    expect(find.text('선택된 추천 경로'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('표시된 카드를 하나씩 모두 눌러도 예외 없이 자기 경로 상세를 연다', (tester) async {
    final expected = SavedRouteDisplay.from(fixture33()).visible;

    for (final target in expected) {
      final (api, _) = await _pump(tester, fixture33());
      final finder = find.byKey(Key('savedRoute-${target.routeId}'));
      await tester.scrollUntilVisible(finder, 200);
      await tester.pumpAndSettle();
      await tester.ensureVisible(finder);
      await tester.pumpAndSettle();
      await tester.tap(finder, warnIfMissed: false);
      await tester.pumpAndSettle();

      expect(api.detailCalls, [target.routeId], reason: target.routeId);
      expect(find.text('경로 상세'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    }
  });

  testWidgets('경로 ID가 없으면 앱을 끄지 않고 안내만 한다', (tester) async {
    final (api, _) = await _pump(tester, [
      route(id: '', rank: 1, selected: true),
      route(id: 'ok', rank: 2, to: '다른곳', minutes: 51),
    ]);

    final finder = find.byKey(const Key('savedRoute-'));
    await tester.scrollUntilVisible(finder, 200);
    await tester.tap(finder);
    await tester.pumpAndSettle();

    expect(find.text('경로 상세를 불러올 수 없어요.'), findsOneWidget);
    expect(api.detailCalls, isEmpty);
    expect(find.text('경로 상세'), findsNothing);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('상세 API가 실패해도 앱이 꺼지지 않고 오류 안내와 재시도를 보여준다', (tester) async {
    final (api, _) = await _pump(tester, fixture33(), failRouteIds: {'sel'});

    final finder = find.byKey(const Key('savedRoute-sel'));
    await tester.scrollUntilVisible(finder, 200);
    await tester.tap(finder);
    await tester.pumpAndSettle();

    expect(api.detailCalls, ['sel']);
    expect(find.text('경로 상세를 불러오지 못했어요.'), findsOneWidget);
    expect(find.text('다시 시도'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('서버가 정리한 경로를 누르면 오류 대신 목록이 오래됐다고 알리고, 돌아오면 목록을 다시 읽는다', (
    tester,
  ) async {
    final (api, _) = await _pump(tester, fixture33(), goneRouteIds: {'sel'});
    final listCallsBefore = api.listCalls;

    final finder = find.byKey(const Key('savedRoute-sel'));
    await tester.scrollUntilVisible(finder, 200);
    await tester.tap(finder);
    await tester.pumpAndSettle();

    // 재계산 뒤 정리된 후보는 서버 장애가 아니다. "다시 시도"가 아니라 돌아가서 새 목록을 보게 한다.
    expect(find.text('이 경로는 새로 계산되면서 정리됐어요.'), findsOneWidget);
    expect(find.text('다시 시도'), findsNothing);
    expect(find.byKey(const Key('savedRouteGoneBack')), findsOneWidget);

    await tester.tap(find.byKey(const Key('savedRouteGoneBack')));
    await tester.pumpAndSettle();

    expect(find.text('경로 상세'), findsNothing);
    expect(api.listCalls, greaterThan(listCallsBefore));
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('빠르게 두 번 눌러도 상세 화면이 두 번 열리지 않는다', (tester) async {
    final (api, _) = await _pump(tester, fixture33());

    final finder = find.byKey(const Key('savedRoute-sel'));
    await tester.scrollUntilVisible(finder, 200);
    await tester.tap(finder);
    await tester.pump();
    await tester.tap(finder, warnIfMissed: false);
    await tester.pumpAndSettle();

    expect(api.detailCalls, ['sel']);
    expect(find.text('경로 상세'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('중복 제거 전후로 카드와 원본 후보의 연결이 유지된다', (tester) async {
    final all = fixture33();
    final display = SavedRouteDisplay.from(all);
    final (api, _) = await _pump(tester, all);

    for (final visible in display.visible) {
      expect(
        find.byKey(Key('savedRoute-${visible.routeId}')),
        findsOneWidget,
        reason: visible.routeId,
      );
      expect(all.where((r) => r.routeId == visible.routeId), hasLength(1));
    }
    expect(api.detailCalls, isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('한 장만 보일 때는 순위 번호 대신 추천이라고 적는다', (tester) async {
    // 선택 표시가 없는 목록이어도 화면에는 제일 앞 경로 한 장만 나온다.
    await _pump(tester, [
      route(id: 'top', rank: 1),
      route(id: 'second', rank: 2, to: '다른곳', minutes: 51),
    ]);

    expect(_cards(), findsOneWidget);
    expect(find.text('가장 추천하는 경로'), findsOneWidget);
    expect(find.textContaining('순위 경로'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
