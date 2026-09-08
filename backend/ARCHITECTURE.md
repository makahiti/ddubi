# 뚜비 백엔드 — 폴더가 뭘 하는지

> 처음 보는 사람용. "이 코드를 어디에 넣어야 하지?"가 궁금할 때 여기부터 본다.
> 규칙·계약은 `BACKEND_CONVENTION.md`, 이 문서는 **지도**다.

---

## 1. 큰 그림 — 요청 하나가 지나가는 길

앱이 `POST /api/v1/places` (장소 등록)를 보내면 이 순서로 흐른다.

```
프론트 요청
   │
   ▼
[filter]      RequestIdFilter        → 요청마다 추적용 ID 부여 (로그에 찍히는 그 UUID)
              JwtAuthenticationFilter → 토큰 검사. "너 누구야?" 를 여기서 끝낸다
   │
   ▼
[controller]  PlaceController        → URL·HTTP 를 아는 유일한 곳. 받아서 넘기고, 받아서 돌려준다
   │
   ▼
[service]     PlaceService           → 진짜 규칙이 사는 곳. "서울 밖이면 422", "남의 장소면 403"
   │
   ▼
[repository]  PlaceRepository        → DB 읽기/쓰기. SQL 을 아는 유일한 곳
   │
   ▼
[entity]      Place                  → DB 테이블 한 줄을 자바 객체로 표현한 것
   │
   ▼
 MySQL
```

돌아올 때는 역순이고, 나가기 직전에 `ApiResponse` 로 감싸져서
`{"success":true,"data":{...},"requestId":"..."}` 모양이 된다.

**외우는 법**: controller 는 "접수창구", service 는 "심사관", repository 는 "창고지기".
접수창구가 심사를 하기 시작하면(컨트롤러에 if 문이 늘어나면) 잘못 가고 있는 것이다.

---

## 2. 최상위 구조

```
backend/
├── src/main/java/com/ssafy/ddubi/
│   ├── DdubiApplication.java   ← main(). 실행 시작점
│   ├── global/                 ← 모든 도메인이 같이 쓰는 것
│   └── domain/                 ← 기능별 묶음 (auth, user, place, schedule, route ...)
├── src/main/resources/
│   ├── application.yml         ← 설정 전부 (포트, DB 주소, 토큰 수명, 외부 API 주소)
│   ├── application-dev.yml     ← dev 프로파일 전용 (.env 읽기)
│   └── db/migration/V1__init.sql ← DB 스키마. docs/ddubi_erd_v2.sql 과 항상 동일
├── src/test/java/              ← 테스트 (main 과 같은 패키지 구조)
├── http/                       ← IntelliJ 로 실행하는 API 시나리오 (.http)
├── pom.xml                     ← 의존성 목록 (Maven)
└── .env                        ← 비밀값. 커밋 안 됨
```

**global 과 domain 을 가르는 질문**: "이 코드가 특정 기능(장소, 일정...)만의 것인가?"
- 장소 검증 로직 → 장소만의 것 → `domain/place`
- JWT 파싱 → 모든 API 가 쓴다 → `global/security`

---

## 3. global/ — 공용 부품

| 폴더 | 안에 있는 것 | 한 줄 설명 |
|---|---|---|
| `common/` | `ApiResponse`, `PageResponse`, `PageRequestParam` | 모든 응답을 같은 모양으로 감싸는 틀. 목록 페이징 규격 |
| `config/` | `SecurityConfig`, `JpaConfig`, `RedisConfig`, `SwaggerConfig` | 스프링 조립 설명서. "어떤 URL 은 로그인 없이 허용", "DB 시각은 마이크로초로" 같은 전역 결정 |
| `exception/` | `ErrorCode`, `BusinessException`, `GlobalExceptionHandler` | 오류 한 곳 처리. 어디서 예외가 터져도 여기를 거쳐 같은 형식으로 나간다. **서비스에서 `throw new BusinessException(ErrorCode.X)` 만 하면 나머지는 자동** |
| `filter/` | `RequestIdFilter` | 컨트롤러에 닿기 전에 모든 요청이 통과하는 관문 |
| `security/` | `JwtTokenProvider`, `JwtAuthenticationFilter`, `@LoginUserId`, `RevokedAccessTokenStore` | 인증. 토큰 만들고·검사하고·무효화한다. 컨트롤러의 `@LoginUserId Long userId` 가 여기서 온다 |
| `mail/` | `EmailSender`, `SmtpEmailSender`, `LoggingEmailSender`, `MailConfig` | 메일 발송. 키 없으면 로그로 대체되는 게 여기 구현 |
| `entity/` | `BaseTimeEntity` | 모든 테이블 공통 created_at/updated_at. 엔티티들이 상속한다 |
| `util/` | `SeoulArea`, `BaselineDayOfWeek` | 작은 도구. 서울 경계 판정, 요일 변환(Spark 1=일 ↔ Java 1=월) |

---

## 4. domain/ — 기능별 묶음

도메인마다 **같은 5개 폴더**가 반복된다. 새 도메인을 만들 때도 이 틀을 복사하면 된다.

