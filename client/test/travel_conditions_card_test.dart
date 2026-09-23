import 'package:ddubi/domain/travel_conditions.dart';
import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/travel_conditions_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 일정 상세의 "오늘의 이동 변수" 칸.
///
/// 예전에는 네 가지를 한 줄로 묶어 "아직 확인되지 않았어요" 라고만 했다. 날씨만 들어와도
/// 문구가 그대로라 무엇이 되고 무엇이 안 되는지 알 수 없었다. 칸을 넷으로 나눈 뒤로는
/// **값이 있는 칸과 없는 칸이 한눈에 갈려야 한다.**
///
/// 여기서 지키는 규칙 하나: 값이 없는 칸을 0 이나 "맑음" 같은 그럴듯한 값으로 채우지 않는다.
class _StubService implements TravelConditionsService {
  _StubService(this._result);

  final TravelConditions _result;
  var calls = 0;

  @override
  Future<TravelConditions> conditionsFor(String scheduleId) async {
    calls++;
    return _result;
  }
}

class _FailingService implements TravelConditionsService {
  @override
  Future<TravelConditions> conditionsFor(String scheduleId) async =>
      throw Exception('서버 없음');
}

Future<void> pumpCard(
  WidgetTester tester, {
  TravelConditionsService? service,
  ValueNotifier<ScheduleEvaluationV2?>? evaluation,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: TravelConditionsCard(
          scheduleId: 's1',
          service: service,
          evaluation: evaluation,
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('통로가 없으면 네 칸 모두 확인 안 됨으로 보인다', (tester) async {
    await pumpCard(tester);

    expect(find.text('날씨'), findsOneWidget);
    expect(find.text('혼잡도'), findsOneWidget);
    expect(find.text('도로 통제'), findsOneWidget);
    expect(find.text('지하철'), findsOneWidget);
    expect(find.text('확인 안 됨'), findsNWidgets(4));
  });

  testWidgets('조회 통로가 없으면 연결되지 않았다고 알린다', (tester) async {
    await pumpCard(tester);

    expect(find.textContaining('이동 변수 조회가 연결되지 않았어요'), findsOneWidget);
  });

  testWidgets('값이 있는 칸만 값으로 바뀐다 — 나머지는 채우지 않는다', (tester) async {
    final service = _StubService(
      const TravelConditions(
        weather: TravelConditionItem.value('20.9℃ · 비 안 옴', note: '종로구'),
        roadBlock: TravelConditionItem.value('봉은교 양방향 전면통제'),
      ),
    );

    await pumpCard(tester, service: service);

    expect(service.calls, 1);
    expect(find.text('20.9℃ · 비 안 옴'), findsOneWidget);
    expect(find.text('종로구'), findsOneWidget);
    expect(find.text('봉은교 양방향 전면통제'), findsOneWidget);
    // 혼잡도와 지하철은 값이 없다. 0 이나 "보통" 으로 채우면 안 된다.
    expect(find.text('확인 안 됨'), findsNWidgets(2));
  });

  testWidgets('값이 하나라도 있으면 안내 문구를 빼서 자리를 아낀다', (tester) async {
    await pumpCard(
      tester,
      service: _StubService(
        const TravelConditions(weather: TravelConditionItem.value('20.9℃')),
      ),
    );

    expect(find.textContaining('이동 변수 조회가 연결되지 않았어요'), findsNothing);
  });

  testWidgets('관측 시각이 오면 기준 시각을 붙인다', (tester) async {
    await pumpCard(
      tester,
      service: _StubService(
        TravelConditions(
          weather: const TravelConditionItem.value('20.9℃'),
          observedAt: DateTime(2026, 9, 22, 8, 5),
        ),
      ),
    );

    expect(find.text('08:05 기준'), findsOneWidget);
  });

  testWidgets('선택 경로의 새 평가가 끝나면 이동 변수를 다시 조회한다', (tester) async {
    final service = _StubService(TravelConditions.unknown);
    final evaluation = ValueNotifier<ScheduleEvaluationV2?>(null);
    addTearDown(evaluation.dispose);
    await pumpCard(tester, service: service, evaluation: evaluation);
    expect(service.calls, 1);

    evaluation.value = ScheduleEvaluationV2.fromJson(const {
      'schemaVersion': 'schedule-evaluation-v2',
      'evaluationId': 'new-route-evaluation',
      'state': 'READY',
    });
    await tester.pump();
    expect(service.calls, 2);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('조회가 실패해도 카드는 남고 오류 화면을 띄우지 않는다', (tester) async {
    await pumpCard(tester, service: _FailingService());

    expect(find.text('오늘의 이동 변수'), findsOneWidget);
    expect(find.text('확인 안 됨'), findsNWidgets(4));
    expect(find.textContaining('조회에 실패했어요'), findsOneWidget);
  });

  testWidgets('긴 통제 문구가 와도 좁은 화면에서 넘치지 않는다', (tester) async {
    // 실제 서버 값이 이만큼 길다. 도로 통제 문구는 공사 안내가 통째로 들어온다.
    tester.view.physicalSize = const Size(360 * 3, 800 * 3);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);

    await pumpCard(
      tester,
      service: _StubService(
        const TravelConditions(
          roadBlock: TravelConditionItem.value(
            '탄천나들목 및 봉은교 구조개선공사 — 양방향 전면통제',
            note: '2027-03-01 해제 예정',
          ),
        ),
      ),
    );

    expect(tester.takeException(), isNull);
  });

  // 아래는 서버 계약(movement-conditions-v1)의 실제 응답 예시를 그대로 쓴다.
  // 서버는 숫자와 코드만 준다. 문장 만들기는 앱이 한다.
  group('서버 응답 읽기', () {
    test('계약 예시를 네 칸으로 읽는다', () {
      final parsed = TravelConditions.fromJson(const {
        'schemaVersion': 'movement-conditions-v1',
        'observedAt': '2026-09-22T01:58:00Z',
        'weather': {
          'available': true,
          'districts': ['종로구', '중구'],
          'temperatureC': 20.9,
          'precipitationMm': 0.0,
        },
        'congestion': {
          'available': true,
          'areaName': '시청',
          'level': 'BUSY',
          'score': 3,
        },
        'roadBlock': {'available': true, 'count': 1, 'summary': '봉은교 양방향 전면통제'},
        'subwayDelay': {
          'available': false,
          'reason': 'NO_MATCHING_ACTIVE_ALERT',
          'count': 0,
        },
      });

      expect(parsed.weather.text, '20.9℃ · 비 안 옴');
      expect(parsed.weather.note, '종로구·중구 기준');
      expect(parsed.congestion.text, '시청 일대 붐빔');
      expect(parsed.roadBlock.text, '봉은교 양방향 전면통제');
      // NO_MATCHING_ACTIVE_ALERT 는 "지연 없음" 이 아니다. 탈 호선을 살펴봤는데 걸린 공지가 없다는 뜻이라
      // "확인 안 됨" 과도 구분해 적는다(2026-09-24).
      expect(parsed.subwayDelay.available, isFalse);
      expect(parsed.subwayDelay.checkedEmpty, '확인된 공지 없음');
      expect(parsed.observedAt, DateTime.utc(2026, 9, 22, 1, 58).toLocal());
    });

    test('비가 오면 양을 적는다', () {
      final parsed = TravelConditions.fromJson(const {
        'weather': {
          'available': true,
          'temperatureC': 18.0,
          'precipitationMm': 2.5,
        },
      });
      expect(parsed.weather.text, '18.0℃ · 비 2.5mm');
    });

    test('available 이 거짓이면 값이 있어도 읽지 않는다', () {
      final parsed = TravelConditions.fromJson(const {
        'weather': {'available': false, 'temperatureC': 20.9},
        'roadBlock': {'available': false, 'summary': '봉은교 통제'},
      });
      expect(parsed.weather.available, isFalse);
      expect(parsed.roadBlock.available, isFalse);
    });

    test('후보 도로 매핑은 추정 정보라고 표시한다', () {
      final parsed = TravelConditions.fromJson(const {
        'roadBlock': {
          'available': true,
          'count': 1,
          'summary': '경로 주변 돌발정보',
          'mappingStatus': 'PROVISIONAL',
        },
      });
      expect(parsed.roadBlock.text, '경로 주변 돌발정보');
      expect(parsed.roadBlock.note, '후보 매핑 · 추정 정보');
    });

    test('시연용 가짜 통제는 실제 사고로 보이지 않게 덧말을 붙인다', () {
      final parsed = TravelConditions.fromJson(const {
        'roadBlock': {
          'available': true,
          'count': 1,
          'summary': '[시연] 선택한 버스 구간에 가짜 도로 통제를 적용했어요.',
          'source': 'DEMO_TOPIS',
          'mappingStatus': 'DEMO',
        },
      });
      expect(parsed.roadBlock.text, startsWith('[시연]'));
      expect(parsed.roadBlock.note, '시연용 가상 통제');
    });

    test('기온이 없으면 강수량만으로 날씨를 만들지 않는다', () {
      final parsed = TravelConditions.fromJson(const {
        'weather': {'available': true, 'precipitationMm': 0.0},
      });
      expect(parsed.weather.available, isFalse);
    });

    /// 도시데이터는 6시간마다 모이고 관측 유효시간은 15분이라, 값이 있는 시간이 하루의 일부뿐이다.
    /// 서버가 stale 로 표시해 보내면 값을 감추지 않고 "언제 기준" 인지 함께 보여 준다.
    /// 살펴봤는데 없는 것과 살펴보지 못한 것은 다른 말이다(2026-09-24 제보).
    /// 경로 도로를 다 확인했는데 통제가 없으면 "확인 안 됨" 이 아니라 "확인된 통제 없음" 이다.
    test('확인했지만 값이 없는 칸은 확인 못 한 칸과 다르게 적는다', () {
      final parsed = TravelConditions.fromJson({
        'roadBlock': {'available': false, 'reason': 'NO_ACTIVE_ROAD_CONTROL'},
        'subwayDelay': {'available': false, 'reason': 'NO_SUBWAY_SEGMENT'},
        'congestion': {'available': false, 'reason': 'NO_BUS_SEGMENT'},
      });

      expect(parsed.roadBlock.checkedEmpty, '확인된 통제 없음');
      expect(parsed.subwayDelay.checkedEmpty, '해당 없음');
      expect(parsed.subwayDelay.note, '지하철로 가는 구간이 없어요');
      expect(parsed.congestion.checkedEmpty, '해당 없음');
      // 값이 있는 것처럼 세지 않는다. 기준 시각·강조 표시는 실제 값에만 붙는다.
      expect(parsed.roadBlock.available, isFalse);
    });

    test('자료가 없거나 도로를 잇지 못했으면 그대로 "확인 안 됨" 이다', () {
      final parsed = TravelConditions.fromJson({
        'roadBlock': {'available': false, 'reason': 'ROAD_PATH_UNMAPPED'},
        'subwayDelay': {
          'available': false,
          'reason': 'SUBWAY_ALERT_READ_FAILED',
        },
        'congestion': {
          'available': false,
          'reason': 'CONGESTION_UNAVAILABLE_OR_STALE',
        },
      });

      expect(parsed.roadBlock.checkedEmpty, isNull);
      expect(parsed.subwayDelay.checkedEmpty, isNull);
      expect(parsed.congestion.checkedEmpty, isNull);
    });

    test('확인된 공지가 없으면 "지연 없음" 이라고 단정하지 않는다', () {
      final parsed = TravelConditions.fromJson({
        'subwayDelay': {
          'available': false,
          'reason': 'NO_MATCHING_ACTIVE_ALERT',
        },
      });

      expect(parsed.subwayDelay.checkedEmpty, '확인된 공지 없음');
      expect(parsed.subwayDelay.checkedEmpty, isNot(contains('지연 없음')));
    });

    test('유효시각 지난 혼잡도는 기준 시각을 덧붙여 보여 준다', () {
      final parsed = TravelConditions.fromJson({
        'congestion': {
          'available': true,
          'areaName': '시청',
          'level': 'BUSY',
          'stale': true,
          'observedAt': '2026-09-23T06:32:00+09:00',
        },
      });

      expect(parsed.congestion.text, '시청 일대 붐빔');
      expect(parsed.congestion.note, contains('기준'));
      expect(parsed.congestion.note, contains('최신 아님'));
    });

    test('최신 혼잡도에는 기준 시각 덧말을 붙이지 않는다', () {
      final parsed = TravelConditions.fromJson({
        'congestion': {
          'available': true,
          'areaName': '시청',
          'level': 'BUSY',
          'stale': false,
          'observedAt': '2026-09-23T06:32:00+09:00',
        },
      });

      expect(parsed.congestion.note, isNull);
    });

    test('모르는 혼잡도 등급은 영어 코드를 그대로 쓰지 않는다', () {
      expect(TravelConditions.congestionLabel('SOMETHING_NEW'), isNull);
      final parsed = TravelConditions.fromJson(const {
        'congestion': {
          'available': true,
          'areaName': '시청',
          'level': 'SOMETHING_NEW',
        },
      });
      expect(parsed.congestion.available, isFalse);
    });

    test('지하철 공지는 요약이 없으면 제목을 쓴다', () {
      final parsed = TravelConditions.fromJson(const {
        'subwayDelay': {
          'available': true,
          'count': 1,
          'routeLines': ['5호선'],
          'alerts': [
            {'lineName': '5호선', 'title': '여의나루역 무정차 통과'},
          ],
        },
      });
      expect(parsed.subwayDelay.text, '여의나루역 무정차 통과');
      expect(parsed.subwayDelay.note, '5호선');
    });

    test('모르는 모양이 와도 네 칸이 확인 안 됨으로 남는다', () {
      expect(TravelConditions.fromJson('이상한 값').hasAny, isFalse);
      expect(TravelConditions.fromJson(const {}).hasAny, isFalse);
    });
  });
}
