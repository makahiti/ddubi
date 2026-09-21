import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/theme.dart';
import '../../domain/models.dart';
import '../../services/app_api.dart';
import '../../services/recommendation_api.dart';
import '../widgets.dart';
import '../departure_headline.dart';
import '../schedule_evaluation_card.dart';
import '../../domain/schedule_evaluation.dart';
import '../../domain/saved_route_display.dart';
import '../../domain/transit_reference.dart';
import '../transit_reference_panel.dart';
import '../../core/diagnostics/developer_diagnostics.dart';
import 'map.dart';
import 'schedule_form.dart';
import 'shared.dart';

class ScheduleDetailScreen extends StatelessWidget {
  const ScheduleDetailScreen({
    super.key,
    required this.repository,
    required this.routeRepository,
    this.recommendationApi,
    required this.scheduleId,
    required this.arrivalApi,
    this.initialSchedule,
    this.initialRoutes,
  });
  final ScheduleRepository repository;
  final DdubiRepository routeRepository;
  final RecommendationApi? recommendationApi;
  final String scheduleId;
  final ArrivalApi arrivalApi;
  final Schedule? initialSchedule;
  final List<RouteOption>? initialRoutes;

  @override
  Widget build(BuildContext c) => FutureBuilder<Schedule?>(
    future: _loadSchedule(),
    builder: (context, snapshot) {
      if (snapshot.hasError) {
        return const AppPage(
          title: '일정 상세',
          child: DataLoadError(message: '일정을 불러오지 못했어요.'),
        );
      }
      if (snapshot.connectionState != ConnectionState.done) {
        return const AppPage(
          title: '일정 상세',
          child: Center(child: CircularProgressIndicator()),
        );
      }
      final schedule = snapshot.data;
      if (schedule == null) {
        return const AppPage(
          title: '일정 상세',
          child: DataLoadError(message: '해당 일정을 찾을 수 없어요.'),
        );
      }
      return FutureBuilder<_ScheduleRecommendationData>(
        future: _loadRecommendation(schedule),
        builder: (context, recommendationSnapshot) {
          if (!recommendationSnapshot.hasData &&
              !recommendationSnapshot.hasError) {
            return const AppPage(
              title: '일정 상세',
              child: Center(child: CircularProgressIndicator()),
            );
          }
          final data = recommendationSnapshot.data;
          return _content(
            context,
            schedule,
            data?.route,
            data?.overview,
            data?.savedRoutes ?? const [],
            recommendationUnavailable:
                recommendationSnapshot.hasError ||
                data?.recommendationUnavailable == true,
            routesUnavailable: data?.routesUnavailable == true,
          );
        },
      );
    },
  );

  Future<Schedule?> _loadSchedule() async {
    if (initialSchedule != null) return initialSchedule;
    try {
      return await repository.scheduleById(scheduleId);
    } on Object {
      final schedules = await repository.schedules();
      for (final schedule in schedules) {
        if (schedule.id == scheduleId) return schedule;
      }
      return null;
    }
  }

  Future<_ScheduleRecommendationData> _loadRecommendation(
    Schedule schedule,
  ) async {
    if (initialRoutes != null && initialRoutes!.isNotEmpty) {
      return _ScheduleRecommendationData(route: initialRoutes!.first);
    }
    if (recommendationApi == null) {
      return const _ScheduleRecommendationData();
    }
    RecommendationOverview? overview;
    var recommendationUnavailable = false;
    try {
      overview = await recommendationApi!.getLatestOverview(schedule.id);
    } on Object {
      recommendationUnavailable = true;
    }
    List<SavedRouteSummary> savedRoutes = const [];
    var routesUnavailable = false;
    try {
      savedRoutes = await recommendationApi!.getSavedRoutes(schedule.id);
    } on Object {
      routesUnavailable = true;
    }
    final selectedSavedRoute = savedRoutes.where((route) => route.selected);
    return _ScheduleRecommendationData(
      overview: overview,
      route: overview?.hasEstimate == true
          ? overview!.toRouteOption(schedule)
          : selectedSavedRoute.isNotEmpty
          ? selectedSavedRoute.first.toRouteOption(schedule)
          : null,
      savedRoutes: savedRoutes,
      recommendationUnavailable: recommendationUnavailable,
      routesUnavailable: routesUnavailable,
    );
  }

