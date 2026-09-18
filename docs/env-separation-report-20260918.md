# 개발·운영 분리 / 임시 콘솔 출력 제거 / 코드 컨벤션 — 완료 보고 (2026-09-18)

기준: develop `28c87aa` / master `18a5c83` (앱 코드는 검증에 쓴 APK 빌드 커밋 `8af12d0` 과 동일). 점검 기록은 [code-audit-20260918.md](code-audit-20260918.md).

## 3줄 요약

1. 앱은 `AppEnvironment` 한 곳에서 개발/운영을 판정하고, 운영 빌드에서 개발자 옵션·가짜 사용자·`local1234`·Mock 데이터가 닫힌다. 백엔드는 `application-prod.yml` 을 신설하고 **기본 기동을 운영**으로 바꿨다.
2. 운영 소스의 임시 콘솔 출력은 0건이다(앱 28건 → `AppLog`, 백엔드 `System.out` 2건 → 로거). CI 가 다시 들어오는 것을 막는다.
3. 컨벤션은 규칙 문서 대신 도구로 고정했다(`.editorconfig`, Spotless/Checkstyle, Ruff, dart format). 앱 코드만 이번에 재포맷(별도 커밋)했고, 백엔드·파이썬 전체 재포맷은 팀 브랜치 병합 시점에 맞춰 남겼다.

---

## 1. 개발/운영 환경 구분

### 1-1. 앱 — 설정과 실제 차이

판정 근거는 빌드 시 `--dart-define=DDUBI_ENV=dev|prod` 하나다(`client/lib/core/config/app_environment.dart`).
지정이 없으면 **릴리스 = prod, 디버그 = dev** 다.

| 항목 | 개발(dev) | 운영(prod) | 결정 위치 |
|---|---|---|---|
| API 주소 | `API_BASE_URL`(기본 `http://localhost:8080/api/v1`) | `https://j15e202.p.ssafy.io/api/v1` 고정. `https://` 가 아닌 `API_BASE_URL` 은 무시 | `AppEnvironment.apiBaseUrl` |
| 더보기 > 개발자 옵션 카드 | 보임 | **카드 자체를 그리지 않음** | `screens.dart` (`AppEnvironment.allowDeveloperTools`) |
| 개발자 진단 기본값 | 켜짐 | 꺼짐(`DDUBI_DEVELOPER_DIAGNOSTICS=true` 로만) | `developer_diagnostics.dart` |
| `localadmin/local1234` 로그인 | 통과 | **차단** | `screens.dart` (`allowFakeAuth`) |
| 세션 없을 때 사용자 프로필 | 가짜 `debug` 사용자 | **오류로 올려 "불러오지 못했어요"** | `app_api.dart` |
| 계정 비밀번호 재확인 `local1234` 우회 | 가능 | **차단** | `screens.dart` (`isLocalPreview`) |
| Mock 저장소(`USE_SCHEDULE_API=false`) | Mock 사용 | **기동 시 즉시 멈춤**(가짜 데이터 fallback 금지) | `app_providers.dart` |
| 스탬프 데모 미션 | 데모 5개 | **빈 목록 + 빈 상태 카드** | `more_page_widgets.dart` |

시험: `test/app_environment_test.dart` — prod 면 세 기능 모두 꺼짐, 모르는 값은 운영으로 보지 않음.

### 1-2. 백엔드 — 프로파일별 실제 차이

| 항목 | `application.yml`(공통) | `application-dev.yml` | `application-prod.yml`(신설) |
|---|---|---|---|
| 기본 프로파일 | `${SPRING_PROFILES_ACTIVE:prod}` (이전 `dev`) | — | — |
| JWT 서명키 | `${JWT_SECRET:}` | `${JWT_SECRET:}` (이전: 저장소 공개 리터럴) | `${JWT_SECRET:}` |
| 개발 API(시험 푸시) | 키 없음 → 꺼짐 | 기본 **꺼짐**(이전 켜짐) | `${DDUBI_DEV_ENDPOINTS_ENABLED:false}` |
| Actuator 공개 | health, info, prometheus | health, info, prometheus, metrics (이전 `*`) | health, info, prometheus + show-details never |
| 로그 수준 | 패턴만 | DEBUG + jdbc.bind TRACE | INFO, hibernate WARN |
| Swagger / OpenAPI | 켜짐 | 켜짐 | **꺼짐** |
| 비밀값 | 환경변수만 | 환경변수만 | 환경변수만(기본값 없음) |

