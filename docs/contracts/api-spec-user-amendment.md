# API 명세서 개정안 — USER-001 / USER-002

작성: 백엔드(서비스) 손정민 · 대상 문서: Notion「DDUBI_APP_API」명세서 v2.1.1 § 사용자

---

## 왜 고쳐야 하나

Phase 1(인증·회원) 구현 중 명세서와 ERD_master 가 어긋나는 지점 두 곳을 발견했다.

| # | 문제 | 근거 |
| --- | --- | --- |
| A | `USER-001` 응답의 **`withdrawnAt` 을 줄 수 없다** | ERD_master 의 `user` 테이블에 해당 컬럼이 없다. 탈퇴는 하드 삭제라 "탈퇴한 사용자"라는 상태 자체가 존재하지 않는다(도메인 규칙 4) |
| B | `USER-002` 로 **설정할 수 없는 컬럼이 3개** 있다 | `briefing_time`, `mobility_note`, `user_transport_pref` 가 ERD 에 있는데 이를 변경하는 API 가 없다 |

**B 가 막는 기능**

| 컬럼 | 요구사항 | 없으면 |
| --- | --- | --- |
| `user.briefing_time` | REQ-NTF-003 아침 브리핑 시각 | 값이 항상 NULL → **브리핑이 아무에게도 발송되지 않는다** (Phase 4 스케줄러가 이 컬럼으로 대상을 고른다) |
| `user.mobility_note` | REQ-SCH-004 이동 시 어려운 점 | 추천 여유시간 가중에 쓸 입력이 들어오지 않는다 |
| `user_transport_pref` | REQ-SCH-004 선호·회피 교통수단 | 추천 순위 가중이 항상 기본값으로 동작한다 |

세 컬럼 모두 ERD_master 에 이미 있고 마이그레이션에도 반영되어 있다. **API 만 없다.**

---

## 개정안 (Notion 에 그대로 반영할 형식)

### USER-001 내 정보 조회 🟢

- **상태:** 🟢
- **Method / URL:** `GET /users/me`
- **호출 권한:** 로그인 사용자
- **처리 주체:** 뚜비 API 서버
- **관련 화면·요구사항:** DDB-016, 내 정보
- **필요한 이유:** DDB-016, 내 정보 기능을 앱 또는 운영 흐름에서 일관된 서버 계약으로 제공하기 위해 필요하다.
- **Request:** 요청 본문 없음.
- **Response:** 200: id, provider, email, nickname, notifyLeadMin, alarmEnabled, historyConsentedAt, briefingTime, mobilityNote, transportPrefs, createdAt, updatedAt.
- **주요 예외:** 400 INVALID_REQUEST · 401 UNAUTHORIZED · 403 FORBIDDEN_RESOURCE · 409 INVALID_STATE

**원문 대비 변경**

- ~~`withdrawnAt`~~ **삭제** — `user` 테이블에 컬럼이 없다. 탈퇴는 하드 삭제라 조회 시점에 탈퇴한 사용자가 존재할 수 없다.
- **`briefingTime`** 추가 — `HH:mm:ss` 또는 null(브리핑 끔)
- **`mobilityNote`** 추가 — 문자열 또는 null
- **`transportPrefs`** 추가 — `[{ "mode": "BUS|SUBWAY", "preference": "PREFER|AVOID" }]`, 없으면 빈 배열
- `provider` 는 단일값 유지. 계정 통합으로 LOCAL·KAKAO 가 함께 있으면 **LOCAL 을 대표값**으로 반환한다.

---

### USER-002 내 정보·지원 설정 변경 🟢