  Widget _content(
    BuildContext c,
    Schedule schedule,
    RouteOption? route,
    RecommendationOverview? overview,
    List<SavedRouteSummary> savedRoutes, {
    bool recommendationUnavailable = false,
    bool routesUnavailable = false,
  }) {
    return AppPage(
      title: '일정 상세',
      child: EvaluationHeadlineScope(
        builder: (headline) => ListView(
          key: const Key('scheduleRouteOverview'),
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
          children: [
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(18),
                border: Border.all(color: const Color(0xFFD5E6FA)),
              ),
              child: Row(
                children: [
                  Container(
                    width: 58,
                    height: 58,
                    decoration: BoxDecoration(
                      color: const Color(0xFFE9F1FF),
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: const Icon(
                      Icons.work_rounded,
                      color: Color(0xFF3C73DD),
                      size: 30,
                    ),
                  ),
                  const SizedBox(width: 13),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          schedule.title,
                          style: const TextStyle(
                            fontSize: 19,
                            fontWeight: FontWeight.w900,
                            color: DdubiColors.navy,
                          ),
                        ),
                        const SizedBox(height: 5),
                        Text(
                          '${formatFullDate(schedule.date)}  ${schedule.time}',
                          style: const TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w700,
                            color: Color(0xFF415A89),
                          ),
                        ),
                        const SizedBox(height: 4),
                        Row(
                          children: [
                            const Icon(
                              Icons.location_on_outlined,
                              size: 16,
                              color: Color(0xFF516A98),
                            ),
                            const SizedBox(width: 3),
                            Expanded(
                              child: Text(
                                schedule.place,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 13,
                                  color: Color(0xFF516A98),
                                ),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 10),
            // 사용자가 가장 먼저 알고 싶은 "몇 시에 나가면 되는지"를 맨 위에 둔다.
            if (repository is ScheduleEvaluationRepository) ...[
              ValueListenableBuilder<ScheduleEvaluationV2?>(
                valueListenable: headline,
                builder: (_, value, _) => DepartureHeadline(evaluation: value),
              ),
              const SizedBox(height: 10),
            ],
            Container(
              padding: const EdgeInsets.fromLTRB(8, 10, 16, 10),
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  colors: [Color(0xFFE1FAF5), Color(0xFFF1FBE8)],
                ),
                borderRadius: BorderRadius.circular(19),
                border: Border.all(color: const Color(0xFFC7EEDC)),
              ),
              child: Row(
                children: [
                  Image.asset(
                    'assets/images/ttubee/running.png',
                    width: 104,
                    height: 92,
                    fit: BoxFit.contain,
                  ),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 10,
                            vertical: 5,
                          ),
                          decoration: BoxDecoration(
                            color: Colors.white,
                            borderRadius: BorderRadius.circular(14),
                          ),
                          child: const Text(
                            '이 일정에 맞춰',
                            style: TextStyle(
                              fontSize: 11,
                              fontWeight: FontWeight.w800,
                              color: Color(0xFF35528A),
                            ),
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          route == null
                              ? '추천 출발 시간을 확인 중이에요.'
                              : '선택한 경로의 기본 정보를\n확인해 주세요.',
                          style: const TextStyle(
                            fontSize: 19,
                            height: 1.28,
                            fontWeight: FontWeight.w900,
                            color: DdubiColors.navy,
                          ),
                        ),
                        const SizedBox(height: 4),
                        const Text(
                          '출발 판단과 실시간 반영 여부는 최신 평가에서 확인해 주세요.',
                          style: TextStyle(
                            fontSize: 11,
                            color: Color(0xFF68808B),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),
            Row(
              children: [
                const Expanded(
                  child: Text(
                    '현재 선택 경로',
                    style: TextStyle(
                      fontSize: 19,
                      fontWeight: FontWeight.w900,
                      color: DdubiColors.navy,
                    ),
                  ),
                ),
                if (overview?.preview == true)
                  const StatusBadge('검증용', tone: DdubiColors.blue),
              ],
            ),
            if (recommendationUnavailable)
              const InfoCard(child: Text('최신 추천을 불러오지 못했어요. 잠시 후 다시 확인해주세요.'))
            else if (route == null)
              InfoCard(
                child: Text(
                  overview?.state == 'PENDING'
                      ? '추천 경로를 계산하고 있어요.'
                      : '아직 생성된 추천 결과가 없어요. 아래에서 다시 계산해주세요.',
                ),
              )
            else ...[
              _FinalRouteSummary(route: route),
              const SizedBox(height: 10),
              if (recommendationApi != null)
                _RecommendationEvidenceCard(
                  api: recommendationApi!,
                  overview: overview,
                ),
            ],
            const SizedBox(height: 16),
            _LiveSavedRouteList(
              initial: savedRoutes,
              unavailable: routesUnavailable,
              api: recommendationApi,
              scheduleId: schedule.id,
              headline: headline,
              onSelected: recommendationApi == null
                  ? null
                  : (savedRoute) => _openSavedRoute(
                      c,
                      schedule,
                      savedRoute,
                      routeRepository,
                    ),
            ),
            const SizedBox(height: 16),
            // 네 칸 모두 "확인 필요"만 나오던 자리다. 값이 붙기 전까지는 한 줄로만 알린다.
            const InfoCard(
              child: Text(
                '오늘의 이동 변수(날씨·혼잡도·도로 통제·지하철 지연)는 아직 확인되지 않았어요.',
                style: TextStyle(fontSize: 13, color: Color(0xFF5A6B85)),
              ),
            ),
            const SizedBox(height: 14),
            if (repository is ScheduleEvaluationRepository)
              // 개발자 진단(원본 코드·버전)은 디버그 빌드나 더보기 > 개발자 옵션을 켠 경우에만 붙는다.
              ValueListenableBuilder<bool>(
                valueListenable: DeveloperDiagnostics.enabled,
                builder: (_, diagnostics, _) => ScheduleEvaluationCard(
                  repository: repository as ScheduleEvaluationRepository,
                  scheduleId: schedule.id,
                  developerDiagnostics: diagnostics,
                  headline: headline,
                ),
              )
            else
              const InfoCard(child: Text('이 일정은 서버 예측에 연결되지 않았습니다.')),
            // 서버 참고자료 적재 현황은 운영 확인용이다. 개발자 진단을 켠 경우에만 보여준다.
            ValueListenableBuilder<bool>(
              valueListenable: DeveloperDiagnostics.enabled,
              builder: (_, diagnostics, _) => diagnostics
                  ? TransitReferencePanel(
                      repository: repository is TransitReferenceRepository
                          ? repository as TransitReferenceRepository
                          : null,
                    )
                  : const SizedBox.shrink(),
            ),
            const SizedBox(height: 14),
            OutlinedButton.icon(
              key: const Key('editScheduleButton'),
              onPressed: () async {
                final changed = await c.push<bool>(
                  '/schedules/${schedule.id}/edit',
                );
                if (changed == true && c.mounted) {
                  c.go('/schedules/${schedule.id}');
                }
              },
              icon: const Icon(Icons.edit_outlined),
              label: const Text('일정 수정'),
            ),
            const SizedBox(height: 8),
            FilledButton.icon(
              key: const Key('completeScheduleButton'),
              onPressed: () => _recordArrival(c, schedule),
              icon: const Icon(Icons.check_circle_outline),
              label: const Text('도착 완료'),
            ),
            const SizedBox(height: 8),
            TextButton.icon(
              key: const Key('cancelScheduleButton'),
              onPressed: () => _confirmCancel(c, schedule),
              icon: const Icon(Icons.delete_outline, color: DdubiColors.red),
              label: const Text(
                '일정 취소',
                style: TextStyle(color: DdubiColors.red),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _confirmCancel(BuildContext context, Schedule schedule) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('일정을 취소할까요?'),
        content: Text('${schedule.title} 일정은 목록에서 제외됩니다.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('돌아가기'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('일정 취소'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await repository.cancelSchedule(schedule.id);
      // 서버는 soft 취소(status=CANCELED)라 목록 API가 취소 일정도 돌려준다.
      // 목록·홈은 저장소에서 취소 일정을 걸러내므로 여기서는 목록으로 돌아가기만 한다.
      if (context.mounted) context.go('/schedules');
    } on ScheduleCommandFailure catch (failure) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(cancelFailureMessage(failure))));
      // 이미 없는 일정은 더 시도할 수 없다. 목록으로 돌아가 실제 상태를 다시 읽는다.
      if (failure.kind == ScheduleCommandFailureKind.notFound) {
        context.go('/schedules');
      }
    } on Object {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('일정을 취소하지 못했어요. 잠시 후 다시 시도해주세요.')),
      );
    }
  }

  /// 저장된 추천 경로 상세를 연다.
  ///
  /// 화면 순번이나 걸러낸 배열의 위치가 아니라 서버가 준 경로 ID로 연다. 필수 ID가 없으면
  /// 앱을 끄지 않고 안내만 한다. 이미 상세로 넘어가는 중이면 두 번 열지 않는다.
  /// 상세 화면이 닫힐 때 넘겨준 값을 그대로 돌려준다. 정리된 경로(404)면 true 가 와서 목록을 다시 읽는다.
  Future<Object?> _openSavedRoute(
    BuildContext c,
    Schedule schedule,
    SavedRouteSummary savedRoute,
    DdubiRepository routeRepository,
  ) {
    if (savedRoute.routeId.trim().isEmpty) {
      ScaffoldMessenger.of(c).showSnackBar(
        const SnackBar(
          key: Key('savedRouteOpenError'),
          content: Text('경로 상세를 불러올 수 없어요.'),
        ),
      );
      return Future<Object?>.value();
    }
    if (ModalRoute.of(c)?.isCurrent != true) return Future<Object?>.value();
    return openPage(
      c,
      RouteMapScreen(
        repository: routeRepository,
        api: recommendationApi,
        savedRoute: savedRoute,
        selectedRoute: savedRoute.toRouteOption(schedule),
        departureLabel: savedRoute.fromName ?? schedule.departurePlace,
        destinationLabel: savedRoute.toName ?? schedule.place,
      ),
    );
  }

  /// 취소 실패를 원인별로 다른 문구로 안내한다. 남의 일정·없는 일정·완료된 일정·연결 문제를 한 문구로 합치지 않는다.
  @visibleForTesting
  static String cancelFailureMessage(ScheduleCommandFailure failure) =>
      switch (failure.kind) {
        ScheduleCommandFailureKind.unauthenticated =>
          '로그인이 만료됐어요. 다시 로그인한 뒤 시도해 주세요.',
        ScheduleCommandFailureKind.forbidden => '다른 사용자의 일정은 취소할 수 없어요.',
        ScheduleCommandFailureKind.notFound => '이미 삭제된 일정이에요. 목록을 다시 불러왔어요.',
        ScheduleCommandFailureKind.conflict => '이미 완료된 일정은 취소할 수 없어요.',
        ScheduleCommandFailureKind.network =>
          '연결이 불안정해 일정을 취소하지 못했어요. 잠시 후 다시 시도해주세요.',
        ScheduleCommandFailureKind.unknown => '일정을 취소하지 못했어요. 잠시 후 다시 시도해주세요.',
      };

  Future<void> _recordArrival(BuildContext context, Schedule schedule) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('도착을 완료할까요?'),
        content: const Text('위치 정보 없이 일정만 완료하고 이동 기록을 남깁니다.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('완료'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await arrivalApi.record(schedule.id);
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('도착 기록을 저장했어요.')));
      context.go('/schedules');
    } on Object {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('도착 기록을 저장하지 못했어요.')));
    }
  }
}

