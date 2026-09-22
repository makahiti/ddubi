import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../providers/app_providers.dart';
import '../../core/theme.dart';
import '../../domain/models.dart';
import '../../domain/route_evaluated_numbers.dart';
import '../arrival_flow.dart';
import '../departure_headline.dart' show formatClock;
import '../micro_animations.dart';
import '../widgets.dart';
import '../travel_conditions_card.dart';
import '../../domain/schedule_evaluation.dart';
import '../../services/recommendation_api.dart';
import 'shared.dart';

/// 앞으로 갈 일정마다 그 일정의 이동 변수(날씨·혼잡도·도로 통제·지하철)를 보여준다.
///
/// 예전에는 여기에 일정 상세의 "이동 예측" 카드를 그대로 넣었다. 같은 카드를 다른 제목 아래에서
/// 다시 보는 것이라 이 화면만의 쓸모가 없었다(2026-09-22 제보). 이제는 일정 상세의 "오늘의 이동
/// 변수" 칸을 일정마다 세워 "이 일정의 이동 변수" 로 보여준다. 자료가 아직 안 들어오는 항목은 카드가
/// 스스로 "확인 안 됨" 으로 적는다 — 실시간 자료(TOPIS 등)가 붙으면 그 자리가 채워진다.
///
/// 지난 일정은 내보이지 않는다. 이 값은 "지금" 의 상황이라 다녀온 일정 옆에 붙으면 그때의 상황으로
/// 오해한다.
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
    final conditions = ref.watch(recommendationApiProvider);
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
          // 취소·완료된 일정과 도착 시각이 지난 일정은 뺀다. 지금 상황을 보여주는 자리다.
          final now = DateTime.now();
          final schedules =
              Schedule.visible(snapshot.data ?? const <Schedule>[])
                  .where(
                    (schedule) =>
                        schedule.status == ScheduleStatus.upcoming &&
                        schedule.arriveBy.isAfter(now),
                  )
                  .toList()
                ..sort((a, b) => a.arriveBy.compareTo(b.arriveBy));
          if (schedules.isEmpty) {
            return const Center(child: Text('앞으로 예정된 일정이 없습니다.'));
          }
          // 화면에 보이는 카드만 만든다. 카드마다 서버에 한 번씩 물으므로, 일정이 많을 때
          // 전부 한 번에 만들면 그만큼 동시에 요청이 나간다.
          return ListView.builder(
            padding: screenPadding,
            itemCount: schedules.length + 1,
            itemBuilder: (context, index) {
              if (index == 0) {
                return const Padding(
                  padding: EdgeInsets.only(bottom: 4),
                  child: Text('앞으로 갈 일정마다 지금의 이동 변수를 보여줘요.'),
                );
              }
              final schedule = schedules[index - 1];
              return Column(
                key: ValueKey('scheduleConditions_${schedule.id}'),
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const SizedBox(height: 16),
                  Text(
                    schedule.title,
                    style: const TextStyle(fontWeight: FontWeight.bold),
                  ),
                  Text(
                    '${formatFullDate(schedule.arriveBy)} 도착',
                    style: const TextStyle(
                      fontSize: 12,
                      color: Color(0xFF6E8193),
                    ),
                  ),
                  const SizedBox(height: 6),
                  TravelConditionsCard(
                    key: ValueKey(schedule.id),
                    scheduleId: schedule.id,
                    service: conditions,
                    title: '이 일정의 이동 변수',
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
/// 출발 안내 → 이동 중 화면.
///
/// "출발했어요" 는 화면 글자만 바꾸는 버튼이 아니다. 서버에 출발을 알려야 그 일정의 출발 안내가
/// 끝나고(평가 상태 STOPPED), 앱을 껐다 켜도 이동 중이라는 사실이 남는다. 예전에는 화면 안의
/// 임시 상태만 바꿔서, 뒤로 나가면 다시 "출발 전" 으로 돌아가고 서버는 출발을 전혀 몰랐다
/// (2026-09-22 확인).
///
/// [scheduleId] 가 없으면(일정과 연결되지 않은 경로 검색 결과 등) 알릴 대상이 없으므로 예전처럼
/// 화면만 바꾼다. 그 경우에는 서버에 기록되지 않는다는 사실을 화면에 적는다.
class MovingScreen extends ConsumerStatefulWidget {
  const MovingScreen({
    super.key,
    required this.repository,
    this.route,
    this.segments = const [],
    this.evaluated = RouteEvaluatedNumbers.empty,
    this.scheduleId,
    this.departedAlready = false,
    this.estimatedArrivalAt,
  });

  final DdubiRepository repository;

  final RouteOption? route;

  /// 이번 평가가 계산한 도착 예정 시각.
  ///
  /// 예전에는 경로 요약의 도착 칸([RouteOption.arrival])을 그대로 썼는데, 저장된 경로의 그 칸은
  /// 늘 "평가 필요" 라는 자리표시자였다. 그래서 이동 중 화면의 "예상 도착" 이 항상 "평가 필요" 로
  /// 보였다(2026-09-22 제보). 시각은 평가에서 받아 온 이 값으로만 보여준다.
  final DateTime? estimatedArrivalAt;

  /// 저장된 경로의 실제 이동 구간. 비어 있으면 구간 안내를 생략한다.
  final List<SavedRouteSegment> segments;

  /// 이번 평가가 계산한 구간 시간. 앞 화면과 같은 숫자를 쓰려고 그대로 받아 온다.
  final RouteEvaluatedNumbers evaluated;

  /// 출발을 알릴 일정. 일정과 이어지지 않은 경로에는 없다.
  final String? scheduleId;

  /// 서버에 이미 출발이 기록돼 있는지. 화면을 열자마자 "이동 중" 으로 시작할지 정한다.
  final bool departedAlready;

  @override
  ConsumerState<MovingScreen> createState() => _MovingScreenState();
}

class _MovingScreenState extends ConsumerState<MovingScreen> {
  late bool _hasDeparted = widget.departedAlready;

  /// 서버에 출발을 알리는 중. 두 번 눌러 두 번 보내지 않게 막는다.
  bool _sendingDeparture = false;

  /// 출발을 눌렀지만 서버에 알리지 못한 상태. 화면은 이동 중으로 넘어가되 사실대로 적는다.
  bool _departureUnsynced = false;

  /// 도착을 보고하는 중. 두 번 눌러 두 번 보내지 않게 막는다.
  bool _recordingArrival = false;

  /// "출발했어요" 처리.
  ///
  /// 서버 호출이 실패해도 화면은 이동 중으로 넘긴다 — 사용자는 실제로 출발했고, 여기서 막으면
  /// 이동 안내를 볼 수 없다. 대신 서버에 알리지 못했다는 사실을 숨기지 않고 화면에 남긴다.
  Future<void> _confirmDeparture() async {
    final scheduleId = widget.scheduleId;
    // 알릴 대상이 없으면 저장소를 읽지도 않는다 — 일정과 이어지지 않은 화면에서도 안내는 열려야 한다.
    if (scheduleId == null) {
      setState(() => _hasDeparted = true);
      return;
    }
    final repository = ref.read(scheduleRepositoryProvider);
    if (repository is! ScheduleEvaluationRepository) {
      setState(() => _hasDeparted = true);
      return;
    }
    setState(() => _sendingDeparture = true);
    var failed = false;
    try {
      await (repository as ScheduleEvaluationRepository).confirmDeparture(
        scheduleId,
      );
    } on Object {
      failed = true;
    }
    if (!mounted) return;
    setState(() {
      _sendingDeparture = false;
      _departureUnsynced = failed;
      _hasDeparted = true;
    });
    if (failed) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('출발을 서버에 알리지 못했어요. 안내는 계속 볼 수 있어요.')),
      );
    }
  }

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

  /// 도착 예정 시각. 값이 없으면 시각을 만들어내지 않는다.
  ///
  /// 평가가 준 시각이 먼저다. 그것이 없을 때만 경로 요약의 도착 칸을 보되, "평가 필요"·"확인 필요"
  /// 같은 자리표시자는 시각이 아니므로 모른다고 적는다.
  Widget _arrivalCard() {
    final fromEvaluation = widget.estimatedArrivalAt;
    final fromRoute = widget.route?.arrival;
    final routeKnown =
        fromRoute != null &&
        fromRoute.isNotEmpty &&
        fromRoute != '--:--' &&
        !fromRoute.contains('필요');
    final known = fromEvaluation != null || routeKnown;
    final arrival = fromEvaluation != null
        ? formatClock(fromEvaluation)
        : fromRoute;
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
                  known ? arrival! : '아직 계산되지 않았어요',
                  key: const Key('journeyArrivalValue'),
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
      if (widget.scheduleId == null)
        const Padding(
          padding: EdgeInsets.only(bottom: 10),
          child: Text(
            '이 경로는 일정과 이어져 있지 않아 출발을 서버에 남기지 않아요.',
            key: Key('departureNotLinkedNotice'),
            style: TextStyle(fontSize: 12, color: Color(0xFF6E8193)),
          ),
        ),
      PrimaryButton(
        _sendingDeparture ? '알리는 중…' : '출발했어요',
        onPressed: _sendingDeparture ? null : _confirmDeparture,
      ),
    ];
  }

  List<Widget> _movingContent(BuildContext context) => [
    const TtubeeGuideCard(
      '목적지까지 뚜비가 함께할게요!',
      compact: true,
      character: TtubeeRunning(asset: 'assets/images/ttubee/running.png'),
    ),
    if (_departureUnsynced) ...[
      const SizedBox(height: 12),
      const InfoCard(
        key: Key('departureUnsyncedNotice'),
        child: Text(
          '출발을 서버에 알리지 못했어요. 일정 상세에서는 아직 출발 전으로 보일 수 있어요.',
          style: TextStyle(fontSize: 12, color: Color(0xFF6E8193)),
        ),
      ),
    ],
    const SizedBox(height: 12),
    _arrivalCard(),
    const SizedBox(height: 12),
    const SectionHeader('남은 이동 순서'),
    const SizedBox(height: 8),
    ..._stepList(),
    const SizedBox(height: 28),
    PrimaryButton(
      _recordingArrival ? '기록하는 중…' : '도착했어요',
      onPressed: _recordingArrival ? null : _recordArrival,
    ),
  ];

  /// 도착 보고. 일정 상세의 "도착 완료" 와 같은 흐름을 쓴다.
  ///
  /// 예전에는 서버를 부르지 않고 결과 화면만 열었다. 그 화면의 숫자도 코드에 박혀 있어서,
  /// 어느 일정을 끝내든 같은 값이 보였다. 이제는 서버가 돌려준 값만 보여준다.
  Future<void> _recordArrival() async {
    final scheduleId = widget.scheduleId;
    if (scheduleId == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('이 경로는 일정과 이어져 있지 않아 도착을 기록할 수 없어요.')),
      );
      return;
    }
    setState(() => _recordingArrival = true);
    final record = await recordArrivalWithConsent(
      context,
      scheduleId: scheduleId,
      arrivalApi: ref.read(arrivalApiProvider),
      userApi: ref.read(userApiProvider),
    );
    if (!mounted) return;
    setState(() => _recordingArrival = false);
    if (record == null) return;
    await openPage(
      context,
      ArrivalScreen(record: record, scheduleId: scheduleId),
    );
  }
}

