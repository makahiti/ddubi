import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/constants/layout_constants.dart';
import '../../core/theme.dart';
import '../../domain/models.dart';
import '../../services/app_api.dart';
import '../../services/recommendation_api.dart';
import '../widgets.dart';
import '../departure_headline.dart';
import '../home_greeting.dart';
import '../../domain/schedule_evaluation.dart';
import 'shared.dart';

/// 홈 화면.
///
/// 조회를 [State] 에서 한 번만 시작하고, 필요할 때만 다시 읽는다. 예전에는 `build()`
/// 안에서 곧바로 서버를 불렀다. 그러면 두 가지가 같이 어긋난다.
///  - 다시 그려질 때마다 요청이 나간다.
///  - 정작 필요한 때(일정을 고치고 돌아왔을 때)는 다시 그려지지 않아 옛 값이 남는다.
/// 실제로 "수정했는데 홈에 안 뜬다" 는 제보가 있었다(2026-09-22).
class HomeScreen extends StatefulWidget {
  const HomeScreen({
    super.key,
    required this.homeApi,
    this.recommendationApi,
    this.evaluationRepository,
    this.scheduleRepository,
    this.go,
  });
  final HomeApi homeApi;
  final RecommendationApi? recommendationApi;
  final ScheduleEvaluationRepository? evaluationRepository;

  /// 날짜 화살표로 다른 날을 보려면 오늘 말고도 필요하다. 홈 요약(summary)은 오늘치만 준다.
  final ScheduleRepository? scheduleRepository;
  final ValueChanged<int>? go;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late Future<_HomeData> _data;

  /// 다른 날 목록을 다시 읽으라고 아래쪽에 알리는 값. 늘릴 때마다 새로 읽는다.
  int _browseGeneration = 0;

  @override
  void initState() {
    super.initState();
    _data = _load();
  }

  /// 홈을 처음부터 다시 읽는다. 다른 날 목록도 같이 버린다.
  Future<void> _reload() async {
    final next = _load();
    setState(() {
      _data = next;
      _browseGeneration++;
    });
    await next;
  }

  /// 일정 화면을 열고, 돌아오면 홈을 다시 읽는다.
  ///
  /// 일정 상세는 홈 위에 얹히는 방식이라 뒤로 나와도 홈이 스스로 다시 그려지지 않는다.
  /// 그래서 수정·완료한 내용이 홈에 그대로 남았다. 돌아온 시점을 여기서 잡는다.
  Future<void> _openAndRefresh(
    BuildContext context,
    String location, {
    Object? extra,
  }) async {
    await context.push<void>(location, extra: extra);
    if (mounted) await _reload();
  }

  Future<_HomeData> _load() async {
    final homeApi = widget.homeApi;
    final evaluationRepository = widget.evaluationRepository;
    final recommendationApi = widget.recommendationApi;
    final summary = await homeApi.getSummary();
    RecommendationOverview? recommendation;
    ScheduleEvaluationV2? evaluation;
    final schedule = summary.nextSchedule;
    if (schedule != null && evaluationRepository != null) {
      try {
        evaluation = await evaluationRepository.latestEvaluation(schedule.id);
      } on Object {
        evaluation = null;
      }
    } else if (schedule != null && recommendationApi != null) {
      try {
        recommendation = await recommendationApi.getLatestOverview(schedule.id);
      } on Object {
        recommendation = null;
      }
    }
    return _HomeData(
      summary: summary,
      recommendation: recommendation,
      evaluation: evaluation,
    );
  }

