# 백엔드 개발 규약 — 뚜비(DDUBI) 서비스 백엔드

> 뚜비 **서비스 백엔드(Spring Boot)** 의 아키텍처·규약·도메인 규칙을 담은 팀 문서다.
> Notion 원본(요구사항 명세서 REQ-*, 기능 명세서 F-*, API 명세서 v2.1.1, 시스템 설계)의 확정 내용을 압축했다.
>
> **정본 우선순위**
> 1. Notion「DDUBI_APP_API」 명세서 v2.1.1 — API 계약의 최종 근거
> 2. 이 문서 — 위 명세서를 코드 관점으로 정리한 것
> 3. `docs/ddubi_erd_v2.sql` — 스키마 원천. 이 문서의 5장과 충돌하면 SQL 파일이 맞다
>
> 개인 AI 협업 지침은 `backend/CLAUDE.md`(git 제외)에 따로 둔다. 팀이 공유할 규약만 여기 적는다.

---

## 1. 프로젝트 개요

- **서비스**: 뚜비(뚜벅이 비서) — 일정과 실시간 교통·날씨·행사 데이터를 분석해 "늦지 않을 출발 시각 + 이동 경로"를 선제적으로 알려주는 대중교통 이동 비서 (SSAFY 특화, 도메인: 빅데이터·분산시스템)
- **핵심 흐름**: 사용자가 "도착 시각(arrive_by)"이 있는 일정을 등록 → 외부 길찾기 API(카카오맵)로 후보 경로 확보 → 후보를 구간(segment) 단위로 분해 → Spark가 계산한 구간별 실시간 상태(Redis) + 평소값(baseline)으로 재평가 → 권장 출발 시각·추천 경로·이유를 응답 → 상황 변화 시 영향 일정만 재계산 후 FCM 알림
- **백엔드(서비스) 담당 범위**: 회원/인증, 일정/반복일정/장소, 경로·추천, 알림, 도착·리포트, 내부(internal) API 전부 + 분산 계산 결과(Redis/baseline)를 프론트에 서빙하는 계층
- **담당 밖**: 수집기(collector), Spark 잡, Flutter 클라이언트, 인프라(compose/CI). 단, `baseline_serving`·`pipeline_config`는 **백엔드 소유**다 — Spark 는 MySQL 직접 쓰기 대신 internal API 로 업로드하고 검증·적재·버전 전환은 백엔드가 한다(2026-09-01 인프라 합의). `detection_log` 만 분산 담당 소유.

## 2. 아키텍처 (확정)

```
Flutter(Android·Web) → Nginx(443) → Spring Boot API ─┬→ MySQL 8 (진실 원장)
                                                      ├→ Redis (구간 최신 상태 조회, 세션/멱등/락)
                                                      ├→ 카카오맵 대중교통 길찾기·로컬 검색
                                                      └→ Kafka(알림 이벤트) → Notification 서비스 → FCM
[분산 파이프라인] 수집기 → Kafka → Spark Structured Streaming → Redis(구간 상태) / HDFS+Parquet(원본·Replay)
```

- 백엔드는 Kafka에서 **직접 소비하지 않는다**. 구간 상태는 Redis를 읽고, 재계산 트리거는 `POST /internal/recalculations`로 받는다.
- 모니터링은 Prometheus + Grafana + Spring Actuator. 운영 상태 테이블을 MySQL에 만들지 않는다.

## 3. 기술 스택·프로젝트 규약 (확정)