/// 도착 결과 화면.
///
/// 보여주는 값은 전부 서버가 돌려준 것이다. 예전에는 "오전 9:00 / +2분 / 절약한 시간 2분" 이
/// 코드에 박혀 있어서 어떤 일정을 끝내도 같은 숫자가 보였다(2026-09-22 확인). 값을 모르면
/// 지어내지 않고 모른다고 적는다.
///
/// 목표 도착 시각은 도착 응답에 없어서 일정을 한 번 더 읽는다. 못 읽으면 그 줄만 비운다 —
/// 도착 기록 자체는 이미 저장됐으므로 화면 전체를 오류로 만들지 않는다.
class ArrivalScreen extends ConsumerStatefulWidget {
  const ArrivalScreen({
    super.key,
    required this.record,
    required this.scheduleId,
  });

  final ArrivalRecord record;
  final String scheduleId;

  @override
  ConsumerState<ArrivalScreen> createState() => _ArrivalScreenState();
}

class _ArrivalScreenState extends ConsumerState<ArrivalScreen> {
  late final Future<Schedule?> _schedule = _load();

  Future<Schedule?> _load() async {
    try {
      return await ref
          .read(scheduleRepositoryProvider)
          .scheduleById(widget.scheduleId);
    } on Object {
      return null;
    }
  }