  @override
  Widget build(BuildContext c) => AppPage(
    title: '안녕하세요! 🌱',
    webTitle: '이동 대시보드',
    webSubtitle: '오늘 일정과 추천 출발 시간, 교통 상태를 한눈에 확인하세요.',
    index: widget.go == null ? null : 2,
    onNav: widget.go,
    actions: [
      IconButton(
        key: const Key('manageRepeatSchedulesButton'),
        tooltip: '반복 일정 관리',
        onPressed: () => c.push('/schedules/repeats'),
        icon: const Icon(Icons.event_repeat_outlined),
      ),
      IconButton(
        tooltip: '알림 보기',
        onPressed: () => c.go('/notifications'),
        icon: const Icon(Icons.notifications_none_rounded),
      ),
    ],
    child: FutureBuilder<_HomeData>(
      future: _data,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          // 실패해도 다시 읽을 길을 남긴다. 예전에는 문구만 있어 앱을 껐다 켜야 했다.
          return RefreshIndicator(
            onRefresh: _reload,
            child: ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              children: const [
                SizedBox(height: 120),
                DataLoadError(message: '홈 정보를 불러오지 못했어요.\n아래로 당기면 다시 시도해요.'),
              ],
            ),
          );
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final data = snapshot.data!;
        final schedule = data.summary.nextSchedule;
        final routes = schedule != null && data.evaluation != null
            ? [?routeFromEvaluation(data.evaluation!, schedule)]
            : schedule != null && data.recommendation?.hasEstimate == true
            ? [data.recommendation!.toRouteOption(schedule)]
            : const <RouteOption>[];
        return LayoutBuilder(
          builder: (context, constraints) {
            final isWide =
                constraints.maxWidth >= LayoutConstants.wideScreenBreakpoint;
            if (isWide) {
              return _WebHomeContent(
                routes: routes,
                summary: data.summary,
                evaluation: data.evaluation,
              );
            }
            // 아래로 당겨 새로 고침. 알림이 와서 우연히 다시 그려지기를 기다리지 않아도 된다.
            return RefreshIndicator(
              onRefresh: _reload,
              child: _MobileHomeContent(
                key: ValueKey(_browseGeneration),
                schedules: data.summary.todaySchedules,
                scheduleRepository: widget.scheduleRepository,
                routes: routes,
                evaluation: data.evaluation,
                scheduleId: schedule?.id,
                openSchedule: (location, {extra}) =>
                    _openAndRefresh(context, location, extra: extra),
              ),
            );
          },
        );
      },
    ),
  );
}

/// 서버가 주는 파이프라인 신선도 코드를 사람 말로 옮긴다.
///
/// 예전에는 `NORMAL`·`STALE`·`UNKNOWN` 을 그대로 화면에 찍었다. 내부 코드를 사용자에게
/// 보여주는 셈이고, 게다가 어떤 값이든 초록색이라 "오래된 자료" 인데도 정상으로 보였다.
/// 모르는 코드는 지어내지 않고 "확인 중" 으로 둔다.
@visibleForTesting
String dataStateLabel(String state) => switch (state) {
  'NORMAL' => '정상',
  'STALE' => '자료 오래됨',
  'UNKNOWN' => '확인 중',
  _ => '확인 중',
};

@visibleForTesting
Color dataStateTone(String state) => switch (state) {
  'NORMAL' => DdubiColors.green,
  'STALE' => const Color(0xFFFF6B35),
  _ => const Color(0xFF7F91A2),
};

@visibleForTesting
IconData dataStateIcon(String state) => switch (state) {
  'NORMAL' => Icons.check_circle_outline,
  'STALE' => Icons.history_toggle_off_outlined,
  _ => Icons.help_outline,
};

class _HomeData {
  const _HomeData({
    required this.summary,
    this.recommendation,
    this.evaluation,
  });

  final HomeSummary summary;
  final RecommendationOverview? recommendation;
  final ScheduleEvaluationV2? evaluation;
}

typedef _OpenSchedule = Future<void> Function(String location, {Object? extra});

class _MobileHomeContent extends StatefulWidget {
  const _MobileHomeContent({
    super.key,
    required this.schedules,
    required this.routes,
    required this.openSchedule,
    this.scheduleRepository,
    this.evaluation,
    this.scheduleId,
  });

  /// 일정 화면을 열고 돌아오면 홈을 다시 읽는 통로.
  final _OpenSchedule openSchedule;

  final List<Schedule> schedules;

  /// 다른 날 일정은 화살표를 처음 누를 때만 불러온다. 홈을 열 때마다 받으면
  /// 오늘만 보는 대부분의 경우에 요청이 하나 늘어난다.
  final ScheduleRepository? scheduleRepository;
  final List<RouteOption> routes;
  final ScheduleEvaluationV2? evaluation;