| 항목 | 결정 |
| --- | --- |
| 언어/프레임워크 | Java 17+, Spring Boot 3.x, **Maven**(단일 모듈, `backend/pom.xml`). 빌드는 `./mvnw` 래퍼로만 실행 |
| 데이터 접근 | **Spring Data JPA** 기본. 성능 구간(영향 일정 역조회 등)은 `@Query(nativeQuery = true)`. MyBatis는 쓰지 않는다 |
| 인증 | Spring Security + JWT (Access 60분 / Refresh 14일, Redis 회전) |
| 문서화 | springdoc-openapi(Swagger UI). API 변경 시 반드시 갱신 |
| 테스트 | JUnit 5 + Testcontainers(MySQL·Redis), 도메인 규칙(4장)은 필수 테스트 대상 |
| 마이그레이션 | Flyway. 스키마 변경은 **새 `V2__`,`V3__` 파일로 쌓는다**(V1 은 고치지 않음). **ERD 문서에도 동시 반영 + 팀 합의 필요**. 운영 방식은 3.2장 |
| 환경변수 | 실제 값은 `.env`(git 제외), `.env.example`에 변수명만 |
| 레포 구조 | 모노레포: `client/ backend/ collector/ sparkjobs/ notification/ infra/ docs/` — 백엔드 작업은 `backend/`에서만 |
| 브랜치 | `feature/backend/{작업명}` → MR → **`be/dev`** (백엔드 트랙). PM이 확인 후 `develop`(서버가 바라보는 테스트 브랜치)로 반영하고 최종적으로 `master`. **`develop`·`master`를 직접 건드리지 않는다.** 다른 트랙은 `fe/dev`·`fe/main`·`infra` |
| 커밋 | `feat: [S15P21E202-42] 일정 등록 API 구현` 형식 (type: feat/fix/refactor/docs/test/chore) |
| MR | develop 대상, 리뷰 1명 + CI 통과 후 병합 |

## 3.1 코딩 규약 (참고 프로젝트 S15P11E204 구조를 따름)

### 패키지 (도메인형)
```
com.ssafy.ddubi
├── global
│   ├── common/      ApiResponse, PageResponse, PageRequestParam
│   ├── config/      JpaConfig, SecurityConfig, SwaggerConfig, RedisConfig
│   ├── entity/      BaseTimeEntity (created_at/updated_at, JPA Auditing)
│   ├── exception/   ErrorCode, BusinessException, GlobalExceptionHandler
│   ├── filter/      RequestIdFilter
│   ├── security/    JwtTokenProvider, JwtAuthenticationFilter, @LoginUserId + ArgumentResolver
│   └── util/        RequestIdHolder 등
└── domain/{auth,user,place,schedule,route,recommendation,notification,report,internal}
    ├── controller/  service/  repository/  entity/  client/
    └── dto/{request, response}
```

### 절대 규칙
1. **영속성은 Spring Data JPA.** 엔티티는 `domain.{도메인}.entity`, 리포지토리는 `domain.{도메인}.repository`. 스키마의 진실은 Flyway이고 Hibernate는 `ddl-auto: validate`로 검증만 한다.
2. **모든 응답은 `ApiResponse<T>`로 감싼다.** Controller가 raw DTO나 `ResponseEntity<원시DTO>`를 그대로 반환하지 않는다.
3. **JSON은 camelCase.** 명세서 v2.1.1 기준이다 — `SNAKE_CASE` 전략을 켜지 말 것.
4. **URL은 `/api/v1/...`**, 리소스는 복수형, URL에 동사 금지. 행위는 HTTP 메서드로.
5. **계층 호출 방향: Controller → Service → Repository.** Controller에서 Repository 직접 호출 금지, Controller에 비즈니스 로직 금지.
6. **예외는 `BusinessException(ErrorCode)`로 던진다.** Controller/Service에서 try-catch로 에러 응답을 직접 만들지 않는다.
7. **`@Transactional`은 Service에만.** 조회 전용은 `readOnly = true`.
8. **쿼리 파라미터는 반드시 바인딩.** JPQL·네이티브 쿼리 모두 `:param` / `?1`을 쓰고 문자열 연결 금지(SQL Injection). 동적 조건은 Specification 또는 조건별 메서드로.
9. **인증 사용자는 `@LoginUserId Long userId`로 받는다.** 소유권 검사(4장 규칙 1)가 모든 리소스에 붙으므로 `SecurityContextHolder`를 매번 파지 않는다.
10. **엔티티를 Controller에 직접 노출 금지.** `~Request` / `~Response` DTO로 변환한다.
11. **연관관계는 전부 `LAZY`**, 목록 조회는 fetch join 또는 `@EntityGraph`로 N+1을 막는다.
12. **`CascadeType.REMOVE` / `orphanRemoval` 금지.** 삭제 연쇄는 DB의 `ON DELETE CASCADE`가 담당한다(탈퇴 = `DELETE user` 1건).

### 네이밍
클래스 PascalCase / 메서드·변수 camelCase / 상수 UPPER_SNAKE / DB snake_case.
에러 코드는 `ErrorCode` enum 이름을 그대로 응답에 쓴다(프론트가 이 문자열로 분기).

