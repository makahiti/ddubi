import 'dart:async';

import 'package:flutter/material.dart';

import '../core/theme.dart';
import '../domain/recommendation_guide.dart';
import '../domain/schedule_evaluation.dart';
import '../services/recommendation_api.dart';

/// 추천 카드 안내.
///
/// 서버가 계산한 사실을 짧은 한국어로 바꿔 보여준다. 숫자와 순서는 모두 서버 값이고
/// 앱은 계산하지 않는다.
///
/// <h3>언제 다시 부르나</h3>
/// 평가가 READY 이고 `evaluationId` 가 바뀌었을 때만 부른다. 화면이 다시 그려질 때마다
/// 부르면 서버가 매번 문장을 새로 만들게 되므로, 같은 계산에는 한 번만 부른다.
class RecommendationGuideCard extends StatefulWidget {
  const RecommendationGuideCard({
    super.key,
    required this.api,
    required this.scheduleId,
    required this.headline,
    this.evaluationRepository,
    this.onStartJourney,
    this.pastTense = false,
  });

  final RecommendationApi api;
  final String scheduleId;

  /// 끝난 일정의 기록으로 보여줄 때 true. 말머리가 과거형이 된다. 버튼은 [evaluationRepository]
  /// 를 넘기지 않으면 어차피 그려지지 않는다.
  final bool pastTense;

  /// 버튼 줄(이 제안 적용·다시 계산·이동 안내 시작)을 쓰려면 필요하다. 없으면 버튼을
  /// 그리지 않는다.
  ///
  /// 예전에는 이 버튼들이 "이동 예측" 카드에 있었다. 결론을 읽은 자리에서 바로 누를 수
  /// 있어야 해서 안내 카드로 옮겼다(2026-09-22).
  final ScheduleEvaluationRepository? evaluationRepository;

  /// 이동 안내 화면을 여는 통로. 일정 상세가 선택된 경로를 알고 있으므로 거기서 넘겨준다.
  /// 없으면 그 버튼을 그리지 않는다.
  final Future<void> Function()? onStartJourney;

  /// 평가 결과를 나르는 통로. 여기서 상태와 evaluationId 를 본다.
  final ValueNotifier<ScheduleEvaluationV2?> headline;

  @override
  State<RecommendationGuideCard> createState() =>
      _RecommendationGuideCardState();
}

class _RecommendationGuideCardState extends State<RecommendationGuideCard> {
  RecommendationGuide? _guide;

  /// 마지막으로 불러온 계산의 버전. 출발 후에는 평가 ID가 같아도 실제 출발
  /// 재계산 시각이 달라지므로 별도 버전으로 취급한다.
  String? _loadedFor;

  /// 조회에 실패한 계산의 id. 값이 있으면 "다시 불러오기" 를 띄운다.
  String? _retryFor;
  bool _loading = false;

  /// 마지막 조회가 "실패" 가 아니라 "아직" 이었는가 — 서버가 계산이 끝나지 않았다고(409) 답한 경우.
  /// 이때는 사용자가 할 일이 없다. 기다리면 새 평가와 함께 다시 부른다.
  bool _pending = false;
  Timer? _pendingRetry;
  int _pendingAttempts = 0;
  String? _pendingVersion;

  /// 화면에 보이는 안내가 최신 계산의 것이 아닌가.
  ///
  /// 안내는 있는데 마지막 조회가 실패했다는 뜻이다. 이때 카드를 비우지 않고 "이전 계산
  /// 기준" 이라고 적는다 — 지우면 1분마다 빈 카드가 되고, 그냥 두면 지난 값이 최신인 척한다.
  bool get _stale => _guide != null && _retryFor != null;

  @override
  void initState() {
    super.initState();
    widget.headline.addListener(_onEvaluation);
    _onEvaluation();
  }

  @override
  void dispose() {
    _pendingRetry?.cancel();
    widget.headline.removeListener(_onEvaluation);
    super.dispose();
  }