`infra/docker-compose.yml`·`infra/.env.example` 기본값도 `prod`. 시험: `ProdProfileConfigTest` (설정 파일만 읽음, 3건).

### 1-3. 서버2

- 배포 전 상태: 컨테이너 프로파일 `local`(존재하지 않는 프로파일 → 공통 설정만 적용). 비밀값은 환경변수로 주입되어 있었고 `/actuator/env` 401, 개발 API 401(인증 필요).
- `.env` 백업: `infra/.env.bak-20260918-prodprofile`. `.env` 의 `SPRING_PROFILES_ACTIVE` 는 `prod` 로 바꿈.
- 재배포: **CI 통과 뒤에만**. 결과는 6절.

---

## 2. 임시 콘솔 출력

### 2-1. 처리

| 영역 | 이전 | 조치 |
|---|---|---|
| 앱 | `debugPrint` 18 + `debugPrintStack` 10 = 28건, 릴리스에서도 출력 | `AppLog.debug/stack` 으로 교체. `kDebugMode` 확인은 `AppLog` 한 곳. 메시지는 그대로(토큰·개인정보 없음) |
| 백엔드 | `System.out` 2건(오프라인 매핑 도구) | SLF4J `log.info` |
| 백엔드 민감 로그 | 메일 대체 발송이 수신자 주소·**인증 코드 본문**을 INFO 로 출력 | 수신자 마스킹, 본문은 DEBUG 전용 |
| 파이썬 | 런타임 경로 `print` 0건(21건 전부 `tools/`·CLI `main()`) | 변경 없음. CI 검사는 런타임 경로만 본다 |
| 웹 | `console.log` 0건 | — |

### 2-2. 재유입 방지

- 앱 시험 `test/logging_convention_test.dart`: `lib/` 에서 `debugPrint`·`debugPrintStack`·`print` 직접 호출이 있으면 실패.
- CI `forbidden-output-check`(validate 단계, 필수): 앱·백엔드·파이썬 런타임·웹을 grep. 현재 코드에서 0건.

---

## 3. 코드 컨벤션

| 언어 | 도구·설정 | CI | 기존 위반 처리 |
|---|---|---|---|
| 공통 | `.editorconfig` — java/py 4칸·120자, dart 2칸·80자 | — | — |
| Dart | `dart format` | `client-test` 에 `--set-exit-if-changed` **필수** | **27개 파일 재포맷, 별도 커밋 `18a0378`** |
| Java | `backend/pom.xml` Spotless(google-java-format AOSP, develop 대비 바뀐 파일만 검사) + Checkstyle(`config/checkstyle.xml`, 와일드카드 import 금지) | `backend-lint` — 러너 첫 실행 확인 전까지 경고 | 와일드카드 import 44파일 미정리(팀 브랜치 병합 후) |
| Python | 루트 `pyproject.toml` Ruff(큰따옴표, 120자, E/W/F/I/B/UP) | `python-lint` — 경고 | `ruff check` 414건(224 자동수정 가능), `ruff format --check` 109/130 파일 재포맷 대상 — 미정리 |

---

## 4. 검증 결과

- Flutter: `flutter analyze` 경고 0건, `dart format --set-exit-if-changed lib test` 통과, `flutter test` **191건 통과**.
- 백엔드: 서버2(도커 있는 환경)에서 전체 시험 실행 — **81개 클래스 604건 통과, 실패 0 · 오류 0 · 건너뜀 7, 14분 34초, BUILD SUCCESS**.
  로그: 서버2 `ddubi-stage3-20260917/verify-20260918/backend-full-test-53c9344.log`. `ProdProfileConfigTest` 3건 포함.
- 금지 출력문 검색(운영 소스): 앱 0 / 백엔드 0 / 파이썬 런타임 0 / 웹 0.
- CI(GitLab): `backend-test`·`client-test`·`forbidden-output-check`·`structure-check` **전부 통과**(6절).
- 릴리스 APK 차단 시험: 5절.

## 5. 릴리스 APK 에서 개발 기능 차단 (실기기)

APK: `release/ddubi-release-8af12d03.apk` (develop 앱 코드와 동일), 서명은 기존 배포본과 같은 DDUBI 팀 인증서.
기기 R39M30GQ43T 에 설치 후 확인.

| 확인 항목 | 결과 |
|---|---|
| 더보기 > 개발자 옵션 카드 | **보이지 않음**(도움말과 로그아웃 사이에 있던 카드가 사라짐) |
| 스탬프 도장판 | 데모 미션 없이 안내 문구만 표시 |
| 가짜 인증(`local1234`) | **차단됨**(아래 근거) |

