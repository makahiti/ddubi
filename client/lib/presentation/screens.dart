import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../core/theme.dart';
import '../data/mock_repository.dart';
import '../domain/auth.dart';
import '../domain/models.dart';
import 'auth_screens.dart';
import 'widgets.dart';

void _open(BuildContext c, Widget page) =>
    Navigator.of(c).push(MaterialPageRoute(builder: (_) => page));
const _pad = EdgeInsets.fromLTRB(16, 8, 16, 20);
const _weekdays = ['월', '화', '수', '목', '금', '토', '일'];

String _fullDate(DateTime date) =>
    '${date.year}.${date.month.toString().padLeft(2, '0')}.${date.day.toString().padLeft(2, '0')} (${_weekdays[date.weekday - 1]})';

class LoginScreen extends StatelessWidget {
  const LoginScreen({super.key, required this.authRepository});
  final AuthRepository authRepository;
  @override
  Widget build(BuildContext c) => Scaffold(
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              children: [
                const Spacer(),
                TweenAnimationBuilder<double>(
                  duration: const Duration(milliseconds: 650),
                  curve: Curves.easeOutBack,
                  tween: Tween(begin: .82, end: 1),
                  builder: (_, value, child) => Opacity(
                    opacity: value.clamp(0, 1),
                    child: Transform.scale(scale: value, child: child),
                  ),
                  child: SizedBox(
                    height: 120,
                    child: Image.asset(
                      'assets/images/ttubee-guide.png',
                      fit: BoxFit.contain,
                      semanticLabel: '반갑게 인사하는 뚜비',
                    ),
                  ),
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
                  '●  Apple로 시작하기',
                  Colors.white,
                  Colors.black,
                  () => _social(c, 'Apple'),
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
  );
  void _completeLogin(BuildContext c) => c.go('/home');
  void _social(BuildContext c, String provider) => _open(
    c,
    SocialAuthScreen(
      provider: provider,
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
        return ListView(
          padding: _pad,
          children: [
            InfoCard(
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
                              style: Theme.of(c).textTheme.headlineLarge,
                            ),
                            Text(
                              '잠실야구장',
                              style: Theme.of(c).textTheme.titleLarge,
                            ),
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
                        style: Theme.of(c).textTheme.headlineMedium
                            ?.copyWith(color: DdubiColors.blue),
                      ),
                      const Spacer(),
                      OutlinedButton(
                        onPressed: () => c.push('/schedules/schedule-baseball'),
                        child: const Text('상세 보기'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
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
                  onTap: () => c.push('/recommendations/mock-recommendation-1'),
                ),
              ),
            ),
          ],
        );
      },
    ),
  );
}

class ScheduleListScreen extends StatefulWidget {
  const ScheduleListScreen({super.key, required this.repository, this.go});
  final DdubiRepository repository;
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

  @override
  Widget build(BuildContext c) => AppPage(
    title: '전체 일정',
    index: widget.go == null ? null : 0,
    onNav: widget.go,
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
        return ListView(
          padding: _pad,
          children: [
            MonthlyCalendar(
              selectedDate: selectedDate,
              schedules: schedules,
              onSelected: (date) => setState(() => selectedDate = date),
            ),
            const SizedBox(height: 18),
            SectionHeader(
              '${selectedDate.month}월 ${selectedDate.day}일 일정',
              trailing: TextButton.icon(
                onPressed: () => _add(c),
                icon: const Icon(Icons.add),
                label: const Text('일정 추가'),
              ),
            ),
            const SizedBox(height: 8),
            if (!s.hasData)
              const Center(child: CircularProgressIndicator())
            else if (selectedSchedules.isEmpty)
              const TtubeeGuideCard(
                '선택한 날짜에는 일정이 없어요.\n새로운 일정을 추가해볼까요?',
                compact: true,
              )
            else
              ...selectedSchedules.map(
                (v) => ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(
                    Icons.location_on_outlined,
                    color: DdubiColors.blue,
                  ),
                  title: Text(
                    '${v.time}  ${v.title}',
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                  subtitle: Text(v.place),
                  trailing: Text(v.dateLabel),
                  onTap: () => c.push('/schedules/${v.id}'),
                ),
              ),
          ],
        );
      },
    ),
  );
}

