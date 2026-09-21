import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../providers/app_providers.dart';
import '../../core/theme.dart';
import '../../domain/models.dart';
import '../../domain/route_evaluated_numbers.dart';
import '../micro_animations.dart';
import '../widgets.dart';
import '../schedule_evaluation_card.dart';
import '../../domain/schedule_evaluation.dart';
import '../../services/recommendation_api.dart';
import 'shared.dart';

/// 저장된 일정과 서버 재평가 결과를 조회한다. 교통 데이터가 없을 때 가상의 지연·대안을 만들지 않는다.
class DelayAlertScreen extends ConsumerStatefulWidget {
  const DelayAlertScreen({super.key, required this.repository});
  // 기존 라우트 호출과의 호환 인자다. 실제 조회는 scheduleRepositoryProvider를 사용한다.
  final DdubiRepository repository;
  @override
  ConsumerState<DelayAlertScreen> createState() => _DelayAlertScreenState();
}

class _DelayAlertScreenState extends ConsumerState<DelayAlertScreen> {
  late Future<List<Schedule>> _schedules;
  @override
  void initState() {
    super.initState();
    _schedules = ref.read(scheduleRepositoryProvider).schedules();
  }

  @override
  Widget build(BuildContext context) {
    final repository = ref.watch(scheduleRepositoryProvider);
    return AppPage(
      title: '일정별 교통 상황',
      child: FutureBuilder<List<Schedule>>(
        future: _schedules,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return Center(
              child: TextButton(
                onPressed: () =>
                    setState(() => _schedules = repository.schedules()),
                child: const Text('일정을 불러오지 못했습니다. 다시 시도'),
              ),
            );
          }
          if (repository is! ScheduleEvaluationRepository) {
            return const Center(
              child: Text('실제 교통 상황은 일정 API 연결 후 확인할 수 있습니다.'),
            );
          }
          final evaluationRepository =
              repository as ScheduleEvaluationRepository;
          // 취소된 일정과 이미 지난 일정은 평가가 멈춘 상태라 카드를 만들 이유가 없다.
          final now = DateTime.now();
          final schedules =
              Schedule.visible(snapshot.data ?? const <Schedule>[])
                  .where((schedule) => schedule.arriveBy.isAfter(now))
                  .toList()
                ..sort((a, b) => a.arriveBy.compareTo(b.arriveBy));
          if (schedules.isEmpty) {
            return const Center(child: Text('앞으로 예정된 일정이 없습니다.'));
          }
          // ListView.builder 로 그린다. 예전에는 모든 카드를 한 번에 만들어서, 일정이
          // 열 개면 열 개가 동시에 서버에 물어보고 각자 3초마다 다시 물었다. 화면에 보이는
          // 카드만 만들면 화면 밖 카드는 아예 요청하지 않고, 스크롤을 벗어나면 타이머도
          // 정리된다(카드의 dispose 가 타이머를 끈다).
          return ListView.builder(
            padding: screenPadding,
            itemCount: schedules.length + 1,
            itemBuilder: (context, index) {
              if (index == 0) {
                return const Padding(
                  padding: EdgeInsets.only(bottom: 4),
                  child: Text('각 일정의 최신 평가와 데이터 부족 사유를 확인하세요.'),
                );
              }
              final schedule = schedules[index - 1];
              return Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const SizedBox(height: 16),
                  Text(
                    schedule.title,
                    style: const TextStyle(fontWeight: FontWeight.bold),
                  ),
                  ScheduleEvaluationCard(
                    key: ValueKey(schedule.id),
                    repository: evaluationRepository,
                    scheduleId: schedule.id,
                  ),
                ],
              );
            },
          );
        },
      ),
    );
  }
}

class SpacerBox extends StatelessWidget {
  const SpacerBox({super.key});
  @override
  Widget build(BuildContext context) => const SizedBox(height: 24);
}

/// 선택한 경로를 따라 이동을 안내한다.
///
/// <h3>없는 값을 지어내지 않는다</h3>
/// 예전에는 어떤 일정이든 "강남역까지 도보 6분 · 450m", "18:45 도착", "지연 위험 낮음"
/// 이 그대로 박혀 있었다. 부산 일정을 눌러도 강남역이 나왔다. 지금은 저장된 경로의 실제
/// 구간만 보여주고, 우리가 모르는 것(구간 거리, 실시간 지연, 진행률)은 아예 말하지 않는다.
///
/// 위치 추적이 없으므로 "지금 몇 번째 구간인지"도 알 수 없다. 그래서 진행률 막대 대신
/// 전체 구간을 순서대로 펼쳐 두고 사용자가 스스로 따라가게 한다.
class MovingScreen extends StatefulWidget {
  const MovingScreen({
    super.key,
    required this.repository,
    this.route,
    this.segments = const [],
    this.evaluated = RouteEvaluatedNumbers.empty,
  });

  final DdubiRepository repository;

  final RouteOption? route;

  /// 저장된 경로의 실제 이동 구간. 비어 있으면 구간 안내를 생략한다.
  final List<SavedRouteSegment> segments;

  /// 이번 평가가 계산한 구간 시간. 앞 화면과 같은 숫자를 쓰려고 그대로 받아 온다.
  final RouteEvaluatedNumbers evaluated;

  @override
  State<MovingScreen> createState() => _MovingScreenState();
}

class _MovingScreenState extends State<MovingScreen> {
  bool _hasDeparted = false;

