import 'dart:async';

import 'package:flutter/widgets.dart';

import '../core/diagnostics/app_log.dart';
import '../services/app_api.dart';
import '../services/device_token_service.dart';

/// 안 읽은 알림 개수를 한 곳에서 들고 있다가 종 아이콘에 보여준다.
///
/// 지금까지 종은 그냥 그림이었다. 서버는 개수를 내려주고 앱도 받아서 담아 뒀는데,
/// 화면에 쓰는 곳이 없었다(2026-09-21 확인).
///
/// <h3>언제 갱신하나</h3>
///
/// - 앱을 켤 때 한 번
/// - **앱이 떠 있는 동안 푸시가 오면 즉시** — 이게 실시간으로 보이는 부분이다
/// - 앱으로 돌아올 때(백그라운드에서 받은 알림을 반영)
/// - 알림을 읽거나 지웠을 때는 화면이 [set] 으로 직접 고친다
///
/// 개수를 앱이 혼자 세지 않고 늘 서버 값을 받아 온다. 여러 기기에서 읽으면 앱이
/// 센 숫자와 실제가 어긋나는데, 그 어긋남은 사용자가 알아차리기 어렵다.
class UnreadNotifications extends ChangeNotifier with WidgetsBindingObserver {
  UnreadNotifications(this._api, {DeviceTokenService? pushes}) {
    WidgetsBinding.instance.addObserver(this);
    if (pushes != null) {
      _pushSubscription = pushes.onForegroundMessage.listen((_) => refresh());
    }
    unawaited(refresh());
  }

  final NotificationApi _api;
  StreamSubscription<void>? _pushSubscription;
  bool _loading = false;

  int _count = 0;

  int get count => _count;

  /// 알림함에서 읽음 처리를 하면 서버가 새 개수를 돌려준다. 그 값을 그대로 반영한다.
  void set(int value) {
    final next = value < 0 ? 0 : value;
    if (next == _count) return;
    _count = next;
    notifyListeners();
  }

  /// 서버에서 지금 개수를 다시 받아온다. 실패하면 이전 값을 유지한다 —
  /// 통신이 한 번 끊겼다고 뱃지를 0으로 만들면 안 읽은 알림을 숨기는 셈이 된다.
  Future<void> refresh() async {
    if (_loading) return;
    _loading = true;
    try {
      final inbox = await _api.getInbox();
      set(inbox.unreadCount);
    } on Object catch (error) {
      AppLog.debug('안 읽은 알림 개수를 갱신하지 못했습니다: $error');
    } finally {
      _loading = false;
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 앱이 꺼져 있는 동안 온 알림은 위 스트림으로 오지 않는다. 돌아올 때 맞춘다.
    if (state == AppLifecycleState.resumed) unawaited(refresh());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_pushSubscription?.cancel());
    super.dispose();
  }
}
