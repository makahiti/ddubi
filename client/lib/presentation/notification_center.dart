import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../core/constants/layout_constants.dart';
import '../core/theme.dart';
import '../domain/models.dart';
import '../services/app_api.dart';
import 'widgets.dart';

enum _NotificationFilter { all, departure, delay, schedule }

enum _NotificationKind { departure, delay, schedule, info }

class NotificationsScreen extends StatefulWidget {
  const NotificationsScreen({
    super.key,
    required this.api,
    this.go,
    this.onInboxChanged,
  });

  final NotificationApi api;
  final ValueChanged<int>? go;

  /// 읽음·삭제로 안 읽은 개수가 달라졌을 때 알린다. 종 아이콘 숫자를 바로 맞추기 위해서다.
  final VoidCallback? onInboxChanged;

  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

class _NotificationsScreenState extends State<NotificationsScreen> {
  late Future<NotificationInbox> _inbox;
  _NotificationFilter _filter = _NotificationFilter.all;
  bool _selectionMode = false;
  bool _deleting = false;
  final Set<String> _selectedIds = {};

  @override
  void initState() {
    super.initState();
    _inbox = widget.api.getInbox();
  }

  void _reload() {
    setState(() => _inbox = widget.api.getInbox());
    widget.onInboxChanged?.call();
  }

  void _toggleSelectionMode() {
    setState(() {
      _selectionMode = !_selectionMode;
      _selectedIds.clear();
    });
  }

