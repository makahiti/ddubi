import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:kakao_maps_flutter/kakao_maps_flutter.dart';

import '../core/constants/layout_constants.dart';
import '../core/maps/kakao_map_config.dart';
import '../core/theme.dart';
import '../data/mock_repository.dart';
import '../domain/auth.dart';
import '../domain/models.dart';
import '../services/kakao_login_service.dart';
import '../services/google_login_service.dart';
import '../services/naver_login_service.dart';
import 'auth_screens.dart';
import 'micro_animations.dart';
import 'widgets.dart';

void _open(BuildContext c, Widget page) =>
    Navigator.of(c).push(MaterialPageRoute(builder: (_) => page));
const _pad = EdgeInsets.fromLTRB(16, 8, 16, 20);
const _weekdays = ['월', '화', '수', '목', '금', '토', '일'];

String _fullDate(DateTime date) =>
    '${date.year}.${date.month.toString().padLeft(2, '0')}.${date.day.toString().padLeft(2, '0')} (${_weekdays[date.weekday - 1]})';

class LoginScreen extends StatelessWidget {
  const LoginScreen({
    super.key,
    required this.authRepository,
    required this.kakaoLoginService,
    required this.googleLoginService,
    required this.naverLoginService,
  });
  final AuthRepository authRepository;
  final KakaoLoginService kakaoLoginService;
  final GoogleLoginService googleLoginService;
  final NaverLoginService naverLoginService;
  @override
  Widget build(BuildContext c) => Scaffold(
    floatingActionButton: kDebugMode
        ? FloatingActionButton.extended(
            key: const Key('debugHomeButton'),
            onPressed: () => _completeLogin(c),
            icon: const Icon(Icons.home_outlined),
            label: const Text('홈 바로가기'),
          )
        : null,
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: LayoutBuilder(
            builder: (context, constraints) => SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: ConstrainedBox(
                constraints: BoxConstraints(
                  minHeight: (constraints.maxHeight - 48).clamp(
                    0,
                    double.infinity,
                  ),
                ),
                child: IntrinsicHeight(
                  child: Column(
                    children: [
                      const Spacer(),
                      const TtubeeEntrance(
                        asset: 'assets/images/ttubee/welcome.png',
                        semanticLabel: '반갑게 인사하는 뚜비',
                      ),
                      const SizedBox(height: 8),
                      Text('뚜비', style: Theme.of(c).textTheme.headlineLarge),
                      const SizedBox(height: 8),
                      const Text(
                        '일정에 늦지 않게,\n뚜비가 먼저 알려드려요',
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 16),
                      _login(
                        c,
                        '●  카카오로 시작하기',
                        const Color(0xFFFFD600),
                        Colors.black,
                        () => _social(c, '카카오'),
                      ),
                      _login(
                        c,
                        'G  Google로 시작하기',
                        Colors.white,
                        DdubiColors.navy,
                        () => _social(c, 'Google'),
                      ),
                      _login(
                        c,
                        'N  네이버로 시작하기',
                        const Color(0xFF03C75A),
                        Colors.white,
                        () => _social(c, '네이버'),
                      ),
                      _login(
                        c,
                        '✉  이메일로 시작하기',
                        Colors.white,
                        DdubiColors.navy,
                        () => _open(
                          c,
                          EmailLoginScreen(
                            repository: authRepository,
                            onAuthenticated: () => _completeLogin(c),
                          ),
                        ),
                      ),
                      TextButton(
                        onPressed: () =>
                            _open(c, SignUpScreen(repository: authRepository)),
                        child: const Text('아직 계정이 없으신가요?  회원가입'),
                      ),
                      const Spacer(),
                      const Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(Icons.lock_outline, size: 16),
                          SizedBox(width: 7),
                          Flexible(
                            child: Text(
                              '안전한 서비스 이용을 위해 최소한의 권한만 사용합니다.',
                              style: TextStyle(fontSize: 11),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  void _completeLogin(BuildContext c) => c.go('/home');
  void _social(BuildContext c, String provider) => _open(
    c,
    SocialAuthScreen(
      provider: provider,
      repository: authRepository,
      kakaoLoginService: provider == '카카오' ? kakaoLoginService : null,
      googleLoginService: provider == 'Google' ? googleLoginService : null,
      naverLoginService: provider == '네이버' ? naverLoginService : null,
      onAuthenticated: () => _completeLogin(c),
    ),
  );
  Widget _login(
    BuildContext c,
    String text,
    Color bg,
    Color fg,
    VoidCallback onPressed,
  ) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: SizedBox(
      width: double.infinity,
      height: 48,
      child: OutlinedButton(
        style: OutlinedButton.styleFrom(
          backgroundColor: bg,
          foregroundColor: fg,
          side: const BorderSide(color: DdubiColors.border),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10),
          ),
        ),
        onPressed: onPressed,
        child: Text(text, style: const TextStyle(fontWeight: FontWeight.w700)),
      ),
    ),
  );
}

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.repository, this.go});
  final DdubiRepository repository;
  final ValueChanged<int>? go;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '다음 중요 일정',
    webTitle: '이동 대시보드',
    webSubtitle: '오늘 일정과 추천 출발 시간, 교통 상태를 한눈에 확인하세요.',
    index: go == null ? null : 2,
    onNav: go,
    actions: const [
      IconButton(onPressed: null, icon: Icon(Icons.notifications_none)),
    ],
    child: FutureBuilder<List<RouteOption>>(
      future: repository.routes(),
      builder: (c, s) {
        if (s.hasError) {
          return const DataLoadError(message: '추천 경로를 불러오지 못했어요.');
        }
        if (!s.hasData) return const Center(child: CircularProgressIndicator());
        final routes = s.data!;
        return LayoutBuilder(
          builder: (context, constraints) {
            final isWide =
                constraints.maxWidth >= LayoutConstants.wideScreenBreakpoint;
            return isWide
                ? _WebHomeContent(routes: routes)
                : _MobileHomeContent(routes: routes);
          },
        );
      },
    ),
  );
}

class _MobileHomeContent extends StatelessWidget {
  const _MobileHomeContent({required this.routes});

  final List<RouteOption> routes;

  @override
  Widget build(BuildContext context) => ListView(
    padding: _pad,
    children: [
      const _HomeScheduleCard(),
      const SizedBox(height: 12),
      const TtubeeGuideCard('17:57에 출발하면 안전해요!\n아직 준비할 시간이 충분해요.'),
      const SizedBox(height: 16),
      SizedBox(
        height: 230,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          itemCount: routes.length,
          separatorBuilder: (_, _) => const SizedBox(width: 10),
          itemBuilder: (_, i) => RouteCard(
            routes[i],
            compact: true,
            onTap: () => context.push('/recommendations/mock-recommendation-1'),
          ),
        ),
      ),
    ],
  );
}

class _WebHomeContent extends StatelessWidget {
  const _WebHomeContent({required this.routes});

  final List<RouteOption> routes;