## 3.2 마이그레이션 운영 방식 (팀 합의)

스키마는 **원천(ERD) 하나와 그것을 만들어 가는 마이그레이션들**로 관리한다.
| 파일 | 역할 |
| --- | --- |
| `docs/ddubi_erd_v2.sql` | 원천(source of truth). 팀·분산 담당과 공유하는 ERD_master의 코드판. **최종 목표 스키마**를 담는다 |
| `backend/src/main/resources/db/migration/V1__init.sql` | 최초 스키마. **더 이상 고치지 않는다** |
| `V2__`, `V3__`, ... | 이후 변경은 전부 여기에 쌓는다 |

### 스키마를 바꿀 때 — 새 마이그레이션을 쌓는다 (2026-09-08 변경)

**`V1__init.sql` 을 직접 고치지 않는다.** 새 파일을 만든다.

```
V4__add_something.sql
ALTER TABLE ... ADD ...;
```

그리고 **`docs/ddubi_erd_v2.sql` 도 함께 고친다** — 최종 목표 스키마를 담는 문서이므로,
새 인덱스·제약이 거기에도 반영돼야 한다. 두 파일이 **글자 단위로 같지는 않지만**
(V1 은 초기 상태, ERD 는 최종 상태), **ERD 에 있는 것은 마이그레이션 어딘가에 반드시 있어야 한다.**

> **왜 바뀌었나**: 예전에는 "V1 을 직접 고치고 DB 를 날린다" 였다. 그러면 스키마를 고칠
> 때마다 **팀 전원이 로컬 DB 를 날려야** 하고(2026-09-08 하루에만 두 번 발생), 서버에
> 데이터가 쌓이기 시작하면 아예 쓸 수 없다.
>
> 실제로 이 규약을 그대로 따라 V1 을 고쳤다가, PM 이 V2·V3 로 옮겨 놓은 것을 모르고
> 되돌리려다 `Duplicate key name` 으로 마이그레이션을 깨뜨릴 뻔했다. 규약이 현실보다
> 뒤처지면 그걸 믿은 사람이 사고를 낸다.

`FlywayValidateException`(checksum mismatch)이 나면 **이미 적용된 마이그레이션 파일을
고친 것**이다. 그 파일을 되돌리고 새 버전으로 만든다.

로컬 DB 를 새로 만들어야 할 때:

```bash
docker compose -f backend/docker-compose.dev.yml down -v
docker compose -f backend/docker-compose.dev.yml up -d
```

기동 시 `FlywayValidateException`(checksum mismatch)이 나면 **V1을 고쳤는데 DB를 안 날린 것**이다. 위 명령으로 해결한다.

### 팀 공유·서버 배포 시작 후 — V2부터 누적

누군가의 로컬이나 dev 서버에 이미 적용된 뒤부터는 **V1을 절대 수정하지 않는다.** `V2__add_xxx.sql` 형식으로 새 파일을 만들고, `docs/ddubi_erd_v2.sql`에도 같은 변경을 반영한다(원천은 항상 최종 형태를 담는다).

### 잊지 말 것
- `ddl-auto`는 영원히 `validate`다. `update`/`create`로 바꾸지 않는다 — 엔티티가 없는 파이프라인 테이블 3개가 조용히 사라지고 분산 담당과 스키마가 어긋난다.
- `baseline_serving`·`pipeline_config`는 백엔드 소유(internal API 로만 적재), `detection_log` 는 분산 담당 소유다. 분산 쪽 변경 요청 시 마이그레이션은 백엔드가 작성하되 내용은 그쪽 확정을 따른다.

## 4. 도메인 규칙 (DB 제약이 아닌 것은 서비스 계층이 반드시 보장)

