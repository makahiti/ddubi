import 'dart:async';

import 'package:ddubi/data/mock_repository.dart';
import 'package:ddubi/domain/auth.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/domain/recommendation_guide.dart';
import 'package:ddubi/domain/travel_conditions.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:ddubi/services/app_api.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 일정 최초 조회만 늦추어 빈 화면 대신 카드 형태 로딩이 나오는지 확인한다.
class _SlowScheduleRepository extends MockDdubiRepository {
  final Completer<Schedule?> requested = Completer<Schedule?>();

  @override
  Future<Schedule?> scheduleById(String id) => requested.future;
}

class _SlowRefreshRecommendationApi extends RecommendationApi {
  _SlowRefreshRecommendationApi() : super(Dio());

  final Completer<RecommendationOverview?> refreshed =
      Completer<RecommendationOverview?>();
  int calls = 0;

  @override
  Future<RecommendationOverview?> getLatestOverview(String scheduleId) {
    calls++;
    return calls == 1 ? Future.value(null) : refreshed.future;
  }

  @override
  Future<List<SavedRouteSummary>> getSavedRoutes(String scheduleId) async =>
      const [];

  @override
  Future<RecommendationGuide?> getLatestGuide(String scheduleId) async => null;

  @override
  Future<TravelConditions> conditionsFor(String scheduleId) async =>
      TravelConditions.unknown;
}

/// 등록 직후에는 저장 응답만 있고 경로가 비어 있다. 두 번째 조회에서 서버 경로가
/// 완성되면 스켈레톤이 실제 카드로 교체되는지 확인한다.
class _RouteAppearsLaterApi extends RecommendationApi {
  _RouteAppearsLaterApi() : super(Dio());

  int calls = 0;

  @override
  Future<RecommendationOverview?> getLatestOverview(String scheduleId) async {
    calls++;
    return null;
  }

  @override
  Future<List<SavedRouteSummary>> getSavedRoutes(String scheduleId) async =>
      calls < 2
      ? const []
      : const [
          SavedRouteSummary(
            routeId: 'route-1',
            rankNo: 1,
            selected: true,
            baseDurationMin: 42,
            fromName: '집',
            toName: '회사',
            segmentCount: 3,
            modes: ['WALK', 'SUBWAY'],
          ),
        ];

  @override
  Future<RecommendationGuide?> getLatestGuide(String scheduleId) async => null;

  @override
  Future<TravelConditions> conditionsFor(String scheduleId) async =>
      TravelConditions.unknown;
}

void main() {
  testWidgets('최초 조회 중에는 애니메이션 스켈레톤, 완료 후에는 실제 카드를 보여준다', (tester) async {
    final repository = _SlowScheduleRepository();
    final schedule = MockDdubiRepository.scheduleData.first;
    await tester.pumpWidget(
      MaterialApp(
        home: ScheduleDetailScreen(
          repository: repository,
          routeRepository: repository,
          scheduleId: schedule.id,
          arrivalApi: ArrivalApi(Dio()),
          userApi: UserApi(Dio(), AuthSessionStore()),
        ),
      ),
    );

    expect(find.byKey(const Key('scheduleDetailSkeleton')), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsNothing);
    // 응답을 기다리는 동안 고정된 회색 박스가 아니라 명도가 변하는 애니메이션이어야 한다.
    final initialOpacity = tester
        .widget<FadeTransition>(
          find.descendant(
            of: find.byKey(const Key('scheduleDetailSkeleton')),
            matching: find.byType(FadeTransition),
          ),
        )
        .opacity
        .value;
    await tester.pump(const Duration(milliseconds: 350));
    final animatedOpacity = tester
        .widget<FadeTransition>(
          find.descendant(
            of: find.byKey(const Key('scheduleDetailSkeleton')),
            matching: find.byType(FadeTransition),
          ),
        )
        .opacity
        .value;
    expect(animatedOpacity, isNot(initialOpacity));
    repository.requested.complete(schedule);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('scheduleDetailSkeleton')), findsNothing);
    expect(find.byKey(const Key('scheduleRouteOverview')), findsOneWidget);
  });

  testWidgets('등록 직후 경로 계산 중에는 스켈레톤을 유지하고 결과가 오면 교체한다', (tester) async {
    final repository = MockDdubiRepository();
    final api = _RouteAppearsLaterApi();
    final schedule = MockDdubiRepository.scheduleData.first;
    await tester.pumpWidget(
      MaterialApp(
        home: ScheduleDetailScreen(
          repository: repository,
          routeRepository: repository,
          recommendationApi: api,
          scheduleId: schedule.id,
          arrivalApi: ArrivalApi(Dio()),
          userApi: UserApi(Dio(), AuthSessionStore()),
        ),
      ),
    );
    await tester.pump();
    expect(find.byKey(const Key('scheduleDetailSkeleton')), findsOneWidget);
    expect(find.byKey(const Key('scheduleRouteOverview')), findsNothing);

    await tester.pump(const Duration(seconds: 3));
    await tester.pump();
    expect(api.calls, 2);
    expect(find.byKey(const Key('scheduleDetailSkeleton')), findsNothing);
    expect(find.byKey(const Key('scheduleRouteOverview')), findsOneWidget);
  });

  testWidgets('새로 계산하는 동안 이미 보이던 상세 정보는 스켈레톤으로 바꾸지 않는다', (tester) async {
    final repository = MockDdubiRepository();
    final api = _SlowRefreshRecommendationApi();
    final schedule = MockDdubiRepository.scheduleData.first;
    await tester.pumpWidget(
      MaterialApp(
        home: ScheduleDetailScreen(
          repository: repository,
          routeRepository: repository,
          recommendationApi: api,
          scheduleId: schedule.id,
          initialRoutes: MockDdubiRepository.routeData,
          arrivalApi: ArrivalApi(Dio()),
          userApi: UserApi(Dio(), AuthSessionStore()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('scheduleRouteOverview')), findsOneWidget);
    expect(api.calls, 1);

    unawaited(
      tester.state<RefreshIndicatorState>(find.byType(RefreshIndicator)).show(),
    );
    await tester.pump(const Duration(seconds: 1));
    await tester.pump(const Duration(seconds: 1));
    expect(api.calls, 2);
    expect(find.byKey(const Key('scheduleRouteOverview')), findsOneWidget);
    expect(find.byKey(const Key('scheduleDetailSkeleton')), findsNothing);

    api.refreshed.complete(null);
    await tester.pumpAndSettle();
  });
}
