# 개발용 테스트 알림 API — 프론트 연동 절차 (NTF-DEV)

> 요청: 프론트, 2026-09-14 (연동 질의 2번) · 대상: `POST /api/v1/dev/notifications/test`

이해하신 대로입니다. 실제 버스 지연을 기다리지 않고 **알림 한 건을 강제로 만들어**
알림 목록·FCM 푸시·중복 억제를 확인하는 dev 전용 API입니다.

---

## ⚠️ 먼저 — 보내주신 요청 예시로는 400 이 납니다

```jsonc
// 보내주신 것
{ "type": "...", "scheduleId": "...", "suppressKey": "delay-test-schedule-1" }
```

**`title` 과 `body` 가 빠졌습니다. 둘 다 필수입니다.**

알림의 제목·본문은 서버가 만들지 않습니다. 이 API는 "무엇을 알릴지"를 **호출한 쪽이 정하는**
테스트 도구라, 문구까지 그대로 받습니다. 운영 알림은 추천 엔진이 이 자리를 채웁니다.

```jsonc
// 실제로 보내야 하는 것
{
  "type": "EARLY_DEPART",
  "title": "10분 일찍 나가세요",
  "body": "2호선 강남역 구간 지연으로 출발 시각이 앞당겨졌습니다.",
  "scheduleId": 1,
  "suppressKey": "delay-test-schedule-1"
}
```

## 1. 필드

| 필드 | 필수 | 제약 | 비고 |
| --- | :---: | --- | --- |
| `type` | ✅ | 아래 8개 중 하나 | 없는 값이면 400 |
| `title` | ✅ | 100자 이하 | |
| `body` | ✅ | 500자 이하 | |
| `scheduleId` | ❌ | **내 일정이어야 한다** | 없으면 일정과 연결되지 않은 알림 |
| `suppressKey` | ❌ | | **null 이면 중복 검사를 아예 안 한다** |

대상 사용자는 **항상 토큰의 본인**입니다. `userId` 를 받지 않습니다 — 받으면 아무나 남의 폰에
푸시를 쏘는 구멍이 됩니다.

### `type` 목록 (8개)

| 값 | 뜻 |
| --- | --- |
| `MORNING_BRIEF` | 아침 브리핑 |
| `DEPART_SOON` | 곧 출발해야 함 |
| `EARLY_DEPART` | 예정보다 일찍 출발 — 지연 발생 |
| `ROUTE_CHANGE` | 더 나은 경로로 바뀜 |
| `ON_TIME_UNLIKELY` | 제시간 도착이 어려움 |
| `LAST_TRIP` | 막차 위험 |
| `SCHEDULE_CONFLICT` | 연속 일정 충돌 |
| `DATA_STALE` | 파이프라인 데이터가 낡음 |

### `scheduleId` — 내 일정만 됩니다 (2026-09-15 변경) ⚠️

| 상황 | HTTP | code |
| --- | --- | --- |
| 없는 일정 ID | 404 | `SCHEDULE_NOT_FOUND` |
| 남의 일정 ID | 403 | `FORBIDDEN_RESOURCE` |

> **이전 안내를 정정합니다.** 처음에 "없는 ID여도 400이 나지 않는다"고 알려 드렸는데,
> 그때는 서버가 없는 ID를 조용히 `null` 로 바꿨습니다. 그러면 화면은 "일정으로 가기" 가
> 없는 알림을 받고 **왜 없는지 알 수 없습니다.** 더 큰 문제는 남의 일정 ID도 그대로
> 붙었다는 것입니다. 리뷰에서 지적받아 바로잡았습니다.

거절된 요청은 **알림 행을 만들지 않고 억제 키도 잡지 않습니다.** 잘못된 ID로 한 번
찔러 봤다고 그 뒤 10분 동안 같은 키의 정상 알림이 막히면 안 되기 때문입니다.

### 테스트용 일정 만들기

평소 일정 등록 API 그대로입니다. 특별한 절차가 없습니다.

```
POST /api/v1/schedules
Authorization: Bearer {token}

{
  "title": "알림 테스트",
  "origin":      { "name": "강남", "lat": 37.4979, "lng": 127.0276 },
  "destination": { "name": "역삼", "lat": 37.5006, "lng": 127.0364 },
  "plannedDepartAt": "2026-09-15T08:30:00",
  "arriveBy":        "2026-09-15T09:00:00"
}
```

응답 `data.id` 가 `scheduleId` 입니다.