1. **소유권 검사**: 모든 사용자 소유 리소스는 조회·변경 전 `user_id == 인증 사용자` 확인. 아니면 `403 FORBIDDEN_RESOURCE`.
2. **장소 스냅샷**: schedule은 place를 FK 참조하지 않고 이름·좌표를 **값 복사**한다. 장소 수정이 기존 일정을 바꾸면 안 된다(REQ-SCH-006).
3. **계정 연동**: 이메일이 계정 키. 소셜 로그인 시 **검증된** 이메일이 기존 user와 일치하면 새 계정을 만들지 않지만 **자동으로 붙이지도 않는다**(2026-09-10 개정). `ACCOUNT_LINK_REQUIRED` 와 일회용 `linkToken`(5분)을 주고, `POST /auth/oauth/link` 에서 **기존 계정 비밀번호로 소유를 증명**해야 `user_auth` 행이 추가된다. 티켓은 성공·실패와 무관하게 한 번 쓰면 폐기 — 살려 두면 비밀번호 대입 창구가 된다.
   - 이유: 자동 통합은 제공자가 이메일 소유를 **잘못 확인한 순간 남의 계정으로 들어가는 경로**다. 이제 비밀번호를 모르면 붙일 수 없다.
   - 이메일 미제공·미검증이면 연동 판단을 하지 않고 별도 계정. **미검증 이메일은 새 계정에도 저장하지 않는다** — 저장하면 `UK_USER_EMAIL` 을 차지해 진짜 주인이 가입하지 못한다.
   - 네이버는 프로필 API 에 검증 플래그가 없어 **검증된 것으로 본다**. 비밀번호 확인이 앞을 막으므로 탈취 위험은 없다.
   - 기존 계정에 LOCAL 인증수단이 없으면(소셜로만 가입) `409 ACCOUNT_LINK_UNAVAILABLE` 로 **거절하고 쓰던 소셜을 안내**한다. `UK_USER_EMAIL` 때문에 새 계정을 만들 수 없고, 이메일 없이 만들면 사용자는 자기 일정이 사라진 빈 계정을 본다.
   - 로컬 가입 시 이미 소셜 계정에 쓰인 이메일이면 `409` + 실제 붙어 있는 제공자 안내.
4. **탈퇴 = 하드 삭제**: `DELETE user` 1건 → FK CASCADE로 전체 삭제. 보관·마스킹 없음. Redis 세션도 전부 폐기. 탈퇴자 관련 추적은 불가(팀 합의).
5. **반복 일정 2단 구조**: `schedule_repeat`(규칙) → `schedule`(인스턴스, `UNIQUE(repeat_id, arrive_by)`). 인스턴스를 개별 수정하면 `is_customized=true`로 세팅하고, 반복 전체 수정(REPEAT-004)은 `is_customized=true` 행을 건너뛴다. 반복 중지는 `is_active=false`(행 삭제 아님), 기존 인스턴스는 유지.
6. **경로 선택 단일성**: 한 schedule에 `route.is_selected=true`는 최대 1건. `PUT .../selection` 트랜잭션에서 나머지를 false로.
7. **추천은 불변 INSERT**: `recommendation`은 절대 UPDATE하지 않는다. 계산 계기는 `trigger_type`, `reasons` JSON에 후보 비교 요약 포함, `data_checked_at` 필수.
   - ~~중요도→백분위 정책 NORMAL=p50 / HIGH=p80 / CRITICAL=p95~~ → **보류** (2026-09-08 ML 연동안 결정).
     여유시간은 중요도와 무관하게 **검증된 공통 안전 여유** 하나를 쓴다. 중요도는 백분위가 아니라
     **감시·재계산·알림 빈도**에만 쓴다(CRITICAL 일수록 자주 다시 계산하고 더 민감하게 알린다).
     이유: ML(`traffic_t03`)이 p50/p80/p95 를 아직 `null` 로 준다. **커버리지 검증 전의 "p95" 는
     이름만 p95** 라, 그 값으로 CRITICAL 일정의 출발 시각을 정하면 정확히 늦으면 안 되는 사람이 늦는다.
   - `baseline_serving` 의 p50/p80/p95 자체는 계속 쓴다 — ML 실패 시 fallback 3단계(구간 기준선)다.
   - ML 백분위 커버리지 검증이 끝나면 이 항목을 되살릴 수 있다. 그래서 지우지 않고 보류로 남긴다.