  /// 판정별 문구·색. 판정이 없으면 "잘했다" 로 포장하지 않는다.
  ({String title, String guide, Color tone, IconData icon}) get _verdict =>
      switch (widget.record.result) {
        'ON_TIME' => (
          title: '여유 있게 도착했어요',
          guide: '오늘 이동도 정말 잘했어요!',
          tone: const Color(0xFF008A72),
          icon: Icons.check_circle,
        ),
        'TIGHT' => (
          title: '빠듯하게 도착했어요',
          guide: '이번엔 아슬아슬했어요. 다음엔 조금 일찍 나가볼까요?',
          tone: const Color(0xFFB26A00),
          icon: Icons.timelapse,
        ),
        'LATE_RISK' => (
          title: '목표 시각을 넘겨 도착했어요',
          guide: '늦었지만 무사히 도착했어요. 다음 계획에 참고할게요.',
          tone: DdubiColors.red,
          icon: Icons.error_outline,
        ),
        _ => (
          title: '도착으로 기록했어요',
          guide: '도착 시각을 남기지 않아 정시 여부는 판정하지 않았어요.',
          tone: const Color(0xFF7F91A2),
          icon: Icons.flag_outlined,
        ),
      };

  /// 목표와 실제의 차이 한 줄. 둘 중 하나라도 없으면 만들지 않는다.
  String? _gapText(DateTime? target) {
    final actual = widget.record.actualArrival;
    if (target == null || actual == null) return null;
    final minutes = target.difference(actual).inMinutes;
    if (minutes == 0) return '목표 시각에 딱 맞춰 도착';
    return minutes > 0 ? '목표보다 $minutes분 일찍' : '목표보다 ${-minutes}분 늦게';
  }