  /// 결론 한 줄을 누르면 열 일정. 다음 일정이 없으면 누를 수 없다.
  final String? scheduleId;

  @override
  State<_MobileHomeContent> createState() => _MobileHomeContentState();
}

class _MobileHomeContentState extends State<_MobileHomeContent> {
  /// 화면에 보이는 날. 처음에는 오늘이다.
  DateTime _selected = _dayOf(DateTime.now());

  static DateTime _dayOf(DateTime value) =>
      DateTime(value.year, value.month, value.day);

  /// 화살표를 처음 누른 뒤에만 채워진다. 실패하면 null 로 남는다.
  List<Schedule>? _browsed;
  bool _loading = false;
  bool _failed = false;

  bool get _canBrowse => widget.scheduleRepository != null;

  bool get _isToday => _selected == _dayOf(DateTime.now());

  Future<void> _shiftDay(int days) async {
    final next = DateTime(
      _selected.year,
      _selected.month,
      _selected.day + days,
    );
    // 한 번 읽고 마는 게 아니라 날짜를 옮길 때마다 다시 읽는다. 예전에는 처음 한 번만
    // 읽어서, 그 뒤에 일정을 고쳐도 홈의 다른 날 목록이 계속 옛 값을 보여줬다.
    setState(() {
      _loading = true;
      _failed = false;
    });
    List<Schedule>? loaded;
    try {
      loaded = await widget.scheduleRepository!.schedules();
    } on Object {
      loaded = null;
    }
    if (!mounted) return;
    setState(() {
      _loading = false;
      if (loaded != null) {
        _browsed = loaded;
        _failed = false;
        _selected = next;
      } else {
        // 못 읽었으면 날짜를 옮기지 않는다. 옮겨 놓고 빈 화면을 보여주면 일정이
        // 없는 날로 오해한다. 이미 읽어 둔 게 있으면 그건 그대로 쓴다.
        _failed = _browsed == null;
        if (_browsed != null) _selected = next;
      }
    });
  }

  void _backToToday() {
    setState(() => _selected = _dayOf(DateTime.now()));
  }

  /// 그 날 일정. 아직 전체를 불러오지 않았으면 홈 요약이 준 오늘치를 쓴다.
  List<Schedule> _schedulesOn(DateTime day) {
    final source = _browsed ?? widget.schedules;
    return source
        // 취소한 일정은 보이지 않아야 한다. 서버 목록 API 는 상태를 안 주면 취소분까지
        // 돌려주므로 여기서 거른다. 홈 요약(오늘치)은 서버가 이미 걸러 준다.
        .where((schedule) => !schedule.isCanceled)
        .where((schedule) => _dayOf(schedule.date) == day)
        .toList(growable: false)
      ..sort((a, b) => a.date.compareTo(b.date));
  }