class _ScheduleRecommendationData {
  const _ScheduleRecommendationData({
    this.route,
    this.overview,
    this.savedRoutes = const [],
    this.recommendationUnavailable = false,
    this.routesUnavailable = false,
  });

  final RouteOption? route;
  final RecommendationOverview? overview;
  final List<SavedRouteSummary> savedRoutes;
  final bool recommendationUnavailable;
  final bool routesUnavailable;
}

/// 저장된 추천 경로 목록을 최신 계산 결과에 맞춰 다시 읽는다.
///
/// 재계산이 끝나면 서버는 옛 후보를 정리한다(유예 10분). 화면이 재계산 전에 받은 목록을 그대로 들고
/// 있으면 누른 경로가 이미 없어 404 가 난다(2026-09-18 실기기). 그래서 이동 예측 카드가 새 결과를
/// 알려 줄 때마다, 그리고 상세 화면이 "정리된 경로"라고 돌아올 때 목록을 다시 읽는다.
class _LiveSavedRouteList extends StatefulWidget {
  const _LiveSavedRouteList({
    required this.initial,
    required this.unavailable,
    required this.api,
    required this.scheduleId,
    required this.headline,
    required this.onSelected,
  });

  final List<SavedRouteSummary> initial;
  final bool unavailable;
  final RecommendationApi? api;
  final String scheduleId;
  final ValueNotifier<ScheduleEvaluationV2?> headline;
  final Future<Object?> Function(SavedRouteSummary)? onSelected;

