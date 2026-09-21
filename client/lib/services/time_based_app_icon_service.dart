import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import '../core/diagnostics/app_log.dart';

enum AppIconType { day, night }

class TimeBasedAppIconService {
  TimeBasedAppIconService({
    this.channel = const MethodChannel('com.ssafy.ddubi/app_icon'),
  });

  static const int dayStartHour = 6;
  static const int nightStartHour = 18;

  final MethodChannel channel;

  static AppIconType iconFor(DateTime localTime) {
    return localTime.hour >= dayStartHour && localTime.hour < nightStartHour
        ? AppIconType.day
        : AppIconType.night;
  }

  Future<void> updateForCurrentTime({DateTime? now}) async {
    if (!_isSupportedPlatform) return;

    final desiredIcon = iconFor((now ?? DateTime.now()).toLocal()).name;
    final activeIcon = await channel.invokeMethod<String>('getActiveIcon');
    if (activeIcon == desiredIcon) return;

    await channel.invokeMethod<void>('setActiveIcon', {'icon': desiredIcon});
  }

  bool get _isSupportedPlatform =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;
}

/// 시간대에 맞는 런처 아이콘으로 바꾼다.
///
/// <h3>화면이 떠 있는 동안에는 절대 바꾸지 않는다</h3>
/// 아이콘 교체는 지금 쓰지 않는 별칭(activity-alias)을 끄는 일이다. 그런데 사용자가 방금
/// 누른 아이콘이 바로 그 별칭이면, 실행 중인 작업의 입구가 사라져 안드로이드가 그 작업을
/// 끝내 버린다 — 사용자 눈에는 "앱이 켜졌다 바로 꺼지는" 것으로 보인다. `DONT_KILL_APP`
/// 은 프로세스만 살릴 뿐 이 동작을 막지 못한다.
///
/// 그래서 앱이 화면에서 내려간 뒤(paused)에만 바꾼다. 이때 작업이 정리되더라도 사용자는
/// 이미 앱을 떠난 뒤라 꺼지는 장면을 보지 않는다.
class TimeBasedAppIconLifecycle with WidgetsBindingObserver {
  TimeBasedAppIconLifecycle({TimeBasedAppIconService? service})
    : _service = service ?? TimeBasedAppIconService();

  final TimeBasedAppIconService _service;

  void start() {
    WidgetsBinding.instance.addObserver(this);
    // 켜질 때는 바꾸지 않는다. 방금 누른 아이콘을 끄면 그 자리에서 앱이 닫힌다.
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      unawaited(_updateIcon());
    }
  }

  Future<void> _updateIcon() async {
    try {
      await _service.updateForCurrentTime();
    } on PlatformException catch (error, stackTrace) {
      AppLog.debug('Failed to update the launcher icon: $error');
      AppLog.stack(stackTrace);
    }
  }
}
