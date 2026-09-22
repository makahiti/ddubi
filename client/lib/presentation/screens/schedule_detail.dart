import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/theme.dart';
import '../../domain/models.dart';
import '../../services/app_api.dart';
import '../../services/recommendation_api.dart';
import '../widgets.dart';
import '../departure_headline.dart';
import '../arrival_flow.dart';
import '../recommendation_guide_card.dart';
import '../schedule_evaluation_card.dart';
import '../../domain/schedule_evaluation.dart';
import '../../domain/route_evaluated_numbers.dart';
import '../../domain/saved_route_display.dart';
import '../../domain/transit_reference.dart';
import '../transit_reference_panel.dart';
import '../travel_conditions_card.dart';
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
    required this.userApi,
    this.initialSchedule,
    this.initialRoutes,
  });
  final ScheduleRepository repository;
  final DdubiRepository routeRepository;
  final RecommendationApi? recommendationApi;
  final String scheduleId;
  final ArrivalApi arrivalApi;

  /// 이동 기록 저장 동의를 켜는 데만 쓴다. 계정 동의가 꺼져 있으면 서버가 도착 시각을 버려서
  /// 판정이 나오지 않기 때문에, 도착 시각을 남기기로 한 경우에만 함께 켠다.
  final UserApi userApi;
  final Schedule? initialSchedule;
  final List<RouteOption>? initialRoutes;

  @override
  Widget build(BuildContext c) => StatefulBuilder(
    // 아래로 당겨 새로 고침. 일정을 만든 직후에는 서버가 경로를 만드는 데 몇십 초가
    // 걸려서, 화면을 먼저 연 사람은 "추천 결과가 없어요" 만 보고 막힌다(2026-09-22 확인).
    // 자동 갱신이 어긋났을 때 사용자가 직접 다시 읽을 길을 둔다.
    builder: (context, rebuild) => _screen(context, () => rebuild(() {})),
  );

  Widget _screen(BuildContext c, VoidCallback reload) =>
      FutureBuilder<Schedule?>(
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
                reload: reload,
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

  /// 요약 카드가 보여줄 값. 선택된 경로의 평가 소요시간이 있으면 그것을 쓴다.
  RouteOption _selectedRouteOption(
    Schedule schedule,
    RouteOption fallback,
    List<SavedRouteSummary> savedRoutes,
    ScheduleEvaluationV2? evaluation,
  ) {
    final selected = savedRoutes.where((route) => route.selected);
    if (selected.isEmpty || evaluation == null) return fallback;
    final numbers = RouteEvaluatedNumbers.of(
      evaluation,
      selected.first.routeId,
    );
    if (numbers.totalMinutes == null) return fallback;
    return selected.first.toRouteOption(
      schedule,
      evaluatedMinutes: numbers.totalMinutes,
    );
  }

  Widget _content(
    BuildContext c,
    Schedule schedule,
    RouteOption? route,
    RecommendationOverview? overview,
    List<SavedRouteSummary> savedRoutes, {
    VoidCallback? reload,
    bool recommendationUnavailable = false,
    bool routesUnavailable = false,
  }) {
    return AppPage(
      // 맨 위 일정 정보 카드를 없앤 자리다. 제목이 사라지면 어느 일정인지 알 수 없어
      // 상단 바가 일정 이름을 든다.
      title: schedule.title,
      child: RefreshIndicator(
        onRefresh: () async => reload?.call(),
        child: EvaluationHeadlineScope(
          builder: (headline) => ListView(
            key: const Key('scheduleRouteOverview'),
            // 내용이 짧아도 당겨서 새로 고칠 수 있어야 한다.
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
            children: [
              // 없앤 일정 정보 카드에서 꼭 필요한 것만 한 줄로 남긴다. 언제 어디로 가는
              // 일정인지 모르면 아래 추천이 무엇에 대한 것인지 알 수 없다.
              Padding(
                key: const Key('scheduleDetailSubtitle'),
                padding: const EdgeInsets.only(bottom: 12),
                child: Row(
                  children: [
                    const Icon(
                      Icons.schedule_rounded,
                      size: 15,
                      color: Color(0xFF5C6B7F),
                    ),
                    const SizedBox(width: 5),
                    Text(
                      '${formatFullDate(schedule.date)} ${schedule.time} 도착',
                      style: const TextStyle(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w700,
                        color: Color(0xFF4A5B70),
                      ),
                    ),
                    const SizedBox(width: 10),
                    const Icon(
                      Icons.location_on_outlined,
                      size: 15,
                      color: Color(0xFF5C6B7F),
                    ),
                    const SizedBox(width: 3),
                    Expanded(
                      child: Text(
                        schedule.place,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 12.5,
                          color: Color(0xFF4A5B70),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              // 이미 다녀왔거나 취소한 일정에 "몇 시에 나가세요" 를 띄우지 않는다.
              // 대신 무엇이 끝났는지만 알린다.
              if (ScheduleStatusBadge.shows(schedule.status)) ...[
                InfoCard(
                  key: const Key('scheduleFinishedNotice'),
                  child: Row(
                    children: [
                      ScheduleStatusBadge(schedule.status),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          schedule.isCanceled
                              ? '취소한 일정이에요. 출발 안내는 하지 않아요.'
                              : '도착을 기록한 일정이에요. 출발 안내는 끝났어요.',
                          style: const TextStyle(
                            fontSize: 13,
                            color: Color(0xFF4A5B70),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 10),
              ] else if (repository is ScheduleEvaluationRepository) ...[
                ValueListenableBuilder<ScheduleEvaluationV2?>(
                  valueListenable: headline,
                  builder: (_, value, _) =>
                      DepartureHeadline(evaluation: value, emphasized: true),
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
                                : '지금 선택한 경로와\n새로 계산한 결과를 비교해 보세요.',
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
              // 반복에서 만들어진 일정이면 먼저 알린다. 수정·삭제가 이 일정 하나에만
              // 적용된다는 점을 모르면 "지웠는데 또 생긴다" 로 느낀다.
              if (schedule.isRepeating) ...[
                const SizedBox(height: 12),
                const InfoCard(
                  key: Key('scheduleRepeatNotice'),
                  child: Row(
                    children: [
                      Icon(
                        Icons.event_repeat,
                        size: 18,
                        color: DdubiColors.blue,
                      ),
                      SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          '반복 일정에서 만들어진 일정이에요. 여기서 고치거나 지우면 이 날짜 하나에만 적용돼요.',
                          style: TextStyle(
                            fontSize: 12,
                            color: Color(0xFF52677B),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
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
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        overview?.state == 'PENDING'
                            ? '추천 경로를 계산하고 있어요.'
                            : '아직 생성된 추천 결과가 없어요.',
                      ),
                      if (reload != null) ...[
                        const SizedBox(height: 4),
                        const Text(
                          '계산이 끝났는데도 이 화면이 그대로면 아래로 당겨 새로 고쳐 주세요.',
                          style: TextStyle(
                            fontSize: 12,
                            color: Color(0xFF7F91A2),
                          ),
                        ),
                        const SizedBox(height: 6),
                        Align(
                          alignment: Alignment.centerLeft,
                          child: OutlinedButton.icon(
                            key: const Key('scheduleDetailReload'),
                            onPressed: reload,
                            icon: const Icon(Icons.refresh, size: 18),
                            label: const Text('새로 고침'),
                          ),
                        ),
                      ],
                    ],
                  ),
                )
              else ...[
                // 같은 화면 안에서 숫자가 갈리지 않게, 아래 추천 목록이 쓰는 평가값을 그대로
                // 쓴다. 예전에는 이 카드만 경로 API 원본을 써서 같은 경로가 위 42분 · 아래
                // 51분으로 보였다(2026-09-22 확인).
                ValueListenableBuilder<ScheduleEvaluationV2?>(
                  valueListenable: headline,
                  builder: (context, evaluation, _) => _FinalRouteSummary(
                    route: _selectedRouteOption(
                      schedule,
                      route,
                      savedRoutes,
                      evaluation,
                    ),
                  ),
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
                    : (savedRoute, displayRank, evaluated) => _openSavedRoute(
                        c,
                        schedule,
                        savedRoute,
                        routeRepository,
                        displayRank: displayRank,
                        evaluated: evaluated,
                      ),
              ),
              const SizedBox(height: 16),
              // 계산 근거(내부 사유 코드) 대신 사용자가 행동할 수 있는 안내를 보여준다.
              // 시간과 순서는 서버 계산값이고 앱은 받은 값을 그대로 쓴다.
              // 예전에는 이 카드가 경로 목록 위에 있었고, 옆에 내용 없는 "이 일정에 맞춰"
              // 카드가 따로 있었다. 둘을 한 장으로 합치고 경로 목록 아래로 내렸다.
              // 끝난 일정에는 안내를 띄우지 않는다. 이미 다녀온 일정에 "이 경로를 제안해요"
              // 나 "안내를 불러오지 못했어요" 가 뜨면 아직 할 일이 남은 것처럼 읽힌다.
              if (recommendationApi != null &&
                  !ScheduleStatusBadge.shows(schedule.status)) ...[
                RecommendationGuideCard(
                  api: recommendationApi!,
                  scheduleId: schedule.id,
                  headline: headline,
                  evaluationRepository:
                      repository is ScheduleEvaluationRepository
                      ? repository as ScheduleEvaluationRepository
                      : null,
                ),
                const SizedBox(height: 16),
              ],
              // 한 줄이던 자리다. 한 줄이면 무엇이 되고 무엇이 안 되는지 알 수 없어서
              // 칸을 넷으로 나눴다.
              //
              // 끝난 일정에는 띄우지 않는다. 이 값은 "지금" 의 날씨·혼잡도라서 어제 다녀온
              // 일정 옆에 붙으면 그때의 상황으로 오해한다.
              if (!ScheduleStatusBadge.shows(schedule.status)) ...[
                TravelConditionsCard(
                  scheduleId: schedule.id,
                  service: recommendationApi,
                ),
                const SizedBox(height: 14),
              ],
              // 끝난 일정에는 이동 예측을 띄우지 않는다. 다시 계산할 것이 없다.
              // else 로 흘러 "서버 예측에 연결되지 않았습니다" 가 뜨면 고장으로 읽힌다.
              if (ScheduleStatusBadge.shows(schedule.status))
                const SizedBox.shrink()
              else if (repository is ScheduleEvaluationRepository)
                // 개발자 진단(원본 코드·버전)은 디버그 빌드나 더보기 > 개발자 옵션을 켠 경우에만 붙는다.
                ValueListenableBuilder<bool>(
                  valueListenable: DeveloperDiagnostics.enabled,
                  builder: (_, diagnostics, _) => ScheduleEvaluationCard(
                    repository: repository as ScheduleEvaluationRepository,
                    scheduleId: schedule.id,
                    developerDiagnostics: diagnostics,
                    // 같은 버튼이 위 안내 카드에 있다. 두 곳에 두면 서로 다른 단추로 읽힌다.
                    showActions: false,
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
    DdubiRepository routeRepository, {
    int? displayRank,
    RouteEvaluatedNumbers evaluated = RouteEvaluatedNumbers.empty,
  }) {
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
        displayRank: displayRank,
        evaluated: evaluated,
        scheduleId: schedule.id,
        departedAlready: schedule.hasDeparted,
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

  /// 도착 보고. 확인 창과 서버 호출은 [recordArrivalWithConsent] 한 곳에 있다 —
  /// 이동 안내의 "도착했어요" 와 같은 흐름을 쓰기 위해서다.
  Future<void> _recordArrival(BuildContext context, Schedule schedule) async {
    final record = await recordArrivalWithConsent(
      context,
      scheduleId: schedule.id,
      arrivalApi: arrivalApi,
      userApi: userApi,
    );
    if (record == null || !context.mounted) return;
    context.go('/schedules');
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

  /// 목록에서 보이던 순번과 평가 결과를 같이 넘긴다. 상세 화면이 서버 rankNo 와 경로 API
  /// 원본 시간을 따로 보여주면 같은 경로인데 다른 숫자가 나온다.
  final Future<Object?> Function(SavedRouteSummary, int, RouteEvaluatedNumbers)?
  onSelected;

  @override
  State<_LiveSavedRouteList> createState() => _LiveSavedRouteListState();
}

class _LiveSavedRouteListState extends State<_LiveSavedRouteList> {
  late List<SavedRouteSummary> _routes = widget.initial;

  /// 서버가 같은 기준으로 비교한 후보별 결과. 새 평가가 오면 갱신한다.
  List<EvaluationCandidateEstimate> _estimates = const [];

  /// 카드·상세·지도가 같은 숫자를 쓰도록 평가 결과를 통째로 들고 있는다.
  ScheduleEvaluationV2? _evaluation;
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
    if (value != null && mounted) {
      // 후보의 경로·소요시간·제안 여부가 같아도 평가 자체는 바뀔 수 있다. 구간별 시간
      // 출처, 실시간 원인, 계산 시각 같은 것들이다. 예전에는 후보가 같으면 평가 객체를
      // 통째로 안 바꿔서 상세 카드와 지도에 낡은 구간 정보가 남았다(BE 지적, 2026-09-22).
      final estimatesChanged = !_sameEstimates(value.candidateEstimates);
      if (estimatesChanged || _isNewEvaluation(value)) {
        setState(() {
          _estimates = value.candidateEstimates;
          _evaluation = value;
        });
      }
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

  /// 들고 있는 것과 다른 계산인가. 계산 id 가 우선이고, 없으면 계산 시각으로 본다.
  bool _isNewEvaluation(ScheduleEvaluationV2 next) {
    final current = _evaluation;
    if (current == null) return true;
    if (next.evaluationId != null || current.evaluationId != null) {
      return next.evaluationId != current.evaluationId;
    }
    return next.computedAt != current.computedAt;
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

  /// 이번 평가에서 서버가 바꾸라고 제안한 경로. 없으면 null 이다.
  String? get _proposedRouteId {
    for (final estimate in _estimates) {
      if (estimate.proposed && estimate.routeId != null) {
        return estimate.routeId;
      }
    }
    return null;
  }

  @override
  Widget build(BuildContext context) => _SavedRouteList(
    display: SavedRouteDisplay.from(_routes, proposedRouteId: _proposedRouteId),
    unavailable: _unavailable,
    estimates: _estimates,
    evaluation: _evaluation,
    onSelected: widget.onSelected == null
        ? null
        : (route, displayRank, evaluated) async {
            final result = await widget.onSelected!(
              route,
              displayRank,
              evaluated,
            );
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
    this.evaluation,
  });

  /// 이번 평가 결과 전체. 카드에 쓸 시간과 출처를 여기서만 꺼낸다.
  final ScheduleEvaluationV2? evaluation;

  final SavedRouteDisplay display;
  final bool unavailable;

  /// 서버가 같은 기준으로 비교한 후보별 결과. `routeId` 로 카드와 이어 붙인다.
  final List<EvaluationCandidateEstimate> estimates;

  /// (경로, 화면 순번, 화면에 보인 소요시간(분)) 를 넘긴다.
  final void Function(SavedRouteSummary, int, RouteEvaluatedNumbers)?
  onSelected;

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
          '추천 경로',
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
          // 순번은 서버가 준 displayRank 를 그대로 쓴다. 없으면(구버전 응답) 1부터
          // 앱이 매긴다. 어느 쪽이든 상세 화면에 같은 값을 넘겨 두 화면이 어긋나지 않게 한다.
          // 내부 순위(rankNo)는 소요시간 기준 캐시라 사용자 화면에 쓰지 않는다.
          for (var i = 0; i < routes.length; i++) ...[
            _SavedRouteTile(
              route: routes[i],
              displayRank: routes[i].displayRank ?? i + 1,
              estimate: _estimateFor(routes[i].routeId),
              evaluated: RouteEvaluatedNumbers.of(
                evaluation,
                routes[i].routeId,
              ),
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
    this.evaluated = RouteEvaluatedNumbers.empty,
  });

  /// 이번 평가가 이 경로에 계산한 시간·출처. 상세와 지도에 그대로 넘겨 같은 숫자를 쓰게 한다.
  final RouteEvaluatedNumbers evaluated;

  /// 서버가 이번 평가에서 계산한 값. 있으면 원본 경로시간 대신 이 값을 보여준다.
  final EvaluationCandidateEstimate? estimate;

  /// 화면에 보이는 순번(1부터). 서버가 준 순위와 다를 수 있다.
  final int displayRank;

  final SavedRouteSummary route;

  /// (경로, 화면 순번, 이번 평가 결과) 를 넘긴다. 상세 화면이 같은 숫자를 보여주게 하려고
  /// 카드가 실제로 쓴 값을 그대로 전달한다.
  final void Function(SavedRouteSummary, int, RouteEvaluatedNumbers)? onTap;

  /// 평가된 소요시간이 있으면 그 값을, 없으면 경로 API 원본을 쓴다(분).
  int get _durationMin =>
      evaluated.totalMinutes ??
      ((estimate?.durationSeconds) == null
          ? route.baseDurationMin
          : (estimate!.durationSeconds! / 60).ceil());

  /// 이 숫자가 어디서 왔는지. 지도 상세와 같은 문구를 쓴다.
  bool get _fromEvaluation =>
      evaluated.totalMinutes != null || estimate?.durationSeconds != null;

  String _durationLabel() =>
      '약 $_durationMin분 · ${_fromEvaluation ? '이동 예측 기준' : '원본 경로 기준'}';

  @override
  Widget build(BuildContext context) => Material(
    color: route.selected ? const Color(0xFFE7FAF5) : Colors.white,
    borderRadius: BorderRadius.circular(16),
    child: InkWell(
      key: Key('savedRoute-${route.routeId}'),
      borderRadius: BorderRadius.circular(16),
      onTap: onTap == null ? null : () => onTap!(route, displayRank, evaluated),
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
                              // 화면에는 제일 추천하는 경로 한 장만 남으므로 순위 번호를
                              // 쓰지 않는다. "3순위" 는 나머지 두 개가 보일 때만 뜻이 있다.
                              : '가장 추천하는 경로',
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
        // 요금은 선택 경로에 저장되지 않는다(route 테이블에 칸이 없다). 그래서 이 자리는
        // 늘 "요금 정보 없음" 이었는데, 바로 아래 안내 카드는 실제 요금을 보여준다.
        // 한 화면에서 "없음" 과 "1,650원" 이 같이 보이는 것보다 비우는 편이 낫다
        // (2026-09-22 확인). 값이 생기면 그때 다시 보여준다.
        if (route.fare != '요금 정보 없음') ...[
          _divider(),
          _metric(Icons.payments_outlined, '예상 요금', route.fare),
        ],
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
