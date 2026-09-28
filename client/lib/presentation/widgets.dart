import 'package:flutter/material.dart';

import '../core/constants/layout_constants.dart';
import '../core/theme.dart';
import '../domain/models.dart';

class PrimaryButton extends StatelessWidget {
  const PrimaryButton(this.label, {super.key, required this.onPressed});
  final String label;

  /// null 이면 버튼이 눌리지 않는다. 처리 중인 동안 같은 요청을 두 번 보내지 않게 막을 때 쓴다.
  final VoidCallback? onPressed;
  @override
  Widget build(BuildContext c) => SizedBox(
    width: double.infinity,
    height: 50,
    child: FilledButton(
      onPressed: onPressed,
      style: FilledButton.styleFrom(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
      ),
      child: Text(label, style: const TextStyle(fontWeight: FontWeight.w800)),
    ),
  );
}

class SectionHeader extends StatelessWidget {
  const SectionHeader(this.title, {super.key, this.trailing});
  final String title;
  final Widget? trailing;
  @override
  Widget build(BuildContext c) => Row(
    mainAxisAlignment: MainAxisAlignment.spaceBetween,
    children: [
      Text(title, style: Theme.of(c).textTheme.titleMedium),
      ?trailing,
    ],
  );
}

class InfoCard extends StatelessWidget {
  const InfoCard({super.key, required this.child, this.color});
  final Widget child;
  final Color? color;
  @override
  Widget build(BuildContext c) => Card(
    color: color,
    child: Padding(padding: const EdgeInsets.all(16), child: child),
  );
}

class DataLoadError extends StatelessWidget {
  const DataLoadError({super.key, required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.error_outline, color: Theme.of(context).colorScheme.error),
          const SizedBox(height: 8),
          Text(message, textAlign: TextAlign.center),
        ],
      ),
    ),
  );
}

class TtubeeGuideCard extends StatelessWidget {
  const TtubeeGuideCard(
    this.message, {
    super.key,
    this.compact = false,
    this.asset = 'assets/images/ttubee-guide.png',
    this.character,
  });
  final String message;
  final bool compact;
  final String asset;
  final Widget? character;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(
      color: const Color(0xFFEAFBF8),
      borderRadius: BorderRadius.circular(18),
      border: Border.all(color: DdubiColors.blue.withValues(alpha: .28)),
    ),
    child: Row(
      children: [
        SizedBox(
          width: compact ? 58 : 76,
          height: compact ? 58 : 76,
          child:
              character ??
              Image.asset(asset, fit: BoxFit.contain, semanticLabel: '뚜비 캐릭터'),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Text(
            message,
            style: Theme.of(context).textTheme.titleMedium
                ?.copyWith(color: DdubiColors.navy, height: 1.35),
          ),
        ),
      ],
    ),
  );
}

/// 일정의 진행 상태 표시.
///
/// 서버는 완료·취소를 제대로 내려주는데 앱이 그 값을 아무 데서도 쓰지 않았다. 그래서
/// 도착 완료를 눌러도 목록에 "예정" 그대로 남아 있었다(2026-09-22 제보).
/// 진행 예정은 표시하지 않는다 — 대부분이 그 상태라 뱃지가 의미를 잃는다.
class ScheduleStatusBadge extends StatelessWidget {
  const ScheduleStatusBadge(this.status, {super.key});

  final ScheduleStatus status;

  /// 이 상태를 표시할 것이 있는지. 없으면 자리를 차지하지 않는다.
  static bool shows(ScheduleStatus status) =>
      status == ScheduleStatus.done || status == ScheduleStatus.canceled;

  /// 끝난 일정은 흐리게 그린다. 목록에서 아직 남은 일정이 먼저 눈에 들어와야 한다.
  static double opacityFor(ScheduleStatus status) => shows(status) ? 0.55 : 1;

