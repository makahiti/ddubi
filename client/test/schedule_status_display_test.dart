import 'package:ddubi/domain/models.dart';
import 'package:ddubi/presentation/widgets.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 일정의 완료·취소 표시.
///
/// 서버는 도착을 기록하면 상태를 DONE 으로 바꾸는데, 앱이 그 값을 어느 화면에서도
/// 쓰지 않았다. 그래서 도착 완료를 눌러도 목록에 "예정" 그대로 남아 있었다
/// (2026-09-22 제보). 취소한 일정도 날짜를 옮기면 다시 나타났다.
void main() {
  group('상태 뱃지', () {
    test('진행 예정은 표시하지 않는다 — 대부분이 그 상태다', () {
      expect(ScheduleStatusBadge.shows(ScheduleStatus.upcoming), isFalse);
      expect(ScheduleStatusBadge.shows(ScheduleStatus.unknown), isFalse);
    });

    test('완료와 취소만 표시한다', () {
      expect(ScheduleStatusBadge.shows(ScheduleStatus.done), isTrue);
      expect(ScheduleStatusBadge.shows(ScheduleStatus.canceled), isTrue);
    });

    test('끝난 일정은 흐리게 그린다', () {
      expect(ScheduleStatusBadge.opacityFor(ScheduleStatus.upcoming), 1);
      expect(ScheduleStatusBadge.opacityFor(ScheduleStatus.done), lessThan(1));
    });

    testWidgets('완료는 "완료" 로 보인다', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: ScheduleStatusBadge(ScheduleStatus.done)),
        ),
      );
      expect(find.text('완료'), findsOneWidget);
      expect(find.byKey(const Key('scheduleDoneBadge')), findsOneWidget);
    });

    testWidgets('취소는 "취소" 로 보인다', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: ScheduleStatusBadge(ScheduleStatus.canceled)),
        ),
      );
      expect(find.text('취소'), findsOneWidget);
      expect(find.byKey(const Key('scheduleCanceledBadge')), findsOneWidget);
    });

    testWidgets('진행 예정은 자리를 차지하지 않는다', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: ScheduleStatusBadge(ScheduleStatus.upcoming)),
        ),
      );
      expect(find.text('완료'), findsNothing);
      expect(find.text('취소'), findsNothing);
    });
  });

  group('서버 상태 읽기', () {
    test('DONE 과 CANCELED 를 알아본다', () {
      expect(ScheduleStatus.fromApi('DONE'), ScheduleStatus.done);
      expect(ScheduleStatus.fromApi('CANCELED'), ScheduleStatus.canceled);
      expect(ScheduleStatus.fromApi('CANCELLED'), ScheduleStatus.canceled);
    });

    test('상태가 없으면 진행 예정으로 본다', () {
      expect(ScheduleStatus.fromApi(null), ScheduleStatus.upcoming);
      expect(ScheduleStatus.fromApi(''), ScheduleStatus.upcoming);
    });

    test('모르는 상태를 진행 예정으로 바꿔치지 않는다', () {
      expect(ScheduleStatus.fromApi('SOMETHING_NEW'), ScheduleStatus.unknown);
    });
  });
}
