import 'dart:convert';
import 'dart:io';

import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/recommendation_guide_card.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 추천 안내 카드.
///
/// BE 리뷰(2026-09-22)에서 두 가지를 지적받았다.
///  1. 서버가 주는 `changeType`·`recommendationReason` 을 앱이 읽지 않아, GMS 를 붙인
///     핵심 결과인 "왜 이 경로인지" 가 화면에 나오지 않았다.
///  2. 새 계산의 안내 조회가 실패하면 지난 계산의 안내가 그대로 남아, 사용자가 옛 경로·
///     출발시각·요금을 방금 결과로 믿게 됐다.
Map<String, dynamic> _guideJson({
  String evaluationId = 'eval-1',
  String? changeType,
  String? reason,
  String headline = '08:00 출발 · 08:46 도착 예정',
}) => {
  'schemaVersion': 'recommendation-guide-v1',
  'evaluationId': evaluationId,
  'departureAt': '2026-09-22T08:00:00',
  'estimatedArrivalAt': '2026-09-22T08:46:00',
  'durationSeconds': 2760,
  'fare': {'minimumWon': 1650, 'maximumWon': 1650},
  'headline': headline,
  'changeType': ?changeType,
  'recommendationReason': ?reason,
  'routeSummary': '도보로 역까지 이동한 뒤 지하철을 이용하세요.',
  'routeSteps': <Map<String, dynamic>>[],
  'delay': {'status': 'NO_CONFIRMED_DELAY'},
};

/// 픽스처 원본. testWidgets 안에서 파일을 읽으면 안 된다 — 위젯 테스트는 가짜 시간에서
/// 돌아 실제 파일 읽기가 끝나지 않고 그대로 멈춘다. setUpAll 에서 한 번만 읽는다.
late String _fixtureRaw;

ScheduleEvaluationV2 _evaluation(String id) {
  final json = jsonDecode(_fixtureRaw) as Map<String, dynamic>;
  json['evaluationId'] = id;
  return ScheduleEvaluationV2.fromJson(json);
}

/// 한 번의 조회에 무엇으로 답할지. null 이면 서버 오류(500).
typedef _Reply = Map<String, dynamic>?;

/// 요청마다 정해진 답을 돌려주는 가짜 서버. 목록을 다 쓰면 마지막 답을 되풀이한다.
Dio _dio(List<_Reply> replies) {
  var call = 0;
  return Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
    ..interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          final reply = replies[call.clamp(0, replies.length - 1)];
          call++;
          if (reply == null) {
            // 예외를 직접 던지면 요청이 끝나지 않는다. 반드시 reject 로 알린다.
            handler.reject(
              DioException(
                requestOptions: options,
                response: Response<dynamic>(
                  requestOptions: options,
                  statusCode: 500,
                ),
                type: DioExceptionType.badResponse,
              ),
            );
            return;
          }
          handler.resolve(
            Response<Map<String, dynamic>>(
              requestOptions: options,
              statusCode: 200,
              data: {'success': true, 'data': reply},
            ),
          );
        },
      ),
    );
}

void main() {
  setUpAll(() async {
    _fixtureRaw = await File('test/fixtures/schedule_evaluation_keep.json')
        .readAsString();
  });

  Future<ValueNotifier<ScheduleEvaluationV2?>> pumpCard(
    WidgetTester tester,
    Dio dio, {
    String evaluationId = 'eval-1',
  }) async {
    final headline = ValueNotifier<ScheduleEvaluationV2?>(
      _evaluation(evaluationId),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: RecommendationGuideCard(
              api: RecommendationApi(dio),
              scheduleId: 'schedule-1',
              headline: headline,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return headline;
  }

  testWidgets('추천 사유와 변경 유형을 화면에 보여준다', (tester) async {
    final dio = _dio([
      _guideJson(
        changeType: 'ROUTE_CHANGED',
        reason: '도로 통제 영향 구간을 피해 다른 경로를 제안해요.',
      ),
    ]);

    await pumpCard(tester, dio);

    expect(find.byKey(const Key('guideRecommendationReason')), findsOneWidget);
    expect(find.text('도로 통제 영향 구간을 피해 다른 경로를 제안해요.'), findsOneWidget);
    expect(find.byKey(const Key('guideChangeType')), findsOneWidget);
    expect(find.text('다른 경로 제안'), findsOneWidget);
  });

  testWidgets('모르는 변경 유형은 영어 코드를 그대로 보여주지 않는다', (tester) async {
    final dio = _dio([
      _guideJson(changeType: 'SOMETHING_NEW', reason: '설명입니다.'),
    ]);

    await pumpCard(tester, dio);

    expect(find.byKey(const Key('guideChangeType')), findsNothing);
    expect(find.text('SOMETHING_NEW'), findsNothing);
    // 사유는 유형을 몰라도 보여준다.
    expect(find.text('설명입니다.'), findsOneWidget);
  });

  testWidgets('사유가 없으면 그 자리를 비운다 — 지어내지 않는다', (tester) async {
    final dio = _dio([_guideJson()]);

    await pumpCard(tester, dio);

    // 사유가 없으면 사유 자리를 만들지 않는다. 서버가 준 결론만 크게 보여준다.
    expect(find.byKey(const Key('guideRecommendationReason')), findsNothing);
    expect(find.byKey(const Key('guideChangeType')), findsNothing);
    expect(find.byKey(const Key('guideFallbackLine')), findsOneWidget);
    // 같은 문장을 아래 상자에서 되풀이하지 않는다.
    expect(find.byKey(const Key('guideHeadline')), findsNothing);
  });

  testWidgets('계산이 바뀌고 조회가 실패하면 지난 안내를 지운다', (tester) async {
    final dio = _dio([
      _guideJson(headline: '08:00 출발 · 08:46 도착 예정'), // 첫 계산은 성공
      null, // 두 번째 계산은 실패
    ]);

    final headline = await pumpCard(tester, dio);
    expect(find.text('08:00 출발 · 08:46 도착 예정'), findsOneWidget);

    // 새 계산이 도착한다.
    headline.value = _evaluation('eval-2');
    await tester.pumpAndSettle();

    // 지난 계산의 출발시각이 남아 있으면 안 된다.
    expect(find.text('08:00 출발 · 08:46 도착 예정'), findsNothing);
    // 카드는 남는다 — 안에 버튼이 함께 있어서 사라지면 누를 자리까지 없어진다.
    expect(find.byKey(const Key('recommendationGuideCard')), findsOneWidget);
    expect(find.byKey(const Key('guideRetryButton')), findsOneWidget);
  });

  testWidgets('다시 불러오기를 누르면 한 번 더 조회한다', (tester) async {
    final dio = _dio([null, _guideJson(reason: '두 번째에 성공했어요.')]);

    await pumpCard(tester, dio);
    expect(find.byKey(const Key('guideRetryButton')), findsOneWidget);

    await tester.tap(find.byKey(const Key('guideRetryButton')));
    await tester.pumpAndSettle();

    expect(find.text('두 번째에 성공했어요.'), findsOneWidget);
    expect(find.byKey(const Key('guideRetryButton')), findsNothing);
  });
}
