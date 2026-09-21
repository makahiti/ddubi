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
import '../../domain/schedule_evaluation.dart';
import 'shared.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({
    super.key,
    required this.homeApi,
    this.recommendationApi,
    this.evaluationRepository,
    this.go,
  });
  final HomeApi homeApi;
  final RecommendationApi? recommendationApi;
  final ScheduleEvaluationRepository? evaluationRepository;
  final ValueChanged<int>? go;

  Future<_HomeData> _load() async {
    final summary = await homeApi.getSummary();
    RecommendationOverview? recommendation;
    ScheduleEvaluationV2? evaluation;
    final schedule = summary.nextSchedule;
    if (schedule != null && evaluationRepository != null) {
      try {
        evaluation = await evaluationRepository!.latestEvaluation(schedule.id);
      } on Object {
        evaluation = null;
      }
    } else if (schedule != null && recommendationApi != null) {
      try {
        recommendation = await recommendationApi!.getLatestOverview(
          schedule.id,
        );
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
    index: go == null ? null : 2,
    onNav: go,
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
      future: _load(),
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const DataLoadError(message: '홈 정보를 불러오지 못했어요.');
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
            return isWide
                ? _WebHomeContent(
                    routes: routes,
                    summary: data.summary,
                    evaluation: data.evaluation,
                  )
                : _MobileHomeContent(
                    schedules: data.summary.todaySchedules,
                    routes: routes,
                    evaluation: data.evaluation,
                    scheduleId: schedule?.id,
                  );
          },
        );
      },
    ),
  );
}

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

class _MobileHomeContent extends StatelessWidget {
  const _MobileHomeContent({
    required this.schedules,
    required this.routes,
    this.evaluation,
    this.scheduleId,
  });

  final List<Schedule> schedules;
  final List<RouteOption> routes;
  final ScheduleEvaluationV2? evaluation;

  /// 결론 한 줄을 누르면 열 일정. 다음 일정이 없으면 누를 수 없다.
  final String? scheduleId;

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final today =
        schedules
            .where(
              (schedule) =>
                  schedule.date.year == now.year &&
                  schedule.date.month == now.month &&
                  schedule.date.day == now.day,
            )
            .toList()
          ..sort((a, b) => a.date.compareTo(b.date));
    return ColoredBox(
      color: const Color(0xFFF7FBFA),
      child: ListView(
        key: const Key('todayScheduleList'),
        padding: const EdgeInsets.fromLTRB(18, 6, 18, 28),
        children: [
          const Text(
            '오늘도 안전한 이동 되세요!',
            style: TextStyle(fontSize: 14, color: Color(0xFF718497)),
          ),
          const SizedBox(height: 14),
          Container(
            height: 130,
            padding: const EdgeInsets.fromLTRB(22, 16, 8, 0),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [Color(0xFFCFF6FA), Color(0xFFE8FAE9)],
              ),
              borderRadius: BorderRadius.circular(22),
            ),
            child: Row(
              children: [
                const Expanded(
                  child: Text(
                    '오늘도\n뚜비와 함께\n좋은 하루 되세요!',
                    style: TextStyle(
                      fontSize: 22,
                      height: 1.35,
                      fontWeight: FontWeight.w900,
                      color: DdubiColors.navy,
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
          ),
          const SizedBox(height: 16),
          if (evaluation != null) ...[
            // 홈은 결론 한 줄만 보여주고, 자세한 근거는 일정 상세에서 본다.
            DepartureHeadline(
              evaluation: evaluation,
              compact: true,
              onTap: scheduleId == null
                  ? null
                  : () => context.push('/schedules/$scheduleId'),
            ),
            const SizedBox(height: 16),
          ],
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.chevron_left_rounded, color: Color(0xFF52709B)),
              const SizedBox(width: 14),
              Text(
                '${now.month}월 ${now.day}일 (${weekdayLabels[now.weekday - 1]})',
                style: const TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w900,
                  color: DdubiColors.navy,
                ),
              ),
              const SizedBox(width: 14),
              const Icon(Icons.chevron_right_rounded, color: Color(0xFF52709B)),
            ],
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              const Expanded(
                child: Text(
                  '오늘의 일정',
                  style: TextStyle(
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
                onPressed: () => context.push('/schedules/new', extra: now),
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
            const InfoCard(
              child: Center(
                child: Padding(
                  padding: EdgeInsets.all(18),
                  child: Text('오늘 등록된 일정이 없어요.'),
                ),
              ),
            )
          else
            for (var index = 0; index < today.length; index++) ...[
              _TodayScheduleCard(
                schedule: today[index],
                routes: routes,
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
  });
  final Schedule schedule;
  final List<RouteOption> routes;
  final int index;

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
    return Material(
      color: Colors.white,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(18),
        side: BorderSide(color: DdubiColors.green.withValues(alpha: .22)),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        key: Key('todaySchedule-${schedule.id}'),
        onTap: () => context.push(
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
                    Text(
                      schedule.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 17,
                        fontWeight: FontWeight.w900,
                        color: DdubiColors.navy,
                      ),
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
              Container(
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
              const Icon(Icons.chevron_right_rounded, color: Color(0xFF1D3F8E)),
            ],
          ),
        ),
      ),
    );
  }
}

// Retained for the desktop dashboard route cards.
// ignore: unused_element
class _HomePlayfulIcon extends StatelessWidget {
  const _HomePlayfulIcon({
    required this.icon,
    required this.color,
    this.size = 38,
  });
  final IconData icon;
  final Color color;
  final double size;
  @override
  Widget build(BuildContext context) => SizedBox(
    width: size + 8,
    height: size + 6,
    child: Stack(
      clipBehavior: Clip.none,
      children: [
        Positioned(
          bottom: 0,
          left: 6,
          right: 2,
          child: Container(
            height: 7,
            decoration: BoxDecoration(
              color: color.withValues(alpha: .12),
              borderRadius: BorderRadius.circular(20),
            ),
          ),
        ),
        Transform.rotate(
          angle: -.09,
          child: ShaderMask(
            blendMode: BlendMode.srcIn,
            shaderCallback: (bounds) => LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [
                Color.lerp(color, Colors.white, .35)!,
                color,
                Color.lerp(color, Colors.black, .15)!,
              ],
            ).createShader(bounds),
            child: Icon(icon, size: size, color: color),
          ),
        ),
        Positioned(
          right: 0,
          top: -2,
          child: Icon(
            Icons.auto_awesome_rounded,
            size: 10,
            color: color.withValues(alpha: .6),
          ),
        ),
      ],
    ),
  );
}

// ignore: unused_element
class _HomeVariable extends StatelessWidget {
  const _HomeVariable({
    required this.icon,
    required this.label,
    required this.tone,
  });
  final IconData icon;
  final String label;
  final Color tone;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 14),
    decoration: BoxDecoration(
      color: tone,
      borderRadius: BorderRadius.circular(16),
    ),
    child: Column(
      children: [
        _HomePlayfulIcon(
          icon: icon,
          color: switch (label) {
            '날씨' => const Color(0xFFF4B829),
            '혼잡도' => const Color(0xFF00AE79),
            '도로 통제' => const Color(0xFFFA716C),
            _ => const Color(0xFF00AB8A),
          },
        ),
        const SizedBox(height: 9),
        Text(
          label,
          textAlign: TextAlign.center,
          style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 5),
        const Text(
          '확인 필요',
          style: TextStyle(fontSize: 11, color: Color(0xFF77868B)),
        ),
      ],
    ),
  );
}

