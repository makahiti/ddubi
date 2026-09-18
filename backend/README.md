# Backend

Java·Spring Boot 사용자 API 서비스 자리입니다.

빌드 도구는 **Maven**(단일 모듈)입니다. Spring Boot 3.x / Java 17+, 패키지명은 `com.ssafy.ddubi`.

빌드·실행은 래퍼로만 합니다.

```bash
cd backend && ./mvnw spring-boot:run
```

## 로컬 DB

MySQL 8 + Redis만 띄우는 개발 전용 compose가 있습니다. (배포용 전체 구성은 `infra/`)

```bash
docker compose -f backend/docker-compose.dev.yml up -d
```

## DB 스키마

Flyway가 `src/main/resources/db/migration/V1__init.sql`을 적용하며, 원천은
`docs/ddubi_erd_v2.sql`입니다. **두 파일은 항상 동일해야 합니다.**

개발 초기(Phase 3까지)에는 `V2__`를 쌓지 않고 **V1을 직접 고친 뒤 DB를 날립니다.**
기동 시 checksum mismatch 오류가 나면 이걸 안 한 것입니다.

```bash
docker compose -f backend/docker-compose.dev.yml down -v && docker compose -f backend/docker-compose.dev.yml up -d
```

팀 공유·서버 배포가 시작된 뒤부터는 V1을 수정하지 않고 `V2__`부터 누적합니다.
자세한 규칙은 `BACKEND_CONVENTION.md` 3.2장.

## 프로파일 (2026-09-18)

프로파일을 지정하지 않으면 **운영(`prod`)** 으로 뜬다. 개발 설정은 `SPRING_PROFILES_ACTIVE=dev`
를 명시해야만 켜진다.

| 항목 | `application.yml`(공통) | `application-dev.yml` | `application-prod.yml` |
|---|---|---|---|
| JWT 서명키 | `${JWT_SECRET:}` | `${JWT_SECRET:}` | `${JWT_SECRET:}` |
| 개발용 알림 API | 꺼짐 | 기본 꺼짐 | 기본 꺼짐 |
| Actuator 공개 | health, info, prometheus | + metrics | health, info, prometheus (`show-details: never`) |
| 로그 수준 | 패턴만 | DEBUG + jdbc.bind TRACE | INFO, hibernate WARN |
| Swagger / OpenAPI | 켜짐 | 켜짐 | 꺼짐 |

비밀값은 어느 프로파일에서도 환경변수로만 받는다. 저장소에 기본값을 두지 않는다.
설정이 이 표에서 벗어나면 `ProdProfileConfigTest` 가 실패한다.

## 코드 컨벤션

`spotless:apply` 로 포맷을 맞춘다(google-java-format AOSP). CI 의 `backend-lint` 는
develop 대비 바뀐 파일만 검사하므로 기존 코드를 한 번에 재포맷하지 않아도 새 코드부터 강제된다.
와일드카드 import 는 `config/checkstyle.xml` 로 금지한다.