  /// 고른 알림을 지운다. 되돌릴 수 없으므로 먼저 묻는다.
  ///
  /// 서버는 발송 이력을 남기고 알림함에서만 감춘다. 그래서 지운 뒤에도 같은 상황의 알림이
  /// 다시 오지는 않는다.
  Future<void> _deleteSelected() async {
    final ids = _selectedIds.toList(growable: false);
    if (ids.isEmpty || _deleting) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('알림 ${ids.length}개를 지울까요?'),
        content: const Text('지운 알림은 알림함에서 사라져요. 되돌릴 수 없어요.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('삭제'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() => _deleting = true);
    try {
      await widget.api.deleteNotifications(ids);
      if (!mounted) return;
      setState(() {
        _selectedIds.clear();
        _selectionMode = false;
      });
      _reload();
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('알림 ${ids.length}개를 지웠어요.')));
    } on Object {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('알림을 지우지 못했어요. 잠시 뒤 다시 시도해 주세요.')),
        );
      }
    } finally {
      if (mounted) setState(() => _deleting = false);
    }
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<NotificationInbox>(
    future: _inbox,
    builder: (context, snapshot) {
      final hasNotifications = snapshot.data?.items.isNotEmpty ?? false;
      final selecting = hasNotifications && _selectionMode;

      final Widget content;
      if (snapshot.hasError) {
        content = const DataLoadError(message: '알림을 불러오지 못했어요.');
      } else if (!snapshot.hasData) {
        content = const Center(child: CircularProgressIndicator());
      } else {
        content = LayoutBuilder(
          builder: (context, constraints) {
            final isWide =
                constraints.maxWidth >= LayoutConstants.wideScreenBreakpoint;
            return isWide
                ? _webLayout(snapshot.data!)
                : _mobileLayout(snapshot.data!);
          },
        );
      }

      return AppPage(
        title: '알림함',
        mobileTitleStyle: Theme.of(context).textTheme.headlineMedium,
        webTitle: '알림 대시보드',
        webSubtitle: '출발과 일정 관련 알림을 한눈에 확인해요.',
        index: widget.go == null ? null : 3,
        onNav: widget.go,
        actions: [
          _HeaderAction(
            key: const Key('notificationSelectionButton'),
            icon: selecting ? Icons.close_rounded : Icons.done_rounded,
            label: selecting ? '완료' : '선택',
            active: selecting,
            onTap: hasNotifications ? _toggleSelectionMode : null,
          ),
          _HeaderAction(
            key: const Key('notificationDeleteButton'),
            icon: Icons.delete_outline_rounded,
            label: '휴지통',
            onTap: selecting && _selectedIds.isNotEmpty && !_deleting
                ? _deleteSelected
                : null,
          ),
          const SizedBox(width: 6),
        ],
        child: content,
      );
    },
  );

  Widget _mobileLayout(NotificationInbox data) => _NotificationList(
    key: const Key('mobileNotificationsLayout'),
    data: data,
    filter: _filter,
    selectionMode: _selectionMode,
    selectedIds: _selectedIds,
    onFilterChanged: (filter) => setState(() => _filter = filter),
    onSelectionChanged: _toggleItem,
    onToggleAll: _toggleAll,
    onDelete: _selectedIds.isEmpty || _deleting ? null : _deleteSelected,
    onMarkAllRead: _markAllRead,
    onOpen: _openNotification,
    onOpenSettings: _openNotificationSettings,
  );

  Widget _webLayout(NotificationInbox data) => ListView(
    key: const Key('webNotificationsLayout'),
    padding: const EdgeInsets.fromLTRB(24, 18, 24, 28),
    children: [
      Row(
        key: const Key('webNotificationMetrics'),
        children: [
          Expanded(
            child: DashboardMetricCard(
              label: '전체 알림',
              value: '${data.items.length}건',
              caption: '최근 수신 기준',
              icon: Icons.notifications_active_outlined,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: DashboardMetricCard(
              label: '안 읽은 알림',
              value: '${data.unreadCount}건',
              caption: '확인이 필요해요',
              icon: Icons.mark_email_unread_outlined,
              tone: DdubiColors.blue,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: DashboardMetricCard(
              label: '읽은 알림',
              value: '${data.items.where((item) => !item.isUnread).length}건',
              caption: '현재 페이지 기준',
              icon: Icons.task_alt_rounded,
              tone: DdubiColors.green,
            ),
          ),
        ],
      ),
      const SizedBox(height: 18),
      _NotificationList(
        data: data,
        filter: _filter,
        selectionMode: _selectionMode,
        selectedIds: _selectedIds,
        onFilterChanged: (filter) => setState(() => _filter = filter),
        onSelectionChanged: _toggleItem,
        onToggleAll: _toggleAll,
        onDelete: _selectedIds.isEmpty || _deleting ? null : _deleteSelected,
        onMarkAllRead: _markAllRead,
        onOpen: _openNotification,
        onOpenSettings: _openNotificationSettings,
        embedded: true,
      ),
    ],
  );

  void _toggleItem(String id) {
    setState(() {
      if (!_selectedIds.add(id)) _selectedIds.remove(id);
    });
  }

  void _toggleAll(List<AppNotification> visibleItems) {
    setState(() {
      final visibleIds = visibleItems.map((item) => item.id).toSet();
      if (visibleIds.isNotEmpty && _selectedIds.containsAll(visibleIds)) {
        _selectedIds.removeAll(visibleIds);
      } else {
        _selectedIds.addAll(visibleIds);
      }
    });
  }

  Future<void> _markAllRead() async {
    await widget.api.markAllRead();
    if (mounted) _reload();
  }

  void _openNotificationSettings() => context.go('/settings');

  Future<void> _openNotification(AppNotification item) async {
    if (item.isUnread) await widget.api.markRead(item.id);
    if (!mounted) return;
    await context.push('/notifications/${item.id}');
    if (mounted) _reload();
  }
}

class _NotificationList extends StatelessWidget {
  const _NotificationList({
    super.key,
    required this.data,
    required this.filter,
    required this.selectionMode,
    required this.selectedIds,
    required this.onFilterChanged,
    required this.onSelectionChanged,
    required this.onToggleAll,
    required this.onDelete,
    required this.onMarkAllRead,
    required this.onOpen,
    required this.onOpenSettings,
    this.embedded = false,
  });

  final NotificationInbox data;
  final _NotificationFilter filter;
  final bool selectionMode;
  final Set<String> selectedIds;
  final ValueChanged<_NotificationFilter> onFilterChanged;
  final ValueChanged<String> onSelectionChanged;
  final ValueChanged<List<AppNotification>> onToggleAll;
  final VoidCallback? onDelete;
  final VoidCallback onMarkAllRead;
  final ValueChanged<AppNotification> onOpen;
  final VoidCallback onOpenSettings;
  final bool embedded;

  @override
  Widget build(BuildContext context) {
    final visibleItems = data.items
        .where((item) => _matchesFilter(item, filter))
        .toList(growable: false);
    final allSelected =
        visibleItems.isNotEmpty &&
        selectedIds.containsAll(visibleItems.map((item) => item.id));

    final content = Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          '출발과 일정 관련 알림을 한눈에 확인해요.',
          style: Theme.of(context).textTheme.bodyMedium,
        ),
        const SizedBox(height: 20),
        Row(
          children: [
            Expanded(
              child: Text(
                '최근 알림',
                style: Theme.of(context).textTheme.titleLarge?.copyWith(
                  color: DdubiColors.navy,
                  fontWeight: FontWeight.w900,
                ),
              ),
            ),
            if (data.items.isNotEmpty) ...[
              Text(
                '안 읽음 ',
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: const Color(0xFF6F7D96),
                  fontWeight: FontWeight.w600,
                ),
              ),
              Text(
                '${data.unreadCount}건',
                key: const Key('notificationUnreadCount'),
                style: const TextStyle(
                  color: Color(0xFF00A99D),
                  fontSize: 17,
                  fontWeight: FontWeight.w900,
                ),
              ),
              if (data.unreadCount > 0) ...[
                const SizedBox(width: 8),
                TextButton.icon(
                  onPressed: onMarkAllRead,
                  icon: const Icon(Icons.done_all_rounded, size: 18),
                  label: const Text('모두 읽음'),
                ),
              ],
            ],
          ],
        ),
        if (data.items.isNotEmpty) ...[
          const SizedBox(height: 10),
          _FilterBar(selected: filter, onChanged: onFilterChanged),
          if (selectionMode) ...[
            const SizedBox(height: 14),
            _SelectionBar(
              allSelected: allSelected,
              selectedCount: selectedIds.length,
              onToggleAll: () => onToggleAll(visibleItems),
              onDelete: onDelete,
            ),
          ],
        ],
        const SizedBox(height: 16),
        if (visibleItems.isEmpty)
          _EmptyNotifications(
            hasOtherNotifications: data.items.isNotEmpty,
            onOpenSettings: onOpenSettings,
          )
        else
          ...visibleItems.map(
            (item) => Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: _NotificationCard(
                item: item,
                selectionMode: selectionMode,
                selected: selectedIds.contains(item.id),
                onTap: selectionMode
                    ? () => onSelectionChanged(item.id)
                    : () => onOpen(item),
                onSelected: () => onSelectionChanged(item.id),
              ),
            ),
          ),
      ],
    );

    if (embedded) {
      return Container(
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(22),
          border: Border.all(color: DdubiColors.border),
        ),
        child: content,
      );
    }

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 6, 16, 24),
      children: [content],
    );
  }
}

