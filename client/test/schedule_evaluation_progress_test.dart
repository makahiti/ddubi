import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/schedule_evaluation_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Future<ScheduleEvaluationV2> _fixture(String name) async {
  final raw = await File('test/fixtures/schedule_evaluation_$name.json')
      .readAsString();
  return ScheduleEvaluationV2.fromJson(jsonDecode(raw) as Map<String, dynamic>);
}

/// 호출 순서대로 결과·실패·미완료(Completer)를 돌려주는 저장소.
class _SequenceRepository implements ScheduleEvaluationRepository {
  _SequenceRepository(
    this.responses, {
    this.refreshCompleter,
    this.refreshFailure,
  });

  final List<Object> responses;
  final Completer<void>? refreshCompleter;

  /// 다시 계산 요청이 이 실패로 끝나게 한다. 409 계산 중 안내 시험에 쓴다.
  final ScheduleEvaluationFailure? refreshFailure;
  var latestCalls = 0;
  var refreshCalls = 0;

  @override
  Future<ScheduleEvaluationV2> latestEvaluation(String scheduleId) {
    final item = responses[latestCalls.clamp(0, responses.length - 1)];
    latestCalls++;
    if (item is ScheduleEvaluationFailure) return Future.error(item);
    if (item is Completer<ScheduleEvaluationV2>) return item.future;
    return Future.value(item as ScheduleEvaluationV2);
  }

  @override
  Future<void> acceptEvaluation(String id, String evaluationId) async {}

  @override
  Future<void> confirmDeparture(String id) async {}

  @override
  Future<void> refreshEvaluation(String id) {
    refreshCalls++;
    if (refreshFailure != null) return Future.error(refreshFailure!);
    return refreshCompleter?.future ?? Future.value();
  }
}

Widget _card(ScheduleEvaluationRepository repository) => MaterialApp(
  home: Scaffold(
    body: ScheduleEvaluationCard(repository: repository, scheduleId: 's-1'),
  ),
);

bool _refreshEnabled(WidgetTester tester) =>
    tester
        .widget<OutlinedButton>(find.widgetWithText(OutlinedButton, '다시 계산'))
        .onPressed !=
    null;

