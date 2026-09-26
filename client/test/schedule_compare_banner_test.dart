import 'dart:convert';
import 'dart:io';

import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/screens/schedule_detail.dart';
import 'package:flutter_test/flutter_test.dart';

/// 일정 상세 맨 위 "이 일정에 맞춰" 카드의 한 줄.
///
/// 2026-09-26 제보: 도착 시간이 지났는데 끝남 처리가 안 된 일정에도 "지금 선택한 경로와 새로 계산한 결과를
/// 비교해 보세요" 가 그대로 떴다. 상황마다 할 일을 말한다.
void main() {
  final now = DateTime(2026, 9, 26, 14, 30);
  late Map<String, dynamic> raw;

  setUpAll(() async {
    raw = jsonDecode(
      await File('test/fixtures/schedule_evaluation_keep.json').readAsString(),
    ) as Map<String, dynamic>;
  });

  ScheduleEvaluationV2 evaluation(Map<String, dynamic> overrides) =>
      ScheduleEvaluationV2.fromJson({...raw, ...overrides});

  (String, String?) banner({
    bool finished = false,
    bool hasRoute = true,
    DateTime? arriveBy,
    DateTime? departedAt,
    ScheduleEvaluationV2? value,
  }) => ScheduleCompareBanner.of(
    finished: finished,
    hasRoute: hasRoute,
    arriveBy: arriveBy ?? now.add(const Duration(hours: 2)),
    departedAt: departedAt,
    evaluation: value,
    now: now,
  );

  test('도착 시간이 지난 일정은 비교하라고 하지 않고 도착 완료를 권한다', () {
    final (title, note) = banner(
      arriveBy: now.subtract(const Duration(minutes: 10)),
    );

    expect(title, '도착 시간이 지난 일정이에요.');
    expect(note, contains('도착 완료'));
    expect(title, isNot(contains('비교')));
  });

  test('출발했으면 이동 중이라고 말한다', () {
    expect(banner(departedAt: now).$1, '이동 중이에요.');
  });

  test('다른 경로를 제안하면 아래 경로 카드에서 바꾸라고 한다', () {
    final (title, note) = banner(
      value: evaluation({
        'selectedRouteId': 'route-1',
        'proposedRouteId': 'route-2',
      }),
    );

    expect(title, '새로 제안된 경로가 있어요.');
    expect(note, contains('경로 카드'));
  });

  test('제안이 없으면 지금 경로 그대로 가면 된다고 한다', () {
    final (title, _) = banner(
      value: evaluation({
        'selectedRouteId': 'route-1',
        'proposedRouteId': 'route-1',
        'plannedDepartAt': '2026-09-26T15:00:00+09:00',
        'proposedDepartAt': '2026-09-26T15:00:00+09:00',
      }),
    );

    expect(title, '지금 경로 그대로 가면 돼요.');
  });

  test('끝난 일정은 기록을 볼 수 있다고만 한다', () {
    expect(banner(finished: true).$1, contains('실제 기록'));
  });
}
