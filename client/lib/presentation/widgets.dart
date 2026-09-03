import 'package:flutter/material.dart';

import '../core/theme.dart';
import '../domain/models.dart';

class PrimaryButton extends StatelessWidget {
  const PrimaryButton(this.label, {super.key, required this.onPressed});
  final String label;
  final VoidCallback onPressed;
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
  const RouteCard(this.route, {super.key, this.compact = false, this.onTap});
  final RouteOption route;
  final bool compact;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext c) {
    final tone = switch (route.kind) {
      RouteKind.recommended => DdubiColors.green,
      RouteKind.fastest => DdubiColors.navy,
      RouteKind.safest => const Color(0xFF7257E8),
    };
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Container(
        width: compact ? 150 : double.infinity,
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
    required this.schedules,
    required this.onSelected,
  });
  final DateTime selectedDate;
  final List<Schedule> schedules;
  final ValueChanged<DateTime> onSelected;

  bool _sameDay(DateTime a, DateTime b) =>
      a.year == b.year && a.month == b.month && a.day == b.day;
  @override
  Widget build(BuildContext context) {
    final month = DateTime(selectedDate.year, selectedDate.month);
    final firstOffset = month.weekday % 7;
    final days = DateTime(month.year, month.month + 1, 0).day;
    final today = DateTime.now();
    return InfoCard(
      child: Column(
        children: [
          Row(
            children: [
              IconButton(
                onPressed: () =>
                    onSelected(DateTime(month.year, month.month - 1, 1)),
                icon: const Icon(Icons.chevron_left),
              ),
              Expanded(
                child: Text(
                  '${month.year}년 ${month.month}월',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
              IconButton(
                onPressed: () =>
                    onSelected(DateTime(month.year, month.month + 1, 1)),
                icon: const Icon(Icons.chevron_right),
              ),
            ],
          ),
          Row(
            children: [
              for (final label in ['일', '월', '화', '수', '목', '금', '토'])
                Expanded(
                  child: Center(
                    child: Text(
                      label,
                      style: TextStyle(
                        fontWeight: FontWeight.w700,
                        fontSize: 12,
                      ),
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 8),
          GridView.builder(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            itemCount: 42,
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 7,
              mainAxisExtent: 43,
            ),
            itemBuilder: (_, index) {
              final day = index - firstOffset + 1;
              if (day < 1 || day > days) return const SizedBox.shrink();
              final date = DateTime(month.year, month.month, day);
              final selected = _sameDay(date, selectedDate),
                  isToday = _sameDay(date, today);
              final count = schedules
                  .where((item) => _sameDay(item.date, date))
                  .length;
              return InkWell(
                onTap: () => onSelected(date),
                borderRadius: BorderRadius.circular(12),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Container(
                      width: 31,
                      height: 28,
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        color: selected ? DdubiColors.blue : null,
                        border: isToday && !selected
                            ? Border.all(color: DdubiColors.blue)
                            : null,
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Text(
                        '$day',
                        style: TextStyle(
                          color: selected ? Colors.white : DdubiColors.navy,
                          fontWeight: isToday
                              ? FontWeight.w800
                              : FontWeight.w500,
                        ),
                      ),
                    ),
                    SizedBox(
                      height: 4,
                      child: count == 0
                          ? null
                          : Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: List.generate(
                                count.clamp(1, 3),
                                (_) => Container(
                                  width: 4,
                                  height: 4,
                                  margin: const EdgeInsets.symmetric(
                                    horizontal: 1,
                                  ),
                                  decoration: const BoxDecoration(
                                    color: DdubiColors.orange,
                                    shape: BoxShape.circle,
                                  ),
                                ),
                              ),
                            ),
                    ),
                  ],
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
  });
  final String title;
  final Widget child;
  final int? index;
  final ValueChanged<int>? onNav;
  final List<Widget>? actions;
  final Widget? fab;
  @override
  Widget build(BuildContext c) => Scaffold(
    appBar: AppBar(
      title: Text(title, style: Theme.of(c).textTheme.titleMedium),
      centerTitle: false,
      actions: actions,
    ),
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 1100),
          child: SizedBox(width: double.infinity, child: child),
        ),
      ),
    ),
    floatingActionButton: fab,
    bottomNavigationBar: index == null
        ? null
        : DdubiBottomNav(index: index!, onTap: onNav!),
  );
}