void main() {
  late ScheduleEvaluationV2 pending;
  late ScheduleEvaluationV2 keep;

  setUpAll(() async {
    pending = await _fixture('pending');
    keep = await _fixture('keep');
  });

  testWidgets('첫 계산 중에는 30초 상한 안내를 보여주고 3초 뒤 다시 확인해 결과가 오면 즉시 바꾼다', (
    tester,
  ) async {
    final repository = _SequenceRepository([pending, keep]);
    await tester.pumpWidget(_card(repository));
    await tester.pump();

    expect(repository.latestCalls, 1);
    expect(
      find.text(ScheduleEvaluationCard.initialComputingMessage),
      findsOneWidget,
    );
    expect(find.text('평가가 완료됐습니다.'), findsNothing);

    await tester.pump(ScheduleEvaluationCard.computingPollInterval);
    await tester.pump();

    expect(repository.latestCalls, 2);
    expect(
      find.text(ScheduleEvaluationCard.initialComputingMessage),
      findsNothing,
    );
    expect(find.text('평가가 완료됐습니다.'), findsOneWidget);

    // 결과가 있으면 보통 간격(30초)으로 돌아간다. 3초 뒤에는 추가 호출이 없다.
    await tester.pump(ScheduleEvaluationCard.computingPollInterval);
    expect(repository.latestCalls, 2);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('결과가 아직 없어(404) 실패해도 계산 중 안내를 유지하고 오류로 보이지 않는다', (tester) async {
    final repository = _SequenceRepository([
      const ScheduleEvaluationFailure(
        kind: EvaluationFailureKind.notFound,
        message: 'not found',
      ),
      keep,
    ]);
    await tester.pumpWidget(_card(repository));
    await tester.pump();

    expect(find.byKey(const Key('evaluationInitialProgress')), findsOneWidget);
    expect(find.byKey(const Key('evaluationError')), findsNothing);
    expect(find.byType(LinearProgressIndicator), findsOneWidget);

    await tester.pump(ScheduleEvaluationCard.computingPollInterval);
    await tester.pump();

    expect(repository.latestCalls, 2);
    expect(find.byKey(const Key('evaluationInitialProgress')), findsNothing);
    expect(find.text('평가가 완료됐습니다.'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('자동 갱신은 진행 표시 없이 배경에서 처리하고 버튼을 잠그지 않는다', (tester) async {
    final second = Completer<ScheduleEvaluationV2>();
    final repository = _SequenceRepository([keep, second]);
    await tester.pumpWidget(_card(repository));
    await tester.pump();
    expect(find.byType(LinearProgressIndicator), findsNothing);

    await tester.pump(ScheduleEvaluationCard.idlePollInterval);
    await tester.pump();

    // 두 번째 조회가 진행 중이지만 화면은 그대로다.
    expect(repository.latestCalls, 2);
    expect(find.byType(LinearProgressIndicator), findsNothing);
    expect(find.byKey(const Key('evaluationActionProgress')), findsNothing);
    expect(_refreshEnabled(tester), isTrue);
    expect(find.text('평가가 완료됐습니다.'), findsOneWidget);

    second.complete(keep);
    await tester.pump();
    expect(find.text('평가가 완료됐습니다.'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('서버가 계산 중(PENDING)이면 다시 계산 버튼을 잠그고 이유를 알린다', (tester) async {
    final repository = _SequenceRepository([pending]);
    await tester.pumpWidget(_card(repository));
    await tester.pumpAndSettle();

    // 첫 계산이 도는 동안 사용자가 다시 계산을 눌러 409 를 받는 일을 화면에서 먼저 막는다.
    expect(_refreshEnabled(tester), isFalse);
    expect(find.byKey(const Key('refreshLockedNotice')), findsOneWidget);
    expect(
      find.text(ScheduleEvaluationCard.refreshLockedMessage),
      findsOneWidget,
    );
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('계산이 끝나(READY) 결과가 오면 다시 계산 버튼이 열린다', (tester) async {
    final repository = _SequenceRepository([keep]);
    await tester.pumpWidget(_card(repository));
    await tester.pumpAndSettle();

    expect(_refreshEnabled(tester), isTrue);
    expect(find.byKey(const Key('refreshLockedNotice')), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('409 INVALID_STATE(이미 계산 중)는 오류가 아니라 계산 중 안내로 보여준다', (
    tester,
  ) async {
    final repository = _SequenceRepository(
      [keep],
      refreshFailure: const ScheduleEvaluationFailure(
        kind: EvaluationFailureKind.staleEvaluation,
        message: '이 일정은 이미 계산 중입니다.',
        code: 'INVALID_STATE',
      ),
    );
    await tester.pumpWidget(_card(repository));
    await tester.pumpAndSettle();

    await tester.tap(find.text('다시 계산'));
    await tester.pumpAndSettle();

    expect(
      find.text(ScheduleEvaluationCard.alreadyComputingMessage),
      findsOneWidget,
    );
    expect(find.byKey(const Key('evaluationActionError')), findsNothing);
    // 추천이 바뀐 경우의 안내와 섞이지 않는다.
    expect(find.text('추천이 변경됐습니다. 최신 결과를 다시 확인해 주세요.'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('수동 다시 계산 중에는 10초 상한 안내와 진행 표시를 띄우고 끝나면 즉시 결과를 다시 읽는다', (
    tester,
  ) async {
    final refresh = Completer<void>();
    final repository = _SequenceRepository([keep], refreshCompleter: refresh);
    await tester.pumpWidget(_card(repository));
    await tester.pumpAndSettle();

    await tester.tap(find.text('다시 계산'));
    await tester.pump();

    expect(repository.refreshCalls, 1);
    expect(
      find.text(ScheduleEvaluationCard.manualRefreshMessage),
      findsOneWidget,
    );
    expect(find.byType(LinearProgressIndicator), findsOneWidget);
    expect(_refreshEnabled(tester), isFalse);

    refresh.complete();
    await tester.pumpAndSettle();

    expect(
      find.text(ScheduleEvaluationCard.manualRefreshMessage),
      findsNothing,
    );
    expect(find.byType(LinearProgressIndicator), findsNothing);
    expect(repository.latestCalls, 2);
    expect(find.text('서버 처리 결과를 다시 확인했습니다.'), findsOneWidget);
    expect(_refreshEnabled(tester), isTrue);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('일반 화면은 번역 문구만, 개발자 진단이 켜진 경우에만 원본 코드·버전을 보여준다', (tester) async {
    final evaluation = ScheduleEvaluationV2.fromJson({
      'schemaVersion': 'schedule-evaluation-v2',
      'state': 'READY',
      'decision': 'KEEP_DEPARTURE',
      'validUntil': DateTime.now()
          .add(const Duration(minutes: 5))
          .toIso8601String(),
      'nextEvaluationAt': DateTime.now()
          .add(const Duration(minutes: 1))
          .toIso8601String(),
      'quality': {
        'preview': true,
        'productionEligible': false,
        'realtimeApplied': false,
        'walkingApiCoverage': 'BOTH',
        'costCoverage': 'COMPLETE',
        'missingInputs': ['BUS:110000004:121000096:122000184'],
        'fallbackReasons': [
          'step-1:ML_NOT_APPLIED:ML_BUDGET_EXHAUSTED',
          'step-1:HISTORICAL_REFERENCE_NOT_APPLIED:EXACT_KEY_UNAVAILABLE',
          'step-1:API_FALLBACK',
          'step-2:ML_TEST_APPLIED',
          'step-2:ML_MODEL_VERSION:lgbm-v12',
          'step-2:ML_MAX_MISSING_FEATURES:15',
        ],
      },
      'versions': {'model': 'lgbm-v12', 'mapping': 'metro-20260910'},
      'legs': [
        {
          'sequence': 1,
          'mode': 'BUS',
          'legRole': 'RIDE',
          'durationSource': 'PUBLIC_TRANSIT_STEP',
          'fromName': '정류장A',
          'toName': '정류장B',
          'durationSeconds': 600,
        },
      ],
    });

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: ScheduleEvaluationSummary(evaluation: evaluation),
          ),
        ),
      ),
    );
    expect(find.byKey(const Key('evaluationDiagnosticsPanel')), findsNothing);
    expect(find.textContaining('ML_BUDGET_EXHAUSTED'), findsNothing);
    expect(find.textContaining('EXACT_KEY_UNAVAILABLE'), findsNothing);
    expect(find.textContaining('유효 시각'), findsNothing);
    // 일반 화면에서도 ML 실패와 과거 자료 부족은 서로 다른 문구다.
    expect(find.textContaining('계산 시간이 부족해 ML 예측 미적용'), findsOneWidget);
    expect(
      find.textContaining('과거 자료 적용 조건이 맞지 않아 기본 경로시간 유지'),
      findsOneWidget,
    );
    expect(find.textContaining('검증 중인 ML 예측값을 시험 적용'), findsOneWidget);

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: ScheduleEvaluationSummary(
              evaluation: evaluation,
              developerDiagnostics: true,
            ),
          ),
        ),
      ),
    );
    expect(find.byKey(const Key('evaluationDiagnosticsPanel')), findsOneWidget);
    // 보조 시각은 기본으로 접혀 있다. 개발자 진단 값도 "자세히"를 펼쳐야 보인다.
    expect(find.textContaining('유효 시각'), findsNothing);
    await tester.tap(find.byKey(const Key('evaluationTimeDetailsToggle')));
    await tester.pump();
    expect(find.textContaining('유효 시각'), findsOneWidget);
    expect(
      find.textContaining('step-1: ML 미적용 (ML_BUDGET_EXHAUSTED)'),
      findsOneWidget,
    );
    expect(
      find.textContaining('과거 평균 미적용 (EXACT_KEY_UNAVAILABLE)'),
      findsOneWidget,
    );
    expect(find.textContaining('API 원본 값 사용'), findsOneWidget);
    expect(find.textContaining('step-2: ML 시험 적용'), findsOneWidget);
    expect(find.textContaining('missingFeatures(max)=15'), findsOneWidget);
    expect(find.textContaining('versions.model=lgbm-v12'), findsOneWidget);
    expect(find.textContaining('source=PUBLIC_TRANSIT_STEP'), findsOneWidget);
  });

  testWidgets('수동 다시 계산이 10초를 넘기면 "최대 10초" 대신 지연 안내로 바꾸고 결과가 오면 즉시 지운다', (
    tester,
  ) async {
    final refresh = Completer<void>();
    final repository = _SequenceRepository([keep], refreshCompleter: refresh);
    await tester.pumpWidget(_card(repository));
    await tester.pumpAndSettle();

    await tester.tap(find.text('다시 계산'));
    await tester.pump();
    expect(
      find.text(ScheduleEvaluationCard.manualRefreshMessage),
      findsOneWidget,
    );

    await tester.pump(ScheduleEvaluationCard.manualRefreshBound);
    expect(
      find.text(ScheduleEvaluationCard.manualRefreshMessage),
      findsNothing,
    );
    expect(find.text(ScheduleEvaluationCard.overdueMessage), findsOneWidget);
    expect(find.byType(LinearProgressIndicator), findsOneWidget);

    refresh.complete();
    await tester.pumpAndSettle();
    expect(find.text(ScheduleEvaluationCard.overdueMessage), findsNothing);
    expect(find.byKey(const Key('evaluationActionProgress')), findsNothing);
    expect(repository.latestCalls, 2);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('서버가 30초 넘게 계산 중이면 지연 안내를 덧붙이고 결과가 오면 지운다', (tester) async {
    final repository = _SequenceRepository([
      for (var i = 0; i < 11; i++) pending,
      keep,
    ]);
    await tester.pumpWidget(_card(repository));
    await tester.pump();
    expect(find.byKey(const Key('evaluationOverdueNotice')), findsNothing);

    for (var i = 0; i < 10; i++) {
      await tester.pump(ScheduleEvaluationCard.computingPollInterval);
      await tester.pump();
    }
    expect(repository.latestCalls, 11);
    expect(find.byKey(const Key('evaluationOverdueNotice')), findsOneWidget);
    expect(
      find.text(ScheduleEvaluationCard.initialComputingMessage),
      findsOneWidget,
    );

    await tester.pump(ScheduleEvaluationCard.computingPollInterval);
    await tester.pump();
    expect(repository.latestCalls, 12);
    expect(find.byKey(const Key('evaluationOverdueNotice')), findsNothing);
    expect(find.text('평가가 완료됐습니다.'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