class _HeaderAction extends StatelessWidget {
  const _HeaderAction({
    super.key,
    required this.icon,
    required this.label,
    required this.onTap,
    this.active = false,
  });

  final IconData icon;
  final String label;
  final VoidCallback? onTap;
  final bool active;

  @override
  Widget build(BuildContext context) => SizedBox(
    width: 54,
    child: InkWell(
      borderRadius: BorderRadius.circular(18),
      onTap: onTap,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            width: 34,
            height: 30,
            decoration: BoxDecoration(
              color: active ? const Color(0xFFDDF8F4) : const Color(0xFFF3F5FA),
              borderRadius: BorderRadius.circular(14),
            ),
            child: Icon(
              icon,
              size: 20,
              color: onTap == null ? const Color(0xFFAAB4C5) : DdubiColors.navy,
            ),
          ),
          Text(
            label,
            style: TextStyle(
              color: onTap == null ? const Color(0xFFAAB4C5) : DdubiColors.navy,
              fontSize: 10,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    ),
  );
}

class _FilterBar extends StatelessWidget {
  const _FilterBar({required this.selected, required this.onChanged});

  final _NotificationFilter selected;
  final ValueChanged<_NotificationFilter> onChanged;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 42,
    child: ListView.separated(
      scrollDirection: Axis.horizontal,
      itemCount: _NotificationFilter.values.length,
      separatorBuilder: (_, _) => const SizedBox(width: 8),
      itemBuilder: (context, index) {
        final filter = _NotificationFilter.values[index];
        final isSelected = selected == filter;
        return ChoiceChip(
          key: Key('notificationFilter-${filter.name}'),
          label: Text(_filterLabel(filter)),
          selected: isSelected,
          onSelected: (_) => onChanged(filter),
          showCheckmark: false,
          side: BorderSide.none,
          backgroundColor: const Color(0xFFF2F5FA),
          selectedColor: const Color(0xFFCFF5EE),
          labelStyle: TextStyle(
            color: DdubiColors.navy,
            fontWeight: isSelected ? FontWeight.w800 : FontWeight.w600,
          ),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          shape: const StadiumBorder(),
        );
      },
    ),
  );
}

