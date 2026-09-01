# Flutter API 연동 가이드

> Jira: `S15P21E202-32`
>
> 기준 문서: [Notion DDUBI_APP_API](https://app.notion.com/p/3cad86006d028071833cfe26e6efa049)
>
> 이 파일은 API 명세를 복제하지 않고 Flutter에서 찾을 위치만 안내한다.

## 기준

- 현재 설계 계약은 Notion `DDUBI_APP_API`를 따른다.
- Spring Boot 구현 후에는 Swagger/OpenAPI를 실제 동작 기준으로 관리한다.
- API 변경 전 백엔드와 Flutter 담당자에게 공유하고 양쪽 문서를 함께 갱신한다.
- Base URL은 `/api/v1`이며 로컬 기본값은 `http://localhost:8080/api/v1`이다.
- 성공과 오류 모두 Notion의 공통 응답 래퍼를 사용한다.

## 공통 응답

성공:

```json
{
  "success": true,
  "data": {},
  "timestamp": "2026-08-31T12:00:00+09:00",
  "requestId": "request-1"
}
```

오류:

```json
{
  "success": false,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "입력값을 확인해주세요.",
    "fieldErrors": [
      {
        "field": "arriveBy",
        "rejectedValue": "2026-08-30T10:00:00+09:00",
        "reason": "미래 시각이어야 합니다."
      }
    ]
  },
  "timestamp": "2026-08-31T12:00:00+09:00",
  "requestId": "request-1"
}
```

Flutter의 `ApiErrorResponse`가 이 오류 형식을 읽는다. 민감한 값은
`rejectedValue`에 포함하지 않는다.

## 화면별 API

| 화면 | API ID | Method / URL |
| --- | --- | --- |
| 로컬 회원가입 | AUTH-001 | `POST /auth/local/signup` |
| 로컬 로그인 | AUTH-002 | `POST /auth/local/login` |
| 일정 목록 | SCH-001 | `GET /schedules?from=&to=` |
| 일정 등록 | SCH-002 | `POST /schedules` |
| 일정 상세 | SCH-003 | `GET /schedules/{scheduleId}` |
| 추천 계산 | REC-001 | `POST /schedules/{scheduleId}/recommendations` |
| 최신 추천 | REC-002 | `GET /schedules/{scheduleId}/recommendations/latest` |
| 추천 근거 | REC-003 | `GET /recommendations/{recommendationId}/explanation` |
| 후보 경로 | ROUTE-001 | `GET /schedules/{scheduleId}/routes` |
| 경로 상세 | ROUTE-002 | `GET /routes/{routeId}` |
| 지도 경로 | ROUTE-004 | `GET /routes/{routeId}/map` |

세부 요청·응답 필드와 예외 코드는 Notion 원문을 기준으로 한다.

## Flutter 코드 위치

- 서버 주소: `client/lib/core/constants/api_constants.dart`
- API 경로: `client/lib/core/constants/api_paths.dart`
- 공통 오류: `client/lib/core/network/api_error_response.dart`
- Dio 설정: `client/lib/core/network/dio_provider.dart`
- HTTP 호출: `client/lib/services/`
- 인메모리 화면 Mock: `client/lib/data/mock_repository.dart`
- API 응답 Mock: `client/mocks/api/`

현재 화면은 인메모리 Mock Repository를 사용한다. Spring Boot 연결 시 API 기반
Repository를 추가하고 Riverpod Provider의 구현만 교체한다.

## Mock 응답

| 파일 | 상태 |
| --- | ---: |
| `auth_login_success.json` | 200 |
| `auth_login_invalid_credentials.json` | 401 |
| `auth_signup_duplicate_resource.json` | 409 |
| `schedules_success.json` | 200 |
| `schedule_create_success.json` | 201 |
| `schedule_create_invalid_request.json` | 400 |
| `schedule_not_found.json` | 404 |
| `recommendation_success.json` | 200/201 |
| `routes_success.json` | 200 |
| `route_not_available.json` | 422 |
| `realtime_data_unavailable.json` | 503 |
