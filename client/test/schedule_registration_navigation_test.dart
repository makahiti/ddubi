import 'package:ddubi/data/mock_repository.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

/// 저장 API가 발급한 ID로 상세에 진입해야 새 일정의 계산 대기 스켈레톤을 볼 수 있다.
/// 폼의 임시 ID를 쓰거나 목록에 머물면 실제 계산 결과를 기다리는 화면이 나타나지 않는다.
void main() {
  testWidgets('일정 저장 직후 서버 ID의 상세 화면으로 이동한다', (tester) async {
    await tester.binding.setSurfaceSize(const Size(1080, 1920));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final repository = MockDdubiRepository();
    final base = MockDdubiRepository.scheduleData.first;
    final created = Schedule(
      id: 'server-schedule-42',
      title: base.title,
      origin: base.origin,
      destination: base.destination,
      arriveBy: base.arriveBy,
    );
    final router = GoRouter(
      initialLocation: '/schedules',
      routes: [
        GoRoute(
          path: '/schedules',
          builder: (_, _) => ScheduleListScreen(repository: repository),
        ),
        GoRoute(
          path: '/schedules/new',
          builder: (context, _) => Scaffold(
            body: FilledButton(
              onPressed: () => context.pop(created),
              child: const Text('저장 완료'),
            ),
          ),
        ),
        GoRoute(
          path: '/schedules/:id',
          builder: (_, state) =>
              Scaffold(body: Text('상세 ${state.pathParameters['id']}')),
        ),
      ],
    );

    await tester.pumpWidget(MaterialApp.router(routerConfig: router));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(FloatingActionButton));
    await tester.pumpAndSettle();
    await tester.tap(find.text('저장 완료'));
    await tester.pumpAndSettle();

    expect(find.text('상세 ${created.id}'), findsOneWidget);
  });
}