  List<SavedRouteSegment> get _steps =>
      [...widget.segments]..sort((a, b) => a.seq.compareTo(b.seq));

  @override
  Widget build(BuildContext context) => AppPage(
    title: _hasDeparted ? '이동 중' : '출발 안내',
    child: ListView(
      padding: screenPadding,
      children: _hasDeparted
          ? _movingContent(context)
          : _departureContent(context),
    ),
  );

  /// 한 구간을 사람이 읽는 한 줄로. 모르는 값은 넣지 않는다.
  String _stepText(SavedRouteSegment step) {
    final mode = routeModeLabel(step.mode);
    final route = step.displayRouteName;
    final head = route == null ? mode : '$mode $route';
    final to = step.toName;
    return to == null || to.isEmpty ? head : '$head · $to 까지';
  }

  IconData _stepIcon(String mode) => switch (mode) {
    'SUBWAY' => Icons.directions_subway_rounded,
    'BUS' => Icons.directions_bus_rounded,
    'WALK' => Icons.directions_walk_rounded,
    _ => Icons.place_outlined,
  };

  /// 평가된 구간 시간이 있으면 그 값을 쓴다. 앞 화면과 다른 숫자를 보여주지 않는다.
  int _stepMinutes(SavedRouteSegment step) =>
      widget.evaluated.minutesOf(step.seq) ?? step.baseDurationMin;

  Widget _stepTile(SavedRouteSegment step, {required bool first}) => InfoCard(
    child: ListTile(
      contentPadding: EdgeInsets.zero,
      leading: CircleAvatar(
        backgroundColor: first ? DdubiColors.green : const Color(0xFFEAF1F8),
        child: Icon(
          _stepIcon(step.mode),
          color: first ? Colors.white : DdubiColors.navy,
        ),
      ),
      title: Text(
        _stepText(step),
        style: const TextStyle(fontWeight: FontWeight.w800),
      ),
      subtitle: Text(
        step.fromName == null || step.fromName!.isEmpty
            ? '약 ${_stepMinutes(step)}분'
            : '${step.fromName} 출발 · 약 ${_stepMinutes(step)}분',
      ),
    ),
  );

  /// 구간 목록. 없으면 왜 없는지 한 줄로 말한다.
  List<Widget> _stepList() {
    final steps = _steps;
    if (steps.isEmpty) {
      return const [
        InfoCard(
          key: Key('movingStepsUnavailable'),
          child: Text('이 경로의 이동 구간 정보를 불러오지 못했어요.'),
        ),
      ];
    }
    return [
      for (var i = 0; i < steps.length; i++) ...[
        _stepTile(steps[i], first: i == 0),
        if (i != steps.length - 1) const SizedBox(height: 10),
      ],
    ];
  }

  /// 도착 예정 시각. 경로에 값이 없으면 시각을 만들어내지 않는다.
  Widget _arrivalCard() {
    final arrival = widget.route?.arrival;
    final known = arrival != null && arrival.isNotEmpty && arrival != '--:--';
    return InfoCard(
      child: Row(
        children: [
          const Icon(Icons.flag_outlined, color: DdubiColors.blue),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('예상 도착'),
                Text(
                  known ? arrival : '아직 계산되지 않았어요',
                  style: const TextStyle(fontWeight: FontWeight.w800),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  List<Widget> _departureContent(BuildContext context) {
    final departure = widget.route?.departure;
    final known =
        departure != null && departure.isNotEmpty && departure != '--:--';
    return [
      const TtubeeGuideCard(
        '경로를 따라 안내해 드릴게요.',
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
            Text(
              known ? '$departure 출발' : '출발 준비',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            Text(known ? '이 시각에 맞춰 출발하세요.' : '추천 출발 시각은 아직 계산되지 않았어요.'),
          ],
        ),
      ),
      const SizedBox(height: 12),
      const SectionHeader('이동 순서'),
      const SizedBox(height: 8),
      ..._stepList(),
      const SizedBox(height: 12),
      _arrivalCard(),
      const SizedBox(height: 12),
      // 실시간 지연은 아직 붙지 않았다. "지연 위험 낮음" 같은 단정을 대신 쓰지 않는다.
      const InfoCard(
        child: Text(
          '이동 중 지연 알림은 아직 준비 중이에요. 지금은 계산해 둔 경로만 안내해요.',
          style: TextStyle(fontSize: 12, color: Color(0xFF6E8193)),
        ),
      ),
      const SizedBox(height: 28),
      PrimaryButton(
        '출발했어요',
        onPressed: () => setState(() => _hasDeparted = true),
      ),
    ];
  }

  List<Widget> _movingContent(BuildContext context) => [
    const TtubeeGuideCard(
      '목적지까지 뚜비가 함께할게요!',
      compact: true,
      character: TtubeeRunning(asset: 'assets/images/ttubee/running.png'),
    ),
    const SizedBox(height: 12),
    _arrivalCard(),
    const SizedBox(height: 12),
    const SectionHeader('남은 이동 순서'),
    const SizedBox(height: 8),
    ..._stepList(),
    const SizedBox(height: 28),
    PrimaryButton(
      '도착했어요',
      onPressed: () =>
          openPage(context, ArrivalScreen(repository: widget.repository)),
    ),
  ];
}

class ArrivalScreen extends StatelessWidget {
  const ArrivalScreen({super.key, required this.repository});
  final DdubiRepository repository;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '',
    child: ListView(
      padding: screenPadding,
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