  @override
  Widget build(BuildContext context) => ListView(
    key: const Key('webHomeDashboard'),
    padding: const EdgeInsets.fromLTRB(24, 20, 24, 32),
    children: [
      const Row(
        key: Key('webHomeMetrics'),
        children: [
          Expanded(
            child: DashboardMetricCard(
              label: '다음 일정',
              value: '19:30',
              caption: '잠실야구장',
              icon: Icons.event_available_outlined,
            ),
          ),
          SizedBox(width: 12),
          Expanded(
            child: DashboardMetricCard(
              label: '추천 출발',
              value: '17:57',
              caption: '15분 여유',
              icon: Icons.directions_run_outlined,
              tone: DdubiColors.green,
            ),
          ),
          SizedBox(width: 12),
          Expanded(
            child: DashboardMetricCard(
              label: '도착 예정',
              value: '18:45',
              caption: '이동 48분',
              icon: Icons.flag_outlined,
              tone: Color(0xFF7257E8),
            ),
          ),
          SizedBox(width: 12),
          Expanded(
            child: DashboardMetricCard(
              label: '교통 상태',
              value: '정상',
              caption: '지하철 2호선',
              icon: Icons.check_circle_outline,
              tone: DdubiColors.green,
            ),
          ),
        ],
      ),
      const SizedBox(height: 20),
      Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Expanded(flex: 3, child: _HomeScheduleCard()),
          const SizedBox(width: 16),
          Expanded(
            flex: 2,
            child: Column(
              children: [
                const TtubeeGuideCard(
                  '17:57에 출발하면 안전해요!\n아직 준비할 시간이 충분해요.',
                  compact: true,
                ),
                const SizedBox(height: 12),
                InfoCard(
                  child: Row(
                    children: const [
                      Expanded(child: _HomeMetric('도착 예정', '18:45')),
                      Expanded(child: _HomeMetric('이동 시간', '48분')),
                      Expanded(child: _HomeMetric('여유 시간', '15분')),
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
          '${routes.length}개의 경로를 비교해보세요',
          style: Theme.of(context).textTheme.bodySmall,
        ),
      ),
      const SizedBox(height: 12),
      GridView.builder(
        key: const Key('webHomeRouteGrid'),
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        itemCount: routes.length,
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 3,
          crossAxisSpacing: 12,
          mainAxisExtent: 220,
        ),
        itemBuilder: (_, i) => RouteCard(
          routes[i],
          compact: true,
          fillWidth: true,
          onTap: () => context.push('/recommendations/mock-recommendation-1'),
        ),
      ),
    ],
  );
}

class _HomeScheduleCard extends StatelessWidget {
  const _HomeScheduleCard();

  @override
  Widget build(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '19:30',
                    style: Theme.of(context).textTheme.headlineLarge,
                  ),
                  Text('잠실야구장', style: Theme.of(context).textTheme.titleLarge),
                  const SizedBox(height: 6),
                  const StatusBadge('지금은 정상'),
                ],
              ),
            ),
            const Icon(
              Icons.stadium_outlined,
              size: 58,
              color: DdubiColors.blue,
            ),
          ],
        ),
        const Divider(height: 28),
        const Text('추천 출발 시간', style: TextStyle(fontSize: 12)),
        Row(
          children: [
            Text(
              '17:57',
              style: Theme.of(context).textTheme.headlineMedium
                  ?.copyWith(color: DdubiColors.blue),
            ),
            const Spacer(),
            OutlinedButton(
              onPressed: () => context.push('/schedules/schedule-baseball'),
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

class ScheduleListScreen extends StatefulWidget {
  const ScheduleListScreen({super.key, required this.repository, this.go});
  final ScheduleRepository repository;
  final ValueChanged<int>? go;
  @override
  State<ScheduleListScreen> createState() => _ScheduleListScreenState();
}

class _ScheduleListScreenState extends State<ScheduleListScreen> {
  DateTime selectedDate = DateTime.now();
  Future<void> _add(BuildContext context) async {
    final saved = await context.push<bool>(
      '/schedules/new',
      extra: selectedDate,
    );
    if (saved == true && mounted) setState(() {});
  }

  Future<void> _addRepeat(BuildContext context) async {
    final saved = await context.push<bool>(
      '/schedules/repeat/new',
      extra: selectedDate,
    );
    if (saved == true && mounted) setState(() {});
  }

  @override
  Widget build(BuildContext c) => AppPage(
    title: '전체 일정',
    webTitle: '일정 대시보드',
    webSubtitle: '월간 일정과 선택한 날짜의 이동 계획을 함께 관리하세요.',
    index: widget.go == null ? null : 0,
    onNav: widget.go,
    actions: [
      IconButton(
        key: const Key('addRepeatScheduleButton'),
        tooltip: '반복 일정 추가',
        onPressed: () => _addRepeat(c),
        icon: const Icon(Icons.repeat_rounded),
      ),
    ],
    fab: FloatingActionButton(
      onPressed: () => _add(c),
      child: const Icon(Icons.add),
    ),
    child: FutureBuilder<List<Schedule>>(
      future: widget.repository.schedules(),
      builder: (c, s) {
        if (s.hasError) {
          return const DataLoadError(message: '일정을 불러오지 못했어요.');
        }
        final schedules = s.data ?? [];
        final selectedSchedules = schedules
            .where(
              (item) =>
                  item.date.year == selectedDate.year &&
                  item.date.month == selectedDate.month &&
                  item.date.day == selectedDate.day,
            )
            .toList();
        final calendar = MonthlyCalendar(
          selectedDate: selectedDate,
          schedules: schedules,
          onSelected: (date) => setState(() => selectedDate = date),
        );
        final dayPanel = _ScheduleDayPanel(
          selectedDate: selectedDate,
          schedules: selectedSchedules,
          isLoading: !s.hasData,
          onAdd: () => _add(c),
        );
        return LayoutBuilder(
          builder: (context, constraints) {
            final isWide =
                constraints.maxWidth >= LayoutConstants.wideScreenBreakpoint;
            if (isWide) {
              return SingleChildScrollView(
                key: const Key('webScheduleLayout'),
                padding: const EdgeInsets.fromLTRB(24, 20, 24, 32),
                child: Column(
                  children: [
                    Row(
                      key: const Key('webScheduleMetrics'),
                      children: [
                        Expanded(
                          child: DashboardMetricCard(
                            label: '전체 일정',
                            value: '${schedules.length}개',
                            caption: '이번 달 등록 기준',
                            icon: Icons.calendar_month_outlined,
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: DashboardMetricCard(
                            label: '선택 날짜',
                            value:
                                '${selectedDate.month}월 ${selectedDate.day}일',
                            caption: '${selectedSchedules.length}개의 일정',
                            icon: Icons.today_outlined,
                            tone: DdubiColors.green,
                          ),
                        ),
                        const SizedBox(width: 12),
                        const Expanded(
                          child: DashboardMetricCard(
                            label: '반복 일정',
                            value: '평일',
                            caption: '출근 일정 활성화',
                            icon: Icons.repeat_rounded,
                            tone: Color(0xFF7257E8),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 20),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: MonthlyCalendar(
                            selectedDate: selectedDate,
                            schedules: schedules,
                            expanded: true,
                            onSelected: (date) =>
                                setState(() => selectedDate = date),
                          ),
                        ),
                        const SizedBox(width: 20),
                        SizedBox(width: 360, child: dayPanel),
                      ],
                    ),
                  ],
                ),
              );
            }
            return ListView(
              key: const Key('mobileScheduleLayout'),
              padding: _pad,
              children: [calendar, const SizedBox(height: 18), dayPanel],
            );
          },
        );
      },
    ),
  );
}

class _ScheduleDayPanel extends StatelessWidget {
  const _ScheduleDayPanel({
    required this.selectedDate,
    required this.schedules,
    required this.isLoading,
    required this.onAdd,
  });

  final DateTime selectedDate;
  final List<Schedule> schedules;
  final bool isLoading;
  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      SectionHeader(
        '${selectedDate.month}월 ${selectedDate.day}일 일정',
        trailing: TextButton.icon(
          onPressed: onAdd,
          icon: const Icon(Icons.add),
          label: const Text('일정 추가'),
        ),
      ),
      const SizedBox(height: 8),
      if (isLoading)
        const Center(child: CircularProgressIndicator())
      else if (schedules.isEmpty)
        InfoCard(
          color: const Color(0xFFF4FCFA),
          child: Column(
            children: [
              const TtubeeIdle(
                asset: 'assets/images/ttubee/empty-schedule.png',
              ),
              const SizedBox(height: 8),
              Text(
                '아직 등록된 일정이 없어요.',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 4),
              const Text('뚜비에게 중요한 일정을 알려주세요!'),
              const SizedBox(height: 14),
              FilledButton.icon(
                onPressed: onAdd,
                icon: const Icon(Icons.add),
                label: const Text('일정 등록하기'),
              ),
            ],
          ),
        )
      else
        ...schedules.map(
          (schedule) => Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: _ScheduleSummaryCard(schedule: schedule),
          ),
        ),
    ],
  );
}

class _ScheduleSummaryCard extends StatelessWidget {
  const _ScheduleSummaryCard({required this.schedule});

  final Schedule schedule;

  @override
  Widget build(BuildContext context) => Card(
    child: InkWell(
      borderRadius: BorderRadius.circular(18),
      onTap: () => context.push('/schedules/${schedule.id}'),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            Container(
              width: 58,
              padding: const EdgeInsets.symmetric(vertical: 10),
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: DdubiColors.blue.withValues(alpha: .1),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                schedule.time,
                style: const TextStyle(
                  color: DdubiColors.blue,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    schedule.title,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      const Icon(
                        Icons.location_on_outlined,
                        size: 15,
                        color: Color(0xFF667085),
                      ),
                      const SizedBox(width: 3),
                      Expanded(
                        child: Text(
                          schedule.repeatLabel == null
                              ? schedule.place
                              : '${schedule.place} · ${schedule.repeatLabel}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: Color(0xFF98A2B3)),
          ],
        ),
      ),
    ),
  );
}

class ScheduleFormScreen extends StatefulWidget {
  const ScheduleFormScreen({
    super.key,
    required this.repository,
    required this.placeSearchService,
    this.initialDate,
    this.schedule,
  });
  final ScheduleRepository repository;
  final PlaceSearchService placeSearchService;
  final DateTime? initialDate;
  final Schedule? schedule;
  @override
  State<ScheduleFormScreen> createState() => _ScheduleFormScreenState();
}

class RepeatScheduleFormScreen extends StatefulWidget {
  const RepeatScheduleFormScreen({
    super.key,
    required this.repository,
    this.initialDate,
  });

  final DdubiRepository repository;
  final DateTime? initialDate;

  @override
  State<RepeatScheduleFormScreen> createState() =>
      _RepeatScheduleFormScreenState();
}

class _RepeatScheduleFormScreenState extends State<RepeatScheduleFormScreen> {
  final formKey = GlobalKey<FormState>();
  final title = TextEditingController();
  final place = TextEditingController();
  final time = TextEditingController(text: '09:00');
  final departure = TextEditingController(text: '집');
  final weekdays = <int>{1, 2, 3, 4, 5};

  late DateTime startDate;
  late DateTime endDate;
  bool hasEndDate = false;
  bool considerLastTrip = false;
  ScheduleImportance importance = ScheduleImportance.high;
  TransportPreferenceChoice transport = TransportPreferenceChoice.balanced;

  @override
  void initState() {
    super.initState();
    startDate = widget.initialDate ?? DateTime.now();
    endDate = startDate.add(const Duration(days: 84));
  }

  @override
  void dispose() {
    title.dispose();
    place.dispose();
    time.dispose();
    departure.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AppPage(
    title: '반복 일정 등록',
    child: Form(
      key: formKey,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final isWide = constraints.maxWidth >= 760;
          final basic = _basicCard(context);
          final repeat = _repeatCard(context);
          final preference = _preferenceCard(context);
          return ListView(
            padding: _pad,
            children: [
              const TtubeeGuideCard(
                '출근이나 수업처럼 정해진 요일에 반복되는 일정을 한 번에 등록해요.',
                compact: true,
              ),
              const SizedBox(height: 16),
              if (isWide)
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(child: basic),
                    const SizedBox(width: 16),
                    Expanded(
                      child: Column(
                        children: [
                          repeat,
                          const SizedBox(height: 16),
                          preference,
                        ],
                      ),
                    ),
                  ],
                )
              else ...[
                basic,
                const SizedBox(height: 12),
                repeat,
                const SizedBox(height: 12),
                preference,
              ],
              const SizedBox(height: 16),
              PrimaryButton('반복 일정 저장하기', onPressed: _save),
              const SizedBox(height: 24),
            ],
          );
        },
      ),
    ),
  );

  Widget _basicCard(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('일정 정보', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 16),
        _field('일정 제목', title, key: const Key('repeatTitleField')),
        _field(
          '도착지',
          place,
          key: const Key('repeatPlaceField'),
          icon: Icons.search,
        ),
        _field(
          '반드시 도착해야 하는 시간',
          time,
          key: const Key('repeatTimeField'),
          icon: Icons.schedule,
          timeField: true,
        ),
        _field(
          '출발지',
          departure,
          key: const Key('repeatDepartureField'),
          icon: Icons.my_location,
        ),
      ],
    ),
  );

  Widget _repeatCard(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('반복 설정', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 16),
        const Text('반복 요일', style: TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: List.generate(7, (index) {
            final weekday = index + 1;
            return FilterChip(
              key: Key('repeatWeekday$weekday'),
              label: Text(_weekdays[index]),
              selected: weekdays.contains(weekday),
              onSelected: (selected) => setState(() {
                selected ? weekdays.add(weekday) : weekdays.remove(weekday);
              }),
            );
          }),
        ),
        if (weekdays.isEmpty) ...[
          const SizedBox(height: 8),
          const Text(
            '반복할 요일을 하나 이상 선택해주세요.',
            style: TextStyle(color: DdubiColors.red, fontSize: 12),
          ),
        ],
        const SizedBox(height: 18),
        _dateRow(
          context,
          label: '시작일',
          date: startDate,
          key: const Key('repeatStartDateButton'),
          onPressed: () => _pickStartDate(context),
        ),
        const SizedBox(height: 14),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('종료일 설정'),
          subtitle: Text(hasEndDate ? _fullDate(endDate) : '종료일 없음'),
          value: hasEndDate,
          onChanged: (value) => setState(() => hasEndDate = value),
        ),
        if (hasEndDate)
          _dateRow(
            context,
            label: '종료일',
            date: endDate,
            key: const Key('repeatEndDateButton'),
            onPressed: () => _pickEndDate(context),
          ),
      ],
    ),
  );

  Widget _preferenceCard(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('이동 설정', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 16),
        const Text('중요도', style: TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        SegmentedButton<ScheduleImportance>(
          segments: const [
            ButtonSegment(value: ScheduleImportance.normal, label: Text('일반')),
            ButtonSegment(value: ScheduleImportance.high, label: Text('중요')),
            ButtonSegment(
              value: ScheduleImportance.critical,
              label: Text('절대 지각 불가'),
            ),
          ],
          selected: {importance},
          onSelectionChanged: (value) =>
              setState(() => importance = value.first),
        ),
        const SizedBox(height: 12),
        SwitchListTile(
          key: const Key('repeatConsiderLastTripSwitch'),
          contentPadding: EdgeInsets.zero,
          title: const Text('막차 고려'),
          subtitle: const Text('늦은 반복 일정의 막차 위험을 함께 확인해요.'),
          value: considerLastTrip,
          onChanged: (value) => setState(() => considerLastTrip = value),
        ),
        const SizedBox(height: 18),
        const Text('교통수단 선호', style: TextStyle(fontWeight: FontWeight.w700)),
        TransportPreferenceSelector(
          value: transport,
          onChanged: (value) => setState(() => transport = value),
        ),
      ],
    ),
  );

  Widget _field(
    String label,
    TextEditingController controller, {
    required Key key,
    IconData? icon,
    bool timeField = false,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 15),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 6),
        TextFormField(
          key: key,
          controller: controller,
          keyboardType: timeField ? TextInputType.datetime : TextInputType.text,
          validator: (value) {
            final text = (value ?? '').trim();
            if (text.isEmpty) return '필수 입력 항목입니다.';
            if (timeField &&
                !RegExp(r'^([01]\d|2[0-3]):[0-5]\d$').hasMatch(text)) {
              return '시간을 HH:mm 형식으로 입력해주세요.';
            }
            return null;
          },
          decoration: InputDecoration(
            suffixIcon: icon == null ? null : Icon(icon),
          ),
        ),
      ],
    ),
  );

  Widget _dateRow(
    BuildContext context, {
    required String label,
    required DateTime date,
    required Key key,
    required VoidCallback onPressed,
  }) => Row(
    children: [
      Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
            Text(_fullDate(date)),
          ],
        ),
      ),
      OutlinedButton.icon(
        key: key,
        onPressed: onPressed,
        icon: const Icon(Icons.calendar_month_outlined),
        label: const Text('변경'),
      ),
    ],
  );

  Future<void> _pickStartDate(BuildContext context) async {
    final selected = await showDatePicker(
      context: context,
      initialDate: startDate,
      firstDate: DateTime.now().subtract(const Duration(days: 1)),
      lastDate: DateTime.now().add(const Duration(days: 365 * 2)),
    );
    if (selected == null || !mounted) return;
    setState(() {
      startDate = selected;
      if (endDate.isBefore(startDate)) {
        endDate = startDate.add(const Duration(days: 84));
      }
    });
  }

  Future<void> _pickEndDate(BuildContext context) async {
    final selected = await showDatePicker(
      context: context,
      initialDate: endDate.isBefore(startDate) ? startDate : endDate,
      firstDate: startDate,
      lastDate: startDate.add(const Duration(days: 365 * 2)),
    );
    if (selected != null && mounted) setState(() => endDate = selected);
  }

  Future<void> _save() async {
    setState(() {});
    if (weekdays.isEmpty || !(formKey.currentState?.validate() ?? false)) {
      return;
    }
    await widget.repository.addRepeatSchedule(
      RepeatScheduleRule(
        id: 'repeat-${DateTime.now().microsecondsSinceEpoch}',
        title: title.text.trim(),
        place: place.text.trim(),
        departurePlace: departure.text.trim(),
        time: time.text.trim(),
        startDate: startDate,
        weekdays: {...weekdays},
        untilDate: hasEndDate ? endDate : null,
        importance: importance,
        considerLastTrip: considerLastTrip,
        preferredTransport: transport,
      ),
    );
    if (mounted) context.pop(true);
  }
}

