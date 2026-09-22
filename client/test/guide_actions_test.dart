import 'dart:convert';
import 'dart:io';

import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/recommendation_guide_card.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 추천 안내 카드의 버튼 줄.
///
/// 2026-09-22 제보에서 세 가지가 함께 지적됐다.
///  1. "이 제안 적용" 을 눌러도 거의 늘 실패했다. 서버는 출발이 가까우면 1분마다 계산을
///     새로 하고 최신 번호로만 수락하는데, 버튼은 화면을 그릴 때의 번호를 들고 있었다.
///  2. 무엇이 잘못됐든 "요청을 처리하지 못했어요" 하나만 떴고, 그 문구가 다음 버튼을 누를
///     때까지 사라지지 않았다. 카드 내용은 1분마다 바뀌는데 지난 오류만 남아 있었다.
///  3. "출발했어요" 를 눌러도 화면이 그대로였다. 같은 이름의 버튼이 이동 안내 화면에도
///     있었는데 그쪽만 다음 단계로 넘어갔다.
///
/// 이 검사가 그 셋을 고정한다.
late String _fixtureRaw;

ScheduleEvaluationV2 _evaluation(String id) {
  final json = jsonDecode(_fixtureRaw) as Map<String, dynamic>;
  json['evaluationId'] = id;
  return ScheduleEvaluationV2.fromJson(json);
}

/// 안내 조회에는 늘 같은 답을 준다. 이 검사의 관심은 버튼 줄이라 안내 내용은 고정한다.
Dio _dio() =>
    Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
      ..interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) => handler.resolve(
            Response<dynamic>(
              requestOptions: options,
              statusCode: 200,
              data: {
                'success': true,
                'data': {
                  'schemaVersion': 'recommendation-guide-v1',
                  'evaluationId': 'eval-1',
                  'departureAt': '2026-09-22T08:00:00',
                  'estimatedArrivalAt': '2026-09-22T08:46:00',
                  'durationSeconds': 2760,
                  'fare': {'minimumWon': 1650, 'maximumWon': 1650},
                  'headline': '08:00 출발 · 08:46 도착 예정',
                  'routeSummary': '도보로 역까지 이동한 뒤 지하철을 이용하세요.',
                  'routeSteps': <Map<String, dynamic>>[],
                  'delay': {'status': 'NO_CONFIRMED_DELAY'},
                },
              },
            ),
          ),
        ),
      );

class _FakeRepository implements ScheduleEvaluationRepository {
  _FakeRepository({required this.latest, this.refreshFailure});

  /// 다시 읽기가 돌려줄 값. 검사가 도중에 바꿔 "눌렀더니 화면이 바뀌었다" 를 확인한다.
  ScheduleEvaluationV2 latest;

  /// 다시 계산이 이 오류로 실패한다. null 이면 성공한다.
  final Object? refreshFailure;

  int latestCalls = 0;
  int refreshCalls = 0;

  @override
  Future<ScheduleEvaluationV2> latestEvaluation(String scheduleId) async {
    latestCalls++;
    return latest;
  }

  @override
  Future<void> refreshEvaluation(String scheduleId) async {
    refreshCalls++;
    final failure = refreshFailure;
    if (failure != null) throw failure;
  }

  @override
  Future<void> acceptEvaluation(String scheduleId, String evaluationId) async {}

  @override
  Future<void> confirmDeparture(String scheduleId) async {}
}

