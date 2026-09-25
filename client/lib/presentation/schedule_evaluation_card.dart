import 'dart:async';

import 'package:flutter/material.dart';

import '../core/theme.dart';
import '../domain/schedule_evaluation.dart';
import 'evaluation_diagnostics_panel.dart';
import 'widgets.dart';

/// 부족한 입력을 일반 사용자 문구로 옮긴다. 상단 결론 한 줄과 카드가 같은 표현을 쓴다.
/// 실제 코드는 "BUS:정류장:MISSING", "step-1:SUBWAY_TIMETABLE_NOT_CONFIGURED" 처럼 앞뒤에 다른 값이
/// 붙어 오므로 접두어가 아니라 포함 여부로 본다. 내부 식별자는 그대로 내보내지 않는다.
String evaluationMissingInputLabel(String value) => switch (value) {
  _
      when value.contains('SUBWAY_TIMETABLE_NOT_CONFIGURED') ||
          value.contains('SUBWAY_TIMING_UNAVAILABLE') =>
    '지하철 시간표가 아직 연결되지 않았어요',
  _ when value.startsWith('BUS:') || value.contains(':BUS:') =>
    '일부 버스 구간의 실시간 정보가 부족해요',
  _ when value.startsWith('SUB:') || value.contains(':SUB:') =>
    '일부 지하철 구간의 운행정보가 부족해요',
  _ when value.contains('ML_NOT_CONFIGURED') => '기본 경로 소요시간을 쓰고 있어요',
  // 아래 두 가지는 "실시간 정보가 없다" 와 뜻이 다르다. 학습에 쓸 과거 기록이 모자라거나,
  // 한 번에 부를 수 있는 예측 횟수를 다 쓴 경우다(2026-09-21 운영 응답 확인).
  _ when value.contains('ML_HISTORY_INSUFFICIENT') => '이 구간은 예측에 쓸 기록이 아직 적어요',
  _ when value.contains('ML_BUDGET_EXHAUSTED') => '예측 횟수를 다 써서 과거 평균으로 계산했어요',
  _ when value.contains('PLANNED_DEPART_AT') => '출발 예정시각이 없어요',
  _ when value.contains('BUS_ARRIVAL') => '버스 도착 정보가 부족해요',
  _ when value.contains('ROUTE_PROVIDER') => '경로 정보가 부족해요',
  _ when value.contains('VERSION_NOT_REPORTED') => '자료 출처 기록이 아직 없어요',
  _ => '일부 교통정보를 확인하지 못했어요',
};

/// 출발·도착 시각이 뜻을 가지는 판단. 이 다섯 가지면 확정이 아니어도 시각을 참고값으로 보여준다.
const Set<DepartureDecision> evaluationTimeDecisions = {
  DepartureDecision.keepDeparture,
  DepartureDecision.advanceDeparture,
  DepartureDecision.departNow,
  DepartureDecision.marginUnavailable,
  DepartureDecision.lateExpected,
};

/// 확정 조건은 못 넘겼지만 계산이 끝나 시각이 있는 결과인지. 참고값으로 보여줄 대상이다.
///
/// 확정 규칙([ScheduleEvaluationV2.canRecommendAt])과 적용 규칙은 바꾸지 않는다. 숫자를 숨기던
/// 것만 멈추고, 색과 문구로 "아직 확정 전"임을 말한다(2026-09-18 결정).
bool evaluationHasReferenceTimes(ScheduleEvaluationV2 evaluation) =>
    evaluation.state == EvaluationState.ready &&
    evaluationTimeDecisions.contains(evaluation.decision) &&
    evaluation.proposedDepartAt != null &&
    evaluation.estimatedArrivalAt != null;

/// 확정은 아니지만 시각을 보여줄 때 쓰는 상태 문장. 출처에 따라 말을 바꾼다.
String evaluationGuidanceMessage(EvaluationGuidance guidance) =>
    switch (guidance.source) {
      'PROVIDER_ESTIMATE' => '기본 경로 기준으로 계산한 예상이에요. 실시간 정보가 들어오면 더 정확해져요.',
      'EXPERIMENTAL_ESTIMATE' => '아직 시험 중인 예측이 섞여 있어요. 참고용이고 일정에 자동 적용은 안 해요.',
      _ => '참고로 계산한 결과예요. 아직 확정 전이라 일정에 자동 적용은 안 해요.',
    };

/// 확정하지 못한 결과에서 사용자에게 말할 대표 이유 한 문장.
///
/// 이유가 여럿이면 가장 설명력이 큰 것 하나만 고른다. 후보 전체의 부족 목록(missingInputs)보다
/// 제안 경로 자체의 대체 계산 사유(fallbackReasons)를 먼저 본다 — 사용자가 보는 경로의 이유가 맞다.
String? evaluationShortfallReason(ScheduleEvaluationV2 evaluation) {
  if (evaluation.decision == DepartureDecision.searchIncomplete) {
    return '경로를 끝까지 살펴보지 못해서예요. 다시 계산해 주세요.';
  }
  if (evaluation.decision == DepartureDecision.routeUnavailable) {
    return '지금 이용할 수 있는 경로를 찾지 못했어요.';
  }
  final reasons = evaluation.quality.fallbackReasons;
  bool has(String token) => reasons.any((reason) => reason.contains(token));
  if (has('SUBWAY_TIMETABLE_NOT_CONFIGURED') ||
      has('SUBWAY_TIMING_UNAVAILABLE')) {
    return '지하철 시간표가 아직 연결되지 않아서예요.';
  }
  if (has('PROVIDER_TOTAL_FALLBACK') ||
      evaluation.quality.costCoverage != 'COMPLETE') {
    return '이동 시간 일부를 아직 확인하지 못해서예요.';
  }
  if (has('ML_HISTORY_INSUFFICIENT')) {
    return '일부 버스 구간은 예측에 쓸 과거 기록이 아직 적어서예요.';
  }
  if (has('ML_BUDGET_EXHAUSTED')) {
    return '예측 횟수를 다 써서 일부 구간은 과거 평균으로 계산했어요.';
  }
  if (has('ML_NOT_APPLIED') ||
      has('API_FALLBACK') ||
      has('HISTORICAL_REFERENCE') ||
      has('GTFS_FORMULA_APPLIED')) {
    return '버스 구간의 실시간 정보가 아직 없어서예요.';
  }
  final missing = evaluation.quality.missingInputs;
  if (missing.any(
    (input) => input.startsWith('BUS:') || input.contains(':BUS:'),
  )) {
    return '버스 구간의 실시간 정보가 아직 없어서예요.';
  }
  if (missing.any(
    (input) => input.startsWith('SUB:') || input.contains(':SUB:'),
  )) {
    return '지하철 구간의 운행정보가 아직 없어서예요.';
  }
  if (evaluation.quality.preview || !evaluation.quality.productionEligible) {
    return '아직 시험 중인 계산이라 확정으로 표시하지 않아요.';
  }
  if (missing.isNotEmpty) return '일부 교통정보를 아직 확인하지 못해서예요.';
  return null;
}

