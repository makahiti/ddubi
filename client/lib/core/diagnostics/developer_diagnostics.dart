import 'package:flutter/foundation.dart';

import '../config/app_environment.dart';

/// 개발자 진단 화면 표시 여부.
///
/// - 개발 환경([AppEnvironment.isDevelopment]): 기본 켜짐.
/// - 운영 환경: 기본 꺼짐이고 더보기의 개발자 옵션 카드 자체가 보이지 않는다.
///   `--dart-define=DDUBI_DEVELOPER_DIAGNOSTICS=true` 로 빌드한 경우에만 켜진 채 시작한다.
/// - 값은 앱 실행 중에만 유지된다(재시작하면 빌드 기본값으로 돌아간다).
///
/// 일반 사용자 화면은 이 값과 무관하게 항상 같은 정보만 보여준다.
class DeveloperDiagnostics {
  DeveloperDiagnostics._();

  static const bool buildDefault = bool.fromEnvironment(
    'DDUBI_DEVELOPER_DIAGNOSTICS',
    defaultValue: AppEnvironment.allowDeveloperTools,
  );

  static final ValueNotifier<bool> enabled = ValueNotifier<bool>(buildDefault);

  @visibleForTesting
  static void reset() => enabled.value = buildDefault;
}