---

## 2. 첫 번째 요청이 어디까지 하나

네 가지 **전부** 합니다. 순서는 이렇습니다.

```
① 중복 확인 (suppressKey)  → 걸리면 SUPPRESSED 로 저장하고 끝
② 알림 켬/끔 확인           → 꺼져 있으면 SUPPRESSED 로 저장하고 끝
③ 유효 FCM 토큰 확인        → 하나도 없으면 SUPPRESSED 로 저장하고 끝
④ FCM 발송                 → 성공 SENT / 전부 실패 FAILED
```

**억제돼도 DB 행은 만들고 알림 목록에도 나옵니다.** 알림을 꺼둔 동안의 일도 나중에 알림함에서
볼 수 있어야 하기 때문입니다(도메인 규칙 9).

### 응답 예시 — 발송 성공

```jsonc
{
  "success": true,
  "data": {
    "id": 41,
    "scheduleId": 1,
    "recommendationId": null,
    "type": "EARLY_DEPART",
    "title": "10분 일찍 나가세요",
    "body": "2호선 강남역 구간 지연으로 출발 시각이 앞당겨졌습니다.",
    "status": "SENT",
    "sentAt": "2026-09-14T15:02:11",
    "readAt": null,
    "createdAt": "2026-09-14T15:02:11"
  },
  "timestamp": "...", "requestId": "..."
}
```

`status` 는 세 가지입니다.

| | |
| --- | --- |
| `SENT` | FCM 발송 성공. 기기 여러 대 중 **하나만 성공해도** SENT |
| `FAILED` | 발송을 시도했으나 전부 실패 |
| `SUPPRESSED` | 보내지 않음 (중복 / 알림 끔 / 기기 없음) |

푸시 payload 에는 `notificationId`, `type`, `scheduleId` 가 실립니다. 앱이 탭했을 때
어디로 갈지 판단하는 값입니다.

### 알림 목록에서 확인

```
GET /api/v1/notifications
GET /api/v1/notifications?type=EARLY_DEPART
GET /api/v1/notifications?unreadOnly=true
GET /api/v1/notifications/{notificationId}
```

---

## 3. 중복 억제 확인

기대하신 동작이 맞습니다. **억제 유효기간은 10분**(`ddubi.notification.suppress-ttl`)입니다.

| | 응답 `status` |
| --- | --- |
| 1차 | `SENT` |
| 2차 (같은 `suppressKey`, 10분 내) | `SUPPRESSED` |

**확인 위치는 네 곳 모두 가능합니다.**

| 위치 | 어떻게 |
| --- | --- |
| 테스트 API 응답 | `data.status` — **제일 확실합니다** |
| 알림 목록 API | 같은 `suppressKey` 로 만든 행 2개가 각각 SENT / SUPPRESSED |
| DB | `SELECT id, status, suppress_key FROM notification ORDER BY id DESC` |
| 서버 로그 | `알림 억제(중복) — userId=..., type=...` (DEBUG 레벨) |

### `suppressReason` 으로 왜 억제됐는지 알 수 있습니다

SUPPRESSED 는 세 가지 이유로 뜹니다. `status` 만 보면 구분되지 않아 **응답에 사유를
추가했습니다**(2026-09-14).

```jsonc
{ "status": "SUPPRESSED", "suppressReason": "NO_VALID_DEVICE", "sentAt": null }
```

| `suppressReason` | 뜻 | 해야 할 일 |
| --- | --- | --- |
| `DUPLICATE` | 같은 `suppressKey` 를 10분 안에 이미 보냈다 | 없음 — **확인하려던 정상 동작** |
| `ALARM_OFF` | 사용자가 알림을 꺼 뒀다 | 마이페이지에서 켠다 |
| `NO_VALID_DEVICE` | 유효한 FCM 토큰이 없다 | **기기 토큰부터 등록한다** |

`SENT`·`FAILED` 이거나 2026-09-14 이전에 쌓인 행은 `null` 입니다.

**⚠️ 연동 초기에는 `NO_VALID_DEVICE` 가 제일 흔합니다.** 토큰을 등록하지 않은 채로
두 번 호출하면 **둘 다 SUPPRESSED** 인데 사유는 각각 `NO_VALID_DEVICE`, `DUPLICATE`
입니다. 이때 status 만 보면 "중복 억제가 잘 되는구나" 로 읽히지만 정작 억제 동작은
검증되지 않았습니다. **1차가 `SENT` 인지부터 확인**하세요.

