import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../domain/models.dart';
import '../../services/app_api.dart';
import '../widgets.dart';
import '../../domain/schedule_evaluation.dart';
import 'shared.dart';

class NotificationDetailScreen extends StatefulWidget {
  const NotificationDetailScreen({
    super.key,
    required this.api,
    required this.notificationId,
    this.evaluationRepository,
  });

  final NotificationApi api;
  final String notificationId;
  final ScheduleEvaluationRepository? evaluationRepository;

  @override
  State<NotificationDetailScreen> createState() =>
      _NotificationDetailScreenState();
}

class _NotificationDetailScreenState extends State<NotificationDetailScreen> {
  late final Future<_NotificationDetailData> notification = _load();

  Future<_NotificationDetailData> _load() async {
    final item = await widget.api.getNotification(widget.notificationId);
    if (item.isUnread) {
      await widget.api.markRead(item.id);
    }
    ScheduleEvaluationV2? latest;
    if (item.scheduleId != null && widget.evaluationRepository != null) {
      try {
        latest = await widget.evaluationRepository!.latestEvaluation(
          item.scheduleId!,
        );
      } on Object {
        latest = null;
      }
    }
    return _NotificationDetailData(item: item, latest: latest);
  }

  @override
  Widget build(BuildContext context) => AppPage(
    title: '알림 상세',
    child: FutureBuilder<_NotificationDetailData>(
      future: notification,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return const DataLoadError(message: '알림을 불러오지 못했어요.');
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }

        final data = snapshot.data!;
        final item = data.item;
        final latest = data.latest;
        final latestExpired =
            latest != null &&
            (latest.state == EvaluationState.stale ||
                latest.isExpiredAt(DateTime.now()));
        final expired = item.validUntil != null
            ? !item.validUntil!.isAfter(DateTime.now())
            : item.evaluationId != null &&
                  item.evaluationId == latest?.evaluationId &&
                  latestExpired;
        final isCurrent =
            item.evaluationId != null &&
            latest?.evaluationId == item.evaluationId &&
            latest?.state == EvaluationState.ready &&
            latest?.validUntil != null &&
            !latestExpired &&
            !expired;
        return ListView(
          padding: screenPadding,
          children: [
            InfoCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(
                        notificationKindIcon(item.type),
                        size: 18,
                        color: const Color(0xFF52709B),
                      ),
                      const SizedBox(width: 6),
                      Text(
                        notificationKindLabel(item.type),
                        key: const Key('notificationKindLabel'),
                        style: const TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w800,
                          color: Color(0xFF52709B),
                        ),
                      ),
                      const Spacer(),
                      Text(
                        notificationReceivedLabel(item.createdAt),
                        style: const TextStyle(
                          fontSize: 12,
                          color: Color(0xFF7F91A2),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Text(
                    item.title,
                    style: Theme.of(context).textTheme.titleLarge
                        ?.copyWith(fontWeight: FontWeight.w900),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    item.body,
                    style: const TextStyle(fontSize: 15, height: 1.5),
                  ),
                ],
              ),
            ),
            if (item.expectsEvaluation) ...[
              const SizedBox(height: 12),
              InfoCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const SectionHeader('지금도 유효한가요?'),
                    const SizedBox(height: 6),
                    Text(
                      item.evaluationId == null
                          ? '이 알림은 계산 결과와 연결돼 있지 않아요. 아래에서 최신 결과를 확인해 주세요.'
                          : expired
                          ? '이 알림의 계산은 유효 기간이 지났어요. 아래에서 최신 결과를 확인해 주세요.'
                          : isCurrent
                          ? '지금 최신 계산과 같은 내용이에요.'
                          : '이 알림 뒤로 추천이 바뀌었을 수 있어요. 아래에서 최신 결과를 확인해 주세요.',
                      key: const Key('notificationEvaluationFreshness'),
                      style: const TextStyle(fontSize: 14, height: 1.5),
                    ),
                  ],
                ),
              ),
            ],
            const SizedBox(height: 20),
            if (item.scheduleId != null)
              FilledButton.icon(
                onPressed: () => context.go('/schedules/${item.scheduleId}'),
                icon: const Icon(Icons.calendar_today_outlined),
                label: const Text('연결된 일정 보기'),
              ),
          ],
        );
      },
    ),
  );
}

class _NotificationDetailData {
  const _NotificationDetailData({required this.item, this.latest});

  final AppNotification item;
  final ScheduleEvaluationV2? latest;
}

/// 알림 종류를 사람 말로 옮긴다. 서버 코드(`ROUTE_CHANGE` 등)를 그대로 보여주지 않는다.
@visibleForTesting
String notificationKindLabel(String type) => switch (type) {
  'MORNING_BRIEF' => '아침 브리핑',
  'NIGHT_BRIEF' => '밤 브리핑',
  'EARLY_DEPART' => '출발 안내',
  'DEPART_SOON' => '출발 안내',
  'ROUTE_CHANGE' => '경로 안내',
  'ON_TIME_UNLIKELY' => '지연 안내',
  'LAST_TRIP' => '막차 안내',
  'SCHEDULE_CONFLICT' => '일정 겹침',
  'DATA_STALE' => '자료 안내',
  _ => '알림',
};

@visibleForTesting
IconData notificationKindIcon(String type) => switch (type) {
  'MORNING_BRIEF' => Icons.wb_sunny_outlined,
  'NIGHT_BRIEF' => Icons.nightlight_outlined,
  'EARLY_DEPART' || 'DEPART_SOON' => Icons.directions_walk_outlined,
  'ROUTE_CHANGE' => Icons.alt_route_outlined,
  'ON_TIME_UNLIKELY' => Icons.running_with_errors_outlined,
  'LAST_TRIP' => Icons.bedtime_outlined,
  'SCHEDULE_CONFLICT' => Icons.event_busy_outlined,
  _ => Icons.info_outline,
};

/// 언제 온 알림인지. 오늘 것은 시각만, 그 밖은 날짜까지 적는다.
@visibleForTesting
String notificationReceivedLabel(DateTime createdAt, {DateTime? now}) {
  final at = createdAt.toLocal();
  final today = now?.toLocal() ?? DateTime.now();
  final clock =
      '${at.hour.toString().padLeft(2, '0')}:'
      '${at.minute.toString().padLeft(2, '0')}';
  final sameDay =
      at.year == today.year && at.month == today.month && at.day == today.day;
  return sameDay ? clock : '${at.month}월 ${at.day}일 $clock';
}