class _SelectionBar extends StatelessWidget {
  const _SelectionBar({
    required this.allSelected,
    required this.selectedCount,
    required this.onToggleAll,
    required this.onDelete,
  });

  final bool allSelected;
  final int selectedCount;
  final VoidCallback onToggleAll;
  final VoidCallback? onDelete;

  @override
  Widget build(BuildContext context) => Container(
    key: const Key('notificationSelectionControls'),
    padding: const EdgeInsets.fromLTRB(8, 8, 10, 8),
    decoration: BoxDecoration(
      color: const Color(0xFFF1FCFA),
      borderRadius: BorderRadius.circular(18),
      border: Border.all(color: const Color(0xFFC6EEE8)),
    ),
    child: Row(
      children: [
        Checkbox(
          value: allSelected,
          onChanged: (_) => onToggleAll(),
          activeColor: DdubiColors.blue,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(5)),
        ),
        const Text(
          '전체 선택',
          style: TextStyle(
            color: DdubiColors.navy,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(width: 12),
        Container(width: 1, height: 24, color: const Color(0xFFD1DFE6)),
        const SizedBox(width: 12),
        Expanded(
          child: Text.rich(
            TextSpan(
              text: '선택 ',
              children: [
                TextSpan(
                  text: '$selectedCount개',
                  style: const TextStyle(color: DdubiColors.blue),
                ),
              ],
            ),
            key: const Key('notificationSelectedCount'),
            style: const TextStyle(
              color: DdubiColors.navy,
              fontWeight: FontWeight.w800,
            ),
          ),
        ),
        TextButton.icon(
          onPressed: onDelete,
          style: TextButton.styleFrom(
            foregroundColor: DdubiColors.red,
            backgroundColor: const Color(0xFFFFE8EA),
            disabledForegroundColor: const Color(0xFFCEA5A9),
            disabledBackgroundColor: const Color(0xFFFFF2F3),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
          ),
          icon: const Icon(Icons.delete_outline_rounded, size: 19),
          label: const Text('삭제'),
        ),
      ],
    ),
  );
}

class _NotificationCard extends StatelessWidget {
  const _NotificationCard({
    required this.item,
    required this.selectionMode,
    required this.selected,
    required this.onTap,
    required this.onSelected,
  });

  final AppNotification item;
  final bool selectionMode;
  final bool selected;
  final VoidCallback onTap;
  final VoidCallback onSelected;

