import 'package:ddubi/data/mock_repository.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// 내일 일정을 만들면 출발 날짜도 내일이어야 한다.
///
/// 예전에는 출발 날짜 기본값이 늘 "오늘" 이라, 도착만 내일로 바꾸면 출발이 오늘로
/// 저장됐다. 서버는 그 값으로 계산하므로 화면에는 "지금 출발" 처럼 보였다.
void main() {
  testWidgets('새 일정의 출발 날짜는 도착 날짜를 따라간다', (tester) async {
    final tomorrow = DateTime.now().add(const Duration(days: 1));
    await tester.pumpWidget(
      MaterialApp(
        home: ScheduleFormScreen(
          repository: MockDdubiRepository(),
          placeSearchService: const MockPlaceSearchService(),
          initialDate: tomorrow,
        ),
      ),
    );
    await tester.pumpAndSettle();

    final state = tester.state(find.byType(ScheduleFormScreen));
    final departureDate = (state as dynamic).departureDate as DateTime;
    final arrivalDate = (state as dynamic).arrivalDate as DateTime;

    expect(departureDate.year, arrivalDate.year);
    expect(departureDate.month, arrivalDate.month);
    expect(departureDate.day, arrivalDate.day);
    expect(departureDate.day, tomorrow.day);
  });
}