8. **폴백**: 실시간(Redis) 데이터가 없거나 stale이면 `baseline_serving`으로 계산하고 `confidence=LOW`, 응답에 `fallbackUsed=true`. 아예 계산 불가일 때만 `503 REALTIME_DATA_UNAVAILABLE`.
9. **알림은 무조건 행 생성**: 발송 억제(알림 끔, 중복)여도 `notification` 행은 만들고 `status=SUPPRESSED`. 재발송 방지는 `suppress_key`(일정·이유·행동 해시) + Redis TTL. `read_at IS NULL` 개수 = 안읽음 뱃지. 알림 on/off 는 사용자 토글(`PATCH /users/me` 의 `alarmEnabled`)이며 **서버가 시간대를 정해 자동 억제하는 DND 는 두지 않는다**(2026-09-03 확정) — 껐다 켜는 것은 사용자가 정하고, 서버가 임의로 막으면 새벽 일정의 출발 알림이 조용히 사라진다.
10. **영향 일정 역조회 경로 (F-05)**: 변경 `segment_id` → `route_segment_edge(IX_RSEG_EDGE_SEGMENT)` → `route_segment` → `route(is_selected=true)` → `schedule(status=UPCOMING)`. 별도 schedule_segment 테이블 없음(합의됨).
    - 외부 API 의 이동 step 하나가 **공식 인접구간 여러 개로 전개**되므로 실제 매핑은 `route_segment` 1 : N `route_segment_edge` 다. 실시간 상태도 인접구간 단위로 오므로 역조회 기준은 edge 다.
    - `route_segment.segment_id` 는 **레거시 단일-edge 호환 열**이다. 신규 저장은 `route_segment_edge` 를 단일 진실로 쓰고, 이 열로 역조회하지 않는다.
    - `segment_id` 채번은 `CanonicalSegmentIdFactory` 한 곳에서만 한다: `BUS:{routeId}:{fromNodeId}:{toNodeId}` / `SUB:{subwayId}:{fromNodeId}:{toNodeId}`. 서울 공식 마스터 ID 기반이라 동명 정류장·노선명 변경에 흔들리지 않는다. **문자열을 직접 조립하지 말 것** — Collector·Spark 와의 공용 키다.
11. **일정 삭제(SCH-005)는 soft**: `status=CANCELED`. 물리 삭제는 탈퇴 CASCADE뿐.
12. **updated_at은 서버가 갱신** (`BaseTimeEntity` + JPA Auditing). DDL의 `ON UPDATE CURRENT_TIMESTAMP`는 안전망일 뿐이고, JPA가 명시값을 쓰므로 발동하지 않는다 — 낙관적 잠금 비교값이라 갱신 주체가 하나여야 한다. `raw_response`(외부 API 원본)는 어떤 사용자 응답에도 노출 금지.
13. **예약어 주의**: 컬럼은 `trigger_type`, `rank_no`다. `trigger`, `rank`를 새로 만들지 말 것.

## 5. DB 스키마 (ERD v2 — Flyway V1의 원천)

전체 DDL은 `docs/ddubi_erd_v2.sql`. 테이블 17개 요약:

| 그룹 | 테이블 | 핵심 |
| --- | --- | --- |
| 회원 | `user` | email(UNIQUE, 통합 키, NULL 허용), nickname, mobility_note, notify_lead_min(기본 30), briefing_time, is_alarm, history_consented_at |
| | `user_auth` | user 1:N. provider ENUM(LOCAL,KAKAO,GOOGLE,NAVER — 4종 모두 구현), provider_user_id, password_hash(LOCAL만). UNIQUE(provider,provider_user_id), UNIQUE(user_id,provider) |
| | `user_transport_pref` | mode ENUM(BUS,SUBWAY) × preference ENUM(PREFER,AVOID), UNIQUE(user_id,mode) |
| | `place` | 즐겨찾는 장소. 일정과 FK 관계 없음(스냅샷 원칙) |
| | `device_token` | fcm_token UNIQUE(토큰 기준 upsert, 마지막 로그인 사용자가 주인), platform, is_valid |
| 일정 | `schedule_repeat` | days_of_week CSV("MON,WED"), arrive_time, until_date, is_active |
| | `schedule` | origin/dest 스냅샷, arrive_by, importance ENUM(NORMAL,HIGH,CRITICAL), consider_last_trip, is_customized, status ENUM(UPCOMING,DONE,CANCELED), UNIQUE(repeat_id,arrive_by) |
| 경로·추천 | `route_request` | 외부 API 호출 1회당 1행, raw_response JSON 원본 보관 |
| | `route` | route_request_id FK, base_duration_min, is_selected, rank_no(최신 순위 캐시) |
| | `route_segment` | 외부 API 의 이동 step 단위. seq, mode ENUM(WALK,BUS,SUBWAY), geometry JSON, UNIQUE(route_id,seq). `segment_id` 는 레거시 호환 열 |
| | `route_segment_edge` | **공식 인접구간 목록 — 신규 저장의 단일 진실.** PK(route_segment_id, edge_seq), segment_id VARCHAR(180), mapping_version, policy_version, **INDEX(segment_id, route_segment_id)** ← F-05 역조회 |
| | `recommendation` | depart_at, eta, buffer_min, percentile_used, confidence, reasons JSON, segment_status_snapshot JSON, data_checked_at, policy_version, trigger_type |
| 알림 | `notification` | type ENUM(MORNING_BRIEF, DEPART_SOON, EARLY_DEPART, ROUTE_CHANGE, ON_TIME_UNLIKELY, LAST_TRIP, SCHEDULE_CONFLICT, DATA_STALE), suppress_key, status ENUM(SENT,FAILED,SUPPRESSED), read_at |
| 리포트 | `report` / `report_detail` | 주간 집계 + 일정별 결과(proxy_* 는 미동의자, actual_arrival은 동의자만). P3 — 스키마만 우선 |
| 파이프라인 | `baseline_serving` | **소유: 백엔드** (Spark 가 internal API 로 업로드). segment_id×dow(1~7)×hour_slot(0~23)×weather_bucket(DRY,RAIN,SNOW)×data_version UNIQUE. p50/p80/p95 **단위: 초** |
| | `detection_log` | **소유: 분산 담당.** 이상 감지 vs 공식 공지 선행 검증 |
| | `pipeline_config` | **소유: 백엔드.** key-value. `baseline_active_version`(활성 버전) + `baseline_last_activated_at`(마지막 활성화 시각 — DATA_STALE·confidence 강등 근거) |

FK: user 하위 전부 `ON DELETE CASCADE`, notification의 schedule_id/recommendation_id는 `SET NULL`, schedule.repeat_id는 `SET NULL`.

## 6. API 계약 (명세서 v2.1.1 + ERD v2 확정 수정 반영)

### 6.1 공통
- Base URL `/api/v1`, 인증 `Authorization: Bearer {accessToken}`, 시간은 ISO 8601 KST(`2026-08-28T19:30:00+09:00`), 좌표 WGS84.
- 성공: `{"success":true,"data":{...},"timestamp":"...","requestId":"..."}` / 목록: `data.content[] + page,size,totalElements,totalPages,hasNext` (page 0부터, size 기본 20 최대 100)
- 오류: `{"success":false,"error":{"code":"...","message":"...","fieldErrors":[]},"timestamp":"...","requestId":"..."}` — 코드: 400 INVALID_REQUEST, 401 UNAUTHORIZED, 403 FORBIDDEN_RESOURCE, 404 *_NOT_FOUND, 409 DUPLICATE_RESOURCE/INVALID_STATE/STALE_RECOMMENDATION, 422 ROUTE_NOT_AVAILABLE/OUT_OF_SERVICE_AREA, 429 RATE_LIMITED, 503 REALTIME_DATA_UNAVAILABLE
- 전역 `@RestControllerAdvice` + requestId(X-Request-Id 없으면 서버 생성) 필수.
- 멱등: `POST /schedules`, `/schedule-repeats`, `/schedules/{id}/recommendations`, `/schedules/{id}/arrival`, `/internal/recalculations`는 `Idempotency-Key` 헤더 지원(같은 사용자·경로·키 24h 내 재요청 → 최초 응답 반환, Redis TTL 관리).
- 낙관적 잠금: 일정 수정은 요청 `updatedAt` 비교, 불일치 시 409.