  void _onEvaluation() {
    final id = _versionFor(widget.headline.value);
    if (id == null) return;
    if (id != _pendingVersion) {
      _pendingRetry?.cancel();
      _pendingAttempts = 0;
      _pendingVersion = id;
    }
    if (id == _loadedFor || _loading) return;
    _load(id);
  }

  String? _versionFor(ScheduleEvaluationV2? value) {
    // 멈춘(출발한) 일정도 부른다. 서버가 마지막 계산의 안내를 그대로 돌려주므로(2026-09-22),
    // 출발한 뒤에도 출발할 때 본 AI 안내가 카드에 남는다. 앱을 다시 켜도 같다.
    if (value == null ||
        (value.state != EvaluationState.ready &&
            value.state != EvaluationState.stopped)) {
      return null;
    }
    final evaluationId = value.evaluationId;
    if (evaluationId == null || evaluationId.isEmpty) return null;
    return value.state == EvaluationState.stopped
        ? '$evaluationId:departed:${value.computedAt?.toIso8601String()}'
        : evaluationId;
  }

  /// 첫 안내 조회가 409/404로 끝나도 같은 evaluationId를 다시 확인한다. 평가 값은
  /// 그대로인 채 안내만 늦게 준비될 수 있으므로 새 평가 알림만 기다리면 영원히 빈 카드다.
  void _retryPending(String id) {
    _pendingRetry?.cancel();
    if (_pendingAttempts >= 10) return;
    _pendingAttempts++;
    _pendingRetry = Timer(const Duration(seconds: 3), () {
      if (!mounted || _pendingVersion != id || _loading || _loadedFor == id) {
        return;
      }
      _load(id);
    });
  }

  Future<void> _load(String evaluationId) async {
    // 지난 안내를 지우지 않고 그대로 둔 채 새로 부른다.
    //
    // 한때는 여기서 _guide 를 비웠다. 지난 계산의 경로·출발시각·요금이 새 결과인 척
    // 남는 것을 막으려던 것이다(BE 지적, 2026-09-22). 그런데 서버는 출발이 가까우면
    // 1분마다 계산을 새로 하고, 안내 조회는 계산이 끝나기 전이거나 유효기간(=다음 계산
    // 예정 시각)이 지난 동안에는 반드시 거절한다. 즉 매 주기마다 반드시 실패하는 구간이
    // 있다. 그래서 카드가 [보임 → 빈칸 → 못 불러왔어요 → 보임] 을 1분마다 반복했다
    // (2026-09-22 제보).
    //
    // 빈 카드도 틀린 카드도 아닌 세 번째 답을 쓴다 — 지난 안내를 그대로 두되, 그것이
    // 최신 계산의 것이 아님을 카드 위에 적는다. 판단은 _stale 이 한다.
    setState(() => _loading = true);
    try {
      final guide = await widget.api.getLatestGuide(widget.scheduleId);
      if (!mounted) return;
      setState(() {
        if (guide != null) {
          _pendingRetry?.cancel();
          _guide = guide;
          _loadedFor = evaluationId;
          _retryFor = null;
          _pending = false;
        } else {
          // 계산이 아직이거나(409) 안내가 아직 없다(404). 실패가 아니라 "아직" 이다 — 계산이
          // 끝나면 평가가 새로 오고 그때 다시 부른다. 그동안 "못 불러왔어요" 라고 쓰지 않는다
          // (2026-09-22 제보).
          _retryFor = evaluationId;
          _pending = true;
          _retryPending(evaluationId);
        }
      });
    } on Object {
      // 연결이 끊겼거나 서버가 오류를 냈다. 안내를 못 받아도 화면의 다른 정보는 그대로 쓸 수 있다.
      if (mounted) {
        setState(() {
          _retryFor = evaluationId;
          _pending = false;
        });
      }
    } finally {
      if (mounted) {
        setState(() => _loading = false);
        // 조회 중 새 평가가 도착했다면 그 버전의 안내를 이어서 불러온다.
        if (_versionFor(widget.headline.value) != evaluationId) {
          _onEvaluation();
        }
      }
    }
  }

