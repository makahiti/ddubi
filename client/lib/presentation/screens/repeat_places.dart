import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/theme.dart';
import '../../domain/models.dart';
import '../../services/app_api.dart';
import '../../services/place_api.dart';
import '../widgets.dart';
import 'schedule_form.dart';
import 'shared.dart';

class RepeatSchedulesScreen extends StatefulWidget {
  const RepeatSchedulesScreen({
    super.key,
    required this.repository,
    required this.placeSearchService,
  });

  final RepeatScheduleRepository repository;
  final PlaceSearchService placeSearchService;

  @override
  State<RepeatSchedulesScreen> createState() => _RepeatSchedulesScreenState();
}

class _RepeatSchedulesScreenState extends State<RepeatSchedulesScreen> {
  late Future<List<RepeatScheduleRule>> _rules;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  void _reload() => _rules = widget.repository.repeatSchedules();

  @override
  Widget build(BuildContext context) => AppPage(
    title: '반복 일정 관리',
    actions: [
      IconButton(
        tooltip: '반복 일정 추가',
        onPressed: () async {
          final saved = await context.push<bool>('/schedules/repeat/new');
          if (saved == true && mounted) setState(_reload);
        },
        icon: const Icon(Icons.add),
      ),
    ],
    child: FutureBuilder<List<RepeatScheduleRule>>(
      future: _rules,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const DataLoadError(message: '반복 일정을 불러오지 못했어요.');
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final rules = snapshot.data!;
        if (rules.isEmpty) {
          return const Center(child: Text('등록된 반복 일정이 없어요.'));
        }
        return ListView.separated(
          padding: screenPadding,
          itemCount: rules.length,
          separatorBuilder: (_, _) => const SizedBox(height: 10),
          itemBuilder: (context, index) {
            final rule = rules[index];
            final days = rule.weekdays.toList()..sort();
            return InfoCard(
              child: ListTile(
                contentPadding: EdgeInsets.zero,
                leading: Icon(
                  Icons.event_repeat,
                  color: rule.active ? DdubiColors.blue : Colors.grey,
                ),
                title: Row(
                  children: [
                    Expanded(
                      child: Text(
                        rule.title,
                        style: const TextStyle(fontWeight: FontWeight.w800),
                      ),
                    ),
                    if (rule.needsDepartTime)
                      const StatusBadge(
                        '출발시각 없음',
                        tone: Color(0xFFFF6B35),
                        icon: Icons.error_outline,
                      )
                    else
                      StatusBadge(
                        rule.active ? '반복 중' : '중지됨',
                        tone: rule.active ? DdubiColors.green : Colors.grey,
                      ),
                  ],
                ),
                // 출발시각이 없는 규칙은 왜 계산이 안 되는지 여기서 바로 알린다.
                // 서버가 값을 지어내지 않으므로 사용자가 넣기 전까지 계산이 멈춰 있다.
                subtitle: Text(
                  rule.needsDepartTime
                      ? '${days.map((day) => weekdayLabels[day - 1]).join(' · ')}  ${rule.time} 도착\n'
                            '출발시각이 없어 추천 계산이 안 되고 있어요. 수정에서 넣어주세요.'
                      : '${days.map((day) => weekdayLabels[day - 1]).join(' · ')}  '
                            '${rule.departTime} 출발 → ${rule.time} 도착\n'
                            '${rule.departurePlace} → ${rule.place}',
                ),
                isThreeLine: true,
                trailing: PopupMenuButton<String>(
                  onSelected: (value) =>
                      value == 'edit' ? _edit(rule) : _deactivate(rule),
                  itemBuilder: (_) => [
                    const PopupMenuItem(value: 'edit', child: Text('수정')),
                    if (rule.active)
                      const PopupMenuItem(value: 'stop', child: Text('반복 중지')),
                  ],
                ),
              ),
            );
          },
        );
      },
    ),
  );

  Future<void> _edit(RepeatScheduleRule rule) async {
    final changed = await context.push<bool>(
      '/schedules/repeats/${rule.id}/edit',
    );
    if (changed == true && mounted) setState(_reload);
  }

  Future<void> _deactivate(RepeatScheduleRule rule) async {
    // 이미 만들어진 앞으로의 일정을 어떻게 할지 물어본다. 예전에는 묻지 않고 남겨 뒀는데,
    // 반복을 중지했는데 다음 주 일정이 그대로 있으면 중지된 것으로 보이지 않는다.
    final cancelFuture = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        key: const Key('repeatDeactivateDialog'),
        title: const Text('반복을 중지할까요?'),
        content: Text(
          '${rule.title}의 새 일정 생성을 중지합니다.\n'
          '이미 만들어 둔 앞으로의 일정은 어떻게 할까요?',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('취소'),
          ),
          TextButton(
            key: const Key('repeatDeactivateKeepFuture'),
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('그대로 두기'),
          ),
          FilledButton(
            key: const Key('repeatDeactivateCancelFuture'),
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('함께 취소'),
          ),
        ],
      ),
    );
    if (cancelFuture == null) return;
    try {
      await widget.repository.deactivateRepeatSchedule(
        rule.id,
        cancelFutureInstances: cancelFuture,
      );
      if (mounted) setState(_reload);
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('반복 일정을 중지하지 못했어요.')));
    }
  }
}

