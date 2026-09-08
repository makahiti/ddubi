# [백엔드 → 인프라] backend CI 잡 요구사항

작성: 백엔드(서비스) 손정민
대상: `.gitlab-ci.yml` — 현재 `backend/**` 잡이 없습니다(파일 하단 "후속 작업" 항목)

---

## 확정 — dind + Testcontainers (2026-09-04)

**백엔드 통합 테스트는 MySQL 8 + Redis 7 이 떠 있어야 돕니다.**

H2 같은 인메모리 DB 로 대체하지 않습니다. 검증 대상이 MySQL 의 실제 동작이기 때문입니다 —
`ON DELETE CASCADE`(탈퇴 시 하위 전체 삭제), `ENUM`, `UNIQUE` 의 NULL 중복 허용,
`DATETIME` 정밀도. H2 로 바꾸면 통과하는데 운영에서 깨집니다.

인프라 확인 결과 **러너가 privileged 를 지원**하므로, 테스트가 컨테이너를 직접 띄우는
Testcontainers 방식(dind)으로 갑니다. DB 를 `services:` 로 따로 붙여줄 필요가 없습니다.

### 왜 이쪽인가

`services:` 로 붙이면 **시간대·문자셋 설정이 CI YAML 에 살게 됩니다.** 누가
`--default-time-zone=+09:00` 을 빠뜨리면 시각 비교 테스트가 9시간 밀려 실패하는데,
원인이 백엔드 코드가 아니라 CI 설정이라 찾기 어렵습니다.

dind 면 그 설정이 테스트 코드 안(`IntegrationTestSupport`)에 있어 어긋날 수 없고,
**로컬과 CI 가 같은 경로로 돕니다.**

---

## 잡 정의

```yaml
backend-test:
  stage: test
  image: eclipse-temurin:17-jdk        # 우리는 Java 17 고정
  services:
    - name: docker:27-dind
      alias: docker
  variables:
    DOCKER_TLS_CERTDIR: ""
    DOCKER_HOST: "tcp://docker:2375"
    # Testcontainers 가 띄운 컨테이너는 dind 안에 있다.
    # 이게 없으면 테스트가 localhost 로 붙으려다 전부 연결 실패한다
    TESTCONTAINERS_HOST_OVERRIDE: "docker"
    # 정리 컨테이너(ryuk)는 dind 에서 자주 막힌다.
    # 잡이 끝나면 dind 가 통째로 사라지므로 없어도 된다
    TESTCONTAINERS_RYUK_DISABLED: "true"
  cache:
    key:
      files: [backend/pom.xml]
    paths: [.m2/repository]
  script:
    - cd backend && ./mvnw -B -Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository test
  rules:
    - changes:
        - backend/**/*
        - docs/ddubi_erd_v2.sql          # 스키마 원천이 바뀌면 마이그레이션 검증이 필요합니다
```

### 각 항목이 왜 필요한가

- **`TESTCONTAINERS_HOST_OVERRIDE`** — dind 에서 가장 흔한 함정입니다. 빠뜨리면 테스트가
  컨테이너를 못 찾아 **전부** 실패합니다.
- **`eclipse-temurin:17-jdk`** — 팀 표준이 Java 17 입니다. 21 로 돌리면 로컬과 다른 결과가 날 수 있습니다.
- **Maven 캐시** — 의존성이 꽤 큽니다(Spring Boot, firebase-admin, Apache POI).
  캐시 없이는 매 실행마다 수백 MB 를 받습니다.
- **`docs/ddubi_erd_v2.sql` 도 트리거** — 이 파일과 `V1__init.sql` 은 항상 같아야 하는데,
  한쪽만 고치는 사고가 실제로 났습니다(2026-09-03 develop 통합). 스키마 원천이 바뀌면
  테스트를 돌려야 합니다.

> MySQL 시간대(`+09:00`)와 `utf8mb4` 는 `IntegrationTestSupport` 가 컨테이너를 띄울 때
> 직접 지정하므로 CI 쪽에서 신경 쓸 것이 없습니다.

### 대안 — 러너가 바뀌어 privileged 를 못 쓰게 되면

백엔드 코드가 이미 대비해 뒀습니다. `CI_MYSQL_HOST` / `CI_REDIS_HOST` 환경변수가 들어오면
Testcontainers 대신 그 주소에 붙습니다. 그때는 `services:` 로 아래를 붙여주시면 됩니다.

```yaml
  services:
    - name: mysql:8.0
      alias: mysql
      command: ["--character-set-server=utf8mb4",
                "--collation-server=utf8mb4_0900_ai_ci",
                "--default-time-zone=+09:00"]
    - name: redis:7-alpine
      alias: redis
  variables:
    MYSQL_DATABASE: ddubi
    MYSQL_USER: ddubi
    MYSQL_PASSWORD: ddubi
    MYSQL_ROOT_PASSWORD: root
    CI_MYSQL_HOST: mysql
    CI_REDIS_HOST: redis
```

### 소요 시간 예상

전체 193건 중 통합 테스트가 다수라 **7~10분** 정도 봅니다. 컨테이너 기동과 Flyway 마이그레이션이
대부분입니다. 너무 길면 단위 테스트만 MR 파이프라인에 두고 통합은 `be/dev` 병합 시로 미루는 안도 있습니다.

---

## 비밀값 — 필요 없습니다

**백엔드 CI 에는 어떤 키도 주입하지 않아도 됩니다.** 테스트는 외부 서비스를 부르지 않습니다.

| 키 | CI 에서 |
| --- | --- |
| `KAKAO_*`, `GOOGLE_*`, `NAVER_*` | 소셜 클라이언트를 목으로 대체합니다 |
| `MAIL_*` | 자격증명이 없으면 로그 발송기로 떨어집니다 |
| `FIREBASE_*` | 마찬가지로 로그 발송기 |
| `JWT_SECRET` | 테스트 프로파일에 고정값이 있습니다 |

키가 없을 때 로그로 대체되는 구조를 일부러 그렇게 만들었습니다. CI 에 비밀값을 넣으면
포크 MR 에서 새어 나갈 위험만 생깁니다.

---

## 이미지 빌드는 아직 넣지 마세요

`backend/Dockerfile` 이 아직 없습니다. `infra/` 의 serving 프로필이 어떻게 빌드하는지
확인한 뒤 맞춰 만들겠습니다. 그때 다시 요청드리겠습니다.

---

## 요약

1. 위 **잡 정의를 그대로** 넣어주시면 됩니다. 백엔드 쪽 추가 작업은 없습니다
2. `TESTCONTAINERS_HOST_OVERRIDE: "docker"` 를 빠뜨리지 말아 주세요 — 없으면 전부 실패합니다
3. 비밀값 주입은 필요 없습니다
4. 이미지 빌드는 `backend/Dockerfile` 이 생긴 뒤 따로 요청드리겠습니다
