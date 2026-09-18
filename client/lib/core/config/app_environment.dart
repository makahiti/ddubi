import 'package:flutter/foundation.dart';

/// 개발/운영 환경을 **한 곳에서** 판정한다.
///
/// 판정 근거는 빌드 시 넘기는 `--dart-define=DDUBI_ENV=dev|prod` 하나다. 넘기지 않으면
/// 릴리스 빌드는 `prod`, 그 외(디버그·프로필)는 `dev` 로 본다. 즉 **아무것도 지정하지 않은
/// 릴리스 APK 는 항상 운영 환경**이며, 개발자 옵션·가짜 사용자·Mock 데이터에 닿을 수 없다.
///
/// 다른 코드는 `kDebugMode` 를 직접 보지 말고 이 클래스의 값을 쓴다. 판정 규칙이 바뀌어도
/// 이 파일만 고치면 되게 하기 위해서다(2026-09-18 점검 후 도입).
abstract final class AppEnvironment {
  static const String _flavor = String.fromEnvironment(
    'DDUBI_ENV',
    defaultValue: kReleaseMode ? 'prod' : 'dev',
  );

  /// 운영 환경. 릴리스 빌드의 기본값이다.
  static const bool isProduction = _flavor == 'prod';

  /// 개발 환경. 디버그 빌드의 기본값이며, 릴리스에서는 `--dart-define=DDUBI_ENV=dev` 로만 켠다.
  static const bool isDevelopment = !isProduction;

  /// 운영 API 주소. 운영에서는 이 값만 쓴다.
  static const String productionApiBaseUrl =
      'https://j15e202.p.ssafy.io/api/v1';

  /// 개발 API 주소. `API_BASE_URL` 로 바꿀 수 있다(예: 로컬 서버, 에뮬레이터 10.0.2.2).
  static const String developmentApiBaseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://localhost:8080/api/v1',
  );

  /// 실제로 쓰는 API 주소. 운영과 개발의 선택이 여기서 갈린다.
  ///
  /// 운영 빌드에 `API_BASE_URL` 을 넘겨도 `localhost` 같은 개발 주소로 새지 않도록,
  /// 운영에서는 `https://` 로 시작하는 값만 받아들이고 아니면 운영 기본 주소를 쓴다.
  static String get apiBaseUrl {
    if (isDevelopment) return developmentApiBaseUrl;
    const override = String.fromEnvironment('API_BASE_URL');
    return override.startsWith('https://') ? override : productionApiBaseUrl;
  }

  /// 더보기 > 개발자 옵션, 진단 패널 토글.
  static const bool allowDeveloperTools = isDevelopment;

  /// `localadmin/local1234` 로그인, 세션 없을 때의 가짜 사용자, 계정 비밀번호 재확인 우회.
  static const bool allowFakeAuth = isDevelopment;

  /// Mock 저장소·데모 데이터. 운영에서는 서버 응답이 없으면 빈 상태를 보여준다.
  static const bool allowMockData = isDevelopment;

  /// 시험에서 판정 규칙 자체를 검증하기 위한 순수 함수. 앱 코드에서는 상수들을 쓴다.
  @visibleForTesting
  static AppEnvironmentDecision resolve({required String flavor}) {
    final production = flavor == 'prod';
    return AppEnvironmentDecision(
      isProduction: production,
      allowDeveloperTools: !production,
      allowFakeAuth: !production,
      allowMockData: !production,
    );
  }
}

/// [AppEnvironment.resolve] 의 결과. 운영이면 개발용 기능이 전부 꺼진다.
class AppEnvironmentDecision {
  const AppEnvironmentDecision({
    required this.isProduction,
    required this.allowDeveloperTools,
    required this.allowFakeAuth,
    required this.allowMockData,
  });

  final bool isProduction;
  final bool allowDeveloperTools;
  final bool allowFakeAuth;
  final bool allowMockData;
}
