# DDUBI Client

## Kakao Maps

지도 앱 키는 저장소에 커밋하지 않고 `--dart-define`으로 전달합니다. 키가 없거나 초기화에 실패하면 기존 Mock 지도가 표시됩니다.

Android는 Kakao Developers에 패키지명 `com.ssafy.ddubi`를 등록한 뒤 Native App Key로 실행합니다.

```sh
flutter run -d <android-device-id> --dart-define-from-file=.env
```

Web은 Kakao Developers에 실행 도메인을 등록한 뒤 JavaScript Key로 실행합니다. 로컬 개발 포트는 팀에서 동일하게 사용할 수 있도록 `3001`을 권장합니다.

```sh
flutter run -d chrome --web-port=3001 --dart-define-from-file=.env
```

카카오 모바일 로그인도 같은 Native App Key를 사용합니다. `client/.env.example`을
`client/.env`로 복사해 키를 넣고 실행합니다. 실제 `.env`는 Git에서 제외됩니다.
Android에서는 카카오 개발자 콘솔에 패키지명 `com.ssafy.ddubi`와 빌드에 사용한
서명 키 해시를 등록해야 합니다. 앱이 받은 카카오 액세스 토큰은
`POST /api/v1/auth/oauth/token`으로 보내며, 백엔드는 `KAKAO_APP_ID`와 대조합니다.

## Google / Naver 로그인

Google과 Naver의 화면 확인용 Mock 성공 흐름은 사용하지 않습니다. 현재 실제 공급자 로그인은 Android 앱에서 지원하며, Web에서 버튼을 누르면 Android 지원 안내를 표시합니다.

`client/.env`에 다음 값을 설정하고 `--dart-define-from-file=.env`로 실행합니다.

```text
GOOGLE_WEB_CLIENT_ID=
NAVER_CLIENT_ID=
NAVER_REDIRECT_URI=https://j15e202.p.ssafy.io/oauth/naver
```

- Google: Android OAuth 클라이언트에 `com.ssafy.ddubi`와 빌드 SHA-1을 등록합니다. `GOOGLE_WEB_CLIENT_ID`는 백엔드의 `GOOGLE_CLIENT_ID`와 같은 Web OAuth 클라이언트 ID여야 합니다. 앱의 ID 토큰은 `POST /api/v1/auth/oauth/id-token`으로 전달합니다.
- Naver: 앱에는 공개 식별자인 `NAVER_CLIENT_ID`만 주입합니다. 인앱 웹 인증의 Callback URL은 `https://j15e202.p.ssafy.io/oauth/naver`이며, 앱이 받은 인가 코드와 검증한 state를 `POST /api/v1/auth/oauth`로 전달합니다. `NAVER_CLIENT_SECRET`은 백엔드에만 둡니다.

현재 출발·현재·도착 마커는 화면 확인용 강남권 Mock 좌표입니다. 백엔드 경로 API에 좌표와 경로 형상이 추가되면 `lib/presentation/screens.dart`의 `_RouteMapView`에서 교체합니다.

Flutter 코드 하나로 Android 앱과 Web을 함께 개발합니다.

## 실행

```sh
flutter pub get
flutter run -d chrome
```

Android 에뮬레이터에서 로컬 Spring Boot에 연결할 때는 다음처럼 실행합니다.

```sh
flutter run -d <android-device-id> --dart-define=API_BASE_URL=http://10.0.2.2:8080/api/v1
```

Web의 기본 API 주소는 `http://localhost:8080/api/v1`입니다. 다른 환경에서는
`--dart-define=API_BASE_URL=<주소>`로 변경할 수 있습니다.

장소·일정 화면은 기본적으로 백엔드 API를 사용합니다. 다음처럼 서버 주소를 지정해
등록→목록→수정 흐름을 확인합니다.

```sh
flutter run -d chrome --web-port=3001 \
  --dart-define-from-file=.env
```

백엔드 없이 UI만 확인해야 할 때에만
`--dart-define=USE_SCHEDULE_API=false`를 명시해 Mock 일정·장소 저장소로 전환합니다.

`build/web`은 Nginx가 제공하는 빌드 산출물이며 Git에 커밋하지 않습니다.

## API와 Mock

백엔드 API가 확정되기 전 사용하는 프론트 임시 계약은
[`../docs/frontend-api-contract.md`](../docs/frontend-api-contract.md)에 정리되어 있습니다.
정상·오류 응답 예시는 `mocks/api/`에서 확인할 수 있습니다.

- API 서버 주소: `lib/core/constants/api_constants.dart`
- API 경로: `lib/core/constants/api_paths.dart`
- Dio 설정: `lib/core/network/dio_provider.dart`
- 실제 HTTP 호출: `lib/services/`
- 현재 화면용 Mock 저장소: `lib/data/mock_repository.dart`

## 개발/운영 환경 (2026-09-18)

판정은 `lib/core/config/app_environment.dart` 한 곳에서 한다. 근거는 빌드 시 넘기는
`--dart-define=DDUBI_ENV=dev|prod` 하나이며, 지정이 없으면 **릴리스는 `prod`, 그 외는 `dev`** 다.

| 항목 | 개발(dev) | 운영(prod) |
|---|---|---|
| API 주소 | `API_BASE_URL`(기본 `http://localhost:8080/api/v1`) | 운영 https 주소 고정. https 가 아닌 override 는 무시 |
| 더보기 > 개발자 옵션 | 보임 | 카드 자체를 그리지 않음 |
| `localadmin`/`local1234` 로그인·계정 비밀번호 우회 | 통과 | 차단 |
| 세션 없을 때 사용자 프로필 | 가짜 `debug` 사용자 | 오류로 올려 화면이 오류 상태를 보여줌 |
| Mock 저장소(`USE_SCHEDULE_API=false`) | 사용 | 기동 시 즉시 중단(가짜 데이터 fallback 금지) |
| 스탬프 데모 미션 | 데모 표시 | 빈 목록 |

새 화면에서 `kDebugMode` 를 직접 보지 말고 `AppEnvironment` 값을 쓴다.

## 로그

출력은 `lib/core/diagnostics/app_log.dart` 의 `AppLog.debug`/`AppLog.stack` 만 쓴다.
`debugPrint` 는 릴리스 빌드에서도 출력되므로 직접 호출하지 않는다.
`test/logging_convention_test.dart` 와 CI 의 `forbidden-output-check` 가 이를 막는다.