- **상태:** 🟢
- **Method / URL:** `PATCH /users/me`
- **호출 권한:** 로그인 사용자
- **처리 주체:** 뚜비 API 서버
- **관련 화면·요구사항:** DDB-015~016, 지원 가능한 설정
- **필요한 이유:** DDB-015~016, 지원 가능한 설정 기능을 앱 또는 운영 흐름에서 일관된 서버 계약으로 제공하기 위해 필요하다.
- **Request:** Body: nickname, notifyLeadMin, alarmEnabled, historyConsent, briefingTime, mobilityNote, transportPrefs 중 변경할 필드만 전달.
- **Response:** 200: 변경된 사용자 정보 전체(USER-001 과 동일 형태).
- **주요 예외:** 400 INVALID_REQUEST · 401 UNAUTHORIZED · 403 FORBIDDEN_RESOURCE · 409 INVALID_STATE

**원문 대비 변경 — 요청 필드 3개 추가**

| 필드 | 타입 | 규칙 |
| --- | --- | --- |
| `briefingTime` | `HH:mm:ss` \| null | null 을 보내면 브리핑 끔. 필드를 생략하면 기존값 유지 |
| `mobilityNote` | string \| null | 최대 100자 |
| `transportPrefs` | array | `[{ mode, preference }]`. **보낸 배열로 전체 교체**(부분 병합 아님). 빈 배열이면 전부 삭제. `mode` 는 중복 불가 |

기존 4개 필드 규칙은 그대로.

- `nickname` 1~50자
- `notifyLeadMin` 0~180
- `alarmEnabled` boolean
- `historyConsent` boolean — true 면 서버가 동의 시각을 기록, false 면 NULL 로 되돌린다

**null 과 미전달의 구분이 필요하다.** `briefingTime: null` 은 "브리핑 끄기", 필드를 아예 안 보내면 "안 바꿈"이다. 두 의미가 다르므로 JSON 에 키가 있는지로 판단한다.

---

## 요청 · 응답 예시

### `PATCH /users/me`

```json
{
  "briefingTime": "07:00:00",
  "mobilityNote": "계단 이동이 어렵습니다",
  "transportPrefs": [
    { "mode": "SUBWAY", "preference": "PREFER" },
    { "mode": "BUS", "preference": "AVOID" }
  ]
}
```

### 응답 (USER-001 · USER-002 공통)

```json
{
  "success": true,
  "data": {
    "id": 1,
    "provider": "LOCAL",
    "email": "user@example.com",
    "nickname": "뚜비사용자",
    "notifyLeadMin": 30,
    "alarmEnabled": true,
    "historyConsentedAt": "2026-09-01T09:00:00",
    "briefingTime": "07:00:00",
    "mobilityNote": "계단 이동이 어렵습니다",
    "transportPrefs": [
      { "mode": "SUBWAY", "preference": "PREFER" },
      { "mode": "BUS", "preference": "AVOID" }
    ],
    "createdAt": "2026-08-31T17:54:13",
    "updatedAt": "2026-09-01T09:10:00"
  },
  "timestamp": "2026-09-01T09:10:00+09:00",
  "requestId": "..."
}
```

---

## 프론트에 미치는 영향

| 변경 | 영향 |
| --- | --- |
| `withdrawnAt` 삭제 | 이 필드를 읽는 코드가 있으면 제거. 어차피 서버가 보낸 적이 없다 |
| 응답 필드 3개 추가 | **기존 코드는 그대로 동작**한다. 새 필드를 안 읽으면 그만이다 |
| 요청 필드 3개 추가 | 선택 필드라 **기존 요청도 그대로 유효**하다 |

즉 **하위 호환이며, 프론트는 설정 화면을 만들 때 쓰면 된다.**

---

## 참고: 이미 구현된 범위

`briefingTime`·`mobilityNote`·`transportPrefs` 를 제외한 부분은 구현·검증이 끝났다.

- `GET /users/me` 200
- `PATCH /users/me` 부분 수정, 범위 검증(notifyLeadMin 0~180), 빈 요청 400
- `DELETE /users/me` — confirmation=WITHDRAW + LOCAL 비밀번호 확인, CASCADE 삭제, 세션·액세스 토큰 즉시 무효화

이 개정안이 승인되면 3개 필드만 추가하면 된다. 엔티티와 DB 컬럼은 이미 있다.