  Widget _row(String label, String value, {Color? tone}) => ListTile(
    contentPadding: EdgeInsets.zero,
    title: Text(label),
    trailing: Text(
      value,
      style: TextStyle(fontWeight: FontWeight.w700, color: tone),
    ),
  );

  @override
  Widget build(BuildContext context) {
    final verdict = _verdict;
    return AppPage(
      title: '도착 결과',
      child: FutureBuilder<Schedule?>(
        future: _schedule,
        builder: (context, snapshot) {
          final target = snapshot.data?.arriveBy;
          final gap = _gapText(target);
          final actual = widget.record.actualArrival;
          return ListView(
            padding: screenPadding,
            children: [
              TtubeeGuideCard(
                verdict.guide,
                character: const TtubeeCelebrate(
                  asset: 'assets/images/ttubee/celebrate.png',
                  height: 82,
                ),
              ),
              const SizedBox(height: 18),
              Icon(verdict.icon, color: verdict.tone, size: 70),
              Center(
                child: Text(
                  verdict.title,
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.titleLarge
                      ?.copyWith(color: verdict.tone),
                ),
              ),
              if (gap != null) ...[
                const SizedBox(height: 4),
                Center(
                  child: Text(
                    gap,
                    style: const TextStyle(color: Color(0xFF6E8193)),
                  ),
                ),
              ],
              const SizedBox(height: 22),
              InfoCard(
                child: Column(
                  children: [
                    if (widget.record.plannedDepart != null)
                      _row('계획 출발', formatClock(widget.record.plannedDepart!)),
                    _row(
                      '목표 도착',
                      target == null ? '확인 안 됨' : formatClock(target),
                    ),
                    _row(
                      '실제 도착',
                      actual == null ? '기록 안 함' : formatClock(actual),
                      tone: actual == null ? null : verdict.tone,
                    ),
                  ],
                ),
              ),
              if (actual == null) ...[
                const SizedBox(height: 12),
                const InfoCard(
                  key: Key('arrivalNotJudgedNotice'),
                  child: Text(
                    '도착 시각을 남기면 정시·빠듯·지연을 판정하고 리포트에도 반영해요. '
                    '더보기 > 이동 기록 저장에서 켤 수 있어요.',
                    style: TextStyle(fontSize: 12, color: Color(0xFF6E8193)),
                  ),
                ),
              ],
              const SizedBox(height: 22),
              PrimaryButton(
                '확인',
                onPressed: () =>
                    Navigator.of(context).popUntil((route) => route.isFirst),
              ),
            ],
          );
        },
      ),
    );
  }
}
