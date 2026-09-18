# Phase 4 — 알림 작업 계획 (NTF-001~004 + 발송 기반)

작성: 백엔드(서비스) 손정민 · 2026-09-03
원천: API 명세서 v2.1.1 §8 · `docs/ddubi_erd_v2.sql` 12번 `notification`

---

## 왜 지금 하나

1. **홈 API 가 이미 이 테이블을 읽고 있다.** `HomeService.unreadCount()` 가 도메인이 없어
   `JdbcTemplate` 로 직접 SQL 을 날리는 임시 상태다. 알림 도메인이 생기면 정리된다.
2. **뚜비의 핵심 가치가 "먼저 알려주는 것"이다.** 알림이 없으면 추천을 계산해도 사용자에게
   닿지 않는다.
3. 추천(Phase 3, 김종무)이 끝나기를 기다릴 필요가 없다 — 아래 경계선 참조.

## 담당 경계

| | 담당 | 이 계획의 범위 |
| --- | --- | --- |
| 무엇을·언제 알릴지 **판단** | 추천 엔진 (Phase 3) | ✗ |
| 알림 **행 생성·억제 판정·발송·조회** | 서비스 백엔드 | ✓ |

추천 엔진이 `NotificationService.create(...)` 를 호출하면 그 뒤는 전부 이쪽이 맡는다.
**지금 이 인터페이스까지 만들어 두면 Phase 3 이 붙일 자리가 생긴다.**

---

## 1단계 — 알림 조회 API (NTF-001~004)

추천 없이 지금 바로 끝낼 수 있다. 프론트 알림함 화면(DDB-014)이 이걸 기다린다.

| ID | Method · URL | 응답 |
| --- | --- | --- |
| NTF-001 | `GET /notifications` | `unreadCount` + 페이지 목록 |
| NTF-002 | `GET /notifications/{id}` | 전체 필드 + 연결된 일정·추천 요약 |
| NTF-003 | `PATCH /notifications/{id}/read` | `{id, readAt}` |
| NTF-004 | `PATCH /notifications/read-all` | `{updatedCount, readAt}` |

### 만들 것

```
domain/notification/
├── entity/       Notification, NotificationType, NotificationStatus
├── repository/   NotificationRepository
├── service/      NotificationQueryService
├── controller/   NotificationController
└── dto/response/ NotificationResponse, NotificationListResponse, ReadResponse
```

### 설계 결정

- **`unreadCount` 를 목록 응답에 함께 싣는다** (명세서 §8.1). 뱃지와 목록을 한 번에 그리는
  화면이라 호출을 두 번 하게 만들 이유가 없다.
- **읽음 처리는 멱등.** 이미 읽은 알림에 다시 요청해도 200 과 기존 `readAt` 을 준다.
  실패시키면 클라이언트가 재시도를 못 한다.
- **`read-all` 은 벌크 UPDATE 한 방.** 알림이 수백 건이면 엔티티를 다 불러올 이유가 없다.
  `updated_at` 도 함께 갱신되도록 JPQL 에 명시한다.
- **소유권 검사**(도메인 규칙 1) — 남의 알림은 403.
- **`HomeService.unreadCount()` 의 JdbcTemplate 제거** → `NotificationRepository` 호출로 교체.

### 테스트로 고정할 것

- 목록 필터(`unreadOnly`, `type`)와 `unreadCount` 정확성
- 읽음 멱등 · 전체 읽음의 `updatedCount`
- 남의 알림 403 · 없는 알림 404
- 일정이 삭제돼도 알림 행은 남고 `scheduleId` 가 null 이 된다(FK SET NULL)

---

## 2단계 — 알림 생성·억제 (도메인 규칙 9)

추천 엔진이 호출할 진입점. **이 단계가 규칙 9의 구현체다.**

> 규칙 9: 발송 억제(알림 끔·DND·중복)여도 `notification` 행은 만들고 `status=SUPPRESSED`.
> 재발송 방지는 `suppress_key` + Redis TTL.

### 만들 것

```
service/NotificationService.create(command)   ← 추천 엔진이 부르는 유일한 문
service/SuppressionPolicy                     ← 억제 판정만 담당
service/NotificationCommand                   ← userId, type, scheduleId, recommendationId,
                                                 title, body, suppressKey
```

### 억제 판정 순서

```
1. 중복      Redis 에 suppress_key 가 살아 있으면 → SUPPRESSED
2. 알림 끔   user.is_alarm = false             → SUPPRESSED
3. DND       조용한 시간대(설정)                 → SUPPRESSED
4. 그 외      FCM 발송 시도 → 성공 SENT / 실패 FAILED
```

**어느 경우에도 행은 만든다.** 사용자가 "왜 안 왔지" 할 때 알림함에 근거가 남아야 하고,
운영에서 억제율을 세려면 억제 사실 자체가 데이터여야 한다.

### 미결 — 팀 확인 필요

- **DND 시간대**가 명세서·ERD 어디에도 없다. `user` 테이블에 컬럼이 없으므로
  ① 서버 공통 정책(예: 22시~07시)으로 고정하거나 ② 컬럼을 추가해야 한다.
  **①을 권한다** — 개인화할 값어치보다 스키마 변경 비용이 크다.
- **`suppress_key` 조합 규칙**: 명세서는 "일정·이유·행동 해시" 라고만 한다.
  `sha256(scheduleId + type + 행동요약)` 로 잡고 TTL 은 타입별로 다르게 두는 안을 제안한다.

---

## 3단계 — FCM 발송

### 만들 것

```
global/push/PushSender          인터페이스
global/push/FcmPushSender       실제 발송
global/push/LoggingPushSender   자격증명 없을 때 로그 대체
```