  @override
  Widget build(BuildContext context) {
    final now = _selected;
    final today = _schedulesOn(_selected);
    return ColoredBox(
      color: const Color(0xFFF7FBFA),
      child: ListView(
        key: const Key('todayScheduleList'),
        // 내용이 한 화면에 들어와도 아래로 당길 수 있어야 새로 고침이 걸린다.
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(18, 6, 18, 28),
        children: [
          const Text(
            '오늘도 안전한 이동 되세요!',
            style: TextStyle(fontSize: 14, color: Color(0xFF718497)),
          ),
          const SizedBox(height: 14),
          Builder(
            builder: (context) {
              final greeting = homeGreeting(DateTime.now());
              return Container(
                key: Key('homeGreeting_${greeting.period}'),
                height: 130,
                padding: const EdgeInsets.fromLTRB(22, 16, 8, 0),
                decoration: BoxDecoration(
                  gradient: LinearGradient(colors: greeting.colors),
                  borderRadius: BorderRadius.circular(22),
                ),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        greeting.message,
                        style: TextStyle(
                          fontSize: 22,
                          height: 1.35,
                          fontWeight: FontWeight.w900,
                          color: greeting.textColor,
                        ),
                      ),
                    ),
                    Image.asset(
                      'assets/images/ttubee/welcome.png',
                      width: 145,
                      fit: BoxFit.contain,
                    ),
                  ],
                ),
              );
            },
          ),
          const SizedBox(height: 16),
          if (widget.evaluation != null) ...[
            // 어느 일정의 결론인지 이름을 붙인다. 일정이 여럿이면 이름 없는 결론은 헷갈린다.
            for (final labeled
                in (_browsed ?? widget.schedules)
                    .where((schedule) => schedule.id == widget.scheduleId)
                    .take(1)) ...[
              HeadlineScheduleLabel(schedule: labeled),
              const SizedBox(height: 6),
            ],
            // 홈은 결론 한 줄만 보여주고, 자세한 근거는 일정 상세에서 본다.
            DepartureHeadline(
              evaluation: widget.evaluation!,
              compact: true,
              onTap: widget.scheduleId == null
                  ? null
                  : () =>
                        widget.openSchedule('/schedules/${widget.scheduleId}'),
            ),
            const SizedBox(height: 16),
          ],
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              _DayArrow(
                key: const Key('homePreviousDay'),
                icon: Icons.chevron_left_rounded,
                tooltip: '전날',
                onTap: _canBrowse && !_loading ? () => _shiftDay(-1) : null,
              ),
              const SizedBox(width: 6),
              GestureDetector(
                // 며칠 넘긴 뒤 오늘로 바로 돌아올 길을 둔다.
                key: const Key('homeDateLabel'),
                onTap: _isToday ? null : _backToToday,
                child: Text(
                  '${now.month}월 ${now.day}일 (${weekdayLabels[now.weekday - 1]})',
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w900,
                    color: DdubiColors.navy,
                  ),
                ),
              ),
              const SizedBox(width: 6),
              _DayArrow(
                key: const Key('homeNextDay'),
                icon: Icons.chevron_right_rounded,
                tooltip: '다음 날',
                onTap: _canBrowse && !_loading ? () => _shiftDay(1) : null,
              ),
            ],
          ),
          if (_loading || _failed || !_isToday) ...[
            const SizedBox(height: 6),
            Center(
              child: Text(
                _loading
                    ? '다른 날 일정을 불러오는 중...'
                    : _failed
                    ? '다른 날 일정을 불러오지 못했어요.'
                    : '날짜를 눌러 오늘로 돌아갑니다',
                style: const TextStyle(fontSize: 12, color: Color(0xFF7F91A2)),
              ),
            ),
          ],
          const SizedBox(height: 14),
          Row(
            children: [
              Expanded(
                child: Text(
                  _isToday ? '오늘의 일정' : '${now.month}월 ${now.day}일 일정',
                  style: const TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.w900,
                    color: DdubiColors.navy,
                  ),
                ),
              ),
              Text(
                '${today.length}',
                style: const TextStyle(
                  fontWeight: FontWeight.w900,
                  color: DdubiColors.green,
                ),
              ),
              const SizedBox(width: 10),
              OutlinedButton.icon(
                onPressed: () =>
                    widget.openSchedule('/schedules/new', extra: now),
                icon: const Icon(Icons.add, size: 18),
                label: const Text('일정 추가'),
                style: OutlinedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  visualDensity: VisualDensity.compact,
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          if (today.isEmpty)
            InfoCard(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.all(18),
                  child: Text(
                    _isToday ? '오늘 등록된 일정이 없어요.' : '이 날은 등록된 일정이 없어요.',
                  ),
                ),
              ),
            )
          else
            for (var index = 0; index < today.length; index++) ...[
              _TodayScheduleCard(
                openSchedule: widget.openSchedule,
                schedule: today[index],
                routes: widget.routes,
                index: index,
              ),
              const SizedBox(height: 10),
            ],
        ],
      ),
    );
  }
}

class _TodayScheduleCard extends StatelessWidget {
  const _TodayScheduleCard({
    required this.schedule,
    required this.routes,
    required this.index,
    required this.openSchedule,
  });
  final Schedule schedule;
  final List<RouteOption> routes;
  final int index;