class ScheduleFormScreen extends StatefulWidget {
  const ScheduleFormScreen({
    super.key,
    required this.repository,
    this.initialDate,
  });
  final DdubiRepository repository;
  final DateTime? initialDate;
  @override
  State<ScheduleFormScreen> createState() => _ScheduleFormScreenState();
}

class _ScheduleFormScreenState extends State<ScheduleFormScreen> {
  int importance = 1, transport = 0;
  final formKey = GlobalKey<FormState>();
  final title = TextEditingController(text: '잠실야구장');
  final place = TextEditingController(text: '잠실야구장');
  final time = TextEditingController(text: '19:30');
  final departure = TextEditingController(text: '집 (서울 강남구 역삼동)');

  @override
  void dispose() {
    title.dispose();
    place.dispose();
    time.dispose();
    departure.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext c) => AppPage(
    title: '일정 등록',
    child: Form(
      key: formKey,
      child: ListView(
        padding: _pad,
        children: [
          _field('일정 제목', title),
          _field('도착지 검색', place, icon: Icons.search),
          Text(
            _fullDate(widget.initialDate ?? DateTime.now()),
            style: Theme.of(c).textTheme.bodySmall,
          ),
          _field('반드시 도착해야 하는 시간', time, icon: Icons.schedule),
          _field('출발지', departure, icon: Icons.my_location),
          const Text('중요도', style: TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: 8),
          SegmentedButton<int>(
            segments: const [
              ButtonSegment(value: 0, label: Text('일반')),
              ButtonSegment(value: 1, label: Text('중요')),
              ButtonSegment(value: 2, label: Text('절대 지각 불가')),
            ],
            selected: {importance},
            onSelectionChanged: (v) => setState(() => importance = v.first),
          ),
          const SizedBox(height: 20),
          const Text('교통수단 선호', style: TextStyle(fontWeight: FontWeight.w700)),
          RadioGroup<int>(
            groupValue: transport,
            onChanged: (v) => setState(() => transport = v!),
            child: Column(
              children: ['지하철 우선', '버스 + 지하철', '버스 우선']
                  .asMap()
                  .entries
                  .map(
                    (e) =>
                        RadioListTile<int>(value: e.key, title: Text(e.value)),
                  )
                  .toList(),
            ),
          ),
          const SizedBox(height: 10),
          PrimaryButton('저장하기', onPressed: _save),
        ],
      ),
    ),
  );