class _ScheduleFormScreenState extends State<ScheduleFormScreen> {
  late ScheduleImportance importance;
  TransportPreferenceChoice transport = TransportPreferenceChoice.balanced;
  late bool considerLastTrip;
  late DateTime selectedDate;
  final formKey = GlobalKey<FormState>();
  late final TextEditingController title;
  late final TextEditingController time;
  PlaceLocation? origin;
  PlaceLocation? destination;

  @override
  void initState() {
    super.initState();
    final schedule = widget.schedule;
    title = TextEditingController(text: schedule?.title ?? '잠실야구장');
    time = TextEditingController(text: schedule?.time ?? '19:30');
    selectedDate = schedule?.date ?? widget.initialDate ?? DateTime.now();
    origin = schedule?.origin;
    destination = schedule?.destination;
    importance = schedule?.importance ?? ScheduleImportance.high;
    considerLastTrip = schedule?.considerLastTrip ?? false;
    transport =
        schedule?.preferredTransport ?? TransportPreferenceChoice.balanced;
  }

  @override
  void dispose() {
    title.dispose();
    time.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext c) => AppPage(
    title: widget.schedule == null ? '일정 등록' : '일정 수정',
    child: Form(
      key: formKey,
      child: ListView(
        padding: _pad,
        children: [
          _field('일정 제목', title),
          PlaceSearchField(
            key: const Key('destinationPlaceField'),
            label: '도착지',
            value: destination,
            searchService: widget.placeSearchService,
            onSelected: (value) => setState(() => destination = value),
          ),
          Row(
            children: [
              Expanded(
                child: Text(
                  _fullDate(selectedDate),
                  style: Theme.of(c).textTheme.bodySmall,
                ),
              ),
              OutlinedButton.icon(
                key: const Key('scheduleDateButton'),
                onPressed: () => _pickDate(c),
                icon: const Icon(Icons.calendar_month_outlined),
                label: const Text('날짜 변경'),
              ),
            ],
          ),
          const SizedBox(height: 12),
          _field('반드시 도착해야 하는 시간', time, icon: Icons.schedule),
          PlaceSearchField(
            key: const Key('originPlaceField'),
            label: '출발지',
            value: origin,
            searchService: widget.placeSearchService,
            onSelected: (value) => setState(() => origin = value),
          ),
          const Text('중요도', style: TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: 8),
          SegmentedButton<ScheduleImportance>(
            segments: const [
              ButtonSegment(
                value: ScheduleImportance.normal,
                label: Text('일반'),
              ),
              ButtonSegment(value: ScheduleImportance.high, label: Text('중요')),
              ButtonSegment(
                value: ScheduleImportance.critical,
                label: Text('절대 지각 불가'),
              ),
            ],
            selected: {importance},
            onSelectionChanged: (v) => setState(() => importance = v.first),
          ),
          const SizedBox(height: 20),
          const Text('교통수단 선호', style: TextStyle(fontWeight: FontWeight.w700)),
          TransportPreferenceSelector(
            value: transport,
            onChanged: (value) => setState(() => transport = value),
          ),
          const SizedBox(height: 12),
          SwitchListTile(
            key: const Key('considerLastTripSwitch'),
            contentPadding: EdgeInsets.zero,
            title: const Text('막차 고려'),
            subtitle: const Text('늦은 일정의 막차 위험을 함께 확인해요.'),
            value: considerLastTrip,
            onChanged: (value) => setState(() => considerLastTrip = value),
          ),
          const SizedBox(height: 10),
          PrimaryButton(
            widget.schedule == null ? '저장하기' : '수정 완료',
            onPressed: _save,
          ),
        ],
      ),
    ),
  );

