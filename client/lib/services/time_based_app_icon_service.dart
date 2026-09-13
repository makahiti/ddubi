import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

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

class TimeBasedAppIconLifecycle with WidgetsBindingObserver {
  TimeBasedAppIconLifecycle({TimeBasedAppIconService? service})
    : _service = service ?? TimeBasedAppIconService();

  final TimeBasedAppIconService _service;

  void start() {
    WidgetsBinding.instance.addObserver(this);
    unawaited(_updateIcon());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(_updateIcon());
    }
  }

  Future<void> _updateIcon() async {
    try {
      await _service.updateForCurrentTime();
    } on PlatformException catch (error, stackTrace) {
      debugPrint('Failed to update the launcher icon: $error');
      debugPrintStack(stackTrace: stackTrace);
    }
  }
}
