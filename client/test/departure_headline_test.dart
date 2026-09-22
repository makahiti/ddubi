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

  // ------------------------------------------------------------------
  // 출발한 뒤
  // ------------------------------------------------------------------

  /// 서버는 멈춘 일정에도 마지막 계산 결과를 실어 준다(2026-09-22). 그 값으로 출발·도착·여유를
  /// 그대로 보여준다. 예전의 "출발 안내가 끝났어요" 는 결과가 없을 때만 남는다.
  test('출발한 뒤에는 실제 출발 시각과 도착 예정·여유를 그대로 보여준다', () async {
    final raw = await _raw('keep');
    raw['state'] = 'STOPPED';
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

  test('출발 시각이 지났는데 출발을 알리지 않았으면 지났다고 크게 말한다', () async {
    final raw = await _raw('keep');
    raw['departureMissed'] = true;
    raw['validUntil'] = now
        .add(const Duration(minutes: 1))
        .toUtc()
        .toIso8601String();
    final missed = ScheduleEvaluationV2.fromJson(raw);

    final content = DepartureHeadlineContent.of(missed, now);

    expect(content.tone, DepartureHeadlineTone.attention);
    expect(content.title, contains('지났어요'));
    expect(content.label, '출발 시각이 지났어요');
    expect(content.departAt, isNotNull, reason: '지난 시각도 큰 숫자로는 보여준다');
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
