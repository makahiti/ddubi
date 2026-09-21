import 'package:ddubi/data/mock_repository.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:ddubi/providers/app_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _EmptySchedules extends MockDdubiRepository
    implements ScheduleEvaluationRepository {
  @override
  Future<List<Schedule>> schedules() async => [];
  @override
  Future<ScheduleEvaluationV2> latestEvaluation(String id) async =>
      ScheduleEvaluationV2.fromLegacyJson({'state': 'PENDING'});
  @override
  Future<void> refreshEvaluation(String id) async {}
  @override
  Future<void> acceptEvaluation(String id, String evaluationId) async {}
  @override
  Future<void> confirmDeparture(String id) async {}
}

class _PastSchedule extends _EmptySchedules {
  @override
  Future<List<Schedule>> schedules() async => [
    Schedule(
      id: 'past-1',
      title: '지난 약속',
      origin: const PlaceLocation(name: '출발', lat: 37.5, lng: 127),
      destination: const PlaceLocation(name: '도착', lat: 37.6, lng: 127.1),
      arriveBy: DateTime.now().subtract(const Duration(days: 1)),
    ),
  ];
}

void main() {
  testWidgets('실제 일정이 없으면 가상 지연이나 대체 노선을 표시하지 않는다', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          scheduleRepositoryProvider.overrideWithValue(_EmptySchedules()),
        ],
        child: MaterialApp(
          home: DelayAlertScreen(repository: MockDdubiRepository()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    // 지난 일정만 남았을 때도 같은 문구가 맞도록 "앞으로 예정된" 으로 말한다.
    expect(find.text('앞으로 예정된 일정이 없습니다.'), findsOneWidget);
    expect(find.textContaining('8분'), findsNothing);
  });

  testWidgets('이미 지난 일정은 평가 카드를 만들지 않는다', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          scheduleRepositoryProvider.overrideWithValue(_PastSchedule()),
        ],
        child: MaterialApp(
          home: DelayAlertScreen(repository: MockDdubiRepository()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('앞으로 예정된 일정이 없습니다.'), findsOneWidget);
    expect(find.text('지난 약속'), findsNothing);
  });
}