class ScheduleEvaluationCard extends StatefulWidget {
  const ScheduleEvaluationCard({
    super.key,
    required this.repository,
    required this.scheduleId,
    this.developerDiagnostics = false,
    this.headline,
    this.departedAt,
    this.showActions = true,
    this.onFirstReadyDisplayed,
    this.refreshGeneration = 0,
    this.showCard = true,
  });

  /// 카드를 그릴지. 일정 상세는 이동 예측 카드를 보여주지 않는다(2026-09-25 요청). 그래도 이 위젯은
  /// 남아서 평가를 주기적으로 읽고 [headline] 과 [onFirstReadyDisplayed] 를 채운다 — 맨 위 결론·
  /// 이동 변수·시연 시작이 모두 이 값을 쓴다. 개발자 옵션을 켜면 다시 보인다.
  final bool showCard;

  /// 버튼 줄을 이 카드에 그릴지. 일정 상세는 같은 버튼을 AI 안내 카드에 두므로 끈다.
  /// 두 곳에 같이 두면 사용자가 서로 다른 단추로 오해한다.
  final bool showActions;

  /// 최초 READY 카드가 프레임에 그려진 직후 한 번만 호출한다. 시연 타이머는 이 시점부터 센다.
  final VoidCallback? onFirstReadyDisplayed;

  /// 외부 시연 이벤트가 저장·재평가된 직후 30초 정기 조회를 기다리지 않고 최신 결과를 읽는다.
  final int refreshGeneration;

  final ScheduleEvaluationRepository repository;
  final String scheduleId;

  /// 화면 위쪽 결론 한 줄에 같은 결과를 전달하는 통로. 없으면 카드만 그린다.
  final ValueNotifier<ScheduleEvaluationV2?>? headline;

  /// 일정 상세가 서버에서 읽은 실제 출발 시각. 출발이 기록되면 정기 확인 시각을
  /// 기다리지 않고 즉시 STOPPED/출발 기준 ETA를 다시 읽는다.
  final DateTime? departedAt;

  /// 개발자 진단 패널 표시 여부. 디버그 빌드나 더보기 > 개발자 옵션에서만 켜진다.
  final bool developerDiagnostics;

  /// 서버의 첫 계산 예산(30초)에 맞춘 안내. 상한이며 결과가 오면 즉시 바꾼다.
  static const String initialComputingMessage =
      '추천 경로를 비교하고 있어요. 최대 30초 정도 걸릴 수 있어요.';

  /// 수동 다시 계산 예산(10초)에 맞춘 안내. 상한이며 결과가 오면 즉시 바꾼다.
  static const String manualRefreshMessage =
      '최신 교통정보로 다시 계산하고 있어요. 최대 10초 정도 걸릴 수 있어요.';

  /// 안내한 상한(첫 계산 30초·수동 10초)을 넘겨도 끝나지 않으면 이 문구로 바꾼다.
  /// 서버 응답 대기는 더 길 수 있으므로(수동 요청 HTTP 대기 30초) "최대 10초" 문구를 그대로 두지 않는다.
  static const String overdueMessage = '예상보다 오래 걸리고 있어요. 결과가 나오면 바로 보여드릴게요.';

  /// 서버가 이미 계산 중이라 요청을 받지 않았을 때(409 INVALID_STATE). 오류가 아니라 안내로 보여준다.
  static const String alreadyComputingMessage =
      '지금 계산 중이에요. 끝나면 결과를 바로 보여드릴게요.';

  /// 계산 중에는 다시 계산 버튼을 잠근다. 잠근 이유를 버튼 아래 한 줄로 알린다.
  static const String refreshLockedMessage = '지금 계산 중이라 끝난 뒤에 다시 계산할 수 있어요.';
  static const Duration manualRefreshBound = Duration(seconds: 10);
  static const Duration initialComputingBound = Duration(seconds: 30);

  /// 계산 중(결과 없음·PENDING·RUNNING)에는 짧게, 결과가 있으면 길게 확인한다.
  static const Duration computingPollInterval = Duration(seconds: 3);
  static const Duration idlePollInterval = Duration(seconds: 30);

  /// 빠른 확인 횟수 상한(3초 × 15회 = 45초 ≒ 초기 예산 30초 + 여유). 넘으면 보통 간격으로 돌아간다.
  static const int maxFastPolls = 15;

  @override
  State<ScheduleEvaluationCard> createState() => _ScheduleEvaluationCardState();
}