  @override
  State<_LiveSavedRouteList> createState() => _LiveSavedRouteListState();
}

class _LiveSavedRouteListState extends State<_LiveSavedRouteList> {
  late List<SavedRouteSummary> _routes = widget.initial;

  /// 서버가 같은 기준으로 비교한 후보별 결과. 새 평가가 오면 갱신한다.
  List<EvaluationCandidateEstimate> _estimates = const [];
  late bool _unavailable = widget.unavailable;
  final DateTime _loadedAt = DateTime.now();
  DateTime? _seenComputedAt;
  int _generation = 0;

  /// 기기와 서버 시계가 조금 어긋나도 "화면을 연 뒤 끝난 계산"을 놓치지 않도록 두는 여유.
  static const Duration _clockSlack = Duration(minutes: 2);

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
    final value = widget.headline.value;
    if (value != null && mounted && !_sameEstimates(value.candidateEstimates)) {
      setState(() => _estimates = value.candidateEstimates);
    }
    final computedAt = widget.headline.value?.computedAt;
    if (computedAt == null) return;
    final seen = _seenComputedAt;
    if (seen != null && !computedAt.isAfter(seen)) return;
    _seenComputedAt = computedAt;
    // 처음 받은 결과가 목록을 읽기 전에 끝난 것이면 목록은 이미 최신이다. 다시 읽지 않는다.
    if (seen == null && computedAt.isBefore(_loadedAt.subtract(_clockSlack))) {
      return;
    }
    _reload();
  }

  bool _sameEstimates(List<EvaluationCandidateEstimate> next) {
    if (next.length != _estimates.length) return false;
    for (var i = 0; i < next.length; i++) {
      if (next[i].routeId != _estimates[i].routeId ||
          next[i].durationSeconds != _estimates[i].durationSeconds ||
          next[i].proposed != _estimates[i].proposed) {
        return false;
      }
    }
    return true;
  }

  Future<void> _reload() async {
    final api = widget.api;
    if (api == null) return;
    final generation = ++_generation;
    try {
      final routes = await api.getSavedRoutes(widget.scheduleId);
      if (!mounted || generation != _generation) return;
      setState(() {
        _routes = routes;
        _unavailable = false;
      });
    } on Object {
      // 갱신 실패는 조용히 넘긴다. 기존 목록을 유지하고 다음 결과가 오면 다시 시도한다.
    }
  }

  @override
  Widget build(BuildContext context) => _SavedRouteList(
    display: SavedRouteDisplay.from(_routes),
    unavailable: _unavailable,
    estimates: _estimates,
    onSelected: widget.onSelected == null
        ? null
        : (route) async {
            final result = await widget.onSelected!(route);
            // 상세 화면이 "이 경로는 정리됐다"고 알려 주면 새 목록으로 바꾼다.
            if (result == true && mounted) {
              await _reload();
            }
          },
  );
}

