import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/constants/layout_constants.dart';
import '../../core/theme.dart';
import '../../domain/models.dart';
import '../micro_animations.dart';
import '../widgets.dart';
import 'shared.dart';

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

  Future<void> _showConflicts(BuildContext context) async {
    final from = DateTime(selectedDate.year, selectedDate.month, 1);
    final to = DateTime(selectedDate.year, selectedDate.month + 1, 1);
    try {
      final conflicts = await widget.repository.conflicts(from, to);
      if (!context.mounted) return;
      await showDialog<void>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: const Text('연속 일정 충돌'),
          content: conflicts.isEmpty
              ? const Text('이동 시간이 부족한 일정 조합이 없어요.')
              : Column(
                  mainAxisSize: MainAxisSize.min,
                  children: conflicts
                      .map(
                        (conflict) => ListTile(
                          leading: Icon(
                            conflict.severity == 'CRITICAL'
                                ? Icons.error_outline
                                : Icons.warning_amber_outlined,
                            color: conflict.severity == 'CRITICAL'
                                ? DdubiColors.red
                                : DdubiColors.orange,
                          ),
                          title: Text('${conflict.shortageMin}분 부족'),
                          subtitle: Text(
                            '일정 ${conflict.previousScheduleId} → ${conflict.nextScheduleId}',
                          ),
                        ),
                      )
                      .toList(growable: false),
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
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('일정 충돌을 확인하지 못했어요.')));
    }
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
        tooltip: '연속 일정 충돌 확인',
        onPressed: () => _showConflicts(c),
        icon: const Icon(Icons.warning_amber_outlined),
      ),
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
              padding: screenPadding,
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
  Widget build(BuildContext context) => Opacity(
    // 끝난 일정은 흐리게. 아직 남은 일정이 먼저 눈에 들어와야 한다.
    opacity: ScheduleStatusBadge.opacityFor(schedule.status),
    child: Card(
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
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            schedule.title,
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                        ),
                        if (ScheduleStatusBadge.shows(schedule.status)) ...[
                          const SizedBox(width: 6),
                          ScheduleStatusBadge(schedule.status),
                        ],
                      ],
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
                        // 반복에서 만들어진 일정임을 밝힌다. 표시가 없으면 하나를 지워도
                        // 다음 주에 또 생기는 이유를 알 수 없다.
                        if (schedule.isRepeating) ...[
                          const SizedBox(width: 6),
                          const StatusBadge(
                            '반복',
                            tone: DdubiColors.blue,
                            icon: Icons.event_repeat,
                          ),
                        ],
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
    ),
  );
}
