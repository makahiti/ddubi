import 'dart:convert';
import 'dart:io';

import 'package:ddubi/domain/auth.dart';
import 'package:ddubi/domain/models.dart';
import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/screens.dart';
import 'package:ddubi/services/app_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Future<ScheduleEvaluationV2> _fixture(String name) async {
  final raw = await File('test/fixtures/schedule_evaluation_$name.json')
      .readAsString();
  return ScheduleEvaluationV2.fromJson(jsonDecode(raw) as Map<String, dynamic>);
}

class _FakeNotificationApi extends NotificationApi {
  _FakeNotificationApi(this.item) : super(Dio(), AuthSessionStore());

  final AppNotification item;
  var readCalls = 0;

  @override
  Future<AppNotification> getNotification(String id) async => item;

  @override
  Future<void> markRead(String id) async {
    readCalls++;
  }
}

class _NotificationEvaluationRepository
    implements ScheduleEvaluationRepository {
  _NotificationEvaluationRepository({this.evaluation, this.failure});

  final ScheduleEvaluationV2? evaluation;
  final Object? failure;
  var latestCalls = 0;

  @override
  Future<ScheduleEvaluationV2> latestEvaluation(String scheduleId) async {
    latestCalls++;
    if (failure != null) throw failure!;
    return evaluation!;
  }

  @override
  Future<void> acceptEvaluation(String id, String evaluationId) async {}

  @override
  Future<void> confirmDeparture(String id) async {}

  @override
  Future<void> refreshEvaluation(String id) async {}
}

AppNotification _notification(String? evaluationId) => AppNotification(
  id: 'notification-1',
  scheduleId: 'schedule-1',
  evaluationId: evaluationId,
  decision: 'ADVANCE_DEPARTURE',
  type: 'DEPARTURE_CHANGE',
  title: '출발시간이 변경됐어요',
  body: '최신 결과를 확인해 주세요.',
  status: 'SENT',
  createdAt: DateTime(2026, 9, 18, 8),
);

Future<void> _pumpDetail(
  WidgetTester tester, {
  required _FakeNotificationApi api,
  required ScheduleEvaluationRepository repository,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      home: NotificationDetailScreen(
        api: api,
        notificationId: api.item.id,
        evaluationRepository: repository,
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  late ScheduleEvaluationV2 keep;
  late ScheduleEvaluationV2 advance;
  late ScheduleEvaluationV2 stale;

  setUpAll(() async {
    keep = await _fixture('keep');
    advance = await _fixture('advance');
    stale = await _fixture('stale');
  });

  testWidgets('알림 evaluationId가 최신 평가와 같으면 현재 알림으로 표시한다', (tester) async {
    final api = _FakeNotificationApi(_notification(keep.evaluationId));
    final repository = _NotificationEvaluationRepository(evaluation: keep);

    await _pumpDetail(tester, api: api, repository: repository);

    expect(find.text('지금 최신 계산과 같은 내용이에요.'), findsOneWidget);
    expect(repository.latestCalls, 1);
    expect(api.readCalls, 1);
  });

  testWidgets('알림 evaluationId와 최신 평가가 다르면 과거 제안으로 안내한다', (tester) async {
    final api = _FakeNotificationApi(_notification('eval-old'));
    final repository = _NotificationEvaluationRepository(evaluation: advance);

    await _pumpDetail(tester, api: api, repository: repository);

    expect(find.textContaining('이 알림 뒤로 추천이 바뀌었을 수 있어요.'), findsOneWidget);
    expect(find.text('이 제안 적용'), findsNothing);
  });

  testWidgets('같은 evaluationId라도 stale 또는 만료 결과는 최신으로 표시하지 않는다', (
    tester,
  ) async {
    final api = _FakeNotificationApi(_notification(stale.evaluationId));
    final repository = _NotificationEvaluationRepository(evaluation: stale);

    await _pumpDetail(tester, api: api, repository: repository);

    expect(find.textContaining('이 알림의 계산은 유효 기간이 지났어요.'), findsOneWidget);
    expect(find.text('지금 최신 계산과 같은 내용이에요.'), findsNothing);
  });

  testWidgets('최신 평가 접근 실패를 과거 알림의 수락 가능 상태로 오인하지 않는다', (tester) async {
    final api = _FakeNotificationApi(_notification(keep.evaluationId));
    final repository = _NotificationEvaluationRepository(
      failure: const ScheduleEvaluationFailure(
        kind: EvaluationFailureKind.authentication,
        message: 'forbidden',
      ),
    );

    await _pumpDetail(tester, api: api, repository: repository);

    expect(find.textContaining('이 알림 뒤로 추천이 바뀌었을 수 있어요.'), findsOneWidget);
    expect(find.text('이 제안 적용'), findsNothing);
  });
}