void main() {
  setUpAll(() async {
    _fixtureRaw = await File('test/fixtures/schedule_evaluation_keep.json')
        .readAsString();
  });

  Future<ValueNotifier<ScheduleEvaluationV2?>> pumpActions(
    WidgetTester tester, {
    required _FakeRepository repository,
    Future<void> Function()? onStartJourney,
  }) async {
    final headline = ValueNotifier<ScheduleEvaluationV2?>(
      _evaluation('eval-1'),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: RecommendationGuideCard(
              api: RecommendationApi(_dio()),
              scheduleId: 'schedule-1',
              headline: headline,
              evaluationRepository: repository,
              onStartJourney: onStartJourney,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return headline;
  }

  // ------------------------------------------------------------------
  // 다음 단계로 가는 문은 하나다
  // ------------------------------------------------------------------

  testWidgets('출발 기록 버튼 대신 이동 안내로 가는 버튼을 둔다', (tester) async {
    await pumpActions(
      tester,
      repository: _FakeRepository(latest: _evaluation('eval-1')),
      onStartJourney: () async {},
    );

    expect(find.byKey(const Key('startJourneyButton')), findsOneWidget);
    expect(find.text('이동 안내 시작'), findsOneWidget);
    // 같은 이름의 버튼이 이동 안내 화면에도 있어서 어느 쪽이 진짜인지 알 수 없었다.
    expect(find.text('출발했어요'), findsNothing);
  });

  testWidgets('이동 안내로 갈 길이 없으면 그 버튼을 그리지 않는다', (tester) async {
    await pumpActions(
      tester,
      repository: _FakeRepository(latest: _evaluation('eval-1')),
    );

    expect(find.byKey(const Key('startJourneyButton')), findsNothing);
  });

  testWidgets('이동 안내 시작을 누르면 이동 안내를 연다', (tester) async {
    var opened = 0;
    await pumpActions(
      tester,
      repository: _FakeRepository(latest: _evaluation('eval-1')),
      onStartJourney: () async => opened++,
    );

    await tester.tap(find.byKey(const Key('startJourneyButton')));
    await tester.pumpAndSettle();

    expect(opened, 1);
  });

  // ------------------------------------------------------------------
  // 누르면 바로 반영된다
  // ------------------------------------------------------------------

  testWidgets('버튼이 성공하면 곧바로 평가를 다시 읽어 화면에 반영한다', (tester) async {
    final repository = _FakeRepository(latest: _evaluation('eval-1'));
    final headline = await pumpActions(tester, repository: repository);

    // 서버는 이 동작으로 상태가 바뀐 값을 돌려준다.
    repository.latest = _evaluation('eval-after');

    await tester.tap(find.byKey(const Key('refreshEvaluationButton')));
    await tester.pumpAndSettle();

    expect(repository.refreshCalls, 1);
    // 다음 폴링(30초)을 기다리지 않는다.
    expect(repository.latestCalls, 1);
    expect(headline.value?.evaluationId, 'eval-after');
  });

  // ------------------------------------------------------------------
  // 실패를 사실대로, 그리고 오래 남기지 않게
  // ------------------------------------------------------------------

  testWidgets('계산이 바뀌어 거절되면 그렇게 적는다', (tester) async {
    await pumpActions(
      tester,
      repository: _FakeRepository(
        latest: _evaluation('eval-1'),
        refreshFailure: const ScheduleEvaluationFailure(
          kind: EvaluationFailureKind.staleEvaluation,
          message: '서버 원문',
        ),
      ),
    );

    await tester.tap(find.byKey(const Key('refreshEvaluationButton')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('guideActionNotice')), findsOneWidget);
    expect(find.text('계산이 방금 새로 됐어요. 바뀐 제안을 확인하고 다시 눌러 주세요.'), findsOneWidget);
    // 무엇이 잘못됐든 같은 문구를 쓰던 자리다.
    expect(find.text('요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.'), findsNothing);
  });

  testWidgets('연결 문제와 계산 변경을 같은 문구로 합치지 않는다', (tester) async {
    await pumpActions(
      tester,
      repository: _FakeRepository(
        latest: _evaluation('eval-1'),
        refreshFailure: const ScheduleEvaluationFailure(
          kind: EvaluationFailureKind.network,
          message: '서버 원문',
        ),
      ),
    );

    await tester.tap(find.byKey(const Key('refreshEvaluationButton')));
    await tester.pumpAndSettle();

    expect(find.text('연결이 불안정해 요청을 보내지 못했어요.'), findsOneWidget);
  });

  testWidgets('계산이 새로 오면 지난 오류 문구를 지운다', (tester) async {
    final repository = _FakeRepository(
      latest: _evaluation('eval-1'),
      refreshFailure: const ScheduleEvaluationFailure(
        kind: EvaluationFailureKind.staleEvaluation,
        message: '서버 원문',
      ),
    );
    final headline = await pumpActions(tester, repository: repository);

    await tester.tap(find.byKey(const Key('refreshEvaluationButton')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('guideActionNotice')), findsOneWidget);

    // 1분 뒤 서버가 계산을 새로 했다.
    headline.value = _evaluation('eval-2');
    await tester.pumpAndSettle();

    // 다 지난 실패가 지금 고장난 것처럼 남아 있으면 안 된다.
    expect(find.byKey(const Key('guideActionNotice')), findsNothing);
  });
}
