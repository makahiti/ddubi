import 'dart:convert';
import 'dart:io';

import 'package:ddubi/domain/auth.dart';
import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/departure_headline.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:ddubi/services/app_api.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Future<ScheduleEvaluationV2> _fixture(String name) async {
  final raw = await File('test/fixtures/schedule_evaluation_$name.json')
      .readAsString();
  return ScheduleEvaluationV2.fromJson(jsonDecode(raw) as Map<String, dynamic>);
}

HomeApi _homeApi() {
  final dio = Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
    ..interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) => handler.resolve(
          Response<Map<String, dynamic>>(
            requestOptions: options,
            statusCode: 200,
            data: {
              'success': true,
              'data': {
                'nextSchedule': {
                  'id': 'schedule-1',
                  'title': '팀 회의',
                  'origin': {'name': '집', 'lat': 37.5, 'lng': 127.0},
                  'destination': {'name': '회사', 'lat': 37.51, 'lng': 127.03},
                  'plannedDepartAt': '2026-09-17T23:50:00+09:00',
                  'arriveBy': '2026-09-18T00:40:00+09:00',
                },
                'todaySchedules': <Object>[],
                'unreadNotificationCount': 0,
                'dataState': 'NORMAL',
              },
            },
          ),
        ),
      ),
    );
  final store = AuthSessionStore()
    ..save(
      const AuthSession(
        accessToken: 'test-access-token',
        refreshToken: 'test-refresh-token',
        tokenType: 'Bearer',
        deviceId: 'test-device',
        user: AuthUser(
          id: 1,
          provider: 'LOCAL',
          email: 'member@example.com',
          nickname: '뚜비',
        ),
      ),
    );
  return HomeApi(dio, store);
}

class _HomeEvaluationRepository implements ScheduleEvaluationRepository {
  _HomeEvaluationRepository({this.evaluation, this.failure});

  final ScheduleEvaluationV2? evaluation;
  final Object? failure;
  var latestCalls = 0;

  @override
  Future<ScheduleEvaluationV2> latestEvaluation(String scheduleId) async {
    latestCalls++;
    if (failure != null) throw failure!;
    return evaluation!;
  }

  @override
  Future<void> acceptEvaluation(String id, String evaluationId) async {}

  @override
  Future<void> confirmDeparture(String id) async {}

  @override
  Future<void> refreshEvaluation(String id) async {}
}

class _CountingLegacyRecommendationApi extends RecommendationApi {
  _CountingLegacyRecommendationApi() : super(Dio());

  var latestCalls = 0;

  @override
  Future<RecommendationOverview?> getLatestOverview(String scheduleId) async {
    latestCalls++;
    return const RecommendationOverview(
      state: 'READY',
      recommendationId: 'legacy',
      preview: true,
    );
  }
}

void main() {
  late ScheduleEvaluationV2 keep;

  setUpAll(() async {
    keep = await _fixture('keep');
  });

  testWidgets('홈도 일정 상세와 같은 v2 P와 ETA를 표시한다', (tester) async {
    final repository = _HomeEvaluationRepository(evaluation: keep);
    await tester.pumpWidget(
      MaterialApp(
        home: HomeScreen(homeApi: _homeApi(), evaluationRepository: repository),
      ),
    );
    await tester.pumpAndSettle();

    expect(repository.latestCalls, 1);
    // 홈은 결론 한 줄만 보여주지만, 시각은 일정 상세와 같은 v2 P와 ETA여야 한다.
    expect(
      find.text(
        '${formatClock(keep.proposedDepartAt!)}에 나가면 '
        '${formatClock(keep.estimatedArrivalAt!)} 도착',
      ),
      findsOneWidget,
    );
    expect(find.byKey(const Key('departureHeadlineTap')), findsOneWidget);
  });

  testWidgets('v2 조회 실패를 구버전 추천 성공으로 숨기지 않는다', (tester) async {
    final repository = _HomeEvaluationRepository(
      failure: const ScheduleEvaluationFailure(
        kind: EvaluationFailureKind.network,
        message: 'network',
      ),
    );
    final legacyApi = _CountingLegacyRecommendationApi();

    await tester.pumpWidget(
      MaterialApp(
        home: HomeScreen(
          homeApi: _homeApi(),
          evaluationRepository: repository,
          recommendationApi: legacyApi,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(repository.latestCalls, 1);
    expect(legacyApi.latestCalls, 0);
    expect(find.textContaining('검증용'), findsNothing);
  });
}