  @override
  Widget build(BuildContext context) {
    if (!shows(status)) return const SizedBox.shrink();
    final done = status == ScheduleStatus.done;
    return Container(
      key: Key(done ? 'scheduleDoneBadge' : 'scheduleCanceledBadge'),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: done ? const Color(0xFFE9F7EF) : const Color(0xFFF1F3F7),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        done ? '완료' : '취소',
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w800,
          color: done ? const Color(0xFF0B5A33) : const Color(0xFF5C6B7F),
        ),
      ),
    );
  }
}

class StatusBadge extends StatelessWidget {
  const StatusBadge(
    this.label, {
    super.key,
    this.tone = DdubiColors.green,
    this.icon = Icons.check_circle_outline,
  });
  final String label;
  final Color tone;
  final IconData icon;
  @override
  Widget build(BuildContext c) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Icon(icon, color: tone, size: 17),
      const SizedBox(width: 5),
      Flexible(
        child: Text(
          label,
          style: TextStyle(
            color: tone,
            fontWeight: FontWeight.w700,
            fontSize: 12,
          ),
        ),
      ),
    ],
  );
}

class RouteCard extends StatelessWidget {
  const RouteCard(
    this.route, {
    super.key,
    this.compact = false,
    this.fillWidth = false,
    this.onTap,
  });
  final RouteOption route;
  final bool compact;
  final bool fillWidth;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext c) {
    final tone = switch (route.kind) {
      RouteKind.candidate => DdubiColors.blue,
      RouteKind.recommended => DdubiColors.green,
      RouteKind.fastest => DdubiColors.navy,
      RouteKind.safest => const Color(0xFF7257E8),
    };
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Container(
        width: compact && !fillWidth ? 150 : double.infinity,
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          border: Border.all(color: tone),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              route.label,
              style: TextStyle(
                color: tone,
                fontWeight: FontWeight.w800,
                fontSize: 12,
              ),
            ),
            const SizedBox(height: 9),
            Text(
              route.transport,
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 10),
            Text(
              '${route.departure} 출발  →  ${route.arrival} 도착',
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 8),
            Text(
              route.margin,
              style: TextStyle(
                color: route.kind == RouteKind.fastest
                    ? DdubiColors.red
                    : DdubiColors.green,
                fontWeight: FontWeight.w800,
              ),
            ),
            if (compact) ...[
              const SizedBox(height: 8),
              Text(route.detail, style: Theme.of(c).textTheme.bodySmall),
              Text(
                route.fare,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class DdubiBottomNav extends StatelessWidget {
  const DdubiBottomNav({super.key, required this.index, required this.onTap});
  final int index;
  final ValueChanged<int> onTap;
  @override
  Widget build(BuildContext c) => NavigationBar(
    height: 68,
    selectedIndex: index,
    onDestinationSelected: onTap,
    labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
    destinations: const [
      NavigationDestination(
        icon: Icon(Icons.calendar_today_outlined),
        label: '일정',
      ),
      NavigationDestination(icon: Icon(Icons.route_outlined), label: '경로'),
      NavigationDestination(
        icon: Icon(Icons.home_outlined, size: 29),
        selectedIcon: Icon(Icons.home, size: 31),
        label: '홈',
      ),
      NavigationDestination(
        icon: Icon(Icons.notifications_none),
        selectedIcon: Icon(Icons.notifications),
        label: '알림',
      ),
      NavigationDestination(icon: Icon(Icons.menu), label: '더보기'),
    ],
  );
}

class MonthlyCalendar extends StatelessWidget {
  const MonthlyCalendar({
    super.key,
    required this.selectedDate,
    required this.onSelected,
    this.schedules = const [],
    this.markedDates,
    this.markerLabel = '일정',
    this.expanded = false,
  });
  final DateTime selectedDate;
  final List<Schedule> schedules;

  /// 일정 대신 표시할 날짜들. 있으면 [schedules] 대신 이것으로 날마다 점·개수를 그린다.
  /// 이동 기록 달력처럼 일정이 아닌 것을 같은 달력에 올릴 때 쓴다.
  final List<DateTime>? markedDates;

  /// 넓은 모양에서 개수 옆에 붙는 말. 일정이면 "일정 3", 기록이면 "기록 3".
  final String markerLabel;
  final ValueChanged<DateTime> onSelected;
  final bool expanded;

  bool _sameDay(DateTime a, DateTime b) =>
      a.year == b.year && a.month == b.month && a.day == b.day;
  @override
  Widget build(BuildContext context) {
    final month = DateTime(selectedDate.year, selectedDate.month);
    final firstOffset = month.weekday % 7;
    final gridStart = month.subtract(Duration(days: firstOffset));
    final today = DateTime.now();
    return InfoCard(
      child: Column(
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '${month.year}년 ${month.month}월',
                      style: expanded
                          ? Theme.of(context).textTheme.headlineMedium
                          : Theme.of(context).textTheme.titleMedium,
                    ),
                    if (expanded) ...[
                      const SizedBox(height: 3),
                      Text(
                        '날짜를 선택해 일정을 확인하세요.',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ],
                ),
              ),
              TextButton(
                onPressed: () => onSelected(today),
                child: const Text('오늘'),
              ),
              IconButton(
                onPressed: () =>
                    onSelected(DateTime(month.year, month.month - 1, 1)),
                icon: const Icon(Icons.chevron_left),
                tooltip: '이전 달',
              ),
              IconButton(
                onPressed: () =>
                    onSelected(DateTime(month.year, month.month + 1, 1)),
                icon: const Icon(Icons.chevron_right),
                tooltip: '다음 달',
              ),
            ],
          ),
          SizedBox(height: expanded ? 20 : 12),
          Container(
            padding: const EdgeInsets.symmetric(vertical: 9),
            decoration: BoxDecoration(
              color: DdubiColors.surface,
              borderRadius: BorderRadius.circular(10),
            ),
            child: Row(
              children: [
                for (var index = 0; index < 7; index++)
                  Expanded(
                    child: Center(
                      child: Text(
                        const ['일', '월', '화', '수', '목', '금', '토'][index],
                        style: TextStyle(
                          color: index == 0
                              ? DdubiColors.red
                              : index == 6
                              ? const Color(0xFF2377E8)
                              : const Color(0xFF667085),
                          fontWeight: FontWeight.w700,
                          fontSize: 12,
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 8),
          GridView.builder(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            itemCount: 42,
            gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 7,
              mainAxisExtent: expanded ? 68 : 48,
            ),
            itemBuilder: (_, index) {
              final date = gridStart.add(Duration(days: index));
              final inMonth = date.month == month.month;
              final selected = _sameDay(date, selectedDate),
                  isToday = _sameDay(date, today);
              final marked = markedDates;
              final count = marked != null
                  ? marked.where((item) => _sameDay(item, date)).length
                  : schedules.where((item) => _sameDay(item.date, date)).length;
              return Padding(
                padding: const EdgeInsets.all(2),
                child: InkWell(
                  onTap: () => onSelected(date),
                  borderRadius: BorderRadius.circular(12),
                  child: Container(
                    padding: EdgeInsets.symmetric(
                      horizontal: expanded ? 8 : 2,
                      vertical: expanded ? 7 : 5,
                    ),
                    decoration: BoxDecoration(
                      color: selected
                          ? DdubiColors.blue
                          : isToday
                          ? DdubiColors.blue.withValues(alpha: .08)
                          : null,
                      border: isToday && !selected
                          ? Border.all(
                              color: DdubiColors.blue.withValues(alpha: .55),
                            )
                          : null,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Column(
                      crossAxisAlignment: expanded
                          ? CrossAxisAlignment.start
                          : CrossAxisAlignment.center,
                      children: [
                        Text(
                          '${date.day}',
                          style: TextStyle(
                            color: selected
                                ? Colors.white
                                : inMonth
                                ? DdubiColors.navy
                                : const Color(0xFFB7C0CC),
                            fontWeight: isToday || selected
                                ? FontWeight.w800
                                : FontWeight.w500,
                          ),
                        ),
                        const Spacer(),
                        if (count > 0)
                          expanded
                              ? Container(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 6,
                                    vertical: 2,
                                  ),
                                  decoration: BoxDecoration(
                                    color: selected
                                        ? Colors.white.withValues(alpha: .22)
                                        : DdubiColors.orange.withValues(
                                            alpha: .12,
                                          ),
                                    borderRadius: BorderRadius.circular(8),
                                  ),
                                  child: Text(
                                    '$markerLabel $count',
                                    style: TextStyle(
                                      color: selected
                                          ? Colors.white
                                          : DdubiColors.orange,
                                      fontSize: 10,
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                                )
                              : Container(
                                  width: 5,
                                  height: 5,
                                  decoration: BoxDecoration(
                                    color: selected
                                        ? Colors.white
                                        : DdubiColors.orange,
                                    shape: BoxShape.circle,
                                  ),
                                )
                        else
                          const SizedBox(height: 5),
                      ],
                    ),
                  ),
                ),
              );
            },
          ),
        ],
      ),
    );
  }
}

class AppPage extends StatelessWidget {
  const AppPage({
    super.key,
    required this.title,
    required this.child,
    this.index,
    this.onNav,
    this.actions,
    this.fab,
    this.webTitle,
    this.webSubtitle,
    this.mobileTitleStyle,
  });
  final String title;
  final Widget child;
  final int? index;
  final ValueChanged<int>? onNav;
  final List<Widget>? actions;
  final Widget? fab;
  final String? webTitle;
  final String? webSubtitle;
  final TextStyle? mobileTitleStyle;

  @override
  Widget build(BuildContext context) {
    final isWide =
        MediaQuery.sizeOf(context).width >=
        LayoutConstants.dashboardScreenBreakpoint;
    if (isWide) {
      return Scaffold(
        backgroundColor: DdubiColors.surface,
        body: SafeArea(
          child: Column(
            children: [
              _WebPageHeader(
                title: webTitle ?? title,
                subtitle: webSubtitle,
                actions: actions,
              ),
              Expanded(
                child: Center(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(
                      maxWidth: LayoutConstants.maxContentWidth,
                    ),
                    child: SizedBox(width: double.infinity, child: child),
                  ),
                ),
              ),
            ],
          ),
        ),
        floatingActionButton: fab,
      );
    }

    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: Colors.white,
        title: Text(
          title,
          style: mobileTitleStyle ?? Theme.of(context).textTheme.titleMedium,
        ),
        centerTitle: false,
        actions: actions,
      ),
      body: SafeArea(child: child),
      floatingActionButton: fab,
      bottomNavigationBar: index == null
          ? null
          : DdubiBottomNav(index: index!, onTap: onNav!),
    );
  }
}

class _WebPageHeader extends StatelessWidget {
  const _WebPageHeader({
    required this.title,
    required this.subtitle,
    required this.actions,
  });

  final String title;
  final String? subtitle;
  final List<Widget>? actions;

  @override
  Widget build(BuildContext context) => Container(
    height: 82,
    padding: const EdgeInsets.symmetric(horizontal: 28),
    decoration: const BoxDecoration(
      color: Colors.white,
      border: Border(bottom: BorderSide(color: DdubiColors.border)),
    ),
    child: Row(
      children: [
        Expanded(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleLarge),
              if (subtitle != null) ...[
                const SizedBox(height: 3),
                Text(subtitle!, style: Theme.of(context).textTheme.bodySmall),
              ],
            ],
          ),
        ),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: const Color(0xFFEAFBF8),
            borderRadius: BorderRadius.circular(20),
          ),
          child: const StatusBadge(
            '서비스 정상',
            tone: DdubiColors.green,
            icon: Icons.cloud_done_outlined,
          ),
        ),
        if (actions != null) ...[const SizedBox(width: 10), ...actions!],
      ],
    ),
  );
}