  @override
  Widget build(BuildContext context) {
    final visual = _visualFor(item);
    return Material(
      key: Key('notificationCard-${item.id}'),
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(20),
        onTap: onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
          decoration: BoxDecoration(
            color: item.isUnread ? visual.tint : Colors.white,
            borderRadius: BorderRadius.circular(20),
            border: Border.all(
              color: selected
                  ? DdubiColors.blue
                  : item.isUnread
                  ? visual.border
                  : DdubiColors.border,
              width: selected ? 1.6 : 1,
            ),
            boxShadow: const [
              BoxShadow(
                color: Color(0x0D10244B),
                blurRadius: 12,
                offset: Offset(0, 4),
              ),
            ],
          ),
          child: Row(
            children: [
              if (selectionMode) ...[
                Checkbox(
                  value: selected,
                  onChanged: (_) => onSelected(),
                  activeColor: DdubiColors.blue,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(5),
                  ),
                ),
                const SizedBox(width: 2),
              ],
              _NotificationIcon(visual: visual),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        _CategoryBadge(visual: visual),
                        const Spacer(),
                        if (item.isUnread)
                          Container(
                            width: 7,
                            height: 7,
                            margin: const EdgeInsets.only(right: 6),
                            decoration: const BoxDecoration(
                              color: DdubiColors.blue,
                              shape: BoxShape.circle,
                            ),
                          ),
                        Text(
                          _relativeTime(item.createdAt),
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                    const SizedBox(height: 7),
                    Text(
                      item.title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: DdubiColors.navy,
                        fontSize: 16,
                        height: 1.25,
                        fontWeight: item.isUnread
                            ? FontWeight.w900
                            : FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 5),
                    Text(
                      item.body,
                      maxLines: 3,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodyMedium
                          ?.copyWith(color: const Color(0xFF465776)),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 6),
              const Icon(
                Icons.chevron_right_rounded,
                color: Color(0xFF536B9C),
                size: 26,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _NotificationIcon extends StatelessWidget {
  const _NotificationIcon({required this.visual});

  final _NotificationVisual visual;

  @override
  Widget build(BuildContext context) => Container(
    width: 56,
    height: 56,
    decoration: BoxDecoration(
      color: visual.iconBackground,
      shape: BoxShape.circle,
    ),
    clipBehavior: Clip.antiAlias,
    child: visual.kind == _NotificationKind.departure
        ? Padding(
            padding: const EdgeInsets.all(3),
            child: Image.asset(
              'assets/images/ttubee/home-megaphone.png',
              fit: BoxFit.contain,
              semanticLabel: '출발 알림 뚜비',
            ),
          )
        : Icon(visual.icon, color: visual.tone, size: 30),
  );
}

class _CategoryBadge extends StatelessWidget {
  const _CategoryBadge({required this.visual});

  final _NotificationVisual visual;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
    decoration: BoxDecoration(
      color: visual.badgeBackground,
      borderRadius: BorderRadius.circular(20),
    ),
    child: Text(
      visual.label,
      style: TextStyle(
        color: visual.tone,
        fontSize: 12,
        fontWeight: FontWeight.w800,
      ),
    ),
  );
}

class _EmptyNotifications extends StatelessWidget {
  const _EmptyNotifications({
    required this.hasOtherNotifications,
    required this.onOpenSettings,
  });

  final bool hasOtherNotifications;
  final VoidCallback onOpenSettings;

  Widget _filteredEmpty(BuildContext context) => Container(
    key: const Key('notificationEmptyState'),
    padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 26),
    decoration: BoxDecoration(
      color: const Color(0xFFF4FCFB),
      borderRadius: BorderRadius.circular(22),
      border: Border.all(color: const Color(0xFFD4EEE9)),
    ),
    child: Column(
      children: [
        const Icon(
          Icons.notifications_none_rounded,
          color: Color(0xFF00A99D),
          size: 48,
        ),
        const SizedBox(height: 10),
        Text(
          '해당 종류의 알림이 없어요.',
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 5),
        Text(
          '다른 카테고리의 알림을 확인해보세요.',
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.bodyMedium,
        ),
      ],
    ),
  );

  @override
  Widget build(BuildContext context) {
    if (hasOtherNotifications) return _filteredEmpty(context);

    final compact = MediaQuery.sizeOf(context).height < 720;
    return Container(
      key: const Key('notificationEmptyState'),
      padding: EdgeInsets.fromLTRB(
        compact ? 18 : 24,
        compact ? 18 : 26,
        compact ? 18 : 24,
        compact ? 22 : 30,
      ),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFFF5FDFC), Color(0xFFEAF8F6)],
        ),
        borderRadius: BorderRadius.circular(28),
        border: Border.all(color: const Color(0xFFCDEBE6)),
        boxShadow: const [
          BoxShadow(
            color: Color(0x1000645D),
            blurRadius: 18,
            offset: Offset(0, 8),
          ),
        ],
      ),
      child: Column(
        children: [
          Image.asset(
            'assets/images/ttubee/notification-empty.png',
            key: const Key('notificationEmptyCharacter'),
            width: compact ? 190 : 240,
            height: compact ? 190 : 240,
            fit: BoxFit.contain,
            semanticLabel: '민트색 종을 안고 알림을 기다리는 뚜비',
          ),
          SizedBox(height: compact ? 12 : 18),
          Text(
            '아직 도착한 알림이 없어요.',
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.titleLarge?.copyWith(
              color: DdubiColors.navy,
              fontSize: compact ? 20 : 22,
              fontWeight: FontWeight.w900,
            ),
          ),
          const SizedBox(height: 10),
          Text(
            '중요한 일정과 교통 정보를\n가장 먼저 알려드릴게요!',
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodyMedium
                ?.copyWith(color: const Color(0xFF71809A), height: 1.5),
          ),
          SizedBox(height: compact ? 18 : 24),
          SizedBox(
            width: compact ? 210 : 240,
            height: 50,
            child: OutlinedButton(
              key: const Key('notificationSettingsButton'),
              onPressed: onOpenSettings,
              style: OutlinedButton.styleFrom(
                foregroundColor: const Color(0xFF008F84),
                backgroundColor: Colors.white.withValues(alpha: .88),
                side: const BorderSide(color: Color(0xFF00A99D), width: 1.5),
                shape: const StadiumBorder(),
                textStyle: const TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w800,
                ),
              ),
              child: const Text('알림 설정 보기'),
            ),
          ),
        ],
      ),
    );
  }
}