### 6.2 엔드포인트 (사용자)
| 그룹 | 엔드포인트 |
| --- | --- |
| 인증 | POST `/auth/local/signup`(201) · `/auth/local/login` · `/auth/oauth` · `/auth/refresh` · `/auth/logout`(204) |
| 사용자 | GET/PATCH/DELETE `/users/me` |
| 기기 | PUT/DELETE `/devices/token` |
| 장소 | GET `/places/search`(외부 검색 프록시, 서울 외 422) · CRUD `/places`, `/places/{id}` |
| 홈 | GET `/home` → nextSchedule + todaySchedules + unreadNotificationCount + dataState |
| 일정 | CRUD `/schedules`(from·to 필수, 최대 366일), GET `/schedules/conflicts`(계산 응답, 저장 없음) |
| 반복 | CRUD `/schedule-repeats` (요청은 daysOfWeek 배열·arriveTime·untilDate — rrule 아님) |
| 추천 | POST `/schedules/{id}/recommendations`(2초 초과 시 202+jobId), GET `.../recommendations/latest`, GET `/recommendations/{id}/explanation` |
| 경로 | GET `/schedules/{id}/routes`, GET `/routes/{id}`, PUT `/schedules/{sid}/routes/{rid}/selection`, GET `/routes/{id}/map`(GeoJSON), GET `/routes/{id}/live`(무상태 계산) |
| 영향·상태 | GET `/schedules/{id}/impacts`, GET `/data-status` |
| 알림 | GET `/notifications`(unreadCount 포함) · GET `/{id}` · PATCH `/{id}/read` · PATCH `/read-all` |
| 도착·기록 | POST `/schedules/{id}/arrival`, GET `/history`, GET `/reports`, `/reports/{id}` (P3, 후순위) |

### 6.3 엔드포인트 (internal — 서비스 계정/관리자 전용, 사용자 JWT 거부)
BASE-001/002 `/internal/baselines`(조회/일괄 적재 — 적재 주체는 Spark 배치, 단위 초), DETECT-001~003 `/internal/detections`, SEG-001 `/internal/segments/states/query`(Redis 일괄 조회), RECALC-001/002 `/internal/recalculations`(202+jobId, 작업 상태는 Redis), OPS/HEALTH `/internal/ops/*`.

### 6.4 ERD v2 반영 메모 (⚠️ 정본은 Notion API 명세서다)

> **정본 우선순위: Notion「DDUBI_APP_API」명세서 v2.1.1 > 이 문서.**
> 아래 항목은 ERD와 맞추려고 적어둔 메모이고, **명세서와 충돌하면 명세서를 따른다.**
> 실제로 2번이 틀린 것으로 확인되어 되돌렸다(2026-08-31). 나머지 항목도 코드로 옮기기 전에
> 명세서 원문을 먼저 확인할 것.

1. 회원가입 = `user` + `user_auth(LOCAL, password_hash)` 생성. password_hash는 user 테이블에 없다. *(ERD 사실 — 명세서와 충돌 없음)*
2. ~~응답의 `user.provider` 단일값 → `providers` 배열~~ → **철회.** 명세서대로 `provider` **단일값**이다. 계정에 LOCAL·KAKAO가 함께 붙어 있어도 **이번 요청에서 인증에 사용한 수단** 하나만 담는다.
3. `GET /users/me` 응답에서 `withdrawnAt` 제거(컬럼 없음).
4. `DELETE /users/me` = 하드 삭제 CASCADE. `ALREADY_WITHDRAWN` 개념 없음.
5. `PATCH /users/me` 지원 필드 확장: nickname, notifyLeadMin(0~180), alarmEnabled, historyConsent, **briefingTime(HH:mm:ss|null), mobilityNote(≤100자), transportPrefs([{mode,preference}])**.
6. 알림 type에 `SCHEDULE_CONFLICT` 포함.
7. FCM 등록은 `UK_DEVICE_FCM` 기반 `INSERT ... ON DUPLICATE KEY UPDATE` upsert.
8. AUTH-003 응답이 두 갈래다: `data.status` = `AUTHENTICATED`(200, 신규 201) 또는 `ACCOUNT_LINK_REQUIRED`(200, linkToken·마스킹 이메일). 연동 확정은 `POST /auth/oauth/link`.
9. ARRIVAL-001의 `actual_arrival` 은 **`user.history_consented_at` 과 요청의 `locationConsent` 가 둘 다 있어야** 저장한다.
   요청 필드는 권한을 주는 스위치가 아니라 이번 건에 대한 거부권이다. 미저장이어도 200 이고 `result` 도 함께 null 이다.
   *(2026-09-04 수정. 이전 문구 "요청 필드는 쓰지 않는다" 는 명세서에 있는 입력을 사문화시켰다 — docs/contracts/api-spec-arrival-report-amendment.md C)*
10. 문서의 "Flink" 표기는 전부 Spark 오기.

