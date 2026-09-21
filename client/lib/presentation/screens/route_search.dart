import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/constants/layout_constants.dart';
import '../../core/theme.dart';
import '../../domain/models.dart';
import '../../services/recommendation_api.dart';
import '../micro_animations.dart';
import '../widgets.dart';
import '../schedule_evaluation_card.dart';
import 'map.dart';
import 'shared.dart';

class RouteResultsScreen extends StatelessWidget {
  const RouteResultsScreen({
    super.key,
    required this.repository,
    required this.api,
    this.condition,
    this.go,
  });
  final DdubiRepository repository;
  final RecommendationApi api;
  final RouteSearchCondition? condition;
  final ValueChanged<int>? go;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '추천 결과',
    webTitle: '추천 경로',
    webSubtitle: '도착 시간과 이동 조건을 기준으로 가장 알맞은 경로를 안내해요.',
    index: go == null ? null : 1,
    onNav: go,
    child: FutureBuilder<RouteEvaluationResult>(
      future: condition == null
          ? repository.routes().then(RouteEvaluationResult.candidates)
          : api.evaluateOrSearch(condition!),
      builder: (c, s) {
        if (s.hasError) {
          return const DataLoadError(message: '추천 경로를 불러오지 못했어요.');
        }
        if (!s.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final result = s.data!;
        if (result.evaluation != null) {
          return ListView(
            padding: screenPadding,
            children: [
              if (condition != null) ...[
                _RouteSearchSummary(condition: condition!),
                const SizedBox(height: 12),
              ],
              ScheduleEvaluationSummary(evaluation: result.evaluation!),
            ],
          );
        }
        // 평가 결과가 없을 때도 화면에는 한 개만 남긴다. 후보를 늘어놓아도 고를 근거가
        // 없고, 여기 숫자는 우리 계산이 아니라 경로 API 원본이다(목록은 그 시간이 짧은 순).
        final routes = result.candidates.take(1).toList(growable: false);
        if (routes.isEmpty) {
          return const Center(child: Text('표시할 경로 후보가 없어요.'));
        }
        return LayoutBuilder(
          builder: (context, constraints) {
            final isWide =
                constraints.maxWidth >= LayoutConstants.wideScreenBreakpoint;
            return isWide
                ? _webRouteResults(c, routes)
                : _mobileRouteResults(c, routes);
          },
        );
      },
    ),
  );

  Widget _mobileRouteResults(BuildContext context, List<RouteOption> routes) =>
      ListView(
        key: const Key('mobileRouteResultsLayout'),
        padding: screenPadding,
        children: [
          if (condition != null) ...[
            _RouteSearchSummary(condition: condition!),
            const SizedBox(height: 12),
          ],
          _candidateGuide,
          const SizedBox(height: 12),
          for (final route in routes)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: RouteCard(
                route,
                onTap: () => _openRouteMap(context, route),
              ),
            ),
          _candidateNotice,
        ],
      );

  Widget _webRouteResults(BuildContext context, List<RouteOption> routes) =>
      ListView(
        key: const Key('webRouteResultsLayout'),
        padding: screenPadding,
        children: [
          if (condition != null) ...[
            _RouteSearchSummary(condition: condition!),
            const SizedBox(height: 16),
          ],
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Expanded(flex: 3, child: _candidateGuide),
              const SizedBox(width: 16),
              const Expanded(flex: 2, child: _candidateNotice),
            ],
          ),
          const SizedBox(height: 20),
          Text('경로', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 12),
          Wrap(
            key: const Key('webRouteResultsGrid'),
            spacing: 12,
            runSpacing: 12,
            children: [
              for (final route in routes)
                SizedBox(
                  width: 320,
                  child: RouteCard(
                    route,
                    compact: true,
                    fillWidth: true,
                    onTap: () => _openRouteMap(context, route),
                  ),
                ),
            ],
          ),
        ],
      );

  void _openRouteMap(BuildContext context, RouteOption route) => openPage(
    context,
    RouteMapScreen(
      repository: repository,
      selectedRoute: route,
      condition: condition,
    ),
  );

  static const _candidateGuide = TtubeeGuideCard(
    '서버 평가 기능이 준비 중이라 경로 API 가 알려 준 경로만 보여드려요.',
    compact: true,
    character: TtubeeEntrance(
      asset: 'assets/images/ttubee/route-guide.png',
      height: 62,
      fromRight: true,
      semanticLabel: '지도를 든 뚜비',
    ),
  );

  static const _candidateNotice = InfoCard(
    color: Color(0xFFF4F8FF),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        StatusBadge(
          '원본 경로 기준',
          tone: DdubiColors.blue,
          icon: Icons.lightbulb_outline,
        ),
        SizedBox(height: 10),
        Text('여기 시간은 경로 API 가 준 값이에요. 출발 추천과 예상 도착은 서버 평가 결과가 준비된 뒤 표시합니다.'),
      ],
    ),
  );
}