/// 저장된 경로 하나에 대응하는 평가 결과를 찾는다. 없으면 null 이고 카드는 원본 값만 보여준다.
class _SavedRouteList extends StatelessWidget {
  const _SavedRouteList({
    required this.display,
    required this.unavailable,
    required this.onSelected,
    this.estimates = const [],
  });

  final SavedRouteDisplay display;
  final bool unavailable;

  /// 서버가 같은 기준으로 비교한 후보별 결과. `routeId` 로 카드와 이어 붙인다.
  final List<EvaluationCandidateEstimate> estimates;
  final ValueChanged<SavedRouteSummary>? onSelected;

  EvaluationCandidateEstimate? _estimateFor(String routeId) {
    for (final estimate in estimates) {
      if (estimate.routeId == routeId) return estimate;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final routes = display.visible;
    return Column(
      key: const Key('savedRouteList'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          '저장된 추천 경로',
          style: TextStyle(
            fontSize: 19,
            fontWeight: FontWeight.w900,
            color: DdubiColors.navy,
          ),
        ),
        const SizedBox(height: 9),
        if (unavailable)
          const InfoCard(child: Text('저장된 경로를 불러오지 못했어요.'))
        else if (routes.isEmpty)
          const InfoCard(
            key: Key('savedRouteEmptyState'),
            child: Text('아직 평가된 추천 경로가 없어요.'),
          )
        else ...[
          // 화면 순번은 1부터 다시 매긴다. 중복을 묶은 뒤 서버 순위(1,2,6,8,10)를 그대로
          // 보여주면 사용자에게는 번호가 건너뛴 것처럼 보인다. 서버 순위는 개발자 진단에 남는다.
          for (var i = 0; i < routes.length; i++) ...[
            _SavedRouteTile(
              route: routes[i],
              displayRank: i + 1,
              estimate: _estimateFor(routes[i].routeId),
              onTap: onSelected,
            ),
            if (i != routes.length - 1) const SizedBox(height: 8),
          ],
          // 후보 수와 묶인 개수는 개발자 진단에서만 본다. 일반 사용자 화면에는 숫자를 내보내지 않는다.
          ValueListenableBuilder<bool>(
            valueListenable: DeveloperDiagnostics.enabled,
            builder: (_, diagnostics, _) => diagnostics
                ? Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      display.diagnosticsLine,
                      key: const Key('savedRouteDiagnostics'),
                      style: const TextStyle(fontSize: 11),
                    ),
                  )
                : const SizedBox.shrink(),
          ),
        ],
      ],
    );
  }
}