// ignore: unused_element
class _HomeRouteTile extends StatelessWidget {
  const _HomeRouteTile({required this.route, required this.index});
  final RouteOption route;
  final int index;
  @override
  Widget build(BuildContext context) {
    final tone = [
      const Color(0xFF009B75),
      const Color(0xFF347FD6),
      const Color(0xFF8261D9),
    ][index % 3];
    return Material(
      color: Color.lerp(Colors.white, tone, .035),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(20),
        side: BorderSide(color: tone.withValues(alpha: .3)),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => _showHomeRouteDetails(context, [route]),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _HomePlayfulIcon(
                icon: [
                  Icons.workspace_premium_rounded,
                  Icons.bolt_rounded,
                  Icons.verified_user_rounded,
                ][index % 3],
                color: tone,
                size: 32,
              ),
              const SizedBox(height: 8),
              Text(
                route.label,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w800,
                  color: tone,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                route.transport,
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w800,
                  color: DdubiColors.navy,
                ),
              ),
              const SizedBox(height: 7),
              Text(
                '${route.departure} → ${route.arrival}',
                style: const TextStyle(fontSize: 11, color: Color(0xFF5E7180)),
              ),
              const SizedBox(height: 6),
              Text(
                route.margin,
                style: TextStyle(
                  fontSize: 17,
                  fontWeight: FontWeight.w800,
                  color: index == 1 ? const Color(0xFFE46C60) : tone,
                ),
              ),
              const SizedBox(height: 7),
              Text(
                route.detail,
                style: const TextStyle(fontSize: 10, color: Color(0xFF72818A)),
              ),
              const SizedBox(height: 4),
              Text(
                route.fare,
                style: const TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w800,
                  color: DdubiColors.navy,
                ),
              ),
            ],
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
              value: summary.dataState,
              caption: '안 읽은 알림 ${summary.unreadNotificationCount}건',
              icon: Icons.check_circle_outline,
              tone: DdubiColors.green,
            ),
          ),
        ],
      ),
      const SizedBox(height: 20),
      if (evaluation != null) ...[
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
              previewRoutes.isEmpty ? '17:57' : previewRoutes.first.departure,
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