```
domain/place/
├── controller/   PlaceController          ← URL 정의 + 요청/응답 변환. 로직 금지
├── service/      PlaceService             ← 도메인 규칙 전부. @Transactional 이 붙는 곳
├── repository/   PlaceRepository          ← JpaRepository 상속. 메서드 이름이 곧 쿼리
├── entity/       Place                    ← 테이블 ↔ 자바 객체 매핑 (@Entity)
├── dto/
│   ├── request/  PlaceCreateRequest 등    ← 프론트가 보내는 JSON 의 모양 + 검증(@NotBlank...)
│   └── response/ PlaceResponse 등         ← 프론트에게 주는 JSON 의 모양
└── client/       KakaoLocalClient         ← 외부 API 호출 (있는 도메인만)
```

### 왜 entity 를 그대로 응답하지 않고 dto 를 따로 두나

entity 는 DB 사정(비밀번호 해시, 내부 플래그)까지 다 들고 있다. 그대로 내보내면
숨겨야 할 게 새어 나가고, 테이블 구조를 바꿀 때마다 API 가 깨진다.
**entity = DB 와의 약속, dto = 프론트와의 약속.** 둘은 따로 늙는다.

### 지금 있는 도메인

| 도메인 | 담당 API | 특징 |
|---|---|---|
| `auth/` | AUTH-001~007 | 로그인 4종(로컬·카카오·구글·네이버), 토큰, 이메일 인증. `client/` 에 소셜 3종 — 공통 흐름은 `AbstractOAuthClient` 에 있고 제공자별 차이만 하위 클래스 |
| `user/` | USER-001~003, DEVICE-001~002 | 내 정보·탈퇴·FCM 토큰 |
| `place/` | PLACE-001~006 | 즐겨찾기 CRUD + 카카오 장소 검색 프록시 |
| `schedule/` | SCH-001~006, REPEAT-001~005, HOME-001 | 일정·반복·홈·충돌. 반복 규칙(`ScheduleRepeat`)이 인스턴스(`Schedule`)를 찍어내는 2단 구조 |
| `route/` | ROUTE (Phase 3) | 카카오 길찾기 연동. 검색 골격만 있고 추천은 아직 |

---

## 5. 헷갈리기 쉬운 것들

**"검증"이 세 군데 있다 — 각자 역할이 다르다**

| 어디 | 무엇을 | 예 |
|---|---|---|
| dto 의 `@NotBlank`, `@Size` | 값의 **모양** | "이메일 형식인가", "6자리 숫자인가" |
| service 의 if + `BusinessException` | 값의 **의미** (DB 를 봐야 아는 것) | "서울 안인가", "네 장소인가", "이미 가입된 이메일인가" |
| DB 의 UNIQUE / FK | **최후 방어선** | 서비스가 실수해도 데이터가 깨지는 것만은 막는다 |

**Redis 는 언제 쓰나** — "짧게 살다 사라져야 하는 값"만.
리프레시 토큰 세션, 이메일 인증 코드(5분), 무효화 표시. 전부 TTL 로 자동 소멸한다.
영구 보관할 것은 전부 MySQL.

**시각이 두 종류다** — `DATETIME` (일정 시각 등 업무 값) 과 `DATETIME(6)` (created_at/updated_at).
updated_at 은 일정 수정의 동시성 검사 값이라 마이크로초까지 정확히 맞아야 한다.

---

## 6. "이 코드 어디에 넣지?" 결정 규칙

```
새 API 를 만든다        → 해당 domain 에 controller·service·dto 추가
새 도메인 규칙          → service (컨트롤러 아님)
새 쿼리                 → repository 에 메서드 추가
프론트와 주고받을 JSON  → dto/request 또는 dto/response
외부 API 호출           → 해당 domain/client (인터페이스로 갈라 테스트에서 목킹)
새 오류 응답            → global/exception/ErrorCode 에 추가
모든 API 에 적용되는 것 → global (filter, config, exception)
새 설정값               → application.yml 의 ddubi.* + @Value 주입
스키마 변경             → docs/ddubi_erd_v2.sql + V1__init.sql 동시 수정 → DB 재생성
```

## 7. 테스트가 두 종류다

```
src/test/java/.../support/IntegrationTestSupport   ← 통합 테스트의 부모 클래스
```

- **통합 테스트** (`*IntegrationTest`) — `IntegrationTestSupport` 상속. 진짜 MySQL·Redis
  컨테이너를 띄우고(Testcontainers) 서비스를 통째로 돌린다. **도메인 규칙은 전부 이걸로 고정**.
  Docker Desktop 이 켜져 있어야 돈다.
- **단위 테스트** (그 외) — 스프링 없이 클래스 하나만. 빠르다.
  `WeeklyRepeatRuleTest`, `JwtTokenProviderTest`, `MockRestServiceServer` 로 HTTP 만 흉내내는
  `OAuthClientTest` 가 이쪽.

외부에서 인가 코드를 받아야 하는 소셜 로그인 같은 건 통합 테스트에서 클라이언트를
목으로 갈아끼운다 — 검증 대상은 카카오 통신이 아니라 그 뒤의 계정 통합 로직이라서다.