개발자 옵션 카드가 사라졌다는 것은 이 빌드에서 `AppEnvironment.isProduction` 이 참이라는 뜻이다.
`allowDeveloperTools`·`allowFakeAuth`·`allowMockData` 는 모두 같은 값(`!isProduction`)에서 갈리므로,
개발자 옵션이 닫혔다면 가짜 인증과 Mock 데이터도 함께 닫혀 있다. 판정 규칙 자체는 `app_environment_test.dart` 가 검증한다.

로그인된 사용자의 계정을 건드리지 않기 위해 로그아웃 후 로그인 시도는 하지 않았다.

## 6. CI·재배포·롤백

### CI — 전부 통과 (2026-09-18 11:42 KST)

| 잡 | 결과 | 비고 |
|---|---|---|
| `backend-test` | 통과 | Testcontainers MySQL·Redis 로 전체 통합 시험 |
| `client-test` | 통과 | `dart format` 검사 + `flutter analyze` + 시험 191건 |
| `forbidden-output-check` | 통과 | 신규 잡. 앱·백엔드·파이썬 런타임·웹 금지 출력문 0건 |
| `structure-check` | 통과 | — |

진행 중 확인한 사항:

- 중간에 두 번 CI 설정 자체의 결함으로 파이프라인이 멈췄고 둘 다 고쳤다.
  1. `backend-lint` 안내 문구의 "콜론+공백" 때문에 GitLab 이 스크립트 줄을 매핑으로 읽어 파이프라인이 생성 단계에서 실패(`d61721a` 에서 수정).
  2. `forbidden-output-check` 의 웹 검사가 alpine 에 git 이 없을 때 빈 목록을 위반으로 읽음(`git` 설치 + 빈 목록 건너뛰기로 수정).
- `rules: changes` 때문에 CI 설정만 바뀐 푸시에서는 앱·서버 검사가 건너뛰어진다. 그래서 백엔드 검증은 서버2에서 직접 전체 시험을 돌려 확보했다(4절).

### 재배포
**하지 않았다.** 서버2 backend 는 이전 이미지·이전 설정으로 계속 돌고 있다.

- 서버2 `.env` 백업: `infra/.env.bak-20260918-prodprofile` (4,993 bytes)
- `.env` 의 `SPRING_PROFILES_ACTIVE` 만 `prod` 로 바꿔 두었다. 재배포 전까지 실행 중인 컨테이너에는 영향이 없다(컨테이너 환경변수는 여전히 `local`).

### 롤백
재배포를 하게 되면 되돌리는 방법:

```bash
cp -a infra/.env.bak-20260918-prodprofile infra/.env
docker tag ddubi/backend:pre-grace-20260918 ddubi/backend:0.1.0
cd infra && docker compose -f docker-compose.yml -f docker-compose.server2.yml   --profile serving up -d --no-deps --no-build --force-recreate backend
```

앱은 이전 APK(`release/ddubi-release-9aecc841.apk` 등)를 다시 설치하면 된다.

## 7. 변경 규모

커밋(작업 브랜치 → fe/dev → fe/main → develop, MR 없음):

| 커밋 | 내용 |
|---|---|
| `e655ce9` | 앱 로그 `AppLog` 통합 + 금지 호출 시험 |
| `94b1e5e` | 앱 `AppEnvironment` 도입, 운영 빌드 개발 기능 차단 |
| `a888697` | 백엔드 운영 프로파일 신설, 기본 기동 운영, 개발용 기본값 제거, 콘솔 출력·민감 로그 정리 |
| `59ad5e6` | `.env.example` 프로파일 기본값 |
| `18a0378` | **앱 포맷 정리(별도 커밋, 동작 변경 없음)** |
| `34bdebf` | 포매터·린터 설정과 CI 검사 |
| `3c301cf`, `d61721a` | CI 잡 조정(경고 단계, YAML 오류 수정) |
| `6aad5a9` | 앱·백엔드 README 환경 구분 문서, 점검·완료 보고 |
| (마지막) | 금지 출력문 검사의 웹 검사 수정 |

앱 소스 변경 파일 수: 포맷 정리 커밋 27개 파일(동작 변경 없음) + 기능 변경 8개 파일.
백엔드: 설정 3개 + 소스 2개 + 시험 1개. 설정/CI: 5개.

다른 작업자의 미커밋 파일(`backend/tools/transit-timing/*`, `infra/.env.example` 지하철 항목)은 포함하지 않았다.