class _SavedRouteTile extends StatelessWidget {
  const _SavedRouteTile({
    required this.route,
    required this.displayRank,
    required this.onTap,
    this.estimate,
  });

  /// 서버가 이번 평가에서 계산한 값. 있으면 원본 경로시간 대신 이 값을 보여준다.
  final EvaluationCandidateEstimate? estimate;

  /// 화면에 보이는 순번(1부터). 서버가 준 순위와 다를 수 있다.
  final int displayRank;

  final SavedRouteSummary route;
  final ValueChanged<SavedRouteSummary>? onTap;

  /// 평가된 소요시간이 있으면 그 값을, 없으면 경로 API 원본을 보여준다.
  String _durationLabel() {
    final seconds = estimate?.durationSeconds;
    if (seconds == null) return '약 ${route.baseDurationMin}분';
    return '약 ${(seconds / 60).ceil()}분';
  }

  @override
  Widget build(BuildContext context) => Material(
    color: route.selected ? const Color(0xFFE7FAF5) : Colors.white,
    borderRadius: BorderRadius.circular(16),
    child: InkWell(
      key: Key('savedRoute-${route.routeId}'),
      borderRadius: BorderRadius.circular(16),
      onTap: onTap == null ? null : () => onTap!(route),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: route.selected
                ? const Color(0xFF9DE2D2)
                : const Color(0xFFDCE6EC),
          ),
        ),
        child: Row(
          children: [
            Container(
              width: 42,
              height: 42,
              decoration: BoxDecoration(
                color: route.selected
                    ? DdubiColors.green
                    : const Color(0xFFEAF1F8),
                shape: BoxShape.circle,
              ),
              child: Icon(
                _transportIcon(route.modes),
                color: route.selected ? Colors.white : DdubiColors.navy,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          route.selected
                              ? '선택된 추천 경로'
                              : estimate?.proposed == true
                              ? '새로 제안된 경로'
                              : '$displayRank순위 경로',
                          style: const TextStyle(
                            fontWeight: FontWeight.w900,
                            color: DdubiColors.navy,
                          ),
                        ),
                      ),
                      if (route.selected)
                        const StatusBadge('선택됨', tone: DdubiColors.green)
                      else if (estimate?.proposed == true)
                        const StatusBadge(
                          '제안',
                          tone: DdubiColors.blue,
                          icon: Icons.auto_awesome_outlined,
                        ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${route.fromName ?? '출발지'} → ${route.toName ?? '도착지'}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Color(0xFF52677B)),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    '${_durationLabel()} · ${route.segmentCount}개 구간 · '
                    '${route.modes.map(routeModeLabel).join(' · ')}',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 12,
                      color: Color(0xFF6E8193),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 4),
            const Icon(Icons.chevron_right_rounded, color: Color(0xFF7F91A2)),
          ],
        ),
      ),
    ),
  );

  static IconData _transportIcon(List<String> modes) {
    if (modes.contains('SUBWAY')) return Icons.directions_subway_rounded;
    if (modes.contains('BUS')) return Icons.directions_bus_rounded;
    return Icons.directions_walk_rounded;
  }
}