  Future<void> _save() async {
    if (!(formKey.currentState?.validate() ?? false)) return;
    if (origin == null || destination == null) return;
    final date = selectedDate;
    final parts = time.text.trim().split(':');
    final arriveBy = DateTime(
      date.year,
      date.month,
      date.day,
      int.parse(parts.first),
      int.parse(parts.last),
    );
    final schedule = Schedule(
      id:
          widget.schedule?.id ??
          'schedule-${DateTime.now().microsecondsSinceEpoch}',
      title: title.text.trim(),
      origin: origin!,
      destination: destination!,
      arriveBy: arriveBy,
      importance: importance,
      considerLastTrip: considerLastTrip,
      preferredTransport: transport,
      repeatLabel: widget.schedule?.repeatLabel,
      updatedAt: widget.schedule?.updatedAt,
    );
    try {
      if (widget.schedule == null) {
        await widget.repository.addSchedule(schedule);
      } else {
        await widget.repository.updateSchedule(schedule);
      }
      if (mounted) context.pop(true);
    } on ScheduleConflictException {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('다른 기기에서 일정이 변경됐어요. 새로 고침 후 다시 시도해주세요.')),
      );
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            widget.schedule == null
                ? '일정을 저장하지 못했어요. 입력값과 연결 상태를 확인해주세요.'
                : '일정을 수정하지 못했어요. 잠시 후 다시 시도해주세요.',
          ),
        ),
      );
    }
  }

  Future<void> _pickDate(BuildContext context) async {
    final selected = await showDatePicker(
      context: context,
      initialDate: selectedDate,
      firstDate: DateTime.now().subtract(const Duration(days: 1)),
      lastDate: DateTime.now().add(const Duration(days: 365 * 2)),
    );
    if (selected != null && mounted) setState(() => selectedDate = selected);
  }

  Widget _field(
    String label,
    TextEditingController controller, {
    IconData? icon,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 15),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12),
        ),
        const SizedBox(height: 6),
        TextFormField(
          controller: controller,
          validator: (value) {
            final text = (value ?? '').trim();
            if (text.isEmpty) return '필수 입력 항목입니다.';
            if (controller == time &&
                !RegExp(r'^([01]\d|2[0-3]):[0-5]\d$').hasMatch(text)) {
              return '시간을 HH:mm 형식으로 입력해주세요.';
            }
            return null;
          },
          decoration: InputDecoration(
            suffixIcon: icon == null ? null : Icon(icon),
          ),
        ),
      ],
    ),
  );
}

class ScheduleDetailScreen extends StatelessWidget {
  const ScheduleDetailScreen({
    super.key,
    required this.repository,
    required this.scheduleId,
  });
  final ScheduleRepository repository;
  final String scheduleId;

  @override
  Widget build(BuildContext c) => FutureBuilder<Schedule?>(
    future: repository.scheduleById(scheduleId),
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
      return _content(context, schedule);
    },
  );

  Widget _content(BuildContext c, Schedule schedule) => AppPage(
    title: '일정 상세',
    child: ListView(
      padding: _pad,
      children: [
        Container(
          padding: const EdgeInsets.all(18),
          decoration: BoxDecoration(
            color: DdubiColors.blue,
            borderRadius: BorderRadius.circular(14),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                _fullDate(schedule.date),
                style: const TextStyle(color: Colors.white, fontSize: 12),
              ),
              const SizedBox(height: 8),
              Text(
                '${schedule.time} ${schedule.title}',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 23,
                  fontWeight: FontWeight.w800,
                ),
              ),
              Text(
                schedule.place,
                style: const TextStyle(color: Colors.white70),
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        Row(
          children: [
            Expanded(
              child: InfoCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('도착까지 남은 시간'),
                    SizedBox(height: 7),
                    Text(
                      '2시간 48분',
                      style: TextStyle(
                        fontSize: 21,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    Text('(17:57 출발 기준)', style: TextStyle(fontSize: 11)),
                  ],
                ),
              ),
            ),
            const SizedBox(width: 10),
            const Expanded(
              child: InfoCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('데이터 최신성'),
                    SizedBox(height: 7),
                    Text('12분 전 업데이트'),
                    StatusBadge('최신 데이터'),
                  ],
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 14),
        InfoCard(
          child: Row(
            children: [
              Stack(
                alignment: Alignment.center,
                children: [
                  SizedBox(
                    width: 75,
                    height: 75,
                    child: CircularProgressIndicator(
                      value: .92,
                      strokeWidth: 8,
                      color: DdubiColors.green,
                      backgroundColor: DdubiColors.surface,
                    ),
                  ),
                  Text('92%', style: Theme.of(c).textTheme.titleLarge),
                ],
              ),
              const SizedBox(width: 18),
              const Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('현재 도착 신뢰도'),
                    StatusBadge('매우 있음'),
                    Text('평소보다 15분 여유 있어요.', style: TextStyle(fontSize: 12)),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        PrimaryButton(
          '추천 경로 보기',
          onPressed: () => c.push('/recommendations/mock-recommendation-1'),
        ),
        const SizedBox(height: 10),
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
        const SizedBox(height: 18),
        const SectionHeader('한눈에 보기'),
        const SizedBox(height: 8),
        const Row(
          children: [
            Expanded(
              child: InfoCard(
                child: Column(
                  children: [
                    Icon(Icons.sunny, color: Colors.orange),
                    Text('날씨'),
                    Text('맑음 22℃'),
                  ],
                ),
              ),
            ),
            SizedBox(width: 8),
            Expanded(
              child: InfoCard(
                child: Column(
                  children: [
                    Icon(Icons.groups_outlined),
                    Text('행사/이벤트'),
                    Text('대규모 행사 없음'),
                  ],
                ),
              ),
            ),
            SizedBox(width: 8),
            Expanded(
              child: InfoCard(
                child: Column(
                  children: [
                    Icon(Icons.check_circle_outline, color: DdubiColors.green),
                    Text('교통 상황'),
                    Text('지금은 정상'),
                  ],
                ),
              ),
            ),
          ],
        ),
      ],
    ),
  );
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

class RouteResultsScreen extends StatelessWidget {
  const RouteResultsScreen({
    super.key,
    required this.repository,
    this.condition,
    this.go,
  });
  final DdubiRepository repository;
  final RouteSearchCondition? condition;
  final ValueChanged<int>? go;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '추천 결과',
    webTitle: '경로 비교',
    webSubtitle: '도착 시간과 이동 조건을 기준으로 추천 경로를 비교하세요.',
    index: go == null ? null : 1,
    onNav: go,
    actions: const [IconButton(onPressed: null, icon: Icon(Icons.refresh))],
    child: FutureBuilder<List<RouteOption>>(
      future: repository.routes(condition: condition),
      builder: (c, s) {
        if (s.hasError) {
          return const DataLoadError(message: '추천 경로를 불러오지 못했어요.');
        }
        if (!s.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        return LayoutBuilder(
          builder: (context, constraints) {
            final isWide =
                constraints.maxWidth >= LayoutConstants.wideScreenBreakpoint;
            return isWide
                ? _webRouteResults(c, s.data!)
                : _mobileRouteResults(c, s.data!);
          },
        );
      },
    ),
  );

  Widget _mobileRouteResults(BuildContext context, List<RouteOption> routes) =>
      ListView(
        key: const Key('mobileRouteResultsLayout'),
        padding: _pad,
        children: [
          if (condition != null) ...[
            _RouteSearchSummary(condition: condition!),
            const SizedBox(height: 12),
          ],
          _routeGuide,
          const SizedBox(height: 12),
          ...routes.map(
            (route) => Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: RouteCard(
                route,
                onTap: () => _openRouteMap(context, route),
              ),
            ),
          ),
          _recommendationReason,
        ],
      );

  Widget _webRouteResults(BuildContext context, List<RouteOption> routes) =>
      ListView(
        key: const Key('webRouteResultsLayout'),
        padding: _pad,
        children: [
          if (condition != null) ...[
            _RouteSearchSummary(condition: condition!),
            const SizedBox(height: 16),
          ],
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Expanded(flex: 3, child: _routeGuide),
              const SizedBox(width: 16),
              const Expanded(flex: 2, child: _recommendationReason),
            ],
          ),
          const SizedBox(height: 20),
          Text('경로 비교', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 12),
          Row(
            key: const Key('webRouteResultsGrid'),
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (var index = 0; index < routes.length; index++) ...[
                if (index > 0) const SizedBox(width: 12),
                Expanded(
                  child: RouteCard(
                    routes[index],
                    compact: true,
                    fillWidth: true,
                    onTap: () => _openRouteMap(context, routes[index]),
                  ),
                ),
              ],
            ],
          ),
        ],
      );

  void _openRouteMap(BuildContext context, RouteOption route) => _open(
    context,
    RouteMapScreen(
      repository: repository,
      selectedRoute: route,
      condition: condition,
    ),
  );

  static const _routeGuide = TtubeeGuideCard(
    '이 경로가 지금 가장 안정적이에요!',
    compact: true,
    character: TtubeeEntrance(
      asset: 'assets/images/ttubee/route-guide.png',
      height: 62,
      fromRight: true,
      semanticLabel: '지도를 든 뚜비',
    ),
  );

  static const _recommendationReason = InfoCard(
    color: Color(0xFFF4F8FF),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        StatusBadge(
          '추천 이유',
          tone: DdubiColors.blue,
          icon: Icons.lightbulb_outline,
        ),
        SizedBox(height: 10),
        Text(
          '현재 버스 정차 구간이 혼잡해 버스 도착 예측 신뢰도가 낮아요.\n현재 정상 운행 중인 지하철 2호선 경로를 추천드려요.',
        ),
      ],
    ),
  );
}