class RouteSearchScreen extends StatefulWidget {
  const RouteSearchScreen({
    super.key,
    required this.repository,
    required this.placeSearchService,
  });

  final DdubiRepository repository;
  final PlaceSearchService placeSearchService;

  @override
  State<RouteSearchScreen> createState() => _RouteSearchScreenState();
}

class _RouteSearchScreenState extends State<RouteSearchScreen> {
  final _formKey = GlobalKey<FormState>();
  PlaceLocation? _origin;
  PlaceLocation? _destination;
  late DateTime _plannedDepartAt;
  bool _departureConfirmed = false;
  late DateTime _desiredArrivalAt;
  late final TextEditingController _departure;
  late final TextEditingController _arrival;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    _plannedDepartAt = now.add(const Duration(minutes: 30));
    _desiredArrivalAt = DateTime(now.year, now.month, now.day + 1, 10);
    _departure = TextEditingController(text: _formatArrival(_plannedDepartAt));
    _arrival = TextEditingController(text: _formatArrival(_desiredArrivalAt));
  }

  @override
  void dispose() {
    _departure.dispose();
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
            padding: screenPadding,
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
                    if (RouteSearchHistory.items.isNotEmpty) ...[
                      const SizedBox(height: 16),
                      _recentSearches(),
                    ],
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
          key: const Key('routeDepartureTimeField'),
          controller: _departure,
          validator: (_) => _departureConfirmed ? null : '출발 예정시간을 직접 선택해 주세요.',
          readOnly: true,
          onTap: _pickDeparture,
          decoration: const InputDecoration(
            labelText: '출발 예정시간',
            prefixIcon: Icon(Icons.directions_walk_outlined),
            suffixIcon: Icon(Icons.edit_calendar_outlined),
          ),
        ),
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
        PrimaryButton('경로 평가하기', onPressed: _search),
      ],
    ),
  );

  Widget _recentSearches() => InfoCard(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Text('최근 검색', style: TextStyle(fontWeight: FontWeight.w800)),
        const SizedBox(height: 8),
        for (final item in RouteSearchHistory.items.take(3))
          ListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.history_rounded),
            title: Text('${item.departurePlace} → ${item.destinationPlace}'),
            subtitle: Text(_formatArrival(item.desiredArrivalAt)),
            onTap: () => setState(() {
              _origin = item.origin;
              _destination = item.destination;
              _plannedDepartAt = item.plannedDepartAt;
              _departureConfirmed = false;
              _desiredArrivalAt = item.desiredArrivalAt;
              _departure.text = _formatArrival(_plannedDepartAt);
              _arrival.text = _formatArrival(_desiredArrivalAt);
            }),
          ),
      ],
    ),
  );

  Widget _placeField(bool departure) {
    return PlaceSearchField(
      key: Key(departure ? 'routeDepartureField' : 'routeDestinationField'),
      label: departure ? '출발지' : '도착지',
      value: departure ? _origin : _destination,
      searchService: widget.placeSearchService,
      onSelected: (value) => setState(() {
        if (departure) {
          _origin = value;
        } else {
          _destination = value;
        }
      }),
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

  Future<void> _pickDeparture() async {
    final date = await showDatePicker(
      context: context,
      initialDate: _plannedDepartAt,
      firstDate: DateTime.now().subtract(const Duration(days: 1)),
      lastDate: DateTime.now().add(const Duration(days: 365)),
    );
    if (date == null || !mounted) return;
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(_plannedDepartAt),
    );
    if (time == null || !mounted) return;
    setState(() {
      _plannedDepartAt = DateTime(
        date.year,
        date.month,
        date.day,
        time.hour,
        time.minute,
      );
      _departure.text = _formatArrival(_plannedDepartAt);
      _departureConfirmed = true;
    });
  }

  void _search() {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    if (_origin == null || _destination == null) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('출발지와 도착지를 검색해 선택해주세요.')));
      return;
    }
    if (!_plannedDepartAt.isBefore(_desiredArrivalAt)) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('출발 예정시간은 도착 희망시간보다 앞서야 합니다.')),
      );
      return;
    }
    final condition = RouteSearchCondition(
      origin: _origin!,
      destination: _destination!,
      plannedDepartAt: _plannedDepartAt,
      desiredArrivalAt: _desiredArrivalAt,
    );
    setState(() => RouteSearchHistory.add(condition));
    context.push('/recommendations/search-results', extra: condition);
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
        Text('${_formatArrival(condition.plannedDepartAt)} 출발 예정'),
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