  /// 일정을 열고 돌아오면 홈을 다시 읽는 통로.
  final _OpenSchedule openSchedule;

  @override
  Widget build(BuildContext context) {
    const tones = [
      Color(0xFF4D80E8),
      Color(0xFFFFA626),
      Color(0xFF8A72E8),
      Color(0xFFFF639A),
    ];
    const icons = [
      Icons.work_rounded,
      Icons.restaurant_rounded,
      Icons.menu_book_rounded,
      Icons.fitness_center_rounded,
    ];
    final tone = tones[index % tones.length];
    return Opacity(
      // 끝난 일정은 흐리게. 아직 남은 일정이 먼저 눈에 들어와야 한다.
      opacity: ScheduleStatusBadge.opacityFor(schedule.status),
      child: Material(
        color: Colors.white,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(18),
          side: BorderSide(color: DdubiColors.green.withValues(alpha: .22)),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          key: Key('todaySchedule-${schedule.id}'),
          // 끝난 일정은 흐리게. 아직 남은 일정이 먼저 눈에 들어와야 한다.
          onTap: () => openSchedule(
            '/schedules/${schedule.id}',
            extra: ScheduleDetailArguments(schedule: schedule),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
            child: Row(
              children: [
                Container(
                  width: 50,
                  height: 50,
                  decoration: BoxDecoration(
                    color: tone.withValues(alpha: .12),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Icon(icons[index % icons.length], color: tone),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        schedule.time,
                        style: const TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w900,
                          color: Color(0xFF1B43A6),
                        ),
                      ),
                      const SizedBox(height: 2),
                      Row(
                        children: [
                          Flexible(
                            child: Text(
                              schedule.title,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 17,
                                fontWeight: FontWeight.w900,
                                color: DdubiColors.navy,
                              ),
                            ),
                          ),
                          if (ScheduleStatusBadge.shows(schedule.status)) ...[
                            const SizedBox(width: 6),
                            ScheduleStatusBadge(schedule.status),
                          ] else if (schedule.hasDeparted) ...[
                            // 출발을 알린 뒤 도착 전까지. 예전에는 이 구간도 "예정" 이라
                            // 출발 버튼을 눌러도 홈에서는 아무것도 달라지지 않았다(2026-09-22 제보).
                            const SizedBox(width: 6),
                            const MovingBadge(),
                          ],
                        ],
                      ),
                      const SizedBox(height: 3),
                      Row(
                        children: [
                          const Icon(
                            Icons.location_on,
                            size: 14,
                            color: Color(0xFF8391A8),
                          ),
                          const SizedBox(width: 3),
                          Expanded(
                            child: Text(
                              schedule.place,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 12,
                                color: Color(0xFF718098),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                // 끝난 일정에는 "예정" 을 붙이지 않는다. 제목 옆에 완료·취소 뱃지가 이미
                // 있는데 오른쪽에 "예정" 까지 붙어 두 상태가 동시에 보였다(2026-09-22 제보).
                // 출발한 일정은 제목 옆 "이동 중" 이 말하므로 오른쪽 표시도 뺀다.
                if (!ScheduleStatusBadge.shows(schedule.status) &&
                    !schedule.hasDeparted) ...[
                  Container(
                    key: const Key('scheduleUpcomingBadge'),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 6,
                    ),
                    decoration: BoxDecoration(
                      color: const Color(0xFFE8F8F2),
                      borderRadius: BorderRadius.circular(14),
                    ),
                    child: const Text(
                      '예정',
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w800,
                        color: DdubiColors.green,
                      ),
                    ),
                  ),
                  const SizedBox(width: 4),
                ],
                const Icon(
                  Icons.chevron_right_rounded,
                  color: Color(0xFF1D3F8E),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

void _showHomeRouteDetails(BuildContext context, List<RouteOption> routes) {
  showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    backgroundColor: Colors.white,
    builder: (context) => SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 4, 24, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              '추천 출발 상세',
              style: TextStyle(
                fontSize: 23,
                fontWeight: FontWeight.w800,
                color: DdubiColors.navy,
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              '현재 홈에 표시된 경로를 비교해보세요.',
              style: TextStyle(color: Color(0xFF6D7C88)),
            ),
            const SizedBox(height: 18),
            if (routes.isEmpty) const Text('예정된 일정이 없어요. 목적지를 설정해 경로를 찾아보세요.'),
            for (final route in routes)
              Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(
                    Icons.route_rounded,
                    color: Color(0xFF009B83),
                  ),
                  title: Text('${route.label} · ${route.transport}'),
                  subtitle: Text(
                    '${route.departure} 출발 → ${route.arrival} 도착\n${route.detail} · ${route.fare}',
                  ),
                  isThreeLine: true,
                ),
              ),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: () {
                  Navigator.pop(context);
                  context.go('/recommendations');
                },
                child: const Text('내 목적지로 경로 찾기'),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

class _WebHomeContent extends StatelessWidget {
  const _WebHomeContent({
    required this.routes,
    required this.summary,
    this.evaluation,
  });

  final List<RouteOption> routes;
  final HomeSummary summary;
  final ScheduleEvaluationV2? evaluation;

  @override
  Widget build(BuildContext context) => ListView(
    key: const Key('webHomeDashboard'),
    padding: const EdgeInsets.fromLTRB(24, 20, 24, 32),
    children: [
      Row(
        key: const Key('webHomeMetrics'),
        children: [
          Expanded(
            child: DashboardMetricCard(
              label: '다음 일정',
              value: summary.nextSchedule?.time ?? '--:--',
              caption: summary.nextSchedule?.title ?? '예정된 일정 없음',
              icon: Icons.event_available_outlined,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: DashboardMetricCard(
              label: '추천 출발',
              value: primaryRoute(routes)?.departure ?? '--:--',
              caption: primaryRoute(routes)?.margin ?? '경로 탐색 필요',
              icon: Icons.directions_run_outlined,
              tone: DdubiColors.green,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: DashboardMetricCard(
              label: '도착 예정',
              value: primaryRoute(routes)?.arrival ?? '--:--',
              caption: primaryRoute(routes)?.detail ?? '경로 탐색 필요',
              icon: Icons.flag_outlined,
              tone: Color(0xFF7257E8),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: DashboardMetricCard(
              label: '데이터 상태',
              value: dataStateLabel(summary.dataState),
              caption: '안 읽은 알림 ${summary.unreadNotificationCount}건',
              icon: dataStateIcon(summary.dataState),
              tone: dataStateTone(summary.dataState),
            ),
          ),
        ],
      ),
      const SizedBox(height: 20),
      if (evaluation != null) ...[
        // 어느 일정의 결론인지 적는다. 일정이 여럿이면 가장 가까운 것 하나의 결론만 보여주는데,
        // 이름이 없으면 어느 일정 이야기인지 알 수 없다(2026-09-22 제보).
        if (summary.nextSchedule != null) ...[
          HeadlineScheduleLabel(schedule: summary.nextSchedule!),
          const SizedBox(height: 6),
        ],
        DepartureHeadline(
          evaluation: evaluation,
          compact: true,
          onTap: summary.nextSchedule == null
              ? null
              : () => context.push('/schedules/${summary.nextSchedule!.id}'),
        ),
        const SizedBox(height: 20),
      ],
      Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            flex: 3,
            child: _HomeScheduleCard(
              schedule: summary.nextSchedule,
              previewRoutes: routes,
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            flex: 2,
            child: Column(
              children: [
                TtubeeGuideCard(
                  primaryRoute(routes) == null
                      ? '다음 일정을 등록하면 출발 시간을 챙겨드릴게요.'
                      : '${primaryRoute(routes)!.departure}에 출발하면 안전해요!\n'
                            '${primaryRoute(routes)!.margin}',
                  compact: true,
                ),
                const SizedBox(height: 12),
                InfoCard(
                  child: Row(
                    children: [
                      Expanded(
                        child: _HomeMetric(
                          '도착 예정',
                          primaryRoute(routes)?.arrival ?? '--:--',
                        ),
                      ),
                      Expanded(
                        child: _HomeMetric(
                          '이동 정보',
                          primaryRoute(routes)?.detail ?? '-',
                        ),
                      ),
                      Expanded(
                        child: _HomeMetric(
                          '여유 시간',
                          primaryRoute(routes)?.margin ?? '-',
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
      const SizedBox(height: 28),
      SectionHeader(
        '추천 경로',
        trailing: Text(
          '가장 알맞은 경로 하나를 안내해요',
          style: Theme.of(context).textTheme.bodySmall,
        ),
      ),
      const SizedBox(height: 12),
      GridView.builder(
        key: const Key('webHomeRouteGrid'),
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        itemCount: routes.isEmpty ? 0 : 1,
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 3,
          crossAxisSpacing: 12,
          mainAxisExtent: 220,
        ),
        itemBuilder: (_, i) => RouteCard(
          primaryRoute(routes)!,
          compact: true,
          fillWidth: true,
          onTap: summary.nextSchedule == null
              ? null
              : () => context.push('/schedules/${summary.nextSchedule!.id}'),
        ),
      ),
    ],
  );
}

class _HomeScheduleCard extends StatelessWidget {
  const _HomeScheduleCard({
    required this.schedule,
    this.previewRoutes = const [],
  });

  final Schedule? schedule;
  final List<RouteOption> previewRoutes;

  @override
  Widget build(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          '다음 중요 일정',
          style: TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w700,
            color: DdubiColors.navy,
          ),
        ),
        const SizedBox(height: 8),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    schedule?.time ?? '--:--',
                    style: Theme.of(context).textTheme.headlineLarge,
                  ),
                  Text(
                    schedule?.title ?? '예정된 일정이 없어요',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 6),
                  Text(
                    schedule == null
                        ? '새 일정을 등록하고 이동을 준비해요'
                        : '일정에 맞춰 여유롭게 준비하세요',
                    style: const TextStyle(
                      fontSize: 12,
                      color: Color(0xFF6D7C88),
                    ),
                  ),
                ],
              ),
            ),
            Image.asset(
              'assets/images/ttubee/welcome.png',
              width: MediaQuery.sizeOf(context).width < 400 ? 124 : 160,
              height: 154,
            ),
          ],
        ),
        const Divider(height: 16),
        const Text('추천 출발 시간', style: TextStyle(fontSize: 12)),
        Row(
          children: [
            Text(
              // 추천이 없을 때 그럴듯한 시각을 지어내지 않는다. 예전에는 '17:57' 이
              // 박혀 있어서, 일정도 추천도 없는 사용자에게 근거 없는 출발 시각을
              // 알려주고 있었다.
              previewRoutes.isEmpty ? '--:--' : previewRoutes.first.departure,
              style: Theme.of(context).textTheme.headlineMedium
                  ?.copyWith(color: DdubiColors.blue),
            ),
            const Spacer(),
            OutlinedButton(
              onPressed: schedule == null
                  ? () => _showHomeRouteDetails(context, previewRoutes)
                  : () => context.push('/schedules/${schedule!.id}'),
              child: const Text('상세 보기'),
            ),
          ],
        ),
      ],
    ),
  );
}

class _HomeMetric extends StatelessWidget {
  const _HomeMetric(this.label, this.value);

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Text(label, style: Theme.of(context).textTheme.bodySmall),
      const SizedBox(height: 5),
      Text(value, style: Theme.of(context).textTheme.titleMedium),
    ],
  );
}

/// 홈 날짜를 앞뒤로 넘기는 화살표.
///
/// 예전에는 그냥 [Icon] 이라 눌러도 아무 일이 없었다. 눌리는 것처럼 보이는데 안 눌리면
/// 고장으로 읽히므로, 쓸 수 없을 때는 흐리게 보여 눌러도 되는지 눈으로 알 수 있게 한다.
class _DayArrow extends StatelessWidget {
  const _DayArrow({
    super.key,
    required this.icon,
    required this.tooltip,
    this.onTap,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return IconButton(
      onPressed: onTap,
      tooltip: tooltip,
      visualDensity: VisualDensity.compact,
      icon: Icon(
        icon,
        color: onTap == null
            ? const Color(0xFFBFCBD8)
            : const Color(0xFF52709B),
      ),
    );
  }
}