class RouteSearchScreen extends StatefulWidget {
  const RouteSearchScreen({super.key, required this.repository});

  final DdubiRepository repository;

  @override
  State<RouteSearchScreen> createState() => _RouteSearchScreenState();
}

class _RouteSearchScreenState extends State<RouteSearchScreen> {
  final _formKey = GlobalKey<FormState>();
  final _departure = TextEditingController(text: '집');
  final _destination = TextEditingController(text: 'SSAFY 서울캠퍼스');
  late DateTime _desiredArrivalAt;
  late final TextEditingController _arrival;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    _desiredArrivalAt = DateTime(now.year, now.month, now.day + 1, 10);
    _arrival = TextEditingController(text: _formatArrival(_desiredArrivalAt));
  }

  @override
  void dispose() {
    _departure.dispose();
    _destination.dispose();
    _arrival.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AppPage(
    title: '경로 찾기',
    webTitle: '경로 탐색',
    webSubtitle: '출발지와 도착 희망시간을 입력해 이동 계획을 만들어보세요.',
    child: Form(
      key: _formKey,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final isWide =
              constraints.maxWidth >= LayoutConstants.wideScreenBreakpoint;
          return ListView(
            padding: _pad,
            children: [
              if (isWide)
                Row(
                  key: const Key('webRouteSearchLayout'),
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Expanded(flex: 2, child: _RouteSearchGuide()),
                    const SizedBox(width: 20),
                    Expanded(flex: 3, child: _searchFormCard(context, true)),
                  ],
                )
              else
                Column(
                  key: const Key('mobileRouteSearchLayout'),
                  children: [
                    const TtubeeGuideCard(
                      '언제 어디까지 가야 하는지 알려주세요.\n뚜비가 알맞은 경로를 찾아드릴게요!',
                      compact: true,
                    ),
                    const SizedBox(height: 16),
                    _searchFormCard(context, false),
                  ],
                ),
            ],
          );
        },
      ),
    ),
  );

  Widget _searchFormCard(BuildContext context, bool isWide) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('이동 조건', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 16),
        if (isWide)
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(child: _placeField(true)),
              const SizedBox(width: 12),
              Expanded(child: _placeField(false)),
            ],
          )
        else ...[
          _placeField(true),
          const SizedBox(height: 12),
          _placeField(false),
        ],
        const SizedBox(height: 12),
        TextFormField(
          key: const Key('routeArrivalField'),
          controller: _arrival,
          readOnly: true,
          onTap: _pickArrival,
          decoration: const InputDecoration(
            labelText: '도착 희망시간',
            prefixIcon: Icon(Icons.schedule_outlined),
            suffixIcon: Icon(Icons.edit_calendar_outlined),
          ),
        ),
        const SizedBox(height: 20),
        PrimaryButton('추천 경로 찾기', onPressed: _search),
      ],
    ),
  );

  Widget _placeField(bool departure) {
    final controller = departure ? _departure : _destination;
    return TextFormField(
      key: Key(departure ? 'routeDepartureField' : 'routeDestinationField'),
      controller: controller,
      textInputAction: departure ? TextInputAction.next : TextInputAction.done,
      validator: (value) => (value ?? '').trim().isEmpty ? '장소를 입력해주세요.' : null,
      decoration: InputDecoration(
        labelText: departure ? '출발지' : '도착지',
        prefixIcon: Icon(
          departure ? Icons.my_location_outlined : Icons.location_on_outlined,
        ),
      ),
    );
  }

  Future<void> _pickArrival() async {
    final date = await showDatePicker(
      context: context,
      initialDate: _desiredArrivalAt,
      firstDate: DateTime.now(),
      lastDate: DateTime.now().add(const Duration(days: 365)),
    );
    if (date == null || !mounted) return;
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(_desiredArrivalAt),
    );
    if (time == null || !mounted) return;
    setState(() {
      _desiredArrivalAt = DateTime(
        date.year,
        date.month,
        date.day,
        time.hour,
        time.minute,
      );
      _arrival.text = _formatArrival(_desiredArrivalAt);
    });
  }

  void _search() {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    final condition = RouteSearchCondition(
      departurePlace: _departure.text.trim(),
      destinationPlace: _destination.text.trim(),
      desiredArrivalAt: _desiredArrivalAt,
    );
    context.push('/recommendations/mock-recommendation-1', extra: condition);
  }
}

class _RouteSearchGuide extends StatelessWidget {
  const _RouteSearchGuide();

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      const TtubeeGuideCard('언제 어디까지 가야 하는지 알려주세요.\n뚜비가 알맞은 경로를 찾아드릴게요!'),
      const SizedBox(height: 16),
      const InfoCard(
        color: Color(0xFFF4F8FF),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            StatusBadge(
              '어떻게 추천하나요?',
              tone: DdubiColors.blue,
              icon: Icons.info_outline,
            ),
            SizedBox(height: 10),
            Text('도착 희망시간과 실시간 교통 상황을 함께 고려해 경로를 비교해요.'),
          ],
        ),
      ),
    ],
  );
}

class _RouteSearchSummary extends StatelessWidget {
  const _RouteSearchSummary({required this.condition});

  final RouteSearchCondition condition;

  @override
  Widget build(BuildContext context) => InfoCard(
    color: const Color(0xFFF4F8FF),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '${condition.departurePlace}  →  ${condition.destinationPlace}',
          style: const TextStyle(fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 6),
        Text('${_formatArrival(condition.desiredArrivalAt)}까지 도착'),
        Align(
          alignment: Alignment.centerRight,
          child: TextButton(
            onPressed: () => context.pop(),
            child: const Text('조건 수정'),
          ),
        ),
      ],
    ),
  );
}

String _formatArrival(DateTime value) =>
    '${value.year}.${value.month.toString().padLeft(2, '0')}.${value.day.toString().padLeft(2, '0')} '
    '${value.hour.toString().padLeft(2, '0')}:${value.minute.toString().padLeft(2, '0')}';

class RouteMapScreen extends StatefulWidget {
  const RouteMapScreen({
    super.key,
    required this.repository,
    this.selectedRoute,
    this.condition,
  });

  final DdubiRepository repository;
  final RouteOption? selectedRoute;
  final RouteSearchCondition? condition;

  @override
  State<RouteMapScreen> createState() => _RouteMapScreenState();
}

class _RouteMapScreenState extends State<RouteMapScreen> {
  late final Future<List<RouteOption>> _routes;
  late RouteKind _selectedKind;

  @override
  void initState() {
    super.initState();
    _selectedKind = widget.selectedRoute?.kind ?? RouteKind.recommended;
    _routes = widget.repository.routes(condition: widget.condition);
  }

  @override
  Widget build(BuildContext context) => AppPage(
    title: '전체 지도',
    webTitle: '경로 지도',
    webSubtitle: '추천 경로와 이동 단계, 현재 위치를 한 화면에서 확인하세요.',
    child: FutureBuilder<List<RouteOption>>(
      future: _routes,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const DataLoadError(message: '지도 경로를 불러오지 못했어요.');
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final routes = snapshot.data!;
        final selected = routes.firstWhere(
          (route) => route.kind == _selectedKind,
          orElse: () => routes.first,
        );
        return LayoutBuilder(
          builder: (context, constraints) {
            final isWide = constraints.maxWidth >= 760;
            final map = _RouteMapView(
              routes: routes,
              selectedKind: selected.kind,
              departureLabel: widget.condition?.departurePlace ?? '역삼역 입구',
              destinationLabel: widget.condition?.destinationPlace ?? '잠실야구장',
              onSelected: (kind) => setState(() => _selectedKind = kind),
            );
            final detail = _RouteMapDetail(
              route: selected,
              repository: widget.repository,
              departureLabel: widget.condition?.departurePlace ?? '역삼역 입구',
              destinationLabel: widget.condition?.destinationPlace ?? '잠실야구장',
            );
            if (isWide) {
              return Padding(
                key: const Key('webRouteMapLayout'),
                padding: const EdgeInsets.all(16),
                child: Row(
                  children: [
                    Expanded(flex: 3, child: map),
                    const SizedBox(width: 16),
                    SizedBox(
                      width: 360,
                      child: SingleChildScrollView(child: detail),
                    ),
                  ],
                ),
              );
            }
            return Column(
              key: const Key('mobileRouteMapLayout'),
              children: [
                Expanded(flex: 5, child: map),
                Flexible(
                  flex: 4,
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 16),
                    child: detail,
                  ),
                ),
              ],
            );
          },
        );
      },
    ),
  );
}

class _RouteMapView extends StatefulWidget {
  const _RouteMapView({
    required this.routes,
    required this.selectedKind,
    required this.departureLabel,
    required this.destinationLabel,
    required this.onSelected,
  });

  final List<RouteOption> routes;
  final RouteKind selectedKind;
  final String departureLabel;
  final String destinationLabel;
  final ValueChanged<RouteKind> onSelected;

  @override
  State<_RouteMapView> createState() => _RouteMapViewState();
}

class _RouteMapViewState extends State<_RouteMapView> {
  KakaoMapController? _mapController;

  static const _departure = LatLng(latitude: 37.5089, longitude: 127.0631);
  static const _current = LatLng(latitude: 37.5045, longitude: 127.0490);
  static const _destination = LatLng(latitude: 37.5006, longitude: 127.0364);
  static const _center = LatLng(latitude: 37.5048, longitude: 127.0498);
  static const _cameraAnimation = CameraAnimation(
    duration: 450,
    autoElevation: true,
    isConsecutive: false,
  );

  int get _entireRouteLevel => kIsWeb ? 5 : 13;