## 7. Redis 키 계약 (docs/contracts/redis-keys.yaml로 관리, 분산 담당과 공유)
| 용도 | 키 | 소유 |
| --- | --- | --- |
| 리프레시 세션 | `auth:refresh:{userId}:{deviceId}` (해시+TTL 14d, 회전 시 즉시 교체, 재사용 감지 시 기기 세션 전체 폐기) | 백엔드 |
| 멱등 응답 | `idem:{userId}:{path}:{key}` TTL 24h | 백엔드 |
| 알림 억제 | `ntf:suppress:{suppressKey}` TTL(정책값) | 백엔드 |
| 재계산 분산 락 | `lock:recalc:schedule:{scheduleId}` | 백엔드 |
| 재계산 작업 상태 | `job:recalc:{jobId}` | 백엔드 |
| 구간 최신 상태 | `segment:status:{segmentId}` TTL 300s (분산 담당 소유 — 값 스키마·직렬화는 확정 대기. 어댑터 인터페이스로 격리할 것) | Spark |
| 후보 경로 캐시 | `route:candidates:{originHash}:{destinationHash}` TTL 600s | 백엔드 |
- segment_id 채번은 확정됐다: `BUS:{routeId}:{fromNodeId}:{toNodeId}` / `SUB:{subwayId}:{fromNodeId}:{toNodeId}` (서울 공식 마스터 ID). **문자열 조립은 `CanonicalSegmentIdFactory` 한 곳에서만** 한다 — Collector·Spark 와의 공용 키라 흩어지면 계약이 깨진다.

## 8. 구현 순서 (이 순서로 진행, 각 Phase 완료 시 테스트 + Swagger 갱신)
1. **Phase 0 — 골격**: Maven 단일 모듈(`backend/pom.xml` + `mvnw` 래퍼), 패키지 구조(3.1장), 공통 응답 래퍼·오류 처리·requestId 필터, JPA 설정(Auditing·BaseTimeEntity), Flyway V1(ERD_master), `backend/docker-compose.dev.yml`(MySQL·Redis), Actuator/Swagger.
2. **Phase 1 — 인증·회원**: signup/login/oauth(카카오)/refresh(회전)/logout, users/me CRUD(확장 필드 포함), 탈퇴 CASCADE, device_token upsert. 통합 시나리오 테스트 필수(3·4번 규칙).
3. **Phase 2 — 장소·일정·반복**: place CRUD + 외부 장소검색 프록시, schedule CRUD(스냅샷·소유권·낙관적 잠금·soft cancel), repeat CRUD + 인스턴스 생성 로직(is_customized 규칙), `/home`, `/schedules/conflicts`.
4. **Phase 3 — 경로·추천(핵심)**: 카카오맵 대중교통 클라이언트(route_request 원본 저장) → 후보 분해(route/route_segment, SegmentIdResolver) → 재평가 엔진(Redis 상태 + baseline 폴백 + percentile 정책 + buffer) → recommendation INSERT → latest/explanation/routes/selection/map/live. 2초 초과 202 비동기 경로 포함.
5. **Phase 4 — 알림·재계산**: notification CRUD/읽음, suppress_key 파이프, Kafka 알림 이벤트 발행(스텁 가능), `/internal/recalculations`(역조회 쿼리 → 재계산 → 변화 시 알림), Spring Scheduler(브리핑·출발 임박).
6. **Phase 5 — internal·도착·리포트**: baselines/detections/segments-query internal API, arrival/history/reports(P3), ops 스텁.

## 9. 하지 말 것 / 스코프 제외
- Apple 로그인, 캘린더 연동, 이동 세션(실제 출발·환승 기록), 사용자별 보관기간, 관리자 웹 UI — 전부 스코프 밖.
- ERD 임의 변경 금지(변경 필요 시 이유와 마이그레이션 초안을 제시하고 멈춰서 확인받기).
- 외부 API 키·raw_response·내부 키를 응답/로그에 노출 금지. 비밀값은 전부 환경변수.
- collector/sparkjobs/notification/client/infra 폴더의 코드를 수정하지 않는다(인터페이스 제안은 docs/contracts에 문서로).
- 성능 목표: 단순 조회 p95 500ms, Redis 재평가 2초 내, 초과 시 202 전환.