class _ScheduleEvaluationCardState extends State<ScheduleEvaluationCard>
    with WidgetsBindingObserver {
  /// 화면에 마지막으로 표시할 수 있었던 평가. PENDING/RUNNING 조회로 덮지 않는다.
  ScheduleEvaluationV2? evaluation;

  /// 서버의 실제 최신 상태. 다음 확인 간격과 계산 중 배지에는 이 상태를 쓴다.
  ScheduleEvaluationV2? latestState;
  String? error;
  String? actionError;
  String? notice;

  /// 사용자 동작(다시 계산·적용·출발) 진행 안내. 자동 갱신에는 쓰지 않는다.
  String? progressMessage;

  /// 사용자 동작 처리 중. 버튼을 잠그고 진행 표시를 띄운다.
  bool busy = false;

  /// 서버 결과 조회 중. 자동 갱신은 이 상태에서도 로딩 UI를 띄우지 않는다.
  bool loading = false;
  bool active = true;
  int requestGeneration = 0;
  int retryCount = 0;
  int fastPolls = 0;
  bool firstReadyReported = false;

  /// 마지막 조회 실패 종류. 404(아직 결과 없음)만 "계산 중"으로 본다. 연결 실패는 30초 backoff 를 유지한다.
  EvaluationFailureKind? lastFailure;
  Timer? timer;

  /// 사용자 동작 안내가 상한(10초)을 넘겼을 때 문구를 바꾸는 타이머.
  Timer? progressTimer;

  /// 첫 계산 안내 상한(30초)을 넘겼는지. 빠른 확인 횟수(3초 간격)로 계산한다.
  bool get _initialOverdue =>
      fastPolls * ScheduleEvaluationCard.computingPollInterval.inSeconds >=
      ScheduleEvaluationCard.initialComputingBound.inSeconds;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void didUpdateWidget(covariant ScheduleEvaluationCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.scheduleId != widget.scheduleId) {
      timer?.cancel();
      requestGeneration++;
      evaluation = null;
      latestState = null;
      firstReadyReported = false;
      // 부모가 새 일정을 그리는 프레임 안에서 notifier를 갱신하면 빌드 중 재빌드가
      // 겹친다. 새 프레임 직후 이전 결론을 비우고 해당 일정만 조회한다.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        widget.headline?.value = null;
        _load(force: true);
      });
    } else if (oldWidget.refreshGeneration != widget.refreshGeneration ||
        oldWidget.departedAt == null && widget.departedAt != null) {
      timer?.cancel();
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _load(force: true);
      });
    }
  }

  @override
  void dispose() {
    requestGeneration++;
    timer?.cancel();
    progressTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    active = state == AppLifecycleState.resumed;
    timer?.cancel();
    if (active) _load(force: true);
  }

  bool get _shouldPoll => switch (latestState?.state) {
    EvaluationState.stopped =>
      latestState?.actualDepartAt != null &&
          latestState?.departureEstimateStatus == null &&
          (latestState?.arriveBy?.isAfter(DateTime.now()) ?? false),
    EvaluationState.departureRequired => false,
    _ => true,
  };

  /// 서버가 첫 결과를 계산 중인 상태: PENDING/RUNNING, 또는 결과가 아직 없어 404 를 받은 경우.
  /// 연결 실패나 다른 오류는 계산 중으로 보지 않는다(서버를 빠르게 재호출하지 않는다).
  bool get _computing => switch (latestState?.state) {
    EvaluationState.pending || EvaluationState.running => true,
    null =>
      lastFailure == null || lastFailure == EvaluationFailureKind.notFound,
    _ => false,
  };

  void _scheduleNext({required bool failed}) {
    timer?.cancel();
    if (!mounted || !active || !_shouldPoll) return;
    if (!_computing) fastPolls = 0;
    final fast = _computing && fastPolls < ScheduleEvaluationCard.maxFastPolls;
    if (fast) fastPolls++;
    final base = fast
        ? ScheduleEvaluationCard.computingPollInterval
        : ScheduleEvaluationCard.idlePollInterval;
    final backoffStep = (retryCount - 1).clamp(0, 2);
    final interval = failed ? base * (1 << backoffStep) : base;
    timer = Timer(interval, _load);
  }

  Future<void> _load({bool force = false}) async {
    if (!active) return;
    if (loading && !force) return;
    final generation = ++requestGeneration;
    setState(() => loading = true);
    var failed = false;
    try {
      final latest = await widget.repository.latestEvaluation(
        widget.scheduleId,
      );
      if (!mounted || generation != requestGeneration) return;
      lastFailure = null;
      if (!_isOlderThanCurrent(latest)) {
        setState(() {
          latestState = latest;
          // 정기 재평가는 계산 중 상태를 먼저 저장한다. 아직 새 소요시간이 없다는
          // 뜻이지 이전 결과가 사라진 것은 아니므로, 완료되기 전까지 이전 카드를 유지한다.
          if (evaluation == null ||
              (latest.state != EvaluationState.pending &&
                  latest.state != EvaluationState.running)) {
            evaluation = latest;
          }
          error = null;
          retryCount = 0;
        });
        // 상단 결론도 실제로 보여주는 평가와 함께 교체한다.
        if (widget.headline?.value != evaluation) {
          widget.headline?.value = evaluation;
        }
        if (!firstReadyReported &&
            evaluation?.state == EvaluationState.ready &&
            evaluation?.selectedRouteId != null) {
          firstReadyReported = true;
          final displayedScheduleId = widget.scheduleId;
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted && widget.scheduleId == displayedScheduleId) {
              widget.onFirstReadyDisplayed?.call();
            }
          });
        }
      }
    } on ScheduleEvaluationFailure catch (failure) {
      failed = true;
      if (!mounted || generation != requestGeneration) return;
      lastFailure = failure.kind;
      setState(() {
        retryCount++;
        // 일정을 막 만든 직후에는 첫 결과가 나오기 전까지 404가 온다. 계산 중 안내를 유지한다.
        final stillComputing =
            failure.kind == EvaluationFailureKind.notFound &&
            evaluation == null &&
            fastPolls < ScheduleEvaluationCard.maxFastPolls;
        error = stillComputing ? null : _failureMessage(failure);
      });
    } on Object {
      failed = true;
      if (!mounted || generation != requestGeneration) return;
      lastFailure = EvaluationFailureKind.unknown;
      setState(() {
        retryCount++;
        error = '예측을 불러오지 못했습니다. 연결 상태를 확인해 주세요.';
      });
    } finally {
      if (mounted && generation == requestGeneration) {
        setState(() => loading = false);
        _scheduleNext(failed: failed);
      }
    }
  }

  bool _isOlderThanCurrent(ScheduleEvaluationV2 next) {
    final current = latestState;
    if (current == null) return false;
    final currentInput = current.inputVersion;
    final nextInput = next.inputVersion;
    if (currentInput != null && nextInput != null && nextInput < currentInput) {
      return true;
    }
    if (currentInput == nextInput &&
        current.computedAt != null &&
        next.computedAt != null &&
        next.computedAt!.isBefore(current.computedAt!)) {
      return true;
    }
    return false;
  }

  Future<void> _action(
    Future<void> Function() action, {
    String? progress,
  }) async {
    if (busy) return;
    timer?.cancel();
    progressTimer?.cancel();
    setState(() {
      busy = true;
      actionError = null;
      notice = null;
      progressMessage = progress;
    });
    if (progress != null) {
      // 안내한 상한을 넘기면 문구를 바꿔 "최대 10초"가 거짓이 되지 않게 한다.
      progressTimer = Timer(ScheduleEvaluationCard.manualRefreshBound, () {
        if (mounted && busy) {
          setState(
            () => progressMessage = ScheduleEvaluationCard.overdueMessage,
          );
        }
      });
    }
    var stale = false;
    var succeeded = false;
    try {
      await action();
      succeeded = true;
    } on ScheduleEvaluationFailure catch (failure) {
      stale = failure.kind == EvaluationFailureKind.staleEvaluation;
      // 같은 409라도 "이미 계산 중"(INVALID_STATE)은 추천이 바뀐 것이 아니다. 기다리면 결과가 온다.
      final alreadyComputing = stale && failure.code == 'INVALID_STATE';
      if (mounted) {
        setState(() {
          if (alreadyComputing) {
            error = null;
            notice = ScheduleEvaluationCard.alreadyComputingMessage;
          } else if (stale) {
            error = null;
            notice = '추천이 변경됐습니다. 최신 결과를 다시 확인해 주세요.';
          } else {
            actionError = _failureMessage(failure);
          }
        });
      }
    } on Object {
      if (mounted) {
        setState(() => actionError = '요청을 처리하지 못했습니다. 다시 시도해 주세요.');
      }
    } finally {
      progressTimer?.cancel();
      if (mounted) {
        setState(() {
          busy = false;
          progressMessage = null;
        });
      }
    }
    if (!mounted) return;
    if (succeeded) setState(() => notice = '서버 처리 결과를 다시 확인했습니다.');
    await _load(force: true);
  }

  String _failureMessage(ScheduleEvaluationFailure failure) =>
      switch (failure.kind) {
        EvaluationFailureKind.rateLimited => '요청이 많아 잠시 후 자동으로 다시 확인합니다.',
        EvaluationFailureKind.dependencyUnavailable =>
          '교통 데이터 연결이 불안정해 잠시 후 다시 확인합니다.',
        EvaluationFailureKind.authentication => '로그인 또는 일정 접근 권한을 확인해 주세요.',
        EvaluationFailureKind.notFound => '아직 생성된 평가 결과가 없습니다.',
        EvaluationFailureKind.invalidResponse => '서버 응답 형식을 확인할 수 없습니다.',
        _ => failure.message,
      };

  @override
  Widget build(BuildContext context) {
    if (!widget.showCard) return const SizedBox.shrink();
    final initialComputing = evaluation == null && error == null;
    final computing = _computing;
    return Container(
      key: const Key('scheduleEvaluationCard'),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: const Color(0xFFD5E6FA)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _header(computing),
          const SizedBox(height: 12),
          if (evaluation != null)
            ScheduleEvaluationSummary(
              evaluation: evaluation!,
              developerDiagnostics: widget.developerDiagnostics,
            ),
          if (initialComputing)
            Text(
              _initialOverdue
                  ? ScheduleEvaluationCard.overdueMessage
                  : ScheduleEvaluationCard.initialComputingMessage,
              key: const Key('evaluationInitialProgress'),
              style: const TextStyle(fontSize: 13, color: kEvaluationMuted),
            ),
          // 서버가 PENDING/RUNNING 을 30초 넘게 유지하면 상한 문구가 거짓이 되지 않도록 덧붙인다.
          if (evaluation != null && computing && _initialOverdue) ...[
            const SizedBox(height: 8),
            const Text(
              ScheduleEvaluationCard.overdueMessage,
              key: Key('evaluationOverdueNotice'),
              style: TextStyle(fontSize: 13, color: kEvaluationMuted),
            ),
          ],
          if (progressMessage != null) ...[
            const SizedBox(height: 8),
            Text(
              progressMessage!,
              key: const Key('evaluationActionProgress'),
              style: const TextStyle(fontSize: 13, color: kEvaluationMuted),
            ),
          ],
          if (error != null) ...[
            const SizedBox(height: 8),
            Text(
              error!,
              key: const Key('evaluationError'),
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ],
          if (actionError != null) ...[
            const SizedBox(height: 8),
            Text(
              actionError!,
              key: const Key('evaluationActionError'),
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ],
          if (notice != null) ...[
            const SizedBox(height: 8),
            EvaluationNoteStrip(
              icon: Icons.info_outline_rounded,
              lines: [notice!],
              textKey: const Key('evaluationNotice'),
            ),
          ],
          if (evaluation != null && widget.showActions) ...[
            const SizedBox(height: 12),
            _actions(evaluation!, computing),
          ],
          // 진행 표시는 첫 결과를 기다릴 때와 사용자 동작 중에만 띄운다. 자동 갱신은 조용히 처리한다.
          if (busy || initialComputing) ...[
            const SizedBox(height: 10),
            const ClipRRect(
              borderRadius: BorderRadius.all(Radius.circular(4)),
              child: LinearProgressIndicator(minHeight: 4),
            ),
          ],
        ],
      ),
    );
  }

  /// 카드 머리. 다른 화면 카드와 같은 원형 아이콘·굵은 제목·상태 배지 구성이다.
  Widget _header(bool computing) {
    final badge = _stateBadge(computing);
    return Row(
      children: [
        Container(
          width: 40,
          height: 40,
          decoration: const BoxDecoration(
            color: Color(0xFFE9F1FF),
            shape: BoxShape.circle,
          ),
          child: const Icon(
            Icons.route_rounded,
            color: Color(0xFF3C73DD),
            size: 22,
          ),
        ),
        const SizedBox(width: 12),
        const Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '이동 예측',
                style: TextStyle(
                  fontSize: 17,
                  fontWeight: FontWeight.w900,
                  color: DdubiColors.navy,
                ),
              ),
              SizedBox(height: 2),
              Text(
                '계획한 출발 시각으로 언제 도착할지 미리 계산해요.',
                style: TextStyle(fontSize: 12, color: kEvaluationFaint),
              ),
            ],
          ),
        ),
        if (badge != null) ...[const SizedBox(width: 8), badge],
      ],
    );
  }

  StatusBadge? _stateBadge(bool computing) {
    final value = evaluation;
    if (value == null) {
      return error == null
          ? const StatusBadge(
              '계산 중',
              tone: DdubiColors.blue,
              icon: Icons.hourglass_bottom_rounded,
            )
          : null;
    }
    if (computing) {
      return const StatusBadge(
        '계산 중',
        tone: DdubiColors.blue,
        icon: Icons.hourglass_bottom_rounded,
      );
    }
    if (value.state == EvaluationState.stale) {
      return const StatusBadge(
        '다시 확인 필요',
        tone: DdubiColors.orange,
        icon: Icons.refresh_rounded,
      );
    }
    // 유효시간(약 1분)만 지난 결과는 오류가 아니다. 언제 확인한 값인지만 조용히 표시한다.
    if (value.isExpiredAt(DateTime.now())) {
      final computedAt = value.computedAt;
      return StatusBadge(
        computedAt == null ? '마지막 확인 결과' : '마지막 확인 ${clockLabel(computedAt)}',
        tone: const Color(0xFF6C7D96),
        icon: Icons.update_rounded,
      );
    }
    return switch (value.state) {
      EvaluationState.ready =>
        value.quality.preview
            ? const StatusBadge(
                '시험 중',
                tone: DdubiColors.blue,
                icon: Icons.science_outlined,
              )
            : const StatusBadge('계산 완료', tone: DdubiColors.green),
      EvaluationState.failed => const StatusBadge(
        '계산 실패',
        tone: DdubiColors.red,
        icon: Icons.error_outline_rounded,
      ),
      EvaluationState.stopped => const StatusBadge(
        '안내 종료',
        tone: Color(0xFF6C7D96),
        icon: Icons.flag_outlined,
      ),
      EvaluationState.departureRequired => const StatusBadge(
        '입력 필요',
        tone: DdubiColors.orange,
        icon: Icons.edit_calendar_outlined,
      ),
      _ => null,
    };
  }

  /// 버튼 줄. 계산 중(첫 계산·다시 계산 모두)에는 다시 계산을 잠근다.
  ///
  /// 서버가 30초 넘게 계산 중이면 잠금을 풀어 사용자가 다시 시도할 길을 남긴다.
  /// 그 경우에도 서버가 중복 계산을 409로 막으므로 안전하다.
  Widget _actions(ScheduleEvaluationV2 value, bool computing) {
    final canAccept = value.canAcceptAt(DateTime.now());
    final evaluationId = value.evaluationId;
    final active =
        value.state != EvaluationState.stopped &&
        value.state != EvaluationState.departureRequired;
    final refreshLocked = computing && !_initialOverdue;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            if (value.proposedDepartAt != null)
              FilledButton.icon(
                key: const Key('acceptEvaluationButton'),
                onPressed:
                    busy ||
                        !canAccept ||
                        evaluationId == null ||
                        evaluationId.isEmpty
                    ? null
                    : () {
                        if (!value.canAcceptAt(DateTime.now())) {
                          setState(
                            () => notice = '제안이 만료됐습니다. 최신 결과를 확인해 주세요.',
                          );
                          _load(force: true);
                          return;
                        }
                        _action(
                          () => widget.repository.acceptEvaluation(
                            widget.scheduleId,
                            evaluationId,
                          ),
                        );
                      },
                icon: const Icon(Icons.check_rounded, size: 18),
                label: const Text('이 제안 적용'),
              ),
            if (active)
              OutlinedButton.icon(
                key: const Key('refreshEvaluationButton'),
                onPressed: busy || refreshLocked
                    ? null
                    : () => _action(
                        () => widget.repository.refreshEvaluation(
                          widget.scheduleId,
                        ),
                        progress: ScheduleEvaluationCard.manualRefreshMessage,
                      ),
                icon: const Icon(Icons.refresh_rounded, size: 18),
                label: const Text('다시 계산'),
              ),
            if (active)
              TextButton.icon(
                onPressed: busy
                    ? null
                    : () => _action(
                        () => widget.repository.confirmDeparture(
                          widget.scheduleId,
                        ),
                      ),
                icon: const Icon(Icons.directions_run_rounded, size: 18),
                label: const Text('출발했어요'),
              ),
          ],
        ),
        if (active && refreshLocked && !busy) ...[
          const SizedBox(height: 6),
          const Text(
            ScheduleEvaluationCard.refreshLockedMessage,
            key: Key('refreshLockedNotice'),
            style: TextStyle(fontSize: 12, color: kEvaluationFaint),
          ),
        ],
      ],
    );
  }
}