  @override
  void didUpdateWidget(covariant _RouteMapView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.selectedKind != widget.selectedKind) {
      _showEntireRoute();
    }
  }

  Future<void> _onMapCreated(KakaoMapController controller) async {
    _mapController = controller;
    try {
      if (!kIsWeb) {
        await controller.addMarkerLayer(
          layerId: KakaoMapController.defaultLabelLayerId,
          zOrder: 1000,
          clickable: false,
        );
      }
      await controller.addMarkers(
        markerOptions: [
          MarkerOption(id: 'departure', latLng: _departure, text: '출발'),
          MarkerOption(id: 'current', latLng: _current, text: '현재 위치'),
          MarkerOption(id: 'destination', latLng: _destination, text: '도착'),
        ],
      );
      await _showEntireRoute();
    } catch (error) {
      debugPrint('Kakao Maps marker setup failed: $error');
    }
  }

  Future<void> _showEntireRoute() =>
      _moveCamera(position: _center, zoomLevel: _entireRouteLevel);

  Future<void> _showCurrentLocation() async {
    final controller = _mapController;
    if (controller == null) return;
    try {
      final currentZoomLevel = await controller.getZoomLevel();
      if (currentZoomLevel == null) return;
      await _moveCamera(position: _current, zoomLevel: currentZoomLevel);
    } catch (error) {
      debugPrint('Kakao Maps zoom level lookup failed: $error');
    }
  }

  Future<void> _moveCamera({
    required LatLng position,
    required int zoomLevel,
  }) async {
    final controller = _mapController;
    if (controller == null) return;
    try {
      await controller.moveCamera(
        cameraUpdate: CameraUpdate(position: position, zoomLevel: zoomLevel),
        animation: _cameraAnimation,
      );
    } catch (error) {
      debugPrint('Kakao Maps camera move failed: $error');
    }
  }

  void _handleRouteSelected(RouteKind kind) {
    widget.onSelected(kind);
    if (kind == widget.selectedKind) {
      _showEntireRoute();
    }
  }

  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(16),
    child: LayoutBuilder(
      builder: (context, constraints) => Stack(
        children: [
          Positioned.fill(
            child: KakaoMapConfig.isReady
                ? KakaoMap(
                    initialPosition: _center,
                    initialLevel: _entireRouteLevel,
                    onMapCreated: _onMapCreated,
                  )
                : ColoredBox(
                    color: const Color(0xFFEAF2EC),
                    child: CustomPaint(
                      painter: _MapPainter(selectedKind: widget.selectedKind),
                    ),
                  ),
          ),
          Positioned(
            left: 12,
            right: 12,
            top: 12,
            child: Material(
              color: Colors.white.withValues(alpha: .94),
              borderRadius: BorderRadius.circular(12),
              child: Padding(
                padding: const EdgeInsets.all(8),
                child: Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: widget.routes
                      .map(
                        (route) => ChoiceChip(
                          label: Text(route.label),
                          selected: route.kind == widget.selectedKind,
                          avatar: CircleAvatar(
                            backgroundColor: _routeTone(route.kind),
                            radius: 5,
                          ),
                          onSelected: (_) => _handleRouteSelected(route.kind),
                        ),
                      )
                      .toList(),
                ),
              ),
            ),
          ),
          if (!KakaoMapConfig.isReady) ...[
            Positioned(
              key: const Key('mapCurrentMarker'),
              left: constraints.maxWidth * .42,
              top: constraints.maxHeight * .54,
              child: const _MapMarker(
                label: '현재 위치 (Mock)',
                icon: Icons.my_location,
                tone: DdubiColors.blue,
              ),
            ),
            Positioned(
              key: const Key('mapDepartureMarker'),
              left: 12,
              bottom: 14,
              child: _MapMarker(
                label: '출발 · ${widget.departureLabel}',
                icon: Icons.trip_origin,
                tone: DdubiColors.green,
              ),
            ),
            Positioned(
              key: const Key('mapDestinationMarker'),
              right: 12,
              top: constraints.maxHeight * .22,
              child: _MapMarker(
                label: '도착 · ${widget.destinationLabel}',
                icon: Icons.location_on,
                tone: DdubiColors.red,
              ),
            ),
          ],
          Positioned(
            right: 12,
            bottom: 12,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                FloatingActionButton.small(
                  key: const Key('mapEntireRouteButton'),
                  heroTag: 'map-entire-route',
                  tooltip: '전체 경로 보기',
                  onPressed: KakaoMapConfig.isReady ? _showEntireRoute : null,
                  backgroundColor: Colors.white,
                  foregroundColor: DdubiColors.blue,
                  child: const Icon(Icons.route),
                ),
                const SizedBox(height: 8),
                FloatingActionButton.small(
                  key: const Key('mapCurrentLocationButton'),
                  heroTag: 'map-current-location',
                  tooltip: '현재 위치로 이동',
                  onPressed: KakaoMapConfig.isReady
                      ? _showCurrentLocation
                      : null,
                  backgroundColor: Colors.white,
                  foregroundColor: DdubiColors.blue,
                  child: const Icon(Icons.my_location),
                ),
                const SizedBox(height: 8),
                _MapModeBadge(isKakaoMap: KakaoMapConfig.isReady),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

class _MapModeBadge extends StatelessWidget {
  const _MapModeBadge({required this.isKakaoMap});

  final bool isKakaoMap;

  @override
  Widget build(BuildContext context) => Container(
    key: const Key('mapModeBadge'),
    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: .92),
      borderRadius: BorderRadius.circular(12),
    ),
    child: Text(
      isKakaoMap ? 'Kakao Map · Mock 좌표' : 'Mock 지도 · Kakao 키 미설정',
      style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700),
    ),
  );
}

class _MapMarker extends StatelessWidget {
  const _MapMarker({
    required this.label,
    required this.icon,
    required this.tone,
  });

  final String label;
  final IconData icon;
  final Color tone;

  @override
  Widget build(BuildContext context) => Container(
    constraints: const BoxConstraints(maxWidth: 170),
    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 7),
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(18),
      boxShadow: const [
        BoxShadow(
          color: Color(0x24000000),
          blurRadius: 8,
          offset: Offset(0, 3),
        ),
      ],
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, color: tone, size: 18),
        const SizedBox(width: 5),
        Flexible(
          child: Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700),
          ),
        ),
      ],
    ),
  );
}

class _RouteMapDetail extends StatelessWidget {
  const _RouteMapDetail({
    required this.route,
    required this.repository,
    required this.departureLabel,
    required this.destinationLabel,
  });

  final RouteOption route;
  final DdubiRepository repository;
  final String departureLabel;
  final String destinationLabel;

  @override
  Widget build(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        StatusBadge(
          route.label,
          tone: _routeTone(route.kind),
          icon: Icons.route_outlined,
        ),
        const SizedBox(height: 12),
        Wrap(
          alignment: WrapAlignment.spaceBetween,
          runSpacing: 8,
          children: [
            Text(
              '${route.departure} 출발',
              style: const TextStyle(fontWeight: FontWeight.w800),
            ),
            const Icon(Icons.arrow_forward, size: 18),
            Text(
              '${route.arrival} 도착',
              style: const TextStyle(fontWeight: FontWeight.w800),
            ),
            Text(
              route.margin,
              style: const TextStyle(
                color: DdubiColors.green,
                fontWeight: FontWeight.w800,
              ),
            ),
          ],
        ),
        const Divider(height: 25),
        Text(route.transport, style: Theme.of(context).textTheme.titleSmall),
        Text('${route.detail} · ${route.fare}'),
        const SizedBox(height: 10),
        ...[
          '${route.departure}  출발 · $departureLabel',
          '이동 중  ${route.transport}',
          '${route.arrival}  도착 · $destinationLabel',
        ].map(
          (text) => ListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            leading: Icon(
              Icons.circle,
              size: 12,
              color: _routeTone(route.kind),
            ),
            title: Text(text),
          ),
        ),
        PrimaryButton(
          '이동 안내 시작',
          onPressed: () => _open(
            context,
            MovingScreen(repository: repository, route: route),
          ),
        ),
      ],
    ),
  );
}

Color _routeTone(RouteKind kind) => switch (kind) {
  RouteKind.recommended => DdubiColors.green,
  RouteKind.fastest => DdubiColors.blue,
  RouteKind.safest => const Color(0xFF7257E8),
};

class _MapPainter extends CustomPainter {
  const _MapPainter({required this.selectedKind});

  final RouteKind selectedKind;

  @override
  void paint(Canvas canvas, Size size) {
    final road = Paint()
      ..color = Colors.white
      ..strokeWidth = 18
      ..style = PaintingStyle.stroke;
    final basePath = Path()
      ..moveTo(size.width * .2, size.height * .84)
      ..cubicTo(
        size.width * .55,
        size.height * .72,
        size.width * .18,
        size.height * .5,
        size.width * .58,
        size.height * .4,
      )
      ..cubicTo(
        size.width * .82,
        size.height * .32,
        size.width * .55,
        size.height * .16,
        size.width * .75,
        size.height * .08,
      );
    canvas.drawPath(basePath, road);

    for (final kind in RouteKind.values.where((kind) => kind != selectedKind)) {
      _drawRoute(canvas, size, kind, selected: false);
    }
    _drawRoute(canvas, size, selectedKind, selected: true);
  }