class RepeatScheduleEditScreen extends StatelessWidget {
  const RepeatScheduleEditScreen({
    super.key,
    required this.repository,
    required this.placeSearchService,
    required this.repeatId,
  });

  final RepeatScheduleRepository repository;
  final PlaceSearchService placeSearchService;
  final String repeatId;

  @override
  Widget build(BuildContext context) => FutureBuilder<RepeatScheduleRule>(
    future: repository.repeatScheduleById(repeatId),
    builder: (context, snapshot) {
      if (snapshot.hasError) {
        return const AppPage(
          title: '반복 일정 수정',
          child: DataLoadError(message: '반복 일정을 불러오지 못했어요.'),
        );
      }
      if (!snapshot.hasData) {
        return const AppPage(
          title: '반복 일정 수정',
          child: Center(child: CircularProgressIndicator()),
        );
      }
      return RepeatScheduleFormScreen(
        repository: repository,
        placeSearchService: placeSearchService,
        rule: snapshot.data,
      );
    },
  );
}

class SavedPlacesScreen extends StatefulWidget {
  const SavedPlacesScreen({
    super.key,
    required this.api,
    required this.searchService,
  });

  final PlaceApi api;
  final PlaceSearchService searchService;

  @override
  State<SavedPlacesScreen> createState() => _SavedPlacesScreenState();
}

class _SavedPlacesScreenState extends State<SavedPlacesScreen> {
  late Future<List<SavedPlace>> _places;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  void _reload() => _places = widget.api.list();

  @override
  Widget build(BuildContext context) => AppPage(
    title: '저장 장소',
    actions: [
      IconButton(
        key: const Key('addSavedPlaceButton'),
        tooltip: '장소 추가',
        onPressed: _add,
        icon: const Icon(Icons.add_location_alt_outlined),
      ),
    ],
    child: FutureBuilder<List<SavedPlace>>(
      future: _places,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const DataLoadError(message: '저장 장소를 불러오지 못했어요.');
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final places = snapshot.data!;
        if (places.isEmpty) {
          return const Center(child: Text('저장한 장소가 없어요.'));
        }
        return ListView.separated(
          padding: screenPadding,
          itemCount: places.length,
          separatorBuilder: (_, _) => const Divider(height: 1),
          itemBuilder: (context, index) {
            final place = places[index];
            return ListTile(
              leading: const Icon(Icons.bookmark_outline),
              title: Text(place.name),
              subtitle: Text(place.address ?? '주소 정보 없음'),
              onTap: () => _showDetail(place),
              trailing: PopupMenuButton<String>(
                onSelected: (value) =>
                    value == 'rename' ? _rename(place) : _delete(place),
                itemBuilder: (_) => const [
                  PopupMenuItem(value: 'rename', child: Text('이름 수정')),
                  PopupMenuItem(value: 'delete', child: Text('삭제')),
                ],
              ),
            );
          },
        );
      },
    ),
  );

  Future<void> _add() async {
    final selected = await showPlaceSearchDialog(
      context,
      title: '저장할 장소 검색',
      searchService: widget.searchService,
    );
    if (selected == null) return;
    try {
      await widget.api.create(selected);
      if (mounted) setState(_reload);
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('장소를 저장하지 못했어요.')));
    }
  }

  Future<void> _showDetail(SavedPlace place) async {
    try {
      final detail = await widget.api.get(place.id);
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text(detail.name),
          content: Text(
            '${detail.address ?? '주소 정보 없음'}\n'
            '${detail.lat}, ${detail.lng}',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('확인'),
            ),
          ],
        ),
      );
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('장소 상세를 불러오지 못했어요.')));
    }
  }

  Future<void> _rename(SavedPlace place) async {
    final controller = TextEditingController(text: place.name);
    final name = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('장소 이름 수정'),
        content: TextField(controller: controller, autofocus: true),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () =>
                Navigator.pop(dialogContext, controller.text.trim()),
            child: const Text('저장'),
          ),
        ],
      ),
    );
    controller.dispose();
    if (name == null || name.isEmpty || name == place.name) return;
    try {
      await widget.api.update(place.id, name: name);
      if (mounted) setState(_reload);
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('장소 이름을 수정하지 못했어요.')));
    }
  }

  Future<void> _delete(SavedPlace place) async {
    try {
      await widget.api.delete(place.id);
      if (mounted) setState(_reload);
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('장소를 삭제하지 못했어요.')));
    }
  }
}

