import 'package:flutter/foundation.dart';

/// 앱 안의 유일한 로그 출구.
///
/// `debugPrint`는 이름과 달리 릴리스 빌드에서도 출력된다(2026-09-18 점검에서 28건 확인).
/// 여기서 빌드 종류를 한 번만 확인해, 릴리스에서는 아무것도 남기지 않는다.
///
/// 규칙: 사용자 식별자·토큰·요청/응답 본문은 어떤 빌드에서도 넘기지 않는다. 이 규칙은
/// 호출하는 쪽이 지키고, `test/logging_convention_test.dart` 가 `debugPrint` 직접 호출을 막는다.
abstract final class AppLog {
  /// 개발용 빌드에서만 한 줄을 남긴다. [error] 가 있으면 뒤에 붙인다.
  static void debug(String message, {Object? error}) {
    if (!kDebugMode) return;
    debugPrint(error == null ? message : '$message: $error');
  }

  /// 개발용 빌드에서만 호출 스택을 남긴다.
  static void stack(StackTrace stackTrace) {
    if (!kDebugMode) return;
    debugPrintStack(stackTrace: stackTrace);
  }
}