class DashboardMetricCard extends StatelessWidget {
  const DashboardMetricCard({
    super.key,
    required this.label,
    required this.value,
    required this.icon,
    this.tone = DdubiColors.blue,
    this.caption,
  });

  final String label;
  final String value;
  final IconData icon;
  final Color tone;
  final String? caption;

  @override
  Widget build(BuildContext context) => InfoCard(
    child: Row(
      children: [
        Container(
          width: 44,
          height: 44,
          decoration: BoxDecoration(
            color: tone.withValues(alpha: .1),
            borderRadius: BorderRadius.circular(12),
          ),
          child: Icon(icon, color: tone),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label, style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: 2),
              Text(value, style: Theme.of(context).textTheme.titleLarge),
              if (caption != null)
                Text(caption!, style: Theme.of(context).textTheme.bodySmall),
            ],
          ),
        ),
      ],
    ),
  );
}

class PlaceSearchField extends StatelessWidget {
  const PlaceSearchField({
    super.key,
    required this.label,
    required this.value,
    required this.searchService,
    required this.onSelected,
  });

  final String label;
  final PlaceLocation? value;
  final PlaceSearchService searchService;
  final ValueChanged<PlaceLocation> onSelected;

  @override
  Widget build(BuildContext context) => FormField<PlaceLocation>(
    initialValue: value,
    validator: (selected) => selected == null ? '$label를 검색해 선택해주세요.' : null,
    builder: (field) => Padding(
      padding: const EdgeInsets.only(bottom: 15),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12),
          ),
          const SizedBox(height: 6),
          InkWell(
            borderRadius: BorderRadius.circular(10),
            onTap: () async {
              final selected = await showDialog<PlaceLocation>(
                context: context,
                builder: (_) => _PlaceSearchDialog(
                  title: '$label 검색',
                  searchService: searchService,
                ),
              );
              if (selected == null) return;
              field.didChange(selected);
              onSelected(selected);
            },
            child: InputDecorator(
              decoration: InputDecoration(
                errorText: field.errorText,
                suffixIcon: const Icon(Icons.search),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(value?.name ?? '$label를 검색해주세요.'),
                  if (value?.address case final address?)
                    Text(
                      address,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    ),
  );
}

class _PlaceSearchDialog extends StatefulWidget {
  const _PlaceSearchDialog({required this.title, required this.searchService});

  final String title;
  final PlaceSearchService searchService;

  @override
  State<_PlaceSearchDialog> createState() => _PlaceSearchDialogState();
}

Future<PlaceLocation?> showPlaceSearchDialog(
  BuildContext context, {
  required String title,
  required PlaceSearchService searchService,
}) => showDialog<PlaceLocation>(
  context: context,
  builder: (_) =>
      _PlaceSearchDialog(title: title, searchService: searchService),
);

class _PlaceSearchDialogState extends State<_PlaceSearchDialog> {
  final _query = TextEditingController();
  List<PlaceLocation> _results = const [];
  bool _loading = false;
  String? _error;

  /// 다음 페이지가 있는지는 서버가 준 hasNext 로만 판단한다. 받은 개수가 요청한 수보다
  /// 적어도 마지막 페이지가 아닐 수 있다 — 서버가 수도권과 부산 결과를 섞어 내려주기
  /// 때문이다.
  bool _hasNext = false;
  int _page = 0;

  @override
  void dispose() {
    _query.dispose();
    super.dispose();
  }

  Future<void> _search({bool more = false}) async {
    final query = _query.text.trim();
    if (query.isEmpty || _loading) return;
    final page = more ? _page + 1 : 0;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final result = await widget.searchService.searchPage(query, page: page);
      if (!mounted) return;
      setState(() {
        // 서버가 준 순서를 그대로 쓴다. 주소나 지역명으로 다시 정렬하면 섞어 보낸
        // 의미가 사라지고 한 지역 결과가 뒤로 밀린다.
        _results = more ? [..._results, ...result.items] : result.items;
        _hasNext = result.hasNext;
        _page = page;
        // 검색 범위는 서버의 서비스 권역이 정한다. 앱이 특정 지역 이름을 단정하면
        // 서버가 권역을 넓혔을 때 안내가 사실과 달라진다.
        if (_results.isEmpty) {
          _error = '서비스 지역 안에서 검색 결과를 찾지 못했어요.';
        }
      });
    } on Object {
      if (!mounted) return;
      setState(() => _error = '장소 검색에 실패했어요. 잠시 후 다시 시도해주세요.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final height = MediaQuery.sizeOf(context).height * .55;
    return AlertDialog(
      title: Text(widget.title),
      content: SizedBox(
        width: 520,
        height: height.clamp(300, 480),
        child: Column(
          children: [
            TextField(
              key: const Key('placeSearchQuery'),
              controller: _query,
              autofocus: true,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _search(),
              decoration: InputDecoration(
                hintText: '예: 잠실야구장',
                suffixIcon: IconButton(
                  key: const Key('placeSearchButton'),
                  onPressed: _search,
                  icon: const Icon(Icons.search),
                ),
              ),
            ),
            const SizedBox(height: 12),
            if (_loading) const LinearProgressIndicator(),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Text(_error!, textAlign: TextAlign.center),
              ),
            Expanded(
              child: ListView.separated(
                itemCount: _results.length + (_hasNext ? 1 : 0),
                separatorBuilder: (_, _) => const Divider(height: 1),
                itemBuilder: (context, index) {
                  if (index == _results.length) {
                    return TextButton(
                      key: const Key('placeSearchMoreButton'),
                      onPressed: _loading ? null : () => _search(more: true),
                      child: const Text('더보기'),
                    );
                  }
                  final place = _results[index];
                  return ListTile(
                    key: Key('placeSearchResult$index'),
                    leading: const Icon(Icons.place_outlined),
                    title: Text(place.name),
                    subtitle: Text(place.address ?? '주소 정보 없음'),
                    onTap: () => Navigator.pop(context, place),
                  );
                },
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('닫기'),
        ),
      ],
    );
  }
}

/// 출발을 알렸고 아직 도착 전인 일정 표시.
///
/// 일정의 상태(status)는 도착을 기록해야 "완료" 가 되고, 그 전까지는 계속 "예정" 이다. 그래서
/// 출발했어요 를 눌러도 홈에서는 아무것도 달라지지 않았다(2026-09-22 제보). 출발 사실은 서버가
/// 따로 기록하므로(actualDepartAt) 그 값으로 이 구간을 보여준다.
class MovingBadge extends StatelessWidget {
  const MovingBadge({super.key});

  @override
  Widget build(BuildContext context) => Container(
    key: const Key('scheduleMovingBadge'),
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
    decoration: BoxDecoration(
      color: const Color(0xFFE4F1FF),
      borderRadius: BorderRadius.circular(999),
    ),
    child: const Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(Icons.directions_walk_rounded, size: 12, color: Color(0xFF1D5FBF)),
        SizedBox(width: 3),
        Text(
          '이동 중',
          style: TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.w800,
            color: Color(0xFF1D5FBF),
          ),
        ),
      ],
    ),
  );
}

/// 홈 결론 카드 위에 붙는 "어느 일정의 결론인가" 한 줄.
///
/// 홈은 가장 가까운 일정 하나의 결론만 보여준다. 일정이 여럿이면 어느 것 이야기인지 이름이 있어야
/// 헷갈리지 않는다(2026-09-22 제보).
class HeadlineScheduleLabel extends StatelessWidget {
  const HeadlineScheduleLabel({super.key, required this.schedule});

  final Schedule schedule;

  @override
  Widget build(BuildContext context) => Row(
    key: const Key('homeHeadlineSchedule'),
    children: [
      const Icon(Icons.event_note_rounded, size: 14, color: Color(0xFF5C6B7F)),
      const SizedBox(width: 5),
      Expanded(
        child: Text(
          '다음 일정 · ${schedule.title} · ${schedule.time} 도착',
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w700,
            color: Color(0xFF5C6B7F),
          ),
        ),
      ),
      if (schedule.hasDeparted) ...[
        const SizedBox(width: 6),
        const MovingBadge(),
      ],
    ],
  );
}