/// "09:38" 형식. 배지처럼 짧은 자리에 쓴다.
/// 구간 소요시간의 출처 문구. 실측 과거 평균과 GTFS 시간표 계산값은 반드시 구분한다.
/// GTFS 계산값은 실제 운행 기록이 아니므로 실측·실시간·실제 평균으로 표시하지 않는다.
String durationSourceLabel(DurationSource source) => switch (source) {
  DurationSource.kakaoWalkingApi => '도보 API',
  DurationSource.publicTransitStep => '원본 경로 기준',
  DurationSource.providerTotalFallback => '전체 경로시간 기준',
  DurationSource.realtimeArrival => '실시간 도착예측',
  DurationSource.timetable => '시간표 기준',
  DurationSource.mlPreview => '검증용 모델',
  DurationSource.mlProduction => 'ML 예측(운영 계약)',
  DurationSource.busHistoricalReference => '실측 과거 버스 평균 기준 · 실시간 아님',
  DurationSource.busGtfsFormula => 'GTFS 시간표 계산 기준 · 실시간 아님',
  DurationSource.unknown => '출처 확인 중',
};

String clockLabel(DateTime value) {
  final local = value.toLocal();
  return '${local.hour.toString().padLeft(2, '0')}:'
      '${local.minute.toString().padLeft(2, '0')}';
}

