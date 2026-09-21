import 'dart:convert';
import 'dart:io';

import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/departure_headline.dart';
import 'package:flutter_test/flutter_test.dart';

Future<Map<String, dynamic>> _raw(String name) async => jsonDecode(
  await File('test/fixtures/schedule_evaluation_$name.json').readAsString(),
) as Map<String, dynamic>;

/// 결론 한 줄은 서버 값을 새로 만들지 않고, 확정 여부에 따라 보여줄지만 정한다.
void main() {
  final now = DateTime.now();

  test('확정 추천이면 "몇 시에 나가면 몇 시 도착"을 결론으로 쓴다', () async {
    final keep = ScheduleEvaluationV2.fromJson(await _raw('keep'));
    final content = DepartureHeadlineContent.of(keep, now);

    expect(content.tone, DepartureHeadlineTone.ready);
    expect(content.title, contains('에 나가면'));
    expect(content.title, contains('도착'));
  });

  test('유효시간만 지난 확정 결과는 고장이 아니라 마지막 확인 결과로 조용히 보여준다', () async {
    final raw = await _raw('keep');
    raw['computedAt'] = now
        .subtract(const Duration(minutes: 2))
        .toUtc()
        .toIso8601String();
    raw['validUntil'] = now
        .subtract(const Duration(minutes: 1))
        .toUtc()
        .toIso8601String();
    final expired = ScheduleEvaluationV2.fromJson(raw);
    expect(expired.isExpiredAt(now), isTrue);
    expect(expired.canRecommendAt(now), isFalse, reason: '적용 가능 여부는 여전히 아니다');

    final content = DepartureHeadlineContent.of(expired, now);

    expect(content.tone, DepartureHeadlineTone.waiting);
    expect(content.title, contains('에 나가면'));
    expect(content.detail, contains('에 확인한 결과예요'));
    expect(content.title, isNot(contains('이전에 계산한')));
  });

  test('검증용(preview) 결과는 시각을 참고값으로 보여주되 확정 색을 쓰지 않는다', () async {
    final raw = await _raw('keep');
    (raw['quality'] as Map<String, dynamic>)['preview'] = true;
    raw['guidance'] = {
      'canDisplayEstimate': true,
      'canAccept': false,
      'source': 'EXPERIMENTAL_ESTIMATE',
      'reasons': ['ML_TEST_RESULT_NOT_ACCEPTABLE'],
    };
    // 만료 동작은 위 시험에서 따로 확인한다. 여기서는 유효한 결과로 둔다.
    raw['validUntil'] = now
        .add(const Duration(minutes: 1))
        .toUtc()
        .toIso8601String();
    final preview = ScheduleEvaluationV2.fromJson(raw);
    expect(preview.canAcceptAt(now), isFalse, reason: '시험 예측이 섞이면 수락은 막힌다');

    final content = DepartureHeadlineContent.of(preview, now);

    // 숫자는 보여주고, "참고"라는 말과 파란색으로 확정과 구분한다.
    expect(content.tone, DepartureHeadlineTone.reference);
    expect(content.title, contains('에 나가면'));
    expect(content.title, contains('도착 예상'));
    expect(content.detail, contains('아직 확정 전 참고값이에요'));
  });

  test('시각이 없는 결과(자료 부족)는 여전히 확정하지 못했다고 말한다', () async {
    final data = ScheduleEvaluationV2.fromJson(await _raw('data'));
    final content = DepartureHeadlineContent.of(data, now);

    expect(content.tone, DepartureHeadlineTone.attention);
    expect(content.title, '아직 출발 시각을 확정하지 못했어요.');
  });

  test('서버가 stale 로 판정한 결과는 다시 확인하라고 크게 알린다', () async {
    final stale = ScheduleEvaluationV2.fromJson(await _raw('stale'));
    final content = DepartureHeadlineContent.of(stale, now);

    expect(content.tone, DepartureHeadlineTone.attention);
    expect(content.title, '이전에 계산한 결과예요.');
  });

  test('결과가 없으면 계산 중으로 안내한다', () {
    final content = DepartureHeadlineContent.of(null, now);
    expect(content.tone, DepartureHeadlineTone.waiting);
    expect(content.title, contains('계산하고 있어요'));
  });
}
