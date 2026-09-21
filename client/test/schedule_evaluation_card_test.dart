import 'dart:convert';
import 'dart:io';
import 'dart:async';

import 'package:ddubi/domain/schedule_evaluation.dart';
import 'package:ddubi/presentation/schedule_evaluation_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Future<ScheduleEvaluationV2> _fixture(String name) async {
  final raw = await File('test/fixtures/schedule_evaluation_$name.json')
      .readAsString();
  return ScheduleEvaluationV2.fromJson(jsonDecode(raw) as Map<String, dynamic>);
}

Widget _summary(ScheduleEvaluationV2 evaluation) => MaterialApp(
  home: Scaffold(body: ScheduleEvaluationSummary(evaluation: evaluation)),
);

class _FakeEvaluationRepository implements ScheduleEvaluationRepository {
  _FakeEvaluationRepository(
    this.responses, {
    this.acceptFailure,
    this.refreshFailure,
  });

  final List<ScheduleEvaluationV2> responses;
  final ScheduleEvaluationFailure? acceptFailure;
  final ScheduleEvaluationFailure? refreshFailure;
  var latestCalls = 0;
  var acceptCalls = 0;

  @override
  Future<ScheduleEvaluationV2> latestEvaluation(String scheduleId) async {
    final index = latestCalls.clamp(0, responses.length - 1);
    latestCalls++;
    return responses[index];
  }

  @override
  Future<void> acceptEvaluation(String id, String evaluationId) async {
    acceptCalls++;
    if (acceptFailure != null) throw acceptFailure!;
  }

  @override
  Future<void> confirmDeparture(String id) async {}

  @override
  Future<void> refreshEvaluation(String id) async {
    if (refreshFailure != null) throw refreshFailure!;
  }
}

class _FailOnceEvaluationRepository implements ScheduleEvaluationRepository {
  _FailOnceEvaluationRepository(this.success);

  final ScheduleEvaluationV2 success;
  var latestCalls = 0;

  @override
  Future<ScheduleEvaluationV2> latestEvaluation(String scheduleId) async {
    latestCalls++;
    if (latestCalls == 1) {
      throw const ScheduleEvaluationFailure(
        kind: EvaluationFailureKind.network,
        message: 'network',
      );
    }
    return success;
  }

  @override
  Future<void> acceptEvaluation(String id, String evaluationId) async {}

  @override
  Future<void> confirmDeparture(String id) async {}

  @override
  Future<void> refreshEvaluation(String id) async {}
}

class _ControlledAcceptRepository implements ScheduleEvaluationRepository {
  _ControlledAcceptRepository(this.evaluation);

  final ScheduleEvaluationV2 evaluation;
  final acceptCompleter = Completer<void>();
  var acceptCalls = 0;
  var latestCalls = 0;

  @override
  Future<ScheduleEvaluationV2> latestEvaluation(String scheduleId) async {
    latestCalls++;
    return evaluation;
  }

  @override
  Future<void> acceptEvaluation(String id, String evaluationId) {
    acceptCalls++;
    return acceptCompleter.future;
  }

  @override
  Future<void> confirmDeparture(String id) async {}

  @override
  Future<void> refreshEvaluation(String id) async {}
}

class _FailOnceRefreshRepository implements ScheduleEvaluationRepository {
  _FailOnceRefreshRepository(this.evaluation);

  final ScheduleEvaluationV2 evaluation;
  var latestCalls = 0;
  var refreshCalls = 0;

  @override
  Future<ScheduleEvaluationV2> latestEvaluation(String scheduleId) async {
    latestCalls++;
    return evaluation;
  }

  @override
  Future<void> acceptEvaluation(String id, String evaluationId) async {}

  @override
  Future<void> confirmDeparture(String id) async {}

  @override
  Future<void> refreshEvaluation(String id) async {
    refreshCalls++;
    if (refreshCalls == 1) {
      throw const ScheduleEvaluationFailure(
        kind: EvaluationFailureKind.network,
        message: '첫 요청 실패',
      );
    }
  }
}

