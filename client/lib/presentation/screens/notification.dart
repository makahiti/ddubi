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
            Text(item.title, style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 12),
            Text(item.body),
            if (item.evaluationId == null)
              const Text('알림의 평가 연결 정보를 확인할 수 없습니다. 연결된 일정에서 최신 결과를 확인해 주세요.'),
            if (item.evaluationId != null) ...[
              const SizedBox(height: 12),
              Text(
                expired
                    ? '이 알림의 평가는 만료됐습니다. 연결된 일정에서 최신 결과를 확인해 주세요.'
                    : isCurrent
                    ? '현재 최신 평가와 같은 알림입니다.'
                    : '이 알림 이후 추천이 변경됐을 수 있습니다. 연결된 일정에서 최신 결과를 확인해 주세요.',
                key: const Key('notificationEvaluationFreshness'),
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