class _RecommendationEvidenceCard extends StatelessWidget {
  const _RecommendationEvidenceCard({
    required this.api,
    required this.overview,
  });

  final RecommendationApi api;
  final RecommendationOverview? overview;

  @override
  Widget build(BuildContext context) {
    final recommendationId = overview?.recommendationId;
    if (recommendationId == null) {
      return _evidence(
        context,
        source: overview?.source,
        reasonCodes: overview?.reasonCodes ?? const [],
        preview: overview?.preview,
      );
    }
    return FutureBuilder<RecommendationExplanation>(
      future: api.getExplanation(recommendationId),
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return _evidence(
            context,
            source: overview?.source,
            reasonCodes: overview?.reasonCodes ?? const [],
            preview: overview?.preview,
            note: '상세 추천 근거를 불러오지 못했어요.',
          );
        }
        final explanation = snapshot.data;
        return _evidence(
          context,
          source: explanation?.source ?? overview?.source,
          reasonCodes:
              explanation?.reasonCodes ?? overview?.reasonCodes ?? const [],
          preview: explanation?.preview ?? overview?.preview,
          checkedAt: explanation?.dataCheckedAt,
          loading: !snapshot.hasData,
        );
      },
    );
  }

  Widget _evidence(
    BuildContext context, {
    String? source,
    required List<String> reasonCodes,
    bool? preview,
    DateTime? checkedAt,
    bool loading = false,
    String? note,
  }) => InfoCard(
    color: const Color(0xFFF4F8FF),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const StatusBadge(
          '추천 근거',
          tone: DdubiColors.blue,
          icon: Icons.lightbulb_outline,
        ),
        const SizedBox(height: 10),
        if (loading)
          const LinearProgressIndicator()
        else ...[
          Text(_sourceDescription(source)),
          if (preview == true) ...[
            const SizedBox(height: 6),
            const Text(
              '현재 결과는 검증용이며 일정에 자동 적용되지 않아요.',
              style: TextStyle(color: Color(0xFF64708B)),
            ),
          ],
          if (reasonCodes.isNotEmpty) ...[
            const SizedBox(height: 8),
            for (final reason in reasonCodes.take(3))
              Text('• ${_reasonDescription(reason)}'),
          ],
          if (checkedAt != null) ...[
            const SizedBox(height: 8),
            Text(
              '${checkedAt.hour.toString().padLeft(2, '0')}:${checkedAt.minute.toString().padLeft(2, '0')} 기준',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
          if (note != null) ...[
            const SizedBox(height: 8),
            Text(note, style: Theme.of(context).textTheme.bodySmall),
          ],
        ],
      ],
    ),
  );

  static String _sourceDescription(String? source) => switch (source) {
    'API_ONLY' => '현재 경로 API의 예상 소요시간을 기준으로 계산했어요.',
    'ML_PREVIEW' => '버스 구간 예측을 포함해 계산했어요.',
    'SUBWAY_PREVIEW' => '지하철 시간표를 포함해 계산했어요.',
    'MIXED_PREVIEW' => '경로 API와 교통 예측 정보를 함께 사용했어요.',
    'HISTORICAL_REFERENCE' || 'MIXED_HISTORICAL_REFERENCE' =>
      '일부 버스 구간에 과거 평균 소요시간을 사용한 참고 추정이에요. 실시간 또는 ML 예측값이 아니에요.',
    'BUS_REFERENCE' =>
      '버스 구간에 미리 계산한 기준 소요시간을 사용한 참고 추정이에요. 실시간 또는 ML 예측값이 아니에요.',
    _ => '계산에 사용된 정보가 확인되지 않았어요. 최신 평가의 제한 사항을 확인해 주세요.',
  };

  static String _reasonDescription(String code) {
    final historical = historicalReferenceReasonLabel(code);
    if (historical != null) return historical;
    if (code.contains('HISTORICAL_REFERENCE_APPLIED')) {
      return '버스 구간에 과거 평균 소요시간을 적용했어요.';
    }
    if (code.contains('HISTORICAL_REFERENCE_WEATHER_UNKNOWN')) {
      return '과거 평균에는 현재 날씨가 반영되지 않았어요.';
    }
    if (code.contains('HISTORICAL_REFERENCE_DATA_VERSION') ||
        code.contains('HISTORICAL_REFERENCE_SAMPLE_KIND')) {
      return '사용한 과거 자료의 출처가 기록돼 있어요.';
    }
    if (code.contains('HISTORICAL_REFERENCE_')) {
      return '과거 자료 적용 조건이 맞지 않아 기본 경로시간을 유지했어요.';
    }
    if (code == 'VALIDATION_ONLY') return '검증 단계의 결과예요.';
    if (code.contains('ML_NOT_CONFIGURED')) return '일부 구간은 경로 API 시간을 사용했어요.';
    if (code.contains('BUS_WAIT_MISSING')) return '버스 대기시간 정보가 부족해요.';
    if (code.contains('SUBWAY_TIMETABLE_NOT_CONFIGURED')) {
      return '지하철 시간표 정보가 부족해요.';
    }
    if (code.startsWith('ROAD_POTENTIAL_IMPACT:')) {
      return '예상 이동 구간에 도로 영향 가능성이 있어요.';
    }
    return '일부 교통정보를 확인하지 못했어요.';
  }
}