/// 이동 예측 영역 공통 글자색. 다른 화면의 본문·보조 글자색과 같은 값이다.
const Color kEvaluationMuted = Color(0xFF52677B);
const Color kEvaluationFaint = Color(0xFF6E8193);

/// 안내 문장 묶음. 짧은 배지 대신 문장으로 쓰고, 배경을 살짝 깔아 본문과 구분한다.
class EvaluationNoteStrip extends StatelessWidget {
  const EvaluationNoteStrip({
    super.key,
    required this.icon,
    required this.lines,
    this.textKey,
  });

  final IconData icon;
  final List<String> lines;

  /// 첫 줄 글자에 붙일 키. 시험이 문구를 찾을 때 쓴다.
  final Key? textKey;

  @override
  Widget build(BuildContext context) => Container(
    width: double.infinity,
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
    decoration: BoxDecoration(
      color: const Color(0xFFF4F8FF),
      borderRadius: BorderRadius.circular(12),
    ),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 16, color: const Color(0xFF3C73DD)),
        const SizedBox(width: 8),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (var i = 0; i < lines.length; i++)
                Padding(
                  padding: EdgeInsets.only(top: i == 0 ? 0 : 3),
                  child: Text(
                    lines[i],
                    key: i == 0 ? textKey : null,
                    style: const TextStyle(
                      fontSize: 12,
                      height: 1.35,
                      color: kEvaluationMuted,
                    ),
                  ),
                ),
            ],
          ),
        ),
      ],
    ),
  );
}

class ScheduleEvaluationSummary extends StatefulWidget {
  const ScheduleEvaluationSummary({
    super.key,
    required this.evaluation,
    this.compact = false,
    this.developerDiagnostics = false,
  });

  final ScheduleEvaluationV2 evaluation;
  final bool compact;

  /// 일반 사용자 화면에는 번역된 문구만, 개발자 진단이 켜진 경우에만 원본 코드·버전·유효 시각을 덧붙인다.
  final bool developerDiagnostics;

  @override
  State<ScheduleEvaluationSummary> createState() => _SummaryState();
}

class _SummaryState extends State<ScheduleEvaluationSummary> {
  ScheduleEvaluationV2 get evaluation => widget.evaluation;
  Timer? _expiry;

  /// 보조 시각(입력·계획·목표·마지막 확인)을 펼쳤는지. 기본은 접힘이다.
  bool _showTimeDetails = false;

  /// 접어둘 시각이 하나라도 있는지. 없으면 "자세히" 자체를 띄우지 않는다.
  /// 상세 화면(compact=false)은 입력·계획 출발을 "확인 불가"로라도 항상 보여준다.
  bool get _hasFoldedTimes =>
      !widget.compact ||
      evaluation.targetArriveAt != null ||
      evaluation.computedAt != null;

  @override
  void initState() {
    super.initState();
    _scheduleExpiry();
  }

  @override
  void didUpdateWidget(covariant ScheduleEvaluationSummary oldWidget) {
    super.didUpdateWidget(oldWidget);
    _scheduleExpiry();
  }

  void _scheduleExpiry() {
    _expiry?.cancel();
    final until = evaluation.validUntil;
    if (until != null && until.isAfter(DateTime.now())) {
      _expiry = Timer(
        until.difference(DateTime.now()) + const Duration(milliseconds: 1),
        () {
          if (mounted) setState(() {});
        },
      );
    }
  }