  Future<void> _save() async {
    if (!(formKey.currentState?.validate() ?? false)) return;
    final date = widget.initialDate ?? DateTime.now();
    final today = DateTime.now();
    final isToday =
        date.year == today.year &&
        date.month == today.month &&
        date.day == today.day;
    await widget.repository.addSchedule(
      Schedule(
        id: 'schedule-${DateTime.now().microsecondsSinceEpoch}',
        title: title.text.trim(),
        place: place.text.trim(),
        departurePlace: departure.text.trim(),
        time: time.text.trim(),
        dateLabel: isToday ? '오늘' : '${date.month}.${date.day}',
        date: date,
        importance: importance,
        preferredTransport: transport,
      ),
    );
    if (mounted) context.pop(true);
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
          validator: (value) =>
              (value ?? '').trim().isEmpty ? '필수 입력 항목입니다.' : null,
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
  final DdubiRepository repository;
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
    index: go == null ? null : 1,
    onNav: go,
    actions: const [IconButton(onPressed: null, icon: Icon(Icons.refresh))],
    child: FutureBuilder<List<RouteOption>>(
      future: repository.routes(condition: condition),
      builder: (c, s) {
        if (s.hasError) {
          return const DataLoadError(message: '추천 경로를 불러오지 못했어요.');
        }
        return ListView(
          padding: _pad,
          children: [
            if (condition != null) ...[
              _RouteSearchSummary(condition: condition!),
              const SizedBox(height: 12),
            ],
            if (!s.hasData)
              const Center(child: CircularProgressIndicator())
            else ...[
              const TtubeeGuideCard('이 경로가 지금 가장 안정적이에요!', compact: true),
              const SizedBox(height: 12),
              ...s.data!.map(
                (r) => Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: RouteCard(
                    r,
                    onTap: () => _open(
                      c,
                      RouteMapScreen(
                        repository: repository,
                        selectedRoute: r,
                        condition: condition,
                      ),
                    ),
                  ),
                ),
              ),
            ],
            const InfoCard(
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
            ),
          ],
        );
      },
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
    child: Form(
      key: _formKey,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final isWide = constraints.maxWidth >= 760;
          return ListView(
            padding: _pad,
            children: [
              const TtubeeGuideCard(
                '언제 어디까지 가야 하는지 알려주세요.\n뚜비가 알맞은 경로를 찾아드릴게요!',
                compact: true,
              ),
              const SizedBox(height: 16),
              InfoCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      '이동 조건',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
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
              ),
            ],
          );
        },
      ),
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
            final map = _MockRouteMap(
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

class _MockRouteMap extends StatelessWidget {
  const _MockRouteMap({
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
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(16),
    child: LayoutBuilder(
      builder: (context, constraints) => Stack(
        children: [
          Positioned.fill(
            child: ColoredBox(
              color: const Color(0xFFEAF2EC),
              child: CustomPaint(
                painter: _MapPainter(selectedKind: selectedKind),
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
                  children: routes
                      .map(
                        (route) => ChoiceChip(
                          label: Text(route.label),
                          selected: route.kind == selectedKind,
                          avatar: CircleAvatar(
                            backgroundColor: _routeTone(route.kind),
                            radius: 5,
                          ),
                          onSelected: (_) => onSelected(route.kind),
                        ),
                      )
                      .toList(),
                ),
              ),
            ),
          ),
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
              label: '출발 · $departureLabel',
              icon: Icons.trip_origin,
              tone: DdubiColors.green,
            ),
          ),
          Positioned(
            key: const Key('mapDestinationMarker'),
            right: 12,
            top: constraints.maxHeight * .22,
            child: _MapMarker(
              label: '도착 · $destinationLabel',
              icon: Icons.location_on,
              tone: DdubiColors.red,
            ),
          ),
        ],
      ),
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
          onPressed: () => _open(context, MovingScreen(repository: repository)),
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

class MovingScreen extends StatelessWidget {
  const MovingScreen({super.key, required this.repository});
  final DdubiRepository repository;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '출발 · 이동 중',
    actions: const [
      IconButton(onPressed: null, icon: Icon(Icons.notifications_none)),
    ],
    child: ListView(
      padding: _pad,
      children: [
        const TtubeeGuideCard(
          '지금 출발하면 딱 좋아요!',
          compact: true,
          asset: 'assets/images/ttubee-moving.png',
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
              Text('지금 출발하세요', style: Theme.of(c).textTheme.titleLarge),
              const Text('여유 있게 이동하면 제시간 도착해요.'),
            ],
          ),
        ),
        const SizedBox(height: 12),
        const InfoCard(
          child: ListTile(
            contentPadding: EdgeInsets.zero,
            leading: CircleAvatar(child: Icon(Icons.directions_walk)),
            title: Text('다음 단계'),
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
          '출발 안내 시작',
          onPressed: () => _open(c, ArrivalScreen(repository: repository)),
        ),
      ],
    ),
  );
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
        const TtubeeGuideCard('제시간에 도착했어요!\n오늘 이동도 정말 잘했어요.'),
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
    index: go == null ? null : 3,
    onNav: go,
    actions: const [
      IconButton(onPressed: null, icon: Icon(Icons.settings_outlined)),
    ],
    child: ListView(
      padding: _pad,
      children: [
        const SectionHeader('출발 알림'),
        ...['08:30  곧 출발 시간이에요', '08:10  출발 20분 전 알림'].map(
          (t) => ListTile(
            leading: const Icon(Icons.circle, size: 8, color: DdubiColors.blue),
            title: Text(t),
            subtitle: const Text('방문 장소 · 09:00 일정'),
          ),
        ),
        const Divider(),
        const SectionHeader('이동 기록'),
        ...[
          '5.18 (토)  강남역 → 여의도역|오전 9:37 도착|제시간',
          '5.16 (목)  강남역 → 여의도역|오전 9:38 도착|제시간',
          '5.14 (화)  강남역 → 여의도역|오전 9:47 도착|지연',
        ].map((t) {
          final p = t.split('|');
          return ListTile(
            title: Text(p[0]),
            subtitle: Text(p[1]),
            trailing: StatusBadge(
              p[2],
              tone: p[2] == '지연' ? DdubiColors.orange : DdubiColors.green,
              icon: p[2] == '지연'
                  ? Icons.warning_amber
                  : Icons.check_circle_outline,
            ),
          );
        }),
        const Divider(),
        const SectionHeader('주간 리포트 (5.12 ~ 5.18)'),
        const SizedBox(height: 10),
        const Row(
          children: [
            Expanded(
              child: InfoCard(
                child: Column(
                  children: [
                    Text('제시간 도착률'),
                    Text(
                      '92%',
                      style: TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            SizedBox(width: 10),
            Expanded(
              child: InfoCard(
                child: Column(
                  children: [
                    Text('평균 여유 시간'),
                    Text(
                      '6분',
                      style: TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
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

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, this.go, required this.repository});
  final ValueChanged<int>? go;
  final DdubiRepository repository;
  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  int selected = 1;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '이동 설정',
    index: widget.go == null ? null : 4,
    onNav: widget.go,
    child: ListView(
      padding: _pad,
      children: [
        InfoCard(
          child: Column(
            children: [
              const ListTile(
                contentPadding: EdgeInsets.zero,
                leading: CircleAvatar(child: Icon(Icons.person_outline)),
                title: Text(
                  '뚜비 사용자',
                  style: TextStyle(fontWeight: FontWeight.w800),
                ),
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
                onTap: () => showDialog<void>(
                  context: c,
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
                          c.go('/login');
                        },
                        child: const Text('로그아웃'),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 18),
        const SectionHeader('교통수단 선호'),
        const SizedBox(height: 10),
        Row(
          children: List.generate(4, (i) {
            final labels = ['버스', '지하철', '도보', '택시'];
            final icons = [
              Icons.directions_bus,
              Icons.directions_subway,
              Icons.directions_walk,
              Icons.local_taxi,
            ];
            return Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 3),
                child: InkWell(
                  onTap: () => setState(() => selected = i),
                  child: Container(
                    padding: const EdgeInsets.symmetric(vertical: 13),
                    decoration: BoxDecoration(
                      border: Border.all(
                        color: selected == i
                            ? DdubiColors.blue
                            : DdubiColors.border,
                      ),
                      color: selected == i
                          ? const Color(0xFFF0F6FF)
                          : Colors.white,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Column(
                      children: [
                        Icon(
                          icons[i],
                          color: selected == i ? DdubiColors.blue : null,
                        ),
                        Text(labels[i], style: const TextStyle(fontSize: 11)),
                      ],
                    ),
                  ),
                ),
              ),
            );
          }),
        ),
        const SizedBox(height: 16),
        const InfoCard(
          child: Column(
            children: [
              ListTile(
                leading: Icon(Icons.location_on_outlined),
                title: Text('위치 권한'),
                trailing: Text('항상 허용  ›'),
              ),
              Divider(),
              ListTile(
                leading: Icon(Icons.notifications_none),
                title: Text('알림 권한'),
                trailing: Text('허용됨  ›'),
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        const TtubeeGuideCard(
          '교통 정보를 불러오지 못했어요.\n잠시 후 다시 확인해주세요.',
          compact: true,
        ),
        const SizedBox(height: 12),
        InfoCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SectionHeader('데이터 부족 알림'),
              const SizedBox(height: 10),
              const StatusBadge(
                '교통 정보 업데이트에 실패했어요.',
                tone: DdubiColors.orange,
                icon: Icons.cloud_off_outlined,
              ),
              const Text('네트워크를 확인해주세요.'),
              const SizedBox(height: 12),
              Align(
                alignment: Alignment.centerRight,
                child: FilledButton(
                  onPressed: () {},
                  child: const Text('다시 시도'),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        InfoCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SectionHeader('예외 상황 기록'),
              const SizedBox(height: 10),
              const Text(
                '최근 06.18 07:30',
                style: TextStyle(fontWeight: FontWeight.w700),
              ),
              const Text('최신 교통정보가 부족해\n추천 경로를 불러오지 못했어요.'),
              const SizedBox(height: 12),
              Align(
                alignment: Alignment.centerRight,
                child: OutlinedButton(
                  onPressed: () => _open(
                    c,
                    DelayAlertScreen(repository: MockDdubiRepository()),
                  ),
                  child: const Text('다시 시도'),
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}
