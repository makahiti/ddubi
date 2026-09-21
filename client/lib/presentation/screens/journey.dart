import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../providers/app_providers.dart';
import '../../core/theme.dart';
import '../../domain/models.dart';
import '../micro_animations.dart';
import '../widgets.dart';
import '../schedule_evaluation_card.dart';
import '../../domain/schedule_evaluation.dart';
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
          final schedules = snapshot.data ?? <Schedule>[];
          if (schedules.isEmpty) {
            return const Center(child: Text('등록된 일정이 없습니다.'));
          }
          return ListView(
            padding: screenPadding,
            children: [
              const Text('각 일정의 최신 평가와 데이터 부족 사유를 확인하세요.'),
              for (final schedule in schedules) ...[
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
            ],
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
      padding: screenPadding,
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
            openPage(context, ArrivalScreen(repository: widget.repository)),
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
