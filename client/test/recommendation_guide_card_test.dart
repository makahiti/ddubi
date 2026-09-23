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
  String? generatedBy,
}) => {
  'schemaVersion': 'recommendation-guide-v1',
  'evaluationId': evaluationId,
  'departureAt': '2026-09-22T08:00:00',
  'estimatedArrivalAt': '2026-09-22T08:46:00',
  'durationSeconds': 2760,
  'fare': {'minimumWon': 1650, 'maximumWon': 1650},
  'headline': headline,
  'generatedBy': ?generatedBy,
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

/// 한 번의 조회에 무엇으로 답할지. null 이면 서버 오류(500), [notReady] 면 계산 중(409).
typedef _Reply = Map<String, dynamic>?;

/// 서버가 "계산이 아직 안 끝났다" 고 답하는 경우. 실패(500)와 구분해야 한다.
const Map<String, dynamic> notReady = {'__status': 409};

/// 요청마다 정해진 답을 돌려주는 가짜 서버. 목록을 다 쓰면 마지막 답을 되풀이한다.
Dio _dio(List<_Reply> replies) {
  var call = 0;
  return Dio(BaseOptions(baseUrl: 'https://example.test/api/v1'))
    ..interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          final reply = replies[call.clamp(0, replies.length - 1)];
          call++;
          if (reply == null || reply['__status'] is int) {
            // 예외를 직접 던지면 요청이 끝나지 않는다. 반드시 reject 로 알린다.
            handler.reject(
              DioException(
                requestOptions: options,
                response: Response<dynamic>(
                  requestOptions: options,
                  statusCode: reply == null ? 500 : reply['__status'] as int,
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
        generatedBy: 'GMS:gpt-5.4-nano',
      ),
    ]);

    await pumpCard(tester, dio);

    expect(find.byKey(const Key('guideRecommendationReason')), findsOneWidget);
    expect(find.text('도로 통제 영향 구간을 피해 다른 경로를 제안해요.'), findsOneWidget);
    expect(find.byKey(const Key('guideChangeType')), findsOneWidget);
    expect(find.text('다른 경로 제안'), findsOneWidget);
    expect(find.text('AI 교통 안내'), findsOneWidget);
    expect(find.byKey(const Key('guideRouteSummary')), findsNothing);
    expect(find.byKey(const Key('guideFare')), findsNothing);
    expect(find.text('도보로 역까지 이동한 뒤 지하철을 이용하세요.'), findsNothing);
  });

  testWidgets('구형 AI의 경로 나열은 숨기고 교통 영향과 경로 변경만 설명한다', (tester) async {
    final response = _guideJson(
      changeType: 'ROUTE_CHANGED',
      reason: '출발지에서 서울역까지 도보 이동 → 1호선 탑승 후 노량진 하차 → 버스 탑승',
      generatedBy: 'GMS:gpt-5.4-nano',
    );
    response['delay'] = {
      'status': 'DISRUPTED',
      'reason': '[시연용] 선택한 버스 경로에 가짜 도로 통제를 적용했어요.',
      'source': 'DEMO_TOPIS',
      'confirmed': true,
    };

    await pumpCard(tester, _dio([response]));

    expect(find.textContaining('서울역까지 도보 이동 →'), findsNothing);
    expect(find.text('확인된 교통 영향을 반영해 다른 경로를 제안해요.'), findsOneWidget);
    expect(find.textContaining('[시연용] 선택한 버스 경로'), findsOneWidget);
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

  testWidgets('구형 응답에 사유가 없어도 시각을 반복하지 않고 안전한 상태 문장을 보여준다', (tester) async {
    final dio = _dio([_guideJson()]);

    await pumpCard(tester, dio);

    // 출발·도착 시각은 상단 카드에 있으므로 구형 서버의 숫자 headline을 다시 읽지 않는다.
    expect(find.byKey(const Key('guideRecommendationReason')), findsNothing);
    expect(find.byKey(const Key('guideChangeType')), findsNothing);
    expect(find.byKey(const Key('guideFallbackLine')), findsOneWidget);
    expect(find.text('08:00 출발 · 08:46 도착 예정'), findsNothing);
    expect(find.textContaining('확인된 교통 영향이나 추천 변경은 없어요'), findsOneWidget);
    // 같은 문장을 아래 상자에서 되풀이하지 않는다.
    expect(find.byKey(const Key('guideHeadline')), findsNothing);
  });

  /// 계산이 바뀌고 조회가 실패했을 때 무엇을 보여줄지.
  ///
  /// 한때는 지난 안내를 지웠다. 지난 계산의 출발시각·요금이 새 결과인 척 남는 것을 막으려던
  /// 것이다. 그런데 서버는 출발이 가까우면 1분마다 계산을 새로 하고, 안내 조회는 계산이
  /// 끝나기 전이거나 유효기간(=다음 계산 예정 시각)이 지난 동안에는 반드시 거절한다. 매
  /// 주기에 반드시 실패 구간이 있어서, 카드가 1분마다 빈칸이 됐다가 돌아왔다(2026-09-22 제보).
  ///
  /// 그래서 지우지 않고 그대로 두되 "이전 계산 기준" 이라고 적는다. 지난 값을 방금 결과로
  /// 오해하게 하지 않는다는 원래 목적은 그 문구가 지킨다.
  testWidgets('계산이 바뀌고 조회가 실패하면 지난 안내를 남기되 이전 계산 기준이라고 알린다', (tester) async {
    final dio = _dio([
      _guideJson(reason: '선택한 경로를 유지해도 돼요.'), // 첫 계산은 성공
      null, // 두 번째 계산은 실패
    ]);

    final headline = await pumpCard(tester, dio);
    expect(find.text('선택한 경로를 유지해도 돼요.'), findsOneWidget);

    // 새 계산이 도착한다.
    headline.value = _evaluation('eval-2');
    await tester.pumpAndSettle();

    // 읽던 내용은 그대로 있다 — 1분마다 빈 카드가 되지 않는다.
    expect(find.text('선택한 경로를 유지해도 돼요.'), findsOneWidget);
    // 대신 최신이 아니라고 적는다.
    expect(find.byKey(const Key('guideStaleNotice')), findsOneWidget);
    expect(find.text('아래는 이전 계산 기준이에요.'), findsOneWidget);
    // 카드는 남는다 — 안에 버튼이 함께 있어서 사라지면 누를 자리까지 없어진다.
    expect(find.byKey(const Key('recommendationGuideCard')), findsOneWidget);
    // 다시 부르는 자리도 그 줄 안에 함께 둔다.
    expect(find.byKey(const Key('guideRefreshStaleButton')), findsOneWidget);
  });

  /// 서버가 409(계산 중)로 답하면 실패가 아니라 "아직" 이다. 예전에는 둘을 같은 문구로 보여줘서,
  /// 계산이 끝나기를 기다리는 동안 "못 불러왔어요" 가 계속 보였다(2026-09-22 제보).
  testWidgets('계산이 아직이면 못 불러왔다고 하지 않고 준비 중이라고 말한다', (tester) async {
    final dio = _dio([notReady]);

    await pumpCard(tester, dio);

    expect(find.text('계산 결과를 확인해 안내를 준비하고 있어요.'), findsOneWidget);
    expect(find.byKey(const Key('guidePendingHint')), findsOneWidget);
    expect(find.text('교통 상황 안내를 아직 불러오지 못했어요.'), findsNothing);
    // 눌러도 또 409 라 다시 불러오기 버튼은 두지 않는다. 새 평가가 오면 스스로 부른다.
    expect(find.byKey(const Key('guideRetryButton')), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('같은 평가가 409 뒤 준비되면 자동 재조회해 안내를 보여준다', (tester) async {
    final dio = _dio([notReady, _guideJson(reason: '도로 상황을 반영해 출발시각을 앞당겨요.')]);

    await pumpCard(tester, dio);
    expect(find.byKey(const Key('guidePendingHint')), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();

    expect(find.text('도로 상황을 반영해 출발시각을 앞당겨요.'), findsOneWidget);
    expect(find.byKey(const Key('guidePendingHint')), findsNothing);
  });

  testWidgets('연결·서버 오류는 실패로 말하고 다시 불러오기를 둔다', (tester) async {
    final dio = _dio([null]);

    await pumpCard(tester, dio);

    expect(find.text('교통 상황 안내를 아직 불러오지 못했어요.'), findsOneWidget);
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