  void _drawRoute(
    Canvas canvas,
    Size size,
    RouteKind kind, {
    required bool selected,
  }) {
    final offset = switch (kind) {
      RouteKind.recommended => 0.0,
      RouteKind.fastest => -18.0,
      RouteKind.safest => 18.0,
    };
    final path = Path()
      ..moveTo(size.width * .2, size.height * .84)
      ..cubicTo(
        size.width * .55 + offset,
        size.height * .72,
        size.width * .18 + offset,
        size.height * .5,
        size.width * .58,
        size.height * .4,
      )
      ..cubicTo(
        size.width * .82 + offset,
        size.height * .32,
        size.width * .55 + offset,
        size.height * .16,
        size.width * .75,
        size.height * .08,
      );
    final paint = Paint()
      ..color = _routeTone(kind).withValues(alpha: selected ? 1 : .32)
      ..strokeWidth = selected ? 8 : 5
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(covariant _MapPainter oldDelegate) =>
      oldDelegate.selectedKind != selectedKind;
}

class DelayAlertScreen extends StatelessWidget {
  const DelayAlertScreen({super.key, required this.repository});
  final DdubiRepository repository;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '상황 변경 알림',
    child: ListView(
      padding: _pad,
      children: [
        const InfoCard(
          color: Color(0xFFFFF5ED),
          child: Row(
            children: [
              Icon(
                Icons.warning_amber_rounded,
                color: DdubiColors.orange,
                size: 30,
              ),
              SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '버스 지연이 커졌어요',
                      style: TextStyle(
                        color: DdubiColors.orange,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    Text('현재 경로로는 늦어질 수 있어요.'),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        const InfoCard(
          child: ListTile(
            contentPadding: EdgeInsets.zero,
            title: Text('기존 경로 (버스)'),
            subtitle: Text('강남역 → 여의도역\n예상 도착  오전 9:47'),
            trailing: StatusBadge(
              '+8분 지연',
              tone: DdubiColors.orange,
              icon: Icons.warning_amber,
            ),
          ),
        ),
        const SizedBox(height: 12),
        const InfoCard(
          child: ListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(
              '새로운 경로 (지하철)',
              style: TextStyle(color: DdubiColors.blue),
            ),
            subtitle: Text('강남역 → 여의도역\n예상 도착  오전 9:39'),
            trailing: StatusBadge('제시간 도착'),
          ),
        ),
        const SizedBox(height: 12),
        const InfoCard(
          color: Color(0xFFF4F8FF),
          child: Text('✓ 지하철로 변경 시 약 8분을 절약할 수 있어요.'),
        ),
        const SpacerBox(),
        Row(
          children: [
            Expanded(
              child: OutlinedButton(
                onPressed: () => Navigator.pop(c),
                child: const Text('기존 경로 유지'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: FilledButton(
                onPressed: () => _open(c, MovingScreen(repository: repository)),
                child: const Text('경로 변경'),
              ),
            ),
          ],
        ),
      ],
    ),
  );
}

class SpacerBox extends StatelessWidget {
  const SpacerBox({super.key});
  @override
  Widget build(BuildContext context) => const SizedBox(height: 24);
}

class MovingScreen extends StatefulWidget {
  const MovingScreen({super.key, required this.repository, this.route});

  final DdubiRepository repository;

  final RouteOption? route;

  @override
  State<MovingScreen> createState() => _MovingScreenState();
}

class _MovingScreenState extends State<MovingScreen> {
  bool _hasDeparted = false;

  @override
  Widget build(BuildContext context) => AppPage(
    title: _hasDeparted ? '이동 중' : '출발 안내',
    actions: const [
      IconButton(onPressed: null, icon: Icon(Icons.notifications_none)),
    ],
    child: ListView(
      padding: _pad,
      children: _hasDeparted
          ? _movingContent(context)
          : _departureContent(context),
    ),
  );

  List<Widget> _departureContent(BuildContext context) => [
    const TtubeeGuideCard(
      '지금 출발하면 딱 좋아요!',
      compact: true,
      character: TtubeeRunning(asset: 'assets/images/ttubee/running.png'),
    ),
    const SizedBox(height: 12),
    InfoCard(
      color: const Color(0xFFF0FAF7),
      child: Column(
        children: [
          const CircleAvatar(
            radius: 32,
            backgroundColor: Colors.white,
            child: Icon(
              Icons.directions_walk,
              size: 36,
              color: DdubiColors.navy,
            ),
          ),
          const SizedBox(height: 15),
          Text('지금 출발하세요', style: Theme.of(context).textTheme.titleLarge),
          const Text('여유 있게 이동하면 제시간 도착해요.'),
        ],
      ),
    ),
    const SizedBox(height: 12),
    const InfoCard(
      child: ListTile(
        contentPadding: EdgeInsets.zero,
        leading: CircleAvatar(child: Icon(Icons.directions_walk)),
        title: Text('첫 번째 단계'),
        subtitle: Text('강남역까지 도보 6분\n450m'),
      ),
    ),
    const SizedBox(height: 12),
    const InfoCard(
      child: ListTile(
        contentPadding: EdgeInsets.zero,
        leading: Icon(Icons.check_circle, color: DdubiColors.green),
        title: Text('현재 지연 상태'),
        subtitle: StatusBadge('지연 위험 낮음 · 제시간 도착 예상'),
      ),
    ),
    const SizedBox(height: 28),
    PrimaryButton(
      '출발했어요',
      onPressed: () => setState(() => _hasDeparted = true),
    ),
  ];

  List<Widget> _movingContent(BuildContext context) {
    final route = widget.route;
    final transport = route?.transport ?? '지하철 2호선';
    final arrival = route?.arrival ?? '18:45';
    return [
      const TtubeeGuideCard(
        '목적지까지 뚜비가 함께할게요!',
        compact: true,
        character: TtubeeRunning(asset: 'assets/images/ttubee/running.png'),
      ),
      const SizedBox(height: 12),
      InfoCard(
        color: const Color(0xFFF0FAF7),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const StatusBadge(
              '현재 이동 단계',
              tone: DdubiColors.green,
              icon: Icons.directions_walk,
            ),
            const SizedBox(height: 12),
            Text('강남역으로 이동 중', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 4),
            const Text('도보 6분 · 450m'),
            const SizedBox(height: 14),
            const LinearProgressIndicator(value: .18),
          ],
        ),
      ),
      const SizedBox(height: 12),
      InfoCard(
        child: Row(
          children: [
            const Icon(Icons.schedule, color: DdubiColors.blue),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('예상 도착'),
                  Text(
                    '$arrival · 제시간 도착 예상',
                    style: const TextStyle(fontWeight: FontWeight.w800),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
      const SizedBox(height: 12),
      InfoCard(
        child: ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const CircleAvatar(child: Icon(Icons.subway)),
          title: const Text('다음 단계'),
          subtitle: Text('강남역에서 $transport 탑승'),
        ),
      ),
      const SizedBox(height: 12),
      const InfoCard(
        child: ListTile(
          contentPadding: EdgeInsets.zero,
          leading: Icon(Icons.check_circle, color: DdubiColors.green),
          title: Text('현재 지연 상태'),
          subtitle: StatusBadge('지연 위험 낮음 · 제시간 도착 예상'),
        ),
      ),
      const SizedBox(height: 28),
      PrimaryButton(
        '도착했어요',
        onPressed: () =>
            _open(context, ArrivalScreen(repository: widget.repository)),
      ),
    ];
  }
}

class ArrivalScreen extends StatelessWidget {
  const ArrivalScreen({super.key, required this.repository});
  final DdubiRepository repository;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '',
    child: ListView(
      padding: _pad,
      children: [
        const TtubeeGuideCard(
          '제시간에 도착했어요!\n오늘 이동도 정말 잘했어요.',
          character: TtubeeCelebrate(
            asset: 'assets/images/ttubee/celebrate.png',
            height: 82,
          ),
        ),
        const SizedBox(height: 18),
        const Icon(Icons.check_circle, color: Color(0xFF12B89A), size: 70),
        Center(
          child: Text(
            '제시간에 도착했어요',
            style: Theme.of(c).textTheme.titleLarge
                ?.copyWith(color: const Color(0xFF008A72)),
          ),
        ),
        const Center(child: Text('모든 도착!')),
        const SizedBox(height: 22),
        const InfoCard(
          child: Column(
            children: [
              ListTile(title: Text('계획 출발'), trailing: Text('오전 9:00')),
              ListTile(title: Text('실제 출발'), trailing: Text('오전 9:02  (+2분)')),
              Divider(),
              ListTile(title: Text('계획 도착'), trailing: Text('오전 9:39')),
              ListTile(
                title: Text('실제 도착'),
                trailing: Text(
                  '오전 9:37  (-2분)',
                  style: TextStyle(color: DdubiColors.green),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        InfoCard(
          color: const Color(0xFFF0FAF7),
          child: Column(
            children: [
              const Text('절약한 시간'),
              Text(
                '2분',
                style: Theme.of(c).textTheme.headlineLarge
                    ?.copyWith(color: const Color(0xFF008A72)),
              ),
            ],
          ),
        ),
        const SizedBox(height: 22),
        const Center(child: Text('이번 경험은 어땠나요?')),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                onPressed: () => Navigator.popUntil(c, (r) => r.isFirst),
                icon: const Icon(Icons.sentiment_satisfied_alt),
                label: const Text('만족해요'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: OutlinedButton.icon(
                onPressed: () {},
                icon: const Icon(Icons.sentiment_dissatisfied_outlined),
                label: const Text('아쉬워요'),
              ),
            ),
          ],
        ),
      ],
    ),
  );
}

class NotificationsScreen extends StatelessWidget {
  const NotificationsScreen({super.key, this.go});
  final ValueChanged<int>? go;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '알림함',
    webTitle: '알림 대시보드',
    webSubtitle: '출발 알림과 최근 이동 결과를 확인하세요.',
    index: go == null ? null : 3,
    onNav: go,
    actions: const [
      IconButton(onPressed: null, icon: Icon(Icons.settings_outlined)),
    ],
    child: LayoutBuilder(
      builder: (context, constraints) {
        final isWide =
            constraints.maxWidth >= LayoutConstants.wideScreenBreakpoint;
        return isWide ? _webLayout(context) : _mobileLayout(context);
      },
    ),
  );

  Widget _mobileLayout(BuildContext context) => ListView(
    key: const Key('mobileNotificationsLayout'),
    padding: _pad,
    children: [
      const SectionHeader('출발 알림'),
      ..._departureAlerts.map(_departureAlertTile),
      const Divider(),
      const SectionHeader('이동 기록'),
      ..._movementRecords.map(_movementRecordTile),
      const Divider(),
      const SectionHeader('주간 리포트 (5.12 ~ 5.18)'),
      const SizedBox(height: 10),
      const Row(
        children: [
          Expanded(child: _NotificationMetricCard('제시간 도착률', '92%')),
          SizedBox(width: 10),
          Expanded(child: _NotificationMetricCard('평균 여유 시간', '6분')),
        ],
      ),
    ],
  );

  Widget _webLayout(BuildContext context) => ListView(
    key: const Key('webNotificationsLayout'),
    padding: _pad,
    children: [
      Text('이번 주 이동 요약', style: Theme.of(context).textTheme.titleLarge),
      const SizedBox(height: 12),
      const Row(
        key: Key('webNotificationMetrics'),
        children: [
          Expanded(
            child: DashboardMetricCard(
              label: '오늘 출발 알림',
              value: '2건',
              caption: '다음 알림 08:30',
              icon: Icons.notifications_active_outlined,
            ),
          ),
          SizedBox(width: 12),
          Expanded(
            child: DashboardMetricCard(
              label: '제시간 도착률',
              value: '92%',
              caption: '지난주 대비 +4%',
              icon: Icons.task_alt_outlined,
              tone: DdubiColors.green,
            ),
          ),
          SizedBox(width: 12),
          Expanded(
            child: DashboardMetricCard(
              label: '평균 여유 시간',
              value: '6분',
              caption: '최근 이동 3건',
              icon: Icons.schedule_outlined,
              tone: Color(0xFF7257E8),
            ),
          ),
        ],
      ),
      const SizedBox(height: 20),
      Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            flex: 3,
            child: InfoCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const SectionHeader('최근 알림'),
                  const SizedBox(height: 8),
                  ..._departureAlerts.map(_departureAlertTile),
                  const Divider(),
                  const ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: CircleAvatar(
                      backgroundColor: Color(0xFFFFF4E5),
                      child: Icon(
                        Icons.warning_amber,
                        color: DdubiColors.orange,
                      ),
                    ),
                    title: Text('일부 구간의 교통정보가 늦게 도착했어요.'),
                    subtitle: Text('오늘 07:30 · 경로 추천은 정상적으로 완료됐어요.'),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(width: 16),
          SizedBox(
            width: 390,
            child: InfoCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const SectionHeader('최근 이동 기록'),
                  const SizedBox(height: 8),
                  ..._movementRecords.map(_movementRecordTile),
                ],
              ),
            ),
          ),
        ],
      ),
    ],
  );

  static Widget _departureAlertTile(String text) => ListTile(
    contentPadding: EdgeInsets.zero,
    leading: const CircleAvatar(
      backgroundColor: Color(0xFFEAFBF8),
      child: Icon(Icons.notifications_active_outlined, color: DdubiColors.blue),
    ),
    title: Text(text),
    subtitle: const Text('방문 장소 · 09:00 일정'),
  );

  static Widget _movementRecordTile(String text) {
    final parts = text.split('|');
    final isDelayed = parts[2] == '지연';
    return ListTile(
      contentPadding: EdgeInsets.zero,
      title: Text(parts[0]),
      subtitle: Text(parts[1]),
      trailing: StatusBadge(
        parts[2],
        tone: isDelayed ? DdubiColors.orange : DdubiColors.green,
        icon: isDelayed ? Icons.warning_amber : Icons.check_circle_outline,
      ),
    );
  }

  static const _departureAlerts = ['08:30  곧 출발 시간이에요', '08:10  출발 20분 전 알림'];

  static const _movementRecords = [
    '5.18 (토)  강남역 → 여의도역|오전 9:37 도착|제시간',
    '5.16 (목)  강남역 → 여의도역|오전 9:38 도착|제시간',
    '5.14 (화)  강남역 → 여의도역|오전 9:47 도착|지연',
  ];
}

class _NotificationMetricCard extends StatelessWidget {
  const _NotificationMetricCard(this.label, this.value);

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => InfoCard(
    child: Column(
      children: [
        Text(label),
        const SizedBox(height: 4),
        Text(
          value,
          style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w800),
        ),
      ],
    ),
  );
}

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, this.go, required this.repository});
  final ValueChanged<int>? go;
  final DdubiRepository repository;
  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  TransportPreferenceChoice selectedTransport =
      TransportPreferenceChoice.balanced;

  @override
  Widget build(BuildContext c) => AppPage(
    title: '이동 설정',
    webTitle: '서비스 설정',
    webSubtitle: '경로 추천 방식과 앱 권한, 계정 상태를 관리하세요.',
    index: widget.go == null ? null : 4,
    onNav: widget.go,
    child: LayoutBuilder(
      builder: (context, constraints) {
        final isWide =
            constraints.maxWidth >= LayoutConstants.wideScreenBreakpoint;
        return isWide ? _webLayout(context) : _mobileLayout(context);
      },
    ),
  );

  Widget _mobileLayout(BuildContext context) => ListView(
    key: const Key('mobileSettingsLayout'),
    padding: _pad,
    children: [
      _accountCard(context),
      const SizedBox(height: 18),
      const SectionHeader('교통수단 선호'),
      const SizedBox(height: 10),
      _transportSelector(),
      const SizedBox(height: 16),
      _permissionCard(),
      const SizedBox(height: 14),
      _serviceStatus(context),
    ],
  );

  Widget _webLayout(BuildContext context) => ListView(
    key: const Key('webSettingsLayout'),
    padding: _pad,
    children: [
      Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 300,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _accountCard(context),
                const SizedBox(height: 16),
                const InfoCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      StatusBadge(
                        '계정 연결됨',
                        tone: DdubiColors.green,
                        icon: Icons.verified_user_outlined,
                      ),
                      SizedBox(height: 12),
                      Text('현재 설정은 모바일 앱과 Web에서 동일하게 사용돼요.'),
                      SizedBox(height: 12),
                      Divider(),
                      SizedBox(height: 4),
                      Text('최근 로그인'),
                      SizedBox(height: 4),
                      Text(
                        '오늘 15:20 · Web',
                        style: TextStyle(fontWeight: FontWeight.w700),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 20),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('이동 설정', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 4),
                const Text('경로 추천에 사용할 교통수단과 앱 권한을 관리하세요.'),
                const SizedBox(height: 16),
                InfoCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const SectionHeader('교통수단 선호'),
                      const SizedBox(height: 12),
                      _transportSelector(),
                    ],
                  ),
                ),
                const SizedBox(height: 16),
                _permissionCard(),
                const SizedBox(height: 16),
                _serviceStatus(context),
              ],
            ),
          ),
        ],
      ),
    ],
  );

  Widget _accountCard(BuildContext context) => InfoCard(
    child: Column(
      children: [
        const ListTile(
          contentPadding: EdgeInsets.zero,
          leading: CircleAvatar(child: Icon(Icons.person_outline)),
          title: Text('뚜비 사용자', style: TextStyle(fontWeight: FontWeight.w800)),
          subtitle: Text('user@example.com'),
        ),
        const Divider(),
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.logout, color: DdubiColors.red),
          title: const Text(
            '로그아웃',
            style: TextStyle(
              color: DdubiColors.red,
              fontWeight: FontWeight.w700,
            ),
          ),
          onTap: () => _showLogoutDialog(context),
        ),
      ],
    ),
  );

  Widget _transportSelector() => TransportPreferenceSelector(
    value: selectedTransport,
    onChanged: (value) => setState(() => selectedTransport = value),
  );

  Widget _permissionCard() => const InfoCard(
    child: Column(
      children: [
        ListTile(
          leading: Icon(Icons.location_on_outlined),
          title: Text('위치 권한'),
          subtitle: Text('현재 위치 기반 경로 안내에 사용'),
          trailing: Text('항상 허용  ›'),
        ),
        Divider(),
        ListTile(
          leading: Icon(Icons.notifications_none),
          title: Text('알림 권한'),
          subtitle: Text('출발 시간과 지연 안내에 사용'),
          trailing: Text('허용됨  ›'),
        ),
      ],
    ),
  );

  Widget _serviceStatus(BuildContext context) => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SectionHeader('서비스 상태'),
        const SizedBox(height: 10),
        const StatusBadge(
          '최근 교통 정보 업데이트가 지연됐어요.',
          tone: DdubiColors.orange,
          icon: Icons.cloud_off_outlined,
        ),
        const SizedBox(height: 6),
        const Text('최신 교통정보가 부족하면 Mock 경로로 화면을 확인할 수 있어요.'),
        const SizedBox(height: 12),
        Align(
          alignment: Alignment.centerRight,
          child: OutlinedButton(
            onPressed: () => _open(
              context,
              DelayAlertScreen(repository: MockDdubiRepository()),
            ),
            child: const Text('상세 확인'),
          ),
        ),
      ],
    ),
  );

  void _showLogoutDialog(BuildContext context) => showDialog<void>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: const Text('로그아웃할까요?'),
      content: const Text('다시 로그인하면 일정과 추천 경로를 확인할 수 있어요.'),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(dialogContext),
          child: const Text('취소'),
        ),
        FilledButton(
          onPressed: () {
            Navigator.pop(dialogContext);
            context.go('/login');
          },
          child: const Text('로그아웃'),
        ),
      ],
    ),
  );
}