class _NotificationVisual {
  const _NotificationVisual({
    required this.kind,
    required this.label,
    required this.icon,
    required this.tone,
    required this.tint,
    required this.border,
    required this.iconBackground,
    required this.badgeBackground,
  });

  final _NotificationKind kind;
  final String label;
  final IconData icon;
  final Color tone;
  final Color tint;
  final Color border;
  final Color iconBackground;
  final Color badgeBackground;
}

_NotificationKind _kindFor(AppNotification item) {
  final type = item.type.toUpperCase();
  if (type.contains('DELAY') ||
      type.contains('LATE') ||
      type.contains('DISRUPTION')) {
    return _NotificationKind.delay;
  }
  if (type.contains('SCHEDULE') || type.contains('REMIND')) {
    return _NotificationKind.schedule;
  }
  if (type.contains('DEPART') ||
      type.contains('RECOMMEND') ||
      type.contains('LEAVE')) {
    return _NotificationKind.departure;
  }
  return _NotificationKind.info;
}

_NotificationVisual _visualFor(AppNotification item) =>
    switch (_kindFor(item)) {
      _NotificationKind.departure => const _NotificationVisual(
        kind: _NotificationKind.departure,
        label: '출발 추천',
        icon: Icons.campaign_rounded,
        tone: Color(0xFF009F93),
        tint: Color(0xFFF3FCFA),
        border: Color(0xFFBEECE6),
        iconBackground: Color(0xFFD8F7F2),
        badgeBackground: Color(0xFFDDF8F4),
      ),
      _NotificationKind.delay => const _NotificationVisual(
        kind: _NotificationKind.delay,
        label: '지연',
        icon: Icons.directions_subway_filled_rounded,
        tone: Color(0xFFE23D50),
        tint: Color(0xFFFFF7F7),
        border: Color(0xFFF7D3D7),
        iconBackground: Color(0xFFFFE3E6),
        badgeBackground: Color(0xFFFFE8EA),
      ),
      _NotificationKind.schedule => const _NotificationVisual(
        kind: _NotificationKind.schedule,
        label: '일정',
        icon: Icons.calendar_month_rounded,
        tone: Color(0xFF0B9F91),
        tint: Color(0xFFF8FCFF),
        border: Color(0xFFD4E6F8),
        iconBackground: Color(0xFFDDF7F3),
        badgeBackground: Color(0xFFE1F1FF),
      ),
      _NotificationKind.info => const _NotificationVisual(
        kind: _NotificationKind.info,
        label: '안내',
        icon: Icons.directions_bus_filled_rounded,
        tone: Color(0xFF49699F),
        tint: Color(0xFFFAFBFD),
        border: Color(0xFFDCE4EF),
        iconBackground: Color(0xFFE7EFFA),
        badgeBackground: Color(0xFFEDF2F8),
      ),
    };

bool _matchesFilter(AppNotification item, _NotificationFilter filter) =>
    switch (filter) {
      _NotificationFilter.all => true,
      _NotificationFilter.departure =>
        _kindFor(item) == _NotificationKind.departure,
      _NotificationFilter.delay => _kindFor(item) == _NotificationKind.delay,
      _NotificationFilter.schedule =>
        _kindFor(item) == _NotificationKind.schedule,
    };

String _filterLabel(_NotificationFilter filter) => switch (filter) {
  _NotificationFilter.all => '전체',
  _NotificationFilter.departure => '출발 추천',
  _NotificationFilter.delay => '지연 안내',
  _NotificationFilter.schedule => '일정 리마인드',
};

String _relativeTime(DateTime createdAt) {
  final difference = DateTime.now().difference(createdAt.toLocal());
  if (difference.isNegative || difference.inMinutes < 1) return '방금 전';
  if (difference.inMinutes < 60) return '${difference.inMinutes}분 전';
  if (difference.inHours < 24) return '${difference.inHours}시간 전';
  if (difference.inDays < 7) return '${difference.inDays}일 전';
  return '${createdAt.month}.${createdAt.day}';
}