class _FinalRouteSummary extends StatelessWidget {
  const _FinalRouteSummary({required this.route});
  final RouteOption route;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(vertical: 13),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(18),
      border: Border.all(color: const Color(0xFFBDE9DF)),
    ),
    child: Row(
      children: [
        _metric(Icons.schedule_rounded, '예상 이동 시간', route.detail),
        _divider(),
        _metric(Icons.payments_outlined, '예상 요금', route.fare),
        _divider(),
        _metric(Icons.eco_rounded, '주요 변동 요소', route.margin),
      ],
    ),
  );

  Widget _metric(IconData icon, String label, String value) => Expanded(
    child: Column(
      children: [
        Icon(icon, size: 23, color: DdubiColors.green),
        const SizedBox(height: 5),
        Text(
          label,
          textAlign: TextAlign.center,
          style: const TextStyle(fontSize: 10, color: Color(0xFF62758A)),
        ),
        const SizedBox(height: 3),
        Text(
          value,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          textAlign: TextAlign.center,
          style: const TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w900,
            color: DdubiColors.navy,
          ),
        ),
      ],
    ),
  );

  Widget _divider() =>
      Container(width: 1, height: 50, color: const Color(0xFFE4ECEF));
}

class ScheduleEditScreen extends StatelessWidget {
  const ScheduleEditScreen({
    super.key,
    required this.repository,
    required this.placeSearchService,
    required this.scheduleId,
  });

  final ScheduleRepository repository;
  final PlaceSearchService placeSearchService;
  final String scheduleId;

  @override
  Widget build(BuildContext context) => FutureBuilder<Schedule?>(
    future: repository.scheduleById(scheduleId),
    builder: (context, snapshot) {
      if (snapshot.hasError) {
        return const AppPage(
          title: '일정 수정',
          child: DataLoadError(message: '일정을 불러오지 못했어요.'),
        );
      }
      if (snapshot.connectionState != ConnectionState.done) {
        return const AppPage(
          title: '일정 수정',
          child: Center(child: CircularProgressIndicator()),
        );
      }
      final schedule = snapshot.data;
      if (schedule == null) {
        return const AppPage(
          title: '일정 수정',
          child: DataLoadError(message: '수정할 일정을 찾을 수 없어요.'),
        );
      }
      return ScheduleFormScreen(
        repository: repository,
        placeSearchService: placeSearchService,
        schedule: schedule,
      );
    },
  );
}
