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

  test('출발 기록이 있으면 새 평가를 기다리는 중에도 실제 출발 시각을 보여준다', () async {
    final departedAt = DateTime(2026, 9, 23, 8, 11);
    final previous = ScheduleEvaluationV2.fromJson(await _raw('keep'));

    for (final evaluation in <ScheduleEvaluationV2?>[null, previous]) {
      final content = DepartureHeadlineContent.of(
        evaluation,
        now,
        departedAt: departedAt,
      );
      expect(content.departAt, departedAt);
      expect(content.arriveAt, isNull, reason: '출발 전 제안 ETA는 실제 출발 기준이 아니다');
      expect(content.title, contains('${formatClock(departedAt)}에 출발했어요'));
      expect(content.title, isNot(contains('추천 출발')));
    }
  });

  // ------------------------------------------------------------------
  // 출발한 뒤
  // ------------------------------------------------------------------

  /// 마지막 출발 전 제안과 실제 출발 뒤 재계산은 다르다. 서버가 새로 계산한 ETA만 표시한다.
  test('출발한 뒤에는 실제 출발 기준으로 다시 계산된 ETA를 보여준다', () async {
    final raw = await _raw('keep');
    raw['state'] = 'STOPPED';
    raw['estimatedArrivalAt'] = '2026-09-18T01:08:00';
    raw['actualDepartAt'] = '2026-09-17T23:52:00';
    final stopped = ScheduleEvaluationV2.fromJson(raw);
    final departedAt = DateTime(2026, 9, 17, 23, 52);

    final content = DepartureHeadlineContent.of(
      stopped,
      now,
      departedAt: departedAt,
    );

    expect(content.tone, DepartureHeadlineTone.departed);
    expect(content.label, '출발 시각');
    expect(content.clockSuffix, '에 출발했어요');
    expect(content.departAt, departedAt, reason: '서버가 기록한 실제 출발이 제안 시각보다 먼저다');
    expect(content.arriveAt, stopped.estimatedArrivalAt);
    expect(content.spareMinutes, isNotNull);
    expect(content.title, contains('에 출발해'));
    expect(content.title, contains('도착 예정'));
    expect(content.title, isNot(contains('끝났어요')));
  });

  test('출발 후 재계산이 실패하면 출발 전 ETA를 임의로 재사용하지 않는다', () async {
    final raw = await _raw('keep');
    raw['state'] = 'STOPPED';
    raw['actualDepartAt'] = '2026-09-17T23:52:00';
    raw['estimatedArrivalAt'] = null;
    raw['departureEstimate'] = {'status': 'UNAVAILABLE'};
    final stopped = ScheduleEvaluationV2.fromJson(raw);

    final content = DepartureHeadlineContent.of(stopped, now);

    expect(content.title, contains('도착 예정 계산 불가'));
    expect(content.arriveAt, isNull);
    expect(content.departAt, isNotNull);
  });

  test('출발했는데 마지막 결과가 없는 예전 일정은 옛 문구를 그대로 쓴다', () {
    final stopped = ScheduleEvaluationV2.fromJson({
      'schemaVersion': 'schedule-evaluation-v2',
      'state': 'STOPPED',
    });

    final content = DepartureHeadlineContent.of(stopped, now);

    expect(content.tone, DepartureHeadlineTone.waiting);
    expect(content.title, '이 일정의 출발 안내가 끝났어요.');
    expect(content.departAt, isNull);
  });

  // ------------------------------------------------------------------
  // 못 가는 경우는 못 간다고 말한다
  // ------------------------------------------------------------------

  /// 여유가 음수인데 초록 카드에 "늦음 3분" 이 작게 붙어 있으면 못 간다는 사실이 읽히지 않는다
  /// (2026-09-22 제보). 제목부터 늦는다고 말하고 카드 전체를 붉게 한다.
  test('이대로면 늦는 결과는 제목부터 늦는다고 말하고 붉게 그린다', () async {
    final raw = await _raw('keep');
    raw['latenessSeconds'] = 300;
    raw['validUntil'] = now
        .add(const Duration(minutes: 1))
        .toUtc()
        .toIso8601String();
    final late = ScheduleEvaluationV2.fromJson(raw);

    final content = DepartureHeadlineContent.of(late, now);

    expect(content.tone, DepartureHeadlineTone.late);
    expect(content.title, contains('늦어요'));
    expect(content.title, contains('에 나가도'));
    expect(content.spareMinutes, -5);
    expect(DepartureHeadline.paletteOf(content).name, 'late');
  });

  // ------------------------------------------------------------------
  // 여유에 따라 색이 달라진다
  // ------------------------------------------------------------------

  /// 안전할수록 지금의 초록, 빠듯할수록 붉어진다. 판단은 여유 분으로만 한다.
  test('확정 카드 색은 여유에 따라 초록 → 노랑 → 주황으로 바뀐다', () {
    DepartureHeadlineContent ready(int? spare) => DepartureHeadlineContent(
      title: '',
      tone: DepartureHeadlineTone.ready,
      spareMinutes: spare,
    );

    expect(DepartureHeadline.paletteOf(ready(25)).name, 'safe');
    expect(DepartureHeadline.paletteOf(ready(10)).name, 'safe');
    expect(DepartureHeadline.paletteOf(ready(9)).name, 'hurry');
    expect(DepartureHeadline.paletteOf(ready(5)).name, 'hurry');
    expect(DepartureHeadline.paletteOf(ready(4)).name, 'tight');
    expect(DepartureHeadline.paletteOf(ready(0)).name, 'tight');
    // 여유를 모르면 겁주지 않는다.
    expect(DepartureHeadline.paletteOf(ready(null)).name, 'safe');
  });

  // ------------------------------------------------------------------
  // 남은 시간에 따라 말이 달라진다
  // ------------------------------------------------------------------

  /// 출발이 몇 시간 뒤여도 "08:10에 나가세요" 라고만 쓰면 당장 나가라는 말처럼 읽힌다(2026-09-22 제보).
  test('한 시간 넘게 남았으면 여유 있다고 말하고 "나가면 돼요" 로 쓴다', () {
    final base = DateTime(2026, 9, 22, 8, 0);
    final timing = DepartureHeadlineContent.timing(
      base,
      base.add(const Duration(hours: 2, minutes: 15)),
    );

    expect(timing.label, contains('여유'));
    expect(timing.clockSuffix, '에 나가면 돼요');
    expect(timing.decorate('제목'), '제목 · 아직 2시간 15분 남았어요');
  });

  test('10분에서 1시간 사이면 지금까지 쓰던 말 그대로다', () {
    final base = DateTime(2026, 9, 22, 8, 0);
    final timing = DepartureHeadlineContent.timing(
      base,
      base.add(const Duration(minutes: 30)),
    );

    expect(timing.label, '추천 출발 시각');
    expect(timing.clockSuffix, '에 나가세요');
    expect(timing.decorate('제목'), '제목');
  });

  test('10분 안이면 곧 출발이라고 말한다', () {
    final base = DateTime(2026, 9, 22, 8, 0);
    final soon = DepartureHeadlineContent.timing(
      base,
      base.add(const Duration(minutes: 4)),
    );
    final now = DepartureHeadlineContent.timing(
      base,
      base.add(const Duration(seconds: 30)),
    );

    expect(soon.label, '곧 출발이에요');
    expect(soon.decorate('제목'), '제목 · 4분 뒤 나가야 해요');
    expect(now.decorate('제목'), '제목 · 지금 나가야 해요');
  });

  test('지났으면 지났다고 말한다', () {
    final base = DateTime(2026, 9, 22, 8, 0);
    final passed = DepartureHeadlineContent.timing(
      base,
      base.subtract(const Duration(minutes: 7)),
    );

    expect(passed.label, '출발 시각이 지났어요');
    expect(passed.clockSuffix, '이 지났어요');
    expect(passed.decorate('제목'), '제목 · 7분 지났어요');
  });

  test('확정 결론에도 남은 시간 말머리가 붙는다', () async {
    final raw = await _raw('keep');
    final depart = now.add(const Duration(hours: 3));
    raw['proposedDepartAt'] = depart.toUtc().toIso8601String();
    raw['estimatedArrivalAt'] = depart
        .add(const Duration(minutes: 40))
        .toUtc()
        .toIso8601String();
    raw['validUntil'] = now
        .add(const Duration(minutes: 1))
        .toUtc()
        .toIso8601String();
    final far = ScheduleEvaluationV2.fromJson(raw);
    expect(far.canAcceptAt(now), isTrue);

    final content = DepartureHeadlineContent.of(far, now);

    expect(content.tone, DepartureHeadlineTone.ready);
    expect(content.label, contains('여유'));
    expect(content.clockSuffix, '에 나가면 돼요');
    expect(content.title, contains('남았어요'));
  });

  // 2026-09-25 제보: 시간 안에 도착할 수 있는데도 노란 "출발 시각이 지났어요" 만 떠서 급한 일처럼 읽혔다.
  // 계획 출발이 지나면 서버는 "지금 출발" 로 다시 계산한다. 지금 나가면 되는지, 늦는지, 왜인지를 말한다.
  Future<ScheduleEvaluationV2> missed({
    List<Map<String, dynamic>> causes = const [],
    bool late = false,
  }) async {
    final raw = await _raw('keep');
    raw['departureMissed'] = true;
    raw['causes'] = causes;
    if (late) raw['latenessSeconds'] = 300;
    raw['validUntil'] = now
        .add(const Duration(minutes: 1))
        .toUtc()
        .toIso8601String();
    return ScheduleEvaluationV2.fromJson(raw);
  }

  const demoRoad = {
    'eventId': 'DEMO-TOPIS-65-1',
    'source': 'DEMO_TOPIS',
    'segmentIds': ['BUS:100:1:2'],
    'effect': 'BLOCKED',
    'confirmed': true,
  };

  test('출발 시각이 지났어도 시간 안에 닿으면 "지금 나가면" 으로 말한다', () async {
    final content = DepartureHeadlineContent.of(await missed(), now);

    expect(content.tone, DepartureHeadlineTone.ready);
    expect(content.title, startsWith('지금 나가면'));
    expect(content.label, '지금 출발하세요');
    expect(content.title + content.label, isNot(contains('지났어요')));
    expect(content.departAt, isNotNull, reason: '큰 숫자는 지금 시각이다');
  });

  test('원인이 있으면 "원인이 예상돼 지금 바로 나가는 게 좋아요" 로 말하고 시연이면 [시연] 만 붙인다', () async {
    final content = DepartureHeadlineContent.of(
      await missed(causes: [demoRoad]),
      now,
    );

    expect(content.title, '[시연] 도로 통제가 예상돼 지금 바로 나가는 게 좋아요');
    expect(content.label, '[시연] 도로 통제가 예상돼요');
    expect(content.detail, startsWith('지금 나가면'));
    expect('${content.title}${content.detail}', isNot(contains('가짜')));
  });

  test('늦으면 "원인으로 희망 도착 시간에 도착하기 어려울 것 같아요" 로 말한다', () async {
    final real = {...demoRoad, 'source': 'seoul.traffic_incident'};
    final content = DepartureHeadlineContent.of(
      await missed(causes: [real], late: true),
      now,
    );

    expect(content.tone, DepartureHeadlineTone.late);
    expect(content.title, '도로 통제로 희망 도착 시간에 도착하기 어려울 것 같아요');
    expect(content.detail, contains('5분 늦어요'));
  });

  test('추정 원인(영향 가능성)으로는 "예상돼요" 라고 단정하지 않는다', () async {
    final potential = {
      ...demoRoad,
      'effect': 'POTENTIAL_IMPACT',
      'confirmed': false,
    };
    final content = DepartureHeadlineContent.of(
      await missed(causes: [potential]),
      now,
    );

    expect(content.title, startsWith('지금 나가면'));
  });

  // 2026-09-26 제보: 여유 34분인데 "도로 통제가 예상돼 서둘러 나가는 게 좋아요" 가 떴다.
  // 다른 경로를 제안하면 피해 간다고, 여유가 넉넉하면 제시간에 닿는다고 말한다.
  Future<ScheduleEvaluationV2> withCause({
    required String proposedRouteId,
    required int spareMinutes,
  }) async {
    final raw = await _raw('keep');
    final arrival = DateTime.parse(raw['estimatedArrivalAt'] as String);
    raw['causes'] = [demoRoad];
    raw['selectedRouteId'] = 'route-1';
    raw['proposedRouteId'] = proposedRouteId;
    raw.remove('latenessSeconds');
    raw['arriveBy'] = arrival
        .add(Duration(minutes: spareMinutes))
        .toUtc()
        .toIso8601String();
    return ScheduleEvaluationV2.fromJson(raw);
  }

  test('원인 때문에 다른 경로를 제안하면 "피해 다른 경로로 안내해요" 라고 말한다', () async {
    final content = DepartureHeadlineContent.of(
      await withCause(proposedRouteId: 'route-2', spareMinutes: 34),
      now,
    );

    expect(content.detail, startsWith('도로 통제를 피해 다른 경로로 안내해요.'));
    expect(content.label, startsWith('[시연] '));
    expect(content.detail, isNot(contains('서둘러')));
  });

  test('같은 경로에 여유가 넉넉하면 서두르라고 하지 않는다', () async {
    final roomy = DepartureHeadlineContent.of(
      await withCause(proposedRouteId: 'route-1', spareMinutes: 34),
      now,
    );
    final tight = DepartureHeadlineContent.of(
      await withCause(proposedRouteId: 'route-1', spareMinutes: 5),
      now,
    );

    expect(roomy.detail, startsWith('도로 통제가 예상되지만 제시간에 도착할 수 있어요.'));
    expect(tight.detail, startsWith('도로 통제가 예상돼 서둘러 나가는 게 좋아요.'));
  });

  test('원인 이름의 조사를 받침에 맞춘다', () {
    expect(const HeadlineCause('도로 통제').subject, '도로 통제가');
    expect(const HeadlineCause('교통 지연').subject, '교통 지연이');
    expect(const HeadlineCause('도로 통제').instrument, '도로 통제로');
    expect(const HeadlineCause('교통 지연').instrument, '교통 지연으로');
    expect(const HeadlineCause('도로 통제').object, '도로 통제를');
    expect(const HeadlineCause('교통 지연').object, '교통 지연을');
  });

  test('참고·대기·주의 카드는 여유와 관계없이 성격별 색을 쓴다', () {
    for (final tone in [
      DepartureHeadlineTone.reference,
      DepartureHeadlineTone.waiting,
      DepartureHeadlineTone.attention,
    ]) {
      final content = DepartureHeadlineContent(
        title: '',
        tone: tone,
        spareMinutes: 1,
      );
      expect(DepartureHeadline.paletteOf(content).name, tone.name);
    }
  });
}
