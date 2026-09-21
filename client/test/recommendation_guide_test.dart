import 'package:ddubi/domain/recommendation_guide.dart';
import 'package:flutter_test/flutter_test.dart';

/// 추천 안내 계약(2026-09-21 백엔드 전달분).
///
/// 숫자와 순서는 서버 계산값이므로 앱은 받은 값을 그대로 쓴다. 값이 없을 때 "0분 지연"
/// 같은 말을 지어내지 않는 것이 핵심이다.
void main() {
  RecommendationGuide parse(Map<String, dynamic> extra) =>
      RecommendationGuide.fromJson({
        'schemaVersion': 'recommendation-guide-v1',
        'evaluationId': 'evaluation-uuid',
        'departureAt': '2026-09-21T08:10:00',
        'estimatedArrivalAt': '2026-09-21T08:52:00',
        'durationSeconds': 2520,
        'headline': '08:10 출발 · 08:52 도착 예정',
        'routeSummary': '강남역까지 걸은 뒤 잠실역까지 지하철로 이동하세요.',
        'routeSteps': [
          {
            'sequence': 1,
            'mode': 'WALK',
            'fromName': '출발지',
            'toName': '강남역',
            'durationSeconds': 300,
            'instruction': '출발지에서 강남역까지 도보 이동',
          },
        ],
        'dataAsOf': '2026-09-20T23:00:00Z',
        'validUntil': '2026-09-20T23:10:00Z',
        ...extra,
      });

  test('시각은 표기 없는 한국 시각과 UTC 를 모두 지역 시각으로 맞춘다', () {
    final guide = parse({
      'delay': {'status': 'NO_CONFIRMED_DELAY'},
    });
    // 표기 없는 값은 그대로 08:10
    expect(guide.departureAt?.hour, 8);
    expect(guide.departureAt?.minute, 10);
    // UTC 23:00Z 는 한국 기기에서 다음 날 08:00
    expect(guide.dataAsOf?.isUtc, isFalse);
    expect(guide.dataAsOf, DateTime.utc(2026, 9, 20, 23).toLocal());
  });

  test('이동 순서와 안내 문장을 그대로 읽는다', () {
    final guide = parse({
      'delay': {'status': 'NO_CONFIRMED_DELAY'},
    });
    expect(guide.steps.single.mode, 'WALK');
    expect(guide.steps.single.instruction, '출발지에서 강남역까지 도보 이동');
    expect(guide.steps.single.durationSeconds, 300);
  });

  test('확인된 지연이 없으면 지연 영역을 숨긴다', () {
    final guide = parse({
      'delay': {'status': 'NO_CONFIRMED_DELAY', 'confirmed': false},
    });
    expect(guide.delay.hasMessage, isFalse);
  });

  test('이유가 없으면 분 단위 숫자를 지어내지 않고 숨긴다', () {
    final guide = parse({
      'delay': {'status': 'POSSIBLE', 'delaySeconds': null, 'reason': null},
    });
    expect(guide.delay.hasMessage, isFalse);
  });

  test('이유가 있으면 보여준다', () {
    final guide = parse({
      'delay': {
        'status': 'DELAYED',
        'reason': '강남대로 사고로 선택 경로에 영향이 확인됐어요.',
        'source': '서울 TOPIS',
        'confirmed': true,
      },
    });
    expect(guide.delay.hasMessage, isTrue);
    expect(guide.delay.source, '서울 TOPIS');
  });

  test('validUntil 이 지나면 만료로 본다', () {
    final guide = parse({
      'delay': {'status': 'NO_CONFIRMED_DELAY'},
    });
    expect(guide.isExpiredAt(DateTime.utc(2026, 9, 20, 23, 5)), isFalse);
    expect(guide.isExpiredAt(DateTime.utc(2026, 9, 20, 23, 30)), isTrue);
  });
}