**권장 절차**

```
1. POST /api/v1/users/me/device-tokens  로 FCM 토큰 등록
2. 마이페이지에서 알림이 켜져 있는지 확인 (isAlarm)
3. 1차 호출 → status 가 SENT 인지 확인   ← 여기가 통과해야 다음이 의미 있다
4. 2차 호출 (같은 suppressKey) → SUPPRESSED
5. suppressKey 를 바꿔서 3차 호출 → 다시 SENT (억제가 키 단위인지 확인)
```

### 발송에 실패하면 키가 풀립니다

1차가 `FAILED` 였다면 억제 키를 **되돌려 놓습니다.** 실패한 알림 때문에 10분 동안
재시도조차 막히면 안 되기 때문입니다. 그래서 이 경우 2차는 `SUPPRESSED` 가 아니라
다시 발송을 시도합니다. **의도된 동작입니다.**

---

## 4. 연동 서버에서 쓸 수 있나

### ⚠️ 지금은 꺼져 있습니다 — 인프라가 켜야 합니다 (2026-09-14 확인)

서버2 backend 는 `SPRING_PROFILES_ACTIVE=local` 로 돌고 있습니다(인프라 확인). 이 API 는
원래 `@Profile("dev")` 였기 때문에 **현재 배포 서버에서는 존재하지 않습니다 — 404** 입니다.

**서버를 `dev` 로 바꾸는 것으로는 해결하지 않았습니다.** `application-dev.yml` 이 통째로
딸려 오는데, 거기에 이 둘이 있습니다.

```yaml
org.hibernate.orm.jdbc.bind: TRACE        # 쿼리 바인딩 값이 로그에 그대로 — 이메일·좌표가 남는다
management.endpoints.web.exposure.include: "*"   # /actuator/env · /actuator/heapdump 노출
```

필요한 건 엔드포인트 하나지 저 둘이 아닙니다. 그래서 **전용 스위치**로 분리했습니다.

```
DDUBI_DEV_ENDPOINTS_ENABLED=true    # 서버2 .env 에 한 줄 + 재기동
```

기본값은 꺼짐입니다. **발표 전에 이 줄만 지우면 API 가 사라집니다** — 코드 변경 없이
되돌릴 수 있습니다. 내 PC(`dev` 프로파일)에서는 지금까지처럼 항상 켜져 있습니다.

| 질문 | 답 |
| --- | --- |
| 지금 쓸 수 있나 | **아직 아닙니다.** 인프라가 위 환경변수를 켜야 합니다. 켜졌는지는 아래로 확인하세요 |
| 확인 방법 | 토큰 받아서 `POST /api/v1/dev/notifications/test` 호출 — **404 면 꺼짐**, 그 외(200/400)면 켜짐 |
| Swagger | **배포 서버에서는 안 열립니다.** nginx 가 `/api/` 와 `/internal/` 만 프록시합니다. 로컬 `http://localhost:8080/swagger-ui/index.html` 을 쓰세요 |
| Access Token 필요 | **필요합니다.** 공개 경로가 아니라 없으면 401 입니다 |
| 발표 때 자동 비활성화 | 자동은 아니고 **환경변수 한 줄**입니다. 기본값이 꺼짐이라 지우기만 하면 됩니다 |

메일·FCM 자격증명은 서버에 들어가 있고 기동 로그에 "메일 발송 준비 완료", "FCM 발송 준비
완료" 가 찍혀 있습니다(인프라 확인). **회원가입 메일과 실제 푸시가 나갑니다.**

---

## 스키마 변경 있음 ⚠️

`suppressReason` 을 위해 `notification.suppress_reason` 컬럼이 추가됐습니다
(`V6__add_notification_suppress_reason.sql`). **로컬 DB는 앱을 다시 띄우면 Flyway 가
알아서 적용**합니다. 초기화할 필요는 없습니다.

기존 행은 `NULL` 로 둡니다 — 그때 무엇이었는지 알 수 없고, 추측을 기록으로 남기면
나중에 사실과 구분할 수 없습니다.

## 이 사유는 목록·상세 API 에도 나옵니다

테스트 API 응답뿐 아니라 `GET /api/v1/notifications`, `GET /api/v1/notifications/{id}`
응답에도 같은 `suppressReason` 이 들어갑니다. 운영 알림이 왜 안 갔는지도 같은 방법으로
볼 수 있습니다.