  @override
  void dispose() {
    _expiry?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    // 시각을 보여줄지는 서버가 정한다. 확정 추천(초록)인지 참고값(파랑)인지만 앱이 나눈다.
    final usable =
        evaluation.canDisplayEstimateAt(now) && evaluation.guidance.canAccept;
    final historicalEstimate = evaluation.canShowHistoricalEstimateAt(now);
    // 서버가 stale 로 판정한 결과와, 유효시간(약 1분)만 지난 결과를 구분한다.
    // 앞은 다시 확인이 필요하다고 크게 알리고, 뒤는 "마지막 확인 결과"로 조용히 보여준다(2026-09-18 B안).
    final serverStale = evaluation.state == EvaluationState.stale;
    final expiredOnly = !serverStale && evaluation.isExpiredAt(now);
    final computedAt = evaluation.computedAt;
    final usableWhenComputed =
        computedAt != null && evaluation.canRecommendAt(computedAt);
    final showTimes = usable || (expiredOnly && usableWhenComputed);
    // 확정은 아니지만 계산이 끝난 결과는 시각을 참고값으로 보여준다. 과거 평균 참고 표시가 있으면 그쪽을 쓴다.
    final referenceTimes =
        !showTimes &&
        !historicalEstimate &&
        evaluation.canDisplayEstimateAt(now);
    final diagnostics = widget.developerDiagnostics;
    // 짧은 배지는 뜻이 전달되지 않아 문장으로 바꿨다. 판단 기준은 그대로다.
    final notes = [
      if (evaluation.quality.preview) '아직 시험 중인 예측이에요.',
      if (serverStale) '결과가 오래됐어요. 다시 계산해 주세요.',
      if (evaluation.hasHistoricalReference) '일부 구간은 과거 평균으로 계산했어요.',
      if (diagnostics && evaluation.isLegacy) '구버전 응답이에요.',
    ];
    final shortfall =
        evaluation.state == EvaluationState.ready && !showTimes && !serverStale
        ? evaluationShortfallReason(evaluation)
        : null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (notes.isNotEmpty) ...[
          EvaluationNoteStrip(icon: Icons.info_outline_rounded, lines: notes),
          const SizedBox(height: 10),
        ],
        // 상태 한 줄. 확정 추천이면 결론 문장을 굵게 올린다.
        Text(
          serverStale
              ? '이전 결과입니다. 최신 결과를 다시 확인해 주세요.'
              : expiredOnly && usableWhenComputed
              ? '마지막으로 확인한 결과예요. 새 정보가 들어오면 자동으로 다시 계산해요.'
              : referenceTimes
              ? evaluationGuidanceMessage(evaluation.guidance)
              : evaluation.state == EvaluationState.ready && !usable
              ? '정확한 출발 추천을 아직 확정하지 못했어요.'
              : _stateMessage(evaluation.state),
          key: const Key('evaluationStateMessage'),
          style: TextStyle(
            fontSize: showTimes ? 13 : 14,
            fontWeight: showTimes ? FontWeight.w500 : FontWeight.w800,
            color: showTimes ? kEvaluationMuted : DdubiColors.navy,
          ),
        ),
        if (shortfall != null) ...[
          const SizedBox(height: 3),
          Text(
            shortfall,
            key: const Key('evaluationShortfallReason'),
            style: const TextStyle(fontSize: 13, color: kEvaluationMuted),
          ),
        ],
        if (showTimes) ...[
          const SizedBox(height: 4),
          Text(
            _decisionMessage(
              evaluation.decision,
              departureMissed: evaluation.departureMissed,
            ),
            key: const Key('evaluationDecisionMessage'),
            style: const TextStyle(
              fontSize: 15,
              height: 1.3,
              fontWeight: FontWeight.w800,
              color: DdubiColors.navy,
            ),
          ),
        ],
        const SizedBox(height: 10),
        // 기본은 두 줄(제안 출발·예상 도착)만 남긴다. 나머지 시각은 "자세히"로 접는다.
        if (showTimes && evaluation.proposedDepartAt != null)
          _timeRow(
            Icons.directions_run_rounded,
            '제안 출발',
            evaluation.proposedDepartAt,
          ),
        if (showTimes)
          _timeRow(Icons.flag_outlined, '예상 도착', evaluation.estimatedArrivalAt),
        // 확정 전 결과의 시각. 라벨을 "참고"로 붙여 제안 출발과 섞이지 않게 한다.
        if (referenceTimes) ...[
          _timeRow(
            Icons.directions_run_rounded,
            evaluation.guidance.isProviderEstimate ? '기본 경로 기준 출발' : '참고 출발',
            evaluation.proposedDepartAt,
          ),
          _timeRow(
            Icons.flag_outlined,
            evaluation.guidance.isProviderEstimate ? '기본 경로 기준 도착' : '참고 도착',
            evaluation.estimatedArrivalAt,
          ),
        ],
        // 유효시간이 지난 결과는 언제 확인한 값인지 접지 않고 바로 보여준다.
        if (expiredOnly && computedAt != null)
          _timeRow(Icons.update_rounded, '마지막 확인', computedAt),
        if (!showTimes && historicalEstimate) ...[
          _timeRow(
            Icons.history_rounded,
            '과거 평균 기반 참고 출발',
            evaluation.proposedDepartAt,
          ),
          _timeRow(
            Icons.history_rounded,
            '과거 평균 기반 참고 도착',
            evaluation.estimatedArrivalAt,
          ),
          const Padding(
            padding: EdgeInsets.only(top: 2, bottom: 4),
            child: Text(
              '과거 자료로 계산한 참고 추정입니다. 현재 운행상황이나 정시 도착을 보장하지 않습니다.',
              style: TextStyle(fontSize: 12, color: kEvaluationFaint),
            ),
          ),
        ],
        if (_hasFoldedTimes) ...[
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              key: const Key('evaluationTimeDetailsToggle'),
              onPressed: () =>
                  setState(() => _showTimeDetails = !_showTimeDetails),
              style: TextButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 4),
                minimumSize: Size.zero,
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                foregroundColor: const Color(0xFF3C73DD),
              ),
              icon: Icon(
                _showTimeDetails
                    ? Icons.expand_less_rounded
                    : Icons.expand_more_rounded,
                size: 18,
              ),
              label: Text(_showTimeDetails ? '자세히 접기' : '자세히'),
            ),
          ),
          if (_showTimeDetails) ...[
            if (!widget.compact) ...[
              _timeRow(
                Icons.edit_calendar_outlined,
                '사용자 입력 출발',
                evaluation.userInputDepartAt,
              ),
              _timeRow(
                Icons.event_note_outlined,
                '현재 계획 출발',
                evaluation.plannedDepartAt,
              ),
            ],
            if (evaluation.targetArriveAt != null)
              _timeRow(
                Icons.sports_score_rounded,
                '평가 목표 도착',
                evaluation.targetArriveAt,
              ),
            if (evaluation.computedAt != null && !expiredOnly)
              _timeRow(Icons.update_rounded, '마지막 확인', evaluation.computedAt),
            if (!widget.compact && diagnostics && evaluation.validUntil != null)
              _timeRow(Icons.timer_outlined, '유효 시각', evaluation.validUntil),
            if (!widget.compact &&
                diagnostics &&
                evaluation.nextEvaluationAt != null)
              _timeRow(
                Icons.schedule_rounded,
                '다음 확인',
                evaluation.nextEvaluationAt,
              ),
          ],
        ],
        const SizedBox(height: 10),
        _sectionLabel('계산 근거'),
        _bullet(
          Icons.directions_walk_rounded,
          _qualityMessage(evaluation.quality),
        ),
        if (evaluation.hasHistoricalReference)
          _bullet(
            Icons.history_rounded,
            '버스 일부 구간에 과거 평균 소요시간을 사용했습니다. 실시간 정보나 ML 예측값이 아닙니다.',
          ),
        if (evaluation.quality.costCoverage != 'COMPLETE')
          _bullet(Icons.timelapse_rounded, '전체 이동시간의 구성요소가 완전히 확인되지 않았습니다.'),
        // 부족한 항목의 건수와 종류는 사용자에게 뜻이 없다(후보 전체 구간을 센 숫자다).
        // 한 문장으로만 알리고, 자세한 목록은 개발자 진단에서 본다(2026-09-18).
        if (evaluation.quality.missingInputs.isNotEmpty)
          _bullet(
            Icons.help_outline_rounded,
            diagnostics
                ? '부족한 정보: ${_groupMissing(evaluation.quality.missingInputs)}'
                : '일부 실시간 정보가 없어 기본 경로 예상시간을 사용했어요.',
          ),
        // 대체 계산 사유는 내부 코드를 옮긴 목록이라 일반 화면에서 뺀다.
        if (!widget.compact &&
            diagnostics &&
            evaluation.quality.fallbackReasons.isNotEmpty)
          _bullet(
            Icons.swap_horiz_rounded,
            '대체 계산 사용: '
            '${evaluation.quality.fallbackReasons.map(_fallbackLabel).toSet().join(', ')}',
          ),
        if (evaluation.decision == DepartureDecision.searchIncomplete)
          _bullet(
            Icons.hourglass_empty_rounded,
            '아직 최신 출발시각을 확정하지 못했어요. 확인된 후보는 참고용입니다.',
          ),
        if (!widget.compact && evaluation.causes.isNotEmpty)
          for (final cause in evaluation.causes)
            _bullet(Icons.report_problem_outlined, _causeMessage(cause)),
        if (!widget.compact && evaluation.legs.isNotEmpty) ...[
          const SizedBox(height: 10),
          _sectionLabel('경로 구간'),
          for (final leg in evaluation.legs) _legRow(leg),
        ],
        if (!widget.compact &&
            !evaluation.canAcceptAt(now) &&
            evaluation.proposedDepartAt != null)
          const Padding(
            padding: EdgeInsets.only(top: 8),
            child: Text(
              '검증·유효성 조건을 충족한 제안만 일정에 적용할 수 있습니다.',
              style: TextStyle(fontSize: 12, color: kEvaluationFaint),
            ),
          ),
        if (!widget.compact && diagnostics)
          EvaluationDiagnosticsPanel(evaluation: evaluation),
      ],
    );
  }

  Widget _sectionLabel(String label) => Padding(
    padding: const EdgeInsets.only(bottom: 6),
    child: Text(
      label,
      style: const TextStyle(
        fontSize: 12,
        fontWeight: FontWeight.w800,
        color: kEvaluationFaint,
        letterSpacing: 0.2,
      ),
    ),
  );

  /// 근거 한 줄. 문구는 그대로 두고 앞에 작은 아이콘만 붙여 목록처럼 읽히게 한다.
  Widget _bullet(IconData icon, String text) => Padding(
    padding: const EdgeInsets.only(bottom: 4),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 2),
          child: Icon(icon, size: 14, color: kEvaluationFaint),
        ),
        const SizedBox(width: 6),
        Expanded(
          child: Text(
            text,
            style: const TextStyle(
              fontSize: 12,
              height: 1.35,
              color: kEvaluationMuted,
            ),
          ),
        ),
      ],
    ),
  );

  /// 경로 구간 한 줄. 수단별 아이콘을 원 안에 넣어 저장된 추천 경로 목록과 같은 결로 맞춘다.
  Widget _legRow(EvaluationRouteLeg leg) => Padding(
    padding: const EdgeInsets.only(bottom: 6),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 26,
          height: 26,
          decoration: const BoxDecoration(
            color: Color(0xFFEAF1F8),
            shape: BoxShape.circle,
          ),
          child: Icon(_legIcon(leg), size: 15, color: DdubiColors.navy),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              _legLabel(leg),
              style: const TextStyle(
                fontSize: 12,
                height: 1.35,
                color: kEvaluationMuted,
              ),
            ),
          ),
        ),
      ],
    ),
  );

  static IconData _legIcon(EvaluationRouteLeg leg) => switch (leg.role) {
    RouteLegRole.accessWalk ||
    RouteLegRole.transferWalk ||
    RouteLegRole.egressWalk => Icons.directions_walk_rounded,
    RouteLegRole.ride =>
      leg.mode == 'BUS' ? Icons.directions_bus_rounded : Icons.subway_rounded,
    RouteLegRole.wait => Icons.hourglass_empty_rounded,
    RouteLegRole.unknown => Icons.route_rounded,
  };

  Widget _timeRow(IconData icon, String label, DateTime? value) => Padding(
    padding: const EdgeInsets.only(bottom: 6),
    child: Row(
      children: [
        Icon(icon, size: 15, color: kEvaluationFaint),
        const SizedBox(width: 6),
        Expanded(
          child: Text(
            label,
            style: const TextStyle(fontSize: 12, color: kEvaluationFaint),
          ),
        ),
        Text(
          value == null ? '확인 불가' : _formatDateTime(value),
          style: const TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w800,
            color: DdubiColors.navy,
          ),
        ),
      ],
    ),
  );

  static String _formatDateTime(DateTime value) {
    final local = value.toLocal();
    return '${local.month}/${local.day} '
        '${local.hour.toString().padLeft(2, '0')}:'
        '${local.minute.toString().padLeft(2, '0')}';
  }

  static String _causeMessage(EvaluationCause cause) {
    final description = cause.description?.trim();
    if (description != null && description.isNotEmpty) return description;

    final effect = cause.effect?.toUpperCase();
    if (cause.confirmed == true && effect == 'BLOCKED') {
      return '이동 경로의 통제가 확인되었습니다.';
    }

    final evidenceLevel = cause.evidenceLevel?.toUpperCase();
    if (evidenceLevel == 'FULL' || evidenceLevel == 'EXACT') {
      return '이동 영향 원인이 확인되었습니다.';
    }
    return '이동 구간에 영향 가능성이 있습니다.';
  }

  static String _stateMessage(EvaluationState state) => switch (state) {
    // 서버가 첫 계산(예산 30초) 중이다. 상한 안내이며 결과가 오면 즉시 바꾼다.
    EvaluationState.pending ||
    EvaluationState.running => ScheduleEvaluationCard.initialComputingMessage,
    EvaluationState.ready => '평가가 완료됐습니다.',
    EvaluationState.failed => '최신 예측을 계산하지 못했습니다.',
    EvaluationState.stopped => '이 일정의 출발 전 감시가 종료됐습니다.',
    EvaluationState.departureRequired => '일정 수정에서 출발 예정시각을 입력해 주세요.',
    EvaluationState.stale => '이전 결과입니다. 최신 결과를 다시 확인해 주세요.',
    EvaluationState.unknown => '평가 상태를 확인하고 있습니다.',
  };

  static String _decisionMessage(
    DepartureDecision decision, {
    bool departureMissed = false,
  }) => switch (decision) {
    DepartureDecision.keepDeparture => '현재 출발 계획을 유지해도 일정 전에 도착할 것으로 예상됩니다.',
    DepartureDecision.advanceDeparture => '제안 시각에 출발하면 일정 10분 전 도착이 예상됩니다.',
    DepartureDecision.departNow =>
      departureMissed
          ? '계획한 출발시각이 지났습니다. 현재 가능한 경로를 확인하세요.'
          : '일정에 맞추려면 지금 출발해야 합니다.',
    DepartureDecision.marginUnavailable => '10분 여유 확보는 어렵지만 일정 전 도착 가능한 경로입니다.',
    DepartureDecision.lateExpected => '현재 확인 가능한 경로로는 일정 시작 이후 도착이 예상됩니다.',
    DepartureDecision.dataInsufficient => '최신 도착 예측에 필요한 정보가 부족합니다.',
    DepartureDecision.routeUnavailable => '현재 이용 가능한 경로를 확인하지 못했습니다.',
    DepartureDecision.searchIncomplete =>
      '제한 시간 안에 탐색을 완료하지 못했습니다. 확인된 대안만 표시합니다.',
    DepartureDecision.unknown => '결과의 의미를 확인 중입니다.',
  };

  static String _qualityMessage(EvaluationQuality quality) {
    final walking = switch (quality.walkingApiCoverage) {
      'BOTH' => '접근·이탈 도보 경로 반영',
      'ACCESS_ONLY' => '접근 도보 경로만 반영',
      'EGRESS_ONLY' => '이탈 도보 경로만 반영',
      _ => '기존 대중교통 도보 정보 사용',
    };
    final realtime = quality.realtimeApplied
        ? '일부 구간에 실시간 정보 반영'
        : '실시간 정보 미적용';
    return '$walking · $realtime';
  }

  static String _groupMissing(List<String> inputs) {
    final groups = <String, int>{};
    for (final input in inputs.toSet()) {
      final label = evaluationMissingInputLabel(input);
      groups[label] = (groups[label] ?? 0) + 1;
    }
    return groups.entries
        .map(
          (entry) =>
              entry.value > 1 ? '${entry.key} (${entry.value}건)' : entry.key,
        )
        .join(', ');
  }

  /// 일반 사용자용 번역. ML 실패와 과거 자료 부족을 같은 문구로 합치지 않는다.
  static String _fallbackLabel(String value) =>
      historicalReferenceReasonLabel(value) ??
      switch (value) {
        _ when value.contains('HISTORICAL_REFERENCE_APPLIED') =>
          '버스 과거 평균 소요시간 적용',
        _ when value.contains('HISTORICAL_REFERENCE_WEATHER_UNKNOWN') =>
          '과거 평균에 현재 날씨 미반영',
        _
            when value.contains('HISTORICAL_REFERENCE_DATA_VERSION') ||
                value.contains('HISTORICAL_REFERENCE_SAMPLE_KIND') =>
          '사용한 과거 자료의 출처 기록',
        _ when value.contains('HISTORICAL_REFERENCE_') =>
          '과거 자료 적용 조건이 맞지 않아 기본 경로시간 유지',
        _ when value.contains('ML_APPLIED') => 'ML 예측 소요시간 적용',
        _ when value.contains('ML_TEST_APPLIED') => '검증 중인 ML 예측값을 시험 적용',
        _ when value.contains('ML_SHADOW_RECORDED') => 'ML 예측값은 기록만 하고 미사용',
        _
            when value.contains('ML_NOT_APPLIED:ML_BUDGET_EXHAUSTED') ||
                value.contains('ML_BUDGET_EXHAUSTED') =>
          '계산 시간이 부족해 ML 예측 미적용',
        _
            when value.contains('ML_NOT_APPLIED:ML_TIMEOUT') ||
                value.contains('ML_TIMEOUT') =>
          'ML 응답 지연으로 예측 미적용',
        _ when value.contains('ML_NOT_APPLIED') => 'ML 예측 미적용',
        _
            when value.contains('ML_MODEL_VERSION') ||
                value.contains('ML_MIN_HISTORY_SAMPLES') ||
                value.contains('ML_MAX_MISSING_FEATURES') ||
                value.contains('ML_HISTORY_INSUFFICIENT') =>
          'ML 예측 근거 기록',
        _ when value.endsWith('API_FALLBACK') => '경로 API 원본 소요시간 사용',
        'EGRESS_ENDPOINT_UNVERIFIED' ||
        'EGRESS_WALK_FALLBACK' => '이탈 도보는 기존 경로 정보 사용',
        'MODEL_VALIDATION_ONLY' || 'VALIDATION_ONLY' => '검증 중인 모델 결과',
        _ => '일부 구간은 기본 경로 정보 사용',
      };

  static String _legLabel(EvaluationRouteLeg leg) {
    final role = switch (leg.role) {
      RouteLegRole.accessWalk => '접근 도보',
      RouteLegRole.transferWalk => '환승 도보',
      RouteLegRole.egressWalk => '이탈 도보',
      RouteLegRole.ride => leg.mode == 'BUS' ? '버스 탑승' : '지하철 탑승',
      RouteLegRole.wait => '대기',
      RouteLegRole.unknown => '이동 구간',
    };
    final source = durationSourceLabel(leg.durationSource);
    final endpoints = [
      leg.fromName,
      leg.toName,
    ].whereType<String>().where((value) => value.isNotEmpty).join(' → ');
    final duration = leg.durationSeconds == null
        ? ''
        : ' · ${(leg.durationSeconds! / 60).ceil()}분';
    return '$role${endpoints.isEmpty ? '' : ' · $endpoints'}$duration · $source';
  }
}