class TravelRecordsScreen extends StatefulWidget {
  const TravelRecordsScreen({super.key, required this.api});
  final ReportApi api;

  @override
  State<TravelRecordsScreen> createState() => _TravelRecordsScreenState();
}

class _TravelRecordsData {
  const _TravelRecordsData(this.history, this.reports);
  final TravelHistory history;
  final List<WeeklyReport> reports;
}

class _TravelRecordsScreenState extends State<TravelRecordsScreen> {
  late Future<_TravelRecordsData> _data;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    final from = DateTime(now.year, now.month - 2, 1);
    final to = DateTime(now.year, now.month + 1, 0);
    _data =
        Future.wait([
          widget.api.history(from: from, to: to),
          widget.api.reports(),
        ]).then(
          (values) => _TravelRecordsData(
            values[0] as TravelHistory,
            values[1] as List<WeeklyReport>,
          ),
        );
  }

  @override
  Widget build(BuildContext context) => AppPage(
    title: '이동 기록 · 리포트',
    child: FutureBuilder<_TravelRecordsData>(
      future: _data,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const DataLoadError(message: '이동 기록을 불러오지 못했어요.');
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final history = snapshot.data!.history;
        final reports = snapshot.data!.reports;
        return ListView(
          padding: screenPadding,
          children: [
            InfoCard(
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceAround,
                children: [
                  _recordMetric('전체', '${history.summary.totalCount}건'),
                  _recordMetric(
                    '정시율',
                    '${(history.summary.onTimeRate * 100).round()}%',
                  ),
                  _recordMetric('지연', '${history.summary.lateCount}건'),
                ],
              ),
            ),
            const SizedBox(height: 18),
            const SectionHeader('주간 리포트'),
            if (reports.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 14),
                child: Text('아직 생성된 리포트가 없어요.'),
              )
            else
              ...reports.map(
                (report) => ListTile(
                  leading: const Icon(Icons.insights_outlined),
                  title: Text(
                    '${report.periodStart.month}.${report.periodStart.day} '
                    '~ ${report.periodEnd.month}.${report.periodEnd.day}',
                  ),
                  subtitle: Text(
                    '전체 ${report.totalSchedules} · 정시 ${report.onTimeEstimated} · '
                    '빠듯 ${report.tightEstimated} · 지연 ${report.delayedEstimated}',
                  ),
                  onTap: () => _showReport(report),
                ),
              ),
            const SizedBox(height: 18),
            const SectionHeader('최근 이동 기록'),
            if (history.items.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 14),
                child: Text('최근 이동 기록이 없어요.'),
              )
            else
              ...history.items.map(_historyTile),
          ],
        );
      },
    ),
  );

  Widget _recordMetric(String label, String value) => Column(
    children: [
      Text(label),
      const SizedBox(height: 4),
      Text(value, style: const TextStyle(fontWeight: FontWeight.w800)),
    ],
  );

  Widget _historyTile(TravelHistoryItem item) => ListTile(
    leading: const Icon(Icons.directions_transit_outlined),
    title: Text(item.title),
    subtitle: Text(formatFullDate(item.arriveBy)),
    trailing: Text(arrivalResultText(item.result)),
  );

  Future<void> _showReport(WeeklyReport report) async {
    try {
      final details = await widget.api.reportDetails(report.id);
      if (!mounted) return;
      await showModalBottomSheet<void>(
        context: context,
        showDragHandle: true,
        builder: (context) => ListView(
          padding: screenPadding,
          children: [
            Text('주간 리포트 상세', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            if (details.isEmpty) const Text('이 주의 이동 기록이 없어요.'),
            ...details.map(_historyTile),
          ],
        ),
      );
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('리포트 상세를 불러오지 못했어요.')));
    }
  }
}
