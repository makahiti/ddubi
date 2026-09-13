import 'package:ddubi/data/mock_repository.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:ddubi/providers/app_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _EmptySchedules extends MockDdubiRepository implements ScheduleEvaluationRepository {
  @override Future<List<Schedule>> schedules() async => [];
  @override Future<Map<String, dynamic>> latestEvaluation(String id) async => {'state': 'PENDING'};
  @override Future<void> refreshEvaluation(String id) async {}
  @override Future<void> acceptEvaluation(String id, String evaluationId) async {}
  @override Future<void> confirmDeparture(String id) async {}
}

void main() {
  testWidgets('실제 일정이 없으면 가상 지연이나 대체 노선을 표시하지 않는다', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [scheduleRepositoryProvider.overrideWithValue(_EmptySchedules())],
      child: MaterialApp(home: DelayAlertScreen(repository: MockDdubiRepository())),
    ));
    await tester.pumpAndSettle();
    expect(find.text('등록된 일정이 없습니다.'), findsOneWidget);
    expect(find.textContaining('8분'), findsNothing);
  });
}
