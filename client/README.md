# DDUBI Client

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
