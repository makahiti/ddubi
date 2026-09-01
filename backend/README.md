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
자세한 규칙은 `CLAUDE.md` 3.2장.