**`EmailSender` 와 같은 구조로 간다.** 팀원이 FCM 키 없이도 알림 흐름을 끝까지 돌려볼 수
있어야 한다(카카오 키·메일 키에서 이미 두 번 겪은 문제).

### 함께 처리할 것 (REQ-NTF-004)

FCM 이 `UNREGISTERED` 를 주면 해당 `device_token.is_valid = false`.
앱을 지운 기기에 계속 쏘면 발송 실패율이 올라가고 FCM 쿼터를 낭비한다.

### 필요한 것

- Firebase 프로젝트 + 서비스 계정 JSON → **비밀값 공유 채널 안건에 추가**
- 의존성 `firebase-admin`

---

## 순서와 규모

| 단계 | 내용 | 추천 의존 | 규모 |
| --- | --- | --- | --- |
| 1 | NTF-001~004 조회 API | 없음 | 반나절 |
| 2 | 생성·억제 (규칙 9) | 없음 (호출자만 나중) | 반나절 |
| 3 | FCM 발송 | 없음 (키 필요) | 반나절 |
| — | 추천 엔진이 2단계 호출 | **있음** | Phase 3 담당 |

**1 → 2 → 3 순서로 간다.** 1단계만 끝나도 프론트 알림함 화면이 붙는다.

---

## 지라

`[BE] 알림 API 및 발송 기반 구현` 스토리를 새로 만든다.
에픽은 `S15P21E202-10 회원·인증 및 일정 관리` 아래가 자연스럽다.

> 완료 조건
> - 알림 목록·상세·읽음·전체읽음이 테스트로 검증됨
> - 억제 상황에서도 행이 생성되고 status 가 SUPPRESSED 로 남음
> - FCM 자격증명 없이도 흐름이 끝까지 동작함(로그 대체)
> - 홈 API 의 unreadCount 가 알림 도메인을 통해 조회됨

---

## 부록 — 명세서 정정 요청 2건

### 1. `SCHEDULE_CONFLICT` 가 명세서 타입 목록에서 빠졌다

ERD 의 `notification.type` ENUM 은 8종인데 명세서 §8.1 은 7종만 나열한다.

```
ERD:    MORNING_BRIEF DEPART_SOON EARLY_DEPART ROUTE_CHANGE
        ON_TIME_UNLIKELY LAST_TRIP SCHEDULE_CONFLICT DATA_STALE   ← 8종
명세서: ... LAST_TRIP, DATA_STALE                                   ← SCHEDULE_CONFLICT 없음
```

`SCH-006 연속 일정 충돌` 을 이미 구현했으므로 이 알림 타입이 필요하다.
**명세서 §8.1 의 `type` 목록에 `SCHEDULE_CONFLICT` 를 추가해 주세요.**

### 2. NTF-003 응답 필드명이 요약과 예시에서 다르다

- 요약: `notificationId, readAt`
- JSON 예시: `{"id": 7001, "readAt": ...}`

**목록·상세가 `id` 를 쓰므로 `id` 로 통일하겠습니다.** 다르면 알려 주세요.

---

## 결정 — Kafka 를 거치지 않는다 (2026-09-09)

`docs/contracts/topics.yaml` 에 **`notification.requested`** 토픽이 있고
`consumers: [notification]` 로 별도 알림 서비스가 정의돼 있다. 인프라 문서에도 서버2
컨테이너 목록에 `notification` 이 있고 `.env.example` 에 `NOTIFICATION_PORT=8081` 이 잡혀 있다.

**그 서비스는 저장소에 없다.** compose 에도 없고 코드도 없다. 발행하는 쪽(backend)도 없다.
**계약만 있고 양쪽 끝이 비어 있는 상태**다.

### 왜 이렇게 됐나

이 계획서를 쓸 때 내가 그 토픽을 확인하지 않았다. "추천 엔진이 `NotificationService.create()`
를 호출하면 그 뒤는 이쪽이 맡는다" 로 설계했고, 그대로 구현·테스트까지 끝냈다.
결과적으로 **잘 도는 코드**와 **아무도 구현 안 한 계약**이 나란히 남았다.

### 세 선택지와 결정

| | 내용 | 판단 |
| --- | --- | --- |
| A | 지금 구조 유지, 문서에 미구현 명시 | **채택** |
| B | 발행만 하고 소비자는 나중에 | ❌ 소비자가 없으면 **알림이 조용히 사라진다** |
| C | `notification` 서비스를 새로 만든다 | 담당자 없음. 데모 전 리스크 |

**A 를 택한 이유**

- 지금 경로가 이미 동작하고 테스트로 고정돼 있다 — 억제 3단계, FCM 무효 토큰 처리, 멱등
- 알림 폭증은 아직 **실측된 문제가 아니다**. 없는 문제를 위해 서비스를 늘리지 않는다
- 서비스가 하나 늘면 배포·모니터링·장애 지점이 함께 는다

**B 를 배제한 이유가 중요하다.** 절반만 도입하면 발행은 되는데 아무도 안 읽어서
사용자에게 알림이 안 간다. **지금 잘 되는 것을 망가뜨리는 유일한 선택지**다.
가려면 C 로 한 번에 가야 한다.

### 토픽 정의를 지우지 않은 이유

`infra/scripts/create-topics.sh` 가 `topics.yaml` 을 읽어 토픽을 만든다. 정의를 지우면
스크립트는 더 이상 만들지 않지만 **이미 생성된 토픽은 서버에 남아** 반대 방향으로 어긋난다.
그래서 지우지 않고 **미구현 주석**을 달았다.

알림 분리가 필요해지면 계약(`events/notification-event.schema.json` 포함)을 그대로 쓸 수 있다.
그때 순서는 **소비자 먼저, 발행은 나중**이다.