void main() {
  late Map<String, ScheduleEvaluationV2> fixtures;

  setUpAll(() async {
    fixtures = {
      for (final name in [
        'keep',
        'advance',
        'margin',
        'late',
        'data',
        'search',
        'pending',
        'running',
        'failed',
        'stopped',
        'departure_required',
        'stale',
        'walking_fallback',
        'be_contract',
      ])
        name: await _fixture(name),
    };
  });

  testWidgets('결정별 문구를 서로 다른 의미로 표시한다', (tester) async {
    final cases = <String, String>{
      'keep': '현재 출발 계획을 유지해도 일정 전에 도착할 것으로 예상됩니다.',
      'advance': '제안 시각에 출발하면 일정 10분 전 도착이 예상됩니다.',
      'margin': '10분 여유 확보는 어렵지만 일정 전 도착 가능한 경로입니다.',
      // 탐색이 끝났고 시험 예측도 안 섞였으면 서버가 수락을 허용한다. 지각 예상도 그대로 안내한다.
      'late': '현재 확인 가능한 경로로는 일정 시작 이후 도착이 예상됩니다.',
      'data': '정확한 출발 추천을 아직 확정하지 못했어요.',
      'search': '아직 최신 출발시각을 확정하지 못했어요. 확인된 후보는 참고용입니다.',
    };

    for (final entry in cases.entries) {
      await tester.pumpWidget(_summary(fixtures[entry.key]!));
      expect(find.text(entry.value), findsOneWidget, reason: entry.key);
    }
  });

  testWidgets('도보 역할과 품질 coverage를 사용자에게 구분해 표시한다', (tester) async {
    await tester.pumpWidget(_summary(fixtures['keep']!));

    expect(find.textContaining('접근·이탈 도보 경로 반영'), findsOneWidget);
    expect(find.textContaining('접근 도보 · 집 → 정류장'), findsOneWidget);
    expect(find.textContaining('환승 도보 · 환승역 → 승강장'), findsOneWidget);
    expect(find.textContaining('이탈 도보 · 도착역 → 회사'), findsOneWidget);
    expect(find.textContaining('접근 도보 · 집 → 정류장 · 6분'), findsOneWidget);
  });

  testWidgets('제안 경로가 바뀌면 새 경로 구간을 표시한다', (tester) async {
    final evaluation = fixtures['advance']!;
    expect(evaluation.selectedRouteId, 'route-old');
    expect(evaluation.proposedRouteId, 'route-new');

    await tester.pumpWidget(_summary(evaluation));

    expect(find.text('경로 구간'), findsOneWidget);
    expect(find.textContaining('버스 탑승 · 새 출발 정류장 → 새 도착 정류장'), findsOneWidget);
  });

  testWidgets('부분 도보 API 적용과 원본 경로 fallback을 함께 표시한다', (tester) async {
    // 대체 계산 사유는 개발자 진단에서만 보인다.
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: ScheduleEvaluationSummary(
              evaluation: fixtures['walking_fallback']!,
              developerDiagnostics: true,
            ),
          ),
        ),
      ),
    );

    expect(find.textContaining('접근 도보 경로만 반영'), findsOneWidget);
    expect(find.textContaining('이탈 도보 · 도착역 → 회사'), findsOneWidget);
    expect(find.textContaining('원본 경로 기준'), findsOneWidget);
    expect(find.textContaining('이탈 도보는 기존 경로 정보 사용'), findsOneWidget);
    expect(find.text('전체 이동시간의 구성요소가 완전히 확인되지 않았습니다.'), findsOneWidget);
  });

  testWidgets('실행 상태와 stale 상태를 각각 다른 문구와 배지로 표시한다', (tester) async {
    final cases = <String, String>{
      // 서버가 첫 계산(예산 30초) 중이면 상한 안내를 보여준다.
      'pending': ScheduleEvaluationCard.initialComputingMessage,
      'running': ScheduleEvaluationCard.initialComputingMessage,
      'failed': '최신 예측을 계산하지 못했습니다.',
      'stopped': '이 일정의 출발 전 감시가 종료됐습니다.',
      'departure_required': '일정 수정에서 출발 예정시각을 입력해 주세요.',
      'stale': '이전 결과입니다. 최신 결과를 다시 확인해 주세요.',
    };

    for (final entry in cases.entries) {
      await tester.pumpWidget(_summary(fixtures[entry.key]!));
      expect(find.text(entry.value), findsOneWidget, reason: entry.key);
    }
    expect(find.text('결과가 오래됐어요. 다시 계산해 주세요.'), findsOneWidget);
  });

  testWidgets('null ETA와 경로가 없어도 사고 원인을 추측하지 않는다', (tester) async {
    await tester.pumpWidget(_summary(fixtures['data']!));

    expect(find.textContaining('예상 도착'), findsNothing);
    expect(find.text('정확한 출발 추천을 아직 확정하지 못했어요.'), findsOneWidget);
    // 확정하지 못한 이유는 내부 코드가 아니라 한 문장으로 붙는다.
    expect(find.byKey(const Key('evaluationShortfallReason')), findsOneWidget);
    expect(find.text('경로 구간'), findsNothing);
    expect(find.textContaining('사고'), findsNothing);
  });

  testWidgets('FULL 원인 근거는 확인 문구로 표시하고 내부 코드는 노출하지 않는다', (tester) async {
    await tester.pumpWidget(_summary(fixtures['be_contract']!));

    expect(find.text('이동 영향 원인이 확인되었습니다.'), findsOneWidget);
    expect(find.textContaining('ROAD_CONTROL'), findsNothing);
  });

  testWidgets('PREVIEW 결과는 표시하되 제안 적용을 비활성화한다', (tester) async {
    final preview = fixtures['data']!;
    final repository = _FakeEvaluationRepository([preview]);

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ScheduleEvaluationCard(
            repository: repository,
            scheduleId: preview.scheduleId!,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('아직 시험 중인 예측이에요.'), findsOneWidget);
    final button = tester.widget<FilledButton>(
      find.byKey(const Key('acceptEvaluationButton')),
    );
    expect(button.onPressed, isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('409 수락 충돌 후 최신 평가를 다시 조회하고 자동 수락하지 않는다', (tester) async {
    final first = fixtures['keep']!;
    final latest = fixtures['advance']!;
    final repository = _FakeEvaluationRepository(
      [first, latest],
      acceptFailure: const ScheduleEvaluationFailure(
        kind: EvaluationFailureKind.staleEvaluation,
        message: 'stale',
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ScheduleEvaluationCard(
            repository: repository,
            scheduleId: 'schedule-1',
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('acceptEvaluationButton')));
    await tester.pumpAndSettle();

    expect(repository.acceptCalls, 1);
    expect(repository.latestCalls, 2);
    expect(find.text('추천이 변경됐습니다. 최신 결과를 다시 확인해 주세요.'), findsOneWidget);
    expect(find.textContaining('제안 시각에 출발하면'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('사용자 동작 실패는 후속 조회가 성공해도 오류로 유지한다', (tester) async {
    final repository = _FakeEvaluationRepository(
      [fixtures['keep']!],
      refreshFailure: const ScheduleEvaluationFailure(
        kind: EvaluationFailureKind.network,
        message: '다시 계산 요청이 실패했습니다.',
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ScheduleEvaluationCard(
            repository: repository,
            scheduleId: 'schedule-1',
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('다시 계산'));
    await tester.pumpAndSettle();

    expect(repository.latestCalls, 2);
    expect(find.byKey(const Key('evaluationActionError')), findsOneWidget);
    expect(find.text('다시 계산 요청이 실패했습니다.'), findsOneWidget);
    expect(find.text('서버 처리 결과를 다시 확인했습니다.'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('사용자 동작을 재시도하면 이전 오류를 지우고 성공 상태를 표시한다', (tester) async {
    final repository = _FailOnceRefreshRepository(fixtures['keep']!);

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ScheduleEvaluationCard(
            repository: repository,
            scheduleId: 'schedule-1',
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('다시 계산'));
    await tester.pumpAndSettle();
    expect(find.text('첫 요청 실패'), findsOneWidget);

    await tester.tap(find.text('다시 계산'));
    await tester.pumpAndSettle();
    expect(repository.refreshCalls, 2);
    expect(find.byKey(const Key('evaluationActionError')), findsNothing);
    expect(find.text('서버 처리 결과를 다시 확인했습니다.'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('확인된 BLOCKED 통제는 가능성이 아니라 확정 원인으로 표시한다', (tester) async {
    final evaluation = ScheduleEvaluationV2.fromJson({
      'schemaVersion': 'schedule-evaluation-v2',
      'state': 'READY',
      'decision': 'ROUTE_UNAVAILABLE',
      'causes': [
        {'effect': 'BLOCKED', 'confirmed': true},
      ],
    });

    await tester.pumpWidget(_summary(evaluation));

    expect(find.text('이동 경로의 통제가 확인되었습니다.'), findsOneWidget);
    expect(find.text('이동 구간에 영향 가능성이 있습니다.'), findsNothing);
  });

  testWidgets('DEPART_NOW는 출발시각 경과 여부에 따라 안내를 구분한다', (tester) async {
    ScheduleEvaluationV2 evaluation(bool departureMissed) =>
        ScheduleEvaluationV2.fromJson({
          'schemaVersion': 'schedule-evaluation-v2',
          'state': 'READY',
          'decision': 'DEPART_NOW',
          'departureMissed': departureMissed,
          'guidance': {
            'canDisplayEstimate': true,
            'canAccept': true,
            'source': 'MIXED_ESTIMATE',
            'reasons': <String>[],
          },
          'estimatedArrivalAt': DateTime.now()
              .add(const Duration(hours: 1))
              .toIso8601String(),
          'validUntil': DateTime.now()
              .add(const Duration(minutes: 1))
              .toIso8601String(),
          'quality': {
            'preview': false,
            'productionEligible': true,
            'costCoverage': 'COMPLETE',
          },
        });

    await tester.pumpWidget(_summary(evaluation(false)));
    expect(find.text('일정에 맞추려면 지금 출발해야 합니다.'), findsOneWidget);
    expect(find.textContaining('계획한 출발시각이 지났습니다.'), findsNothing);

    await tester.pumpWidget(_summary(evaluation(true)));
    expect(find.textContaining('계획한 출발시각이 지났습니다.'), findsOneWidget);
    expect(find.text('일정에 맞추려면 지금 출발해야 합니다.'), findsNothing);
  });

  testWidgets('네트워크 실패 후 첫 재조회는 30초 backoff를 적용한다', (tester) async {
    final repository = _FailOnceEvaluationRepository(fixtures['keep']!);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ScheduleEvaluationCard(
            repository: repository,
            scheduleId: 'schedule-1',
          ),
        ),
      ),
    );
    await tester.pump();

    expect(repository.latestCalls, 1);
    await tester.pump(const Duration(seconds: 29));
    expect(repository.latestCalls, 1);
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    expect(repository.latestCalls, 2);
    expect(find.text('평가가 완료됐습니다.'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('STOPPED와 출발시각 입력 필요 상태에서는 polling을 중단한다', (tester) async {
    for (final name in ['stopped', 'departure_required']) {
      final repository = _FakeEvaluationRepository([fixtures[name]!]);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ScheduleEvaluationCard(
              repository: repository,
              scheduleId: 'schedule-1',
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.pump(const Duration(minutes: 3));
      expect(repository.latestCalls, 1, reason: name);
      await tester.pumpWidget(const SizedBox.shrink());
    }
  });

  testWidgets('백그라운드에서는 멈추고 복귀할 때 최신 평가를 즉시 조회한다', (tester) async {
    final repository = _FakeEvaluationRepository([
      fixtures['running']!,
      fixtures['running']!,
    ]);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ScheduleEvaluationCard(
            repository: repository,
            scheduleId: 'schedule-1',
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(repository.latestCalls, 1);

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump(const Duration(minutes: 2));
    expect(repository.latestCalls, 1);

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(repository.latestCalls, 2);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('제안 적용을 연속으로 눌러도 요청은 한 번만 전송한다', (tester) async {
    final repository = _ControlledAcceptRepository(fixtures['keep']!);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ScheduleEvaluationCard(
            repository: repository,
            scheduleId: 'schedule-1',
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final button = find.byKey(const Key('acceptEvaluationButton'));
    await tester.tap(button);
    await tester.pump();
    await tester.tap(button, warnIfMissed: false);
    expect(repository.acceptCalls, 1);

    repository.acceptCompleter.complete();
    await tester.pumpAndSettle();
    expect(repository.acceptCalls, 1);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('사용자 동작 처리 중 화면을 벗어나도 상태 갱신이나 후속 조회를 하지 않는다', (tester) async {
    final repository = _ControlledAcceptRepository(fixtures['keep']!);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ScheduleEvaluationCard(
            repository: repository,
            scheduleId: 'schedule-1',
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('acceptEvaluationButton')));
    await tester.pump();
    await tester.pumpWidget(const SizedBox.shrink());
    repository.acceptCompleter.complete();
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(repository.latestCalls, 1);
  });
}
