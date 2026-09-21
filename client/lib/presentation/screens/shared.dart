import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:firebase_messaging/firebase_messaging.dart';

import '../../domain/models.dart';
import '../../domain/schedule_evaluation.dart';

Future<Object?> openPage(BuildContext c, Widget page) =>
    Navigator.of(c).push(MaterialPageRoute(builder: (_) => page));

const screenPadding = EdgeInsets.fromLTRB(16, 8, 16, 20);

const weekdayLabels = ['월', '화', '수', '목', '금', '토', '일'];

RouteOption? primaryRoute(List<RouteOption> routes) {
  if (routes.isEmpty) return null;
  return routes.firstWhere(
    (route) => route.kind == RouteKind.recommended,
    orElse: () => routes.first,
  );
}

RouteOption? routeFromEvaluation(
  ScheduleEvaluationV2 evaluation,
  Schedule schedule,
) {
  final departure = evaluation.proposedDepartAt;
  final arrival = evaluation.estimatedArrivalAt;
  if (evaluation.state != EvaluationState.ready ||
      departure == null ||
      arrival == null) {
    return null;
  }
  String clock(DateTime value) {
    final local = value.toLocal();
    return '${local.hour.toString().padLeft(2, '0')}:'
        '${local.minute.toString().padLeft(2, '0')}';
  }

  return RouteOption(
    kind: evaluation.quality.preview || !evaluation.quality.productionEligible
        ? RouteKind.candidate
        : RouteKind.recommended,
    label: evaluation.hasHistoricalReference
        ? '과거 평균 기반 참고 추정'
        : evaluation.quality.preview
        ? '최신 평가 · 검증용'
        : '최신 평가',
    transport: '${schedule.departurePlace} → ${schedule.place}',
    departure: clock(departure),
    arrival: clock(arrival),
    margin: evaluation.canAcceptAt(DateTime.now()) ? '서버 평가 완료' : '확인 필요',
    detail: evaluation.durationSeconds == null
        ? '서버 평가 결과'
        : '약 ${(evaluation.durationSeconds! / 60).ceil()}분',
    fare: '요금 정보 없음',
  );
}

class ScheduleDetailArguments {
  const ScheduleDetailArguments({
    required this.schedule,
    this.routes = const [],
  });

  final Schedule schedule;
  final List<RouteOption> routes;
}

class RouteSearchHistory {
  static final List<RouteSearchCondition> items = [];

  static void add(RouteSearchCondition condition) {
    items.removeWhere(
      (item) =>
          item.origin.name == condition.origin.name &&
          item.destination.name == condition.destination.name,
    );
    items.insert(0, condition);
    if (items.length > 5) items.removeRange(5, items.length);
  }
}

class ProfileImageCache {
  static int? characterIndex = 0;
  static Uint8List? bytes;
}

class AppPermissionController {
  static const _channel = MethodChannel('com.ssafy.ddubi/app_permissions');

  static Future<bool> hasLocationPermission() async {
    if (kIsWeb) return false;
    return await _channel.invokeMethod<bool>('hasLocationPermission') ?? false;
  }

  static Future<bool> requestLocationPermission() async {
    if (kIsWeb) return false;
    return await _channel.invokeMethod<bool>('requestLocationPermission') ??
        false;
  }

  static Future<bool> hasNotificationPermission() async {
    final settings = await FirebaseMessaging.instance.getNotificationSettings();
    return settings.authorizationStatus == AuthorizationStatus.authorized ||
        settings.authorizationStatus == AuthorizationStatus.provisional;
  }

  static Future<bool> requestNotificationPermission() async {
    final settings = await FirebaseMessaging.instance.requestPermission();
    return settings.authorizationStatus == AuthorizationStatus.authorized ||
        settings.authorizationStatus == AuthorizationStatus.provisional;
  }

  static Future<void> openSettings() async {
    if (!kIsWeb) await _channel.invokeMethod<void>('openAppSettings');
  }
}

String formatFullDate(DateTime date) =>
    '${date.year}.${date.month.toString().padLeft(2, '0')}.${date.day.toString().padLeft(2, '0')} (${weekdayLabels[date.weekday - 1]})';

String arrivalResultText(String? result) => switch (result) {
  'ON_TIME' => '정시',
  'TIGHT' => '빠듯',
  'LATE_RISK' => '지연',
  _ => '판정 없음',
};

String routeModeLabel(String mode) => switch (mode) {
  'WALK' => '도보',
  'BUS' => '버스',
  'SUBWAY' => '지하철',
  'TAXI' => '택시',
  _ => mode,
};

/// 일정 상세 안에서 "결론 한 줄"과 아래 이동 예측 카드가 같은 결과를 쓰게 묶어준다.
///
/// 결론을 따로 조회하면 카드와 다른 값이 보일 수 있다. 조회는 카드 한 곳에서만 하고
/// 그 결과를 이 통로로 위쪽에 전달한다.
class EvaluationHeadlineScope extends StatefulWidget {
  const EvaluationHeadlineScope({super.key, required this.builder});

  final Widget Function(ValueNotifier<ScheduleEvaluationV2?>) builder;

  @override
  State<EvaluationHeadlineScope> createState() =>
      _EvaluationHeadlineScopeState();
}

class _EvaluationHeadlineScopeState extends State<EvaluationHeadlineScope> {
  final ValueNotifier<ScheduleEvaluationV2?> _headline = ValueNotifier(null);

  @override
  void dispose() {
    _headline.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.builder(_headline);
}