  /// 다시 불러본다. 실패했던 계산에 대해서만 동작한다.
  ///
  /// 안내는 화면의 곁가지라 자동으로 계속 조르지 않는다. 사용자가 원할 때만 부른다.
  Future<void> _retry() async {
    final id = _retryFor;
    if (id == null || _loading) return;
    await _load(id);
  }

  @override
  Widget build(BuildContext context) {
    final guide = _guide;
    return Container(
      key: const Key('recommendationGuideCard'),
      padding: const EdgeInsets.fromLTRB(14, 14, 14, 14),
      decoration: BoxDecoration(
        // 예전 "이 일정에 맞춰" 카드의 모양을 그대로 쓴다. 두 카드가 나란히 있으면서
        // 하나는 비어 있고 하나는 흰 카드라 따로 놀았다. 한 장으로 합친다.
        gradient: const LinearGradient(
          colors: [Color(0xFFE1FAF5), Color(0xFFF1FBE8)],
        ),
        borderRadius: BorderRadius.circular(19),
        border: Border.all(color: const Color(0xFFC7EEDC)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Image.asset(
                'assets/images/ttubee/running.png',
                width: 62,
                height: 56,
                fit: BoxFit.contain,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // 이 안내는 "지금 선택된 경로" 가 아니라 "가장 최근 계산 결과" 를 설명한다.
                    // 말머리를 달지 않으면 바로 위 경로 카드와 같은 이야기로 읽힌다.
                    Container(
                      key: const Key('guideScopeLabel'),
                      padding: const EdgeInsets.symmetric(
                        horizontal: 10,
                        vertical: 4,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.white,
                        borderRadius: BorderRadius.circular(14),
                      ),
                      child: Text(
                        // 끝난 일정에서는 과거형이다. 다녀온 일정에 "이 일정에 맞춰 … 제안해요" 가
                        // 현재형으로 남아 있으면 아직 할 일이 있는 것처럼 읽힌다.
                        widget.pastTense
                            ? '이 일정에 맞춰 안내했어요'
                            : guide?.generatedBy?.startsWith('GMS:') == true
                            ? 'AI 교통 안내'
                            : '교통 상황 안내',
                        style: const TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.w800,
                          color: Color(0xFF2F4A7D),
                        ),
                      ),
                    ),
                    const SizedBox(height: 8),
                    ..._summary(guide),
                  ],
                ),
              ),
            ],
          ),
          if (guide != null && guide.delay.hasMessage) ...[
            const SizedBox(height: 12),
            _detailBox(guide),
          ],
          if (widget.evaluationRepository != null) ...[
            const SizedBox(height: 12),
            const Divider(height: 1, color: Color(0xFFC7EEDC)),
            const SizedBox(height: 12),
            _GuideActions(
              repository: widget.evaluationRepository!,
              scheduleId: widget.scheduleId,
              headline: widget.headline,
              onStartJourney: widget.onStartJourney,
            ),
          ],
        ],
      ),
    );
  }

  /// 카드 위쪽 한 문단. 안내가 없어도 카드를 비우지 않는다.
  ///
  /// 예전에는 안내를 못 받으면 카드가 통째로 사라졌다. 이제 같은 카드에 버튼이 함께
  /// 있어서, 사라지면 누를 자리까지 없어진다.
  List<Widget> _summary(RecommendationGuide? guide) {
    if (guide == null) {
      return [
        Text(
          widget.headline.value == null
              ? '교통 상황과 변경 이유를 확인하고 있어요.'
              : _loading
              ? '교통 상황 안내를 불러오고 있어요.'
              : _pending
              ? '계산 결과를 확인해 안내를 준비하고 있어요.'
              : '교통 상황 안내를 아직 불러오지 못했어요.',
          key: const Key('guideEmptyMessage'),
          style: const TextStyle(
            fontSize: 15,
            height: 1.35,
            fontWeight: FontWeight.w900,
            color: DdubiColors.navy,
          ),
        ),
        if (!_loading && _pending) ...[
          const SizedBox(height: 4),
          const Text(
            '계산이 끝나면 자동으로 이어서 불러와요.',
            key: Key('guidePendingHint'),
            style: TextStyle(fontSize: 12, color: Color(0xFF5C6B7F)),
          ),
        ],
        // 진짜 실패(연결·서버 오류)일 때만 다시 누르게 한다. "아직" 인 경우에 누르면 또 409 다.
        if (!_loading && !_pending && _retryFor != null) ...[
          const SizedBox(height: 6),
          TextButton(
            key: const Key('guideRetryButton'),
            onPressed: _retry,
            style: TextButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              minimumSize: const Size(0, 36),
              backgroundColor: Colors.white,
              foregroundColor: const Color(0xFF00665F),
            ),
            child: const Text('다시 불러오기'),
          ),
        ],
      ];
    }
    return [
      // 아래 내용이 최신 계산의 것이 아닐 때만 붙는다. 사용자가 지난 값을 방금 결과로
      // 믿지 않게 하는 것이 목적이라, 숨기지 않고 제일 위에 적는다.
      if (_stale) ...[
        Row(
          key: const Key('guideStaleNotice'),
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(
              Icons.history_rounded,
              size: 14,
              color: Color(0xFF5C6B7F),
            ),
            const SizedBox(width: 4),
            Expanded(
              child: Text(
                _loading
                    ? '새 계산을 반영하는 중이에요. 아래는 이전 계산 기준이에요.'
                    : '아래는 이전 계산 기준이에요.',
                style: const TextStyle(
                  fontSize: 12,
                  height: 1.3,
                  color: Color(0xFF5C6B7F),
                ),
              ),
            ),
            if (!_loading)
              TextButton(
                key: const Key('guideRefreshStaleButton'),
                onPressed: _retry,
                style: TextButton.styleFrom(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 8,
                    vertical: 2,
                  ),
                  minimumSize: const Size(0, 28),
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  foregroundColor: const Color(0xFF00665F),
                ),
                child: const Text('새로 보기', style: TextStyle(fontSize: 12)),
              ),
          ],
        ),
        const SizedBox(height: 8),
      ],
      if (_changeLabel(guide.changeType) != null) ...[
        _changeBadge(guide.changeType!),
        const SizedBox(height: 8),
      ],
      // 구형 서버가 사유 없이 출발·도착 숫자만 주더라도 그것을 다시 읽어 주지 않는다.
      // 현재 계산에서 실제로 확인된 변경 유형 또는 지연 사유만 설명한다.
      Text(
        _reason(guide),
        key: guide.recommendationReason != null
            ? const Key('guideRecommendationReason')
            : const Key('guideFallbackLine'),
        style: const TextStyle(
          fontSize: 16,
          height: 1.35,
          fontWeight: FontWeight.w900,
          color: DdubiColors.navy,
        ),
      ),
    ];
  }

  static String _reason(RecommendationGuide guide) {
    if (guide.recommendationReason != null &&
        !_looksLikeRouteNarration(guide.recommendationReason!)) {
      return guide.recommendationReason!;
    }
    // 구형 서버 캐시나 GMS 응답이 경로를 화살표로 다시 나열해도, 바로 아래의
    // 구간별 목록과 중복되지 않게 상황·변경 이유만 보여준다.
    final impact = guide.delay.hasMessage;
    return switch (guide.changeType) {
      'ROUTE_CHANGED' =>
        impact
            ? '확인된 교통 영향을 반영해 다른 경로를 제안해요.'
            : '새 계산에서 다른 경로를 제안해요. 변경 이유를 확인하고 있어요.',
      'DEPARTURE_TIME_CHANGED' =>
        impact
            ? '확인된 교통 영향을 반영해 출발시각을 다시 제안해요.'
            : '새 계산에서 출발시각이 바뀌었어요. 변경 이유를 확인하고 있어요.',
      'ETA_UPDATED' =>
        impact
            ? '교통 영향을 반영하면서 도착 예상이 달라졌어요.'
            : '새 계산에서 도착 예상이 바뀌었어요. 변경 이유를 확인하고 있어요.',
      'NO_CHANGE' when impact => '교통 영향은 확인됐지만 현재 경로와 출발시각은 유지해요.',
      _ =>
        impact
            ? '교통 영향 정보를 확인했어요. 자세한 내용은 아래에서 볼 수 있어요.'
            : '확인된 교통 영향이나 추천 변경은 없어요. 새 정보가 들어오면 다시 살펴볼게요.',
    };
  }

  static bool _looksLikeRouteNarration(String value) =>
      value.contains('→') ||
      (value.contains('탑승 후') && value.contains('하차') && value.contains('에서'));

  /// 중복되는 경로·시각·요금은 아래 전용 카드에 맡긴다. 여기에는 확인된
  /// 교통 영향과 변경 이유만 남겨 AI 안내의 목적을 분명히 한다.
  Widget _detailBox(RecommendationGuide guide) => Container(
    padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: 0.72),
      borderRadius: BorderRadius.circular(14),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _delayRow(guide.delay),
        if (guide.dataAsOf != null) ...[
          const SizedBox(height: 10),
          Text(
            '확인한 자료 기준 ${_clock(guide.dataAsOf!)}',
            key: const Key('guideDataAsOf'),
            style: const TextStyle(fontSize: 11, color: Color(0xFF6B7A8C)),
          ),
        ],
      ],
    ),
  );

  /// 서버가 확정한 변경 유형을 사람 말로 바꾼다.
  ///
  /// 모르는 값이면 null 을 돌려주고 아무것도 그리지 않는다. 서버가 나중에 유형을
  /// 늘렸을 때 앱이 영어 코드를 그대로 보여주는 일이 없어야 한다.
  static String? _changeLabel(String? changeType) => switch (changeType) {
    'ROUTE_CHANGED' => '다른 경로 제안',
    'DEPARTURE_TIME_CHANGED' => '출발시각 변경 제안',
    'ETA_UPDATED' => '도착 예상 변경',
    'NO_CHANGE' => '지금 경로 유지',
    _ => null,
  };

  /// 유형별 색. 바꾸라는 제안은 눈에 띄게, 유지는 차분하게.
  static (Color, Color) _changeColors(String changeType) =>
      switch (changeType) {
        'ROUTE_CHANGED' || 'DEPARTURE_TIME_CHANGED' => (
          const Color(0xFFFFF3E6),
          const Color(0xFFB45309),
        ),
        'NO_CHANGE' => (const Color(0xFFE9F7EF), const Color(0xFF13703F)),
        _ => (const Color(0xFFEEF3FA), const Color(0xFF3A5A87)),
      };

  Widget _changeBadge(String changeType) {
    final (background, foreground) = _changeColors(changeType);
    return Container(
      key: const Key('guideChangeType'),
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        _changeLabel(changeType)!,
        style: TextStyle(
          fontSize: 11.5,
          fontWeight: FontWeight.w800,
          color: foreground,
        ),
      ),
    );
  }

  /// 지연 안내. 확인된 이유가 있을 때만 그린다 — 분 단위 숫자가 없으면 만들어내지 않는다.
  Widget _delayRow(GuideDelay delay) {
    final tone = _delayTone(delay.status);
    return Container(
      key: const Key('guideDelay'),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: tone.withValues(alpha: .10),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.warning_amber_rounded, size: 17, color: tone),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  delay.reason!,
                  style: TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w700,
                    color: tone,
                  ),
                ),
                if (delay.source != null)
                  Text(
                    delay.source!,
                    style: const TextStyle(
                      fontSize: 11,
                      color: Color(0xFF8A99A8),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  static Color _delayTone(String status) => switch (status) {
    'POSSIBLE' => const Color(0xFFE0A100),
    'DELAYED' => const Color(0xFFFF6B35),
    'DISRUPTED' => DdubiColors.red,
    _ => const Color(0xFF7F91A2),
  };

  static String _clock(DateTime value) {
    final local = value.toLocal();
    return '${local.hour.toString().padLeft(2, '0')}:'
        '${local.minute.toString().padLeft(2, '0')}';
  }
}

/// 안내 카드 아래쪽 버튼 줄.
///
/// 예전에는 "이동 예측" 카드에 있었다. 사용자가 결론을 읽는 자리와 누르는 자리가 멀어서
/// 안내 카드 안으로 옮겼다(2026-09-22). 이동 예측 카드는 나중에 없앨 예정이다.
///
/// 계산 상태는 [headline] 이 나르는 평가 결과로 판단한다. 따로 조회하지 않는다 —
/// 같은 값을 두 곳에서 읽으면 화면 안에서 서로 다른 상태가 보인다.
class _GuideActions extends StatefulWidget {
  const _GuideActions({
    required this.repository,
    required this.scheduleId,
    required this.headline,
    this.onStartJourney,
  });

  final ScheduleEvaluationRepository repository;
  final String scheduleId;
  final ValueNotifier<ScheduleEvaluationV2?> headline;

  /// 이동 안내 화면을 여는 통로. 없으면 그 버튼을 그리지 않는다 — 열어 줄 경로가 없는
  /// 화면에서 눌리지 않는 버튼을 보여 주지 않는다.
  final Future<void> Function()? onStartJourney;

  @override
  State<_GuideActions> createState() => _GuideActionsState();
}

class _GuideActionsState extends State<_GuideActions> {
  bool _busy = false;
  String? _notice;

  /// 지금 띄운 문구가 어느 계산에 대한 것인가. 계산이 바뀌면 문구를 지운다.
  ///
  /// 예전에는 한 번 뜬 오류 문구가 다음 버튼을 누를 때까지 사라지지 않았다. 카드 내용은
  /// 1분마다 새로 바뀌는데 그 아래 오류만 그대로 남아, 다 지난 실패가 지금 고장난 것처럼
  /// 읽혔다(2026-09-22 제보).
  String? _noticeFor;

  @override
  void initState() {
    super.initState();
    widget.headline.addListener(_onEvaluation);
  }

  @override
  void dispose() {
    widget.headline.removeListener(_onEvaluation);
    super.dispose();
  }

  void _onEvaluation() {
    if (_notice == null || !mounted) return;
    final id = widget.headline.value?.evaluationId;
    if (id == null || id == _noticeFor) return;
    setState(() {
      _notice = null;
      _noticeFor = null;
    });
  }

  void _say(String? message) {
    if (!mounted) return;
    setState(() {
      _notice = message;
      _noticeFor = widget.headline.value?.evaluationId;
    });
  }

  /// 방금 누른 결과를 서버에서 다시 읽어 화면에 반영한다.
  ///
  /// 이게 없으면 버튼이 성공해도 카드가 그대로였다. 평가 카드가 30초마다 도는 다음
  /// 차례까지 기다려야 바뀌어서, 눌러도 아무 일도 안 일어난 것처럼 보였다(2026-09-22 제보).
  Future<void> _reload() async {
    try {
      final latest = await widget.repository.latestEvaluation(
        widget.scheduleId,
      );
      if (mounted) widget.headline.value = latest;
    } on Object {
      // 다시 읽기 실패는 알리지 않는다. 방금 누른 동작 자체는 성공했고 카드 폴링이 곧
      // 같은 값을 가져온다. 여기서 오류를 띄우면 성공을 실패로 잘못 알리게 된다.
    }
  }

  Future<void> _run(Future<void> Function() action, {String? done}) async {
    setState(() {
      _busy = true;
      _notice = null;
    });
    try {
      await action();
      await _reload();
      _say(done);
    } on Object catch (failure) {
      _say(failureMessage(failure));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// "이 제안 적용".
  ///
  /// 버튼이 들고 있던 계산 번호를 그대로 보내면 거의 실패한다 — 출발이 가까우면 서버가
  /// 1분마다 계산을 새로 하고, 수락은 최신 번호와 같을 때만 통과한다. 안내를 읽고 누르는
  /// 그 사이에 이미 번호가 달라져 있다(2026-09-22 제보). 그래서 누른 순간에 최신 계산을
  /// 다시 읽어 그 번호로 보낸다.
  ///
  /// 대신 사용자가 읽지 않은 제안을 대신 적용하지는 않는다. 그 사이 제안 시각이 바뀌었으면
  /// 화면만 새 값으로 바꾸고, 확인한 뒤 다시 누르게 한다.
  Future<void> _accept(DateTime shown) async {
    setState(() {
      _busy = true;
      _notice = null;
    });
    try {
      final latest = await widget.repository.latestEvaluation(
        widget.scheduleId,
      );
      if (mounted) widget.headline.value = latest;
      final id = latest.evaluationId;
      final proposed = latest.proposedDepartAt;
      if (id == null ||
          id.isEmpty ||
          proposed == null ||
          !latest.canAcceptAt(DateTime.now())) {
        _say('지금은 이 제안을 적용할 수 없어요. 계산이 끝난 뒤 다시 눌러 주세요.');
        return;
      }
      if (proposed != shown) {
        _say('제안한 출발 시각이 바뀌었어요. 새 시각을 확인하고 다시 눌러 주세요.');
        return;
      }
      await widget.repository.acceptEvaluation(widget.scheduleId, id);
      await _reload();
      _say('제안한 출발 시각을 적용했어요.');
    } on Object catch (failure) {
      _say(failureMessage(failure));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 실패 이유를 나눠 적는다.
  ///
  /// 무엇이 잘못됐든 "요청을 처리하지 못했어요" 하나만 보여 주면 사용자는 다시 눌러야
  /// 할지 기다려야 할지 알 수 없다. 특히 계산이 바뀌어 거절된 경우는 할 일이 분명하므로
  /// 그대로 적는다.
  @visibleForTesting
  static String failureMessage(Object failure) {
    if (failure is! ScheduleEvaluationFailure) {
      return '요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.';
    }
    return switch (failure.kind) {
      EvaluationFailureKind.staleEvaluation =>
        '계산이 방금 새로 됐어요. 바뀐 제안을 확인하고 다시 눌러 주세요.',
      // 서버가 이유를 문장으로 준다(예: 검증 중인 제안은 적용할 수 없다).
      EvaluationFailureKind.validation => failure.message,
      EvaluationFailureKind.authentication => '로그인이 만료됐어요. 다시 로그인한 뒤 시도해 주세요.',
      EvaluationFailureKind.notFound => '이 일정의 계산을 찾지 못했어요. 다시 계산해 주세요.',
      EvaluationFailureKind.rateLimited => '요청이 너무 잦아요. 잠시 뒤 다시 눌러 주세요.',
      EvaluationFailureKind.dependencyUnavailable =>
        '지금은 서버가 응답하지 못해요. 잠시 뒤 다시 시도해 주세요.',
      EvaluationFailureKind.network => '연결이 불안정해 요청을 보내지 못했어요.',
      _ => '요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    };
  }

  @override
  Widget build(
    BuildContext context,
  ) => ValueListenableBuilder<ScheduleEvaluationV2?>(
    valueListenable: widget.headline,
    builder: (context, evaluation, _) {
      // 끝난 안내·입력 대기 상태에서는 다시 계산이 의미가 없다.
      final active =
          evaluation != null &&
          evaluation.state != EvaluationState.stopped &&
          evaluation.state != EvaluationState.departureRequired;
      final computing =
          evaluation?.state == EvaluationState.pending ||
          evaluation?.state == EvaluationState.running;
      final proposed = evaluation?.proposedDepartAt;
      final canAccept =
          evaluation != null &&
          proposed != null &&
          evaluation.canAcceptAt(DateTime.now());
      final departed = evaluation?.actualDepartAt != null;
      final journey = widget.onStartJourney;

      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (proposed != null)
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                key: const Key('acceptEvaluationButton'),
                onPressed: _busy || !canAccept ? null : () => _accept(proposed),
                icon: const Icon(Icons.check_rounded, size: 18),
                label: const Text('이 제안 적용'),
              ),
            ),
          if (proposed != null && (active || journey != null))
            const SizedBox(height: 8),
          Row(
            children: [
              if (active)
                Expanded(
                  child: OutlinedButton.icon(
                    key: const Key('refreshEvaluationButton'),
                    onPressed: _busy || computing
                        ? null
                        : () => _run(
                            () => widget.repository.refreshEvaluation(
                              widget.scheduleId,
                            ),
                          ),
                    style: OutlinedButton.styleFrom(
                      backgroundColor: Colors.white.withValues(alpha: 0.85),
                    ),
                    icon: const Icon(Icons.refresh_rounded, size: 18),
                    label: const Text('다시 계산'),
                  ),
                ),
              if (active && journey != null) const SizedBox(width: 8),
              // 출발 기록은 여기서 하지 않는다. 이 버튼은 이동 안내 화면을 열기만 하고,
              // "출발했어요" 는 그 화면 한 곳에만 둔다.
              //
              // 예전에는 여기에도 "출발했어요" 가 있었다. 같은 이름인데 하는 일이 달랐다 —
              // 여기 것은 기록만 하고 화면이 그대로였고, 이동 안내 화면의 것은 기록한 뒤
              // 이동 중으로 넘어갔다. 사용자는 어느 쪽이 진짜인지 알 수 없었다
              // (2026-09-22 제보). 다음 단계로 가는 문을 하나만 남긴다.
              //
              // 이 버튼은 active 바깥에 둔다. 출발을 기록하면 평가가 멈춰 active 가 false 가
              // 되는데, 그때 이 버튼까지 사라지면 이동 안내로 돌아갈 길이 끊긴다.
              if (journey != null)
                Expanded(
                  child: OutlinedButton.icon(
                    key: const Key('startJourneyButton'),
                    onPressed: _busy ? null : () => journey(),
                    style: OutlinedButton.styleFrom(
                      backgroundColor: Colors.white.withValues(alpha: 0.85),
                      foregroundColor: const Color(0xFF2F4A7D),
                      side: const BorderSide(color: Color(0xFFB9CBE8)),
                    ),
                    icon: const Icon(Icons.directions_run_rounded, size: 18),
                    label: Text(departed ? '이동 안내 이어보기' : '이동 안내 시작'),
                  ),
                ),
            ],
          ),
          if (computing && !_busy) ...[
            const SizedBox(height: 6),
            const Text(
              '지금 계산 중이라 끝난 뒤에 다시 계산할 수 있어요.',
              key: Key('guideRefreshLocked'),
              style: TextStyle(fontSize: 12, color: Color(0xFF5C6B7F)),
            ),
          ],
          if (_notice != null) ...[
            const SizedBox(height: 6),
            Text(
              _notice!,
              key: const Key('guideActionNotice'),
              style: const TextStyle(fontSize: 12, color: Color(0xFF2F4A7D)),
            ),
          ],
        ],
      );
    },
  );
}
