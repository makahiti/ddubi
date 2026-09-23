import 'package:ddubi/data/mock_repository.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:ddubi/domain/auth.dart';
import 'package:ddubi/services/app_api.dart';
import 'package:ddubi/services/recommendation_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

/// 취소 요청을 기록하고, 지정한 실패를 서버 대신 던진다.
class _CancelRepository extends MockDdubiRepository {
  _CancelRepository({this.failure});

  final ScheduleCommandFailure? failure;
  final cancelCalls = <String>[];

  @override
  Future<void> cancelSchedule(String id) async {
    cancelCalls.add(id);
    if (failure != null) throw failure!;
    await super.cancelSchedule(id);
  }
}

class _NoRecommendationApi extends RecommendationApi {
  _NoRecommendationApi() : super(Dio());

  @override
  Future<RecommendationOverview?> getLatestOverview(String scheduleId) async =>
      null;

  @override
  Future<List<SavedRouteSummary>> getSavedRoutes(String scheduleId) async =>
      const [];
}

Future<GoRouter> _pumpDetail(
  WidgetTester tester,
  _CancelRepository repository,
  Schedule schedule,
) async {
  tester.view.physicalSize = const Size(390, 844);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final router = GoRouter(
    initialLocation: '/schedules/${schedule.id}',
    routes: [
      GoRoute(
        path: '/schedules',
        builder: (_, _) => const Scaffold(body: Text('일정 목록 화면')),
      ),
      GoRoute(
        path: '/schedules/:id',
        builder: (_, _) => ScheduleDetailScreen(
          repository: repository,
          routeRepository: repository,
          recommendationApi: _NoRecommendationApi(),
          scheduleId: schedule.id,
          arrivalApi: ArrivalApi(Dio()),
          userApi: UserApi(Dio(), AuthSessionStore()),
          initialSchedule: schedule,
        ),
      ),
    ],
  );
  await tester.pumpWidget(MaterialApp.router(routerConfig: router));
  await tester.pumpAndSettle();
  return router;
}

Future<void> _cancelFromDetail(WidgetTester tester) async {
  final button = find.byKey(const Key('cancelScheduleButton'));
  await tester.scrollUntilVisible(button, 250);
  await tester.tap(button);
  await tester.pumpAndSettle();
  expect(find.text('일정을 취소할까요?'), findsOneWidget);
  await tester.tap(find.widgetWithText(FilledButton, '일정 취소'));
  await tester.pumpAndSettle();
}

Future<void> _dispose(WidgetTester tester) async {
  // 이동 예측 카드의 주기 확인 타이머를 정리한다.
  await tester.pumpWidget(const SizedBox.shrink());
}

void main() {
  testWidgets('정상 취소: 서버에 한 번 요청하고 목록으로 돌아가며 목록에서 사라진다', (tester) async {
    final repository = _CancelRepository();
    final schedule = (await repository.schedules()).first;
    final router = await _pumpDetail(tester, repository, schedule);

    await _cancelFromDetail(tester);

    expect(repository.cancelCalls, [schedule.id]);
    expect(
      router.routerDelegate.currentConfiguration.uri.toString(),
      '/schedules',
    );
    expect(find.text('일정 목록 화면'), findsOneWidget);
    final remaining = await repository.schedules();
    expect(remaining.map((s) => s.id), isNot(contains(schedule.id)));
    await _dispose(tester);
  });

  testWidgets('이미 취소된 일정을 다시 취소해도(서버 멱등 204) 오류 없이 목록으로 돌아간다', (tester) async {
    final repository = _CancelRepository();
    final schedule = (await repository.schedules()).first;
    await _pumpDetail(tester, repository, schedule);

    // 화면을 연 뒤 서버 쪽에서 먼저 취소된 경쟁 상황이다. 열기 전에 Mock 목록에서
    // 지워버리면 상세 화면 자체가 없어져 멱등 취소 동작을 시험할 수 없다.
    await repository.cancelSchedule(schedule.id);

    await _cancelFromDetail(tester);

    expect(repository.cancelCalls, [schedule.id, schedule.id]);
    expect(find.text('일정 목록 화면'), findsOneWidget);
    expect(find.byType(SnackBar), findsNothing);
    await _dispose(tester);
  });

  testWidgets('다른 사용자의 일정(403)은 거절 문구를 보여주고 화면에 남는다', (tester) async {
    final repository = _CancelRepository(
      failure: const ScheduleCommandFailure(
        kind: ScheduleCommandFailureKind.forbidden,
        message: '권한 없음',
      ),
    );
    final schedule = (await repository.schedules()).first;
    await _pumpDetail(tester, repository, schedule);

    await _cancelFromDetail(tester);

    expect(find.text('다른 사용자의 일정은 취소할 수 없어요.'), findsOneWidget);
    expect(find.text('일정 목록 화면'), findsNothing);
    expect(
      (await repository.schedules()).map((s) => s.id),
      contains(schedule.id),
    );
    await _dispose(tester);
  });

  testWidgets('네트워크 실패는 연결 문구를 보여주고 일정을 지우지 않는다', (tester) async {
    final repository = _CancelRepository(
      failure: const ScheduleCommandFailure(
        kind: ScheduleCommandFailureKind.network,
        message: '연결 실패',
      ),
    );
    final schedule = (await repository.schedules()).first;
    await _pumpDetail(tester, repository, schedule);

    await _cancelFromDetail(tester);

    expect(
      find.text('연결이 불안정해 일정을 취소하지 못했어요. 잠시 후 다시 시도해주세요.'),
      findsOneWidget,
    );
    expect(find.text('일정 목록 화면'), findsNothing);
    expect(
      (await repository.schedules()).map((s) => s.id),
      contains(schedule.id),
    );
    await _dispose(tester);
  });

  testWidgets('완료된 일정(409)과 없는 일정(404)은 서로 다른 문구로 안내한다', (tester) async {
    expect(
      ScheduleDetailScreen.cancelFailureMessage(
        const ScheduleCommandFailure(
          kind: ScheduleCommandFailureKind.conflict,
          message: '',
        ),
      ),
      '이미 완료된 일정은 취소할 수 없어요.',
    );
    expect(
      ScheduleDetailScreen.cancelFailureMessage(
        const ScheduleCommandFailure(
          kind: ScheduleCommandFailureKind.notFound,
          message: '',
        ),
      ),
      '이미 삭제된 일정이에요. 목록을 다시 불러왔어요.',
    );
    // 401 은 남의 일정(403) 문구가 아니라 로그인 안내여야 한다.
    expect(
      ScheduleDetailScreen.cancelFailureMessage(
        const ScheduleCommandFailure(
          kind: ScheduleCommandFailureKind.unauthenticated,
          message: '',
        ),
      ),
      '로그인이 만료됐어요. 다시 로그인한 뒤 시도해 주세요.',
    );

    final repository = _CancelRepository(
      failure: const ScheduleCommandFailure(
        kind: ScheduleCommandFailureKind.notFound,
        message: '없음',
      ),
    );
    final schedule = (await repository.schedules()).first;
    await _pumpDetail(tester, repository, schedule);

    await _cancelFromDetail(tester);

    // 없는 일정은 더 시도할 수 없으니 목록으로 돌아간다.
    expect(find.text('일정 목록 화면'), findsOneWidget);
    await _dispose(tester);
  });
}
