import 'package:ddubi/core/config/app_environment.dart';
import 'package:ddubi/core/constants/api_constants.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';

/// 개발/운영 판정은 한 곳에서 하고, 운영이면 개발용 기능이 전부 꺼진다.
void main() {
  test('운영(prod)이면 개발자 옵션·가짜 인증·Mock 데이터가 모두 꺼진다', () {
    final prod = AppEnvironment.resolve(flavor: 'prod');
    expect(prod.isProduction, isTrue);
    expect(prod.allowDeveloperTools, isFalse);
    expect(prod.allowFakeAuth, isFalse);
    expect(prod.allowMockData, isFalse);
  });

  test('개발(dev)이면 개발용 기능을 쓸 수 있다', () {
    final dev = AppEnvironment.resolve(flavor: 'dev');
    expect(dev.isProduction, isFalse);
    expect(dev.allowDeveloperTools, isTrue);
    expect(dev.allowFakeAuth, isTrue);
    expect(dev.allowMockData, isTrue);
  });

  test('모르는 값은 운영으로 보지 않는다 — 명시적으로 prod 라고 해야 운영이다', () {
    // 오타나 빈 값으로 운영 기능이 조용히 켜지는 것보다, 개발로 보고 개발자 눈에 띄는 쪽이 안전하다.
    expect(AppEnvironment.resolve(flavor: '').isProduction, isFalse);
    expect(AppEnvironment.resolve(flavor: 'production').isProduction, isFalse);
  });

  test('시험(디버그) 실행에서는 개발 환경이고 개발 API 주소를 쓴다', () {
    expect(kReleaseMode, isFalse);
    expect(AppEnvironment.isDevelopment, isTrue);
    expect(ApiConstants.baseUrl, AppEnvironment.developmentApiBaseUrl);
    expect(AppEnvironment.productionApiBaseUrl, startsWith('https://'));
  });
}
