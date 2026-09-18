# 코드 점검 기록 — 환경 구분·콘솔 출력·컨벤션 (2026-09-18, 기준 커밋 develop 9aecc84)

저장소 전체(backend 326 java, client 49 dart, python 125개, infra 41개 설정)를 네 갈래로 훑고,
심각도 높은 지적은 서버2 실제 설정과 실물 APK로 다시 확인했다. **코드는 고치지 않았다.**

## 3줄 요약

1. **콘솔 출력**: 대체로 깨끗하다. 앱의 `debugPrint` 28건이 릴리스에서도 그대로 찍히는 것 하나가 실질적 결함이다.
2. **개발/운영 구분**: 앱과 파이썬 서비스는 잘 갈라져 있다. 백엔드는 **운영 프로파일이 없고** 기본값이 `dev`라 설계 결함이다. 다만 서버2는 `local`(존재하지 않는 프로파일 = 기본 설정만)로 떠 있어 dev 위험은 **지금은 발동하지 않는다**.
3. **컨벤션**: 한 사람 코드로 읽히지 않는다. 백엔드 3군락, 앱 3군락, 파이썬 2군락. 원인은 공통: 포매터·린터가 CI에 없다.

---

## 1. 개발/운영 환경 구현체 구분

### 1-1. 서버2 실제 상태 (컨테이너 환경변수·HTTP로 확인)

| 항목 | 값 | 판정 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` — `application-local.yml` 없음 → 기본 `application.yml`만 적용 | 이름은 오해 소지, 동작은 안전 |
| `JWT_SECRET`, `MYSQL_PASSWORD` | 환경변수로 설정됨 (리터럴 기본값 미사용) | 양호 |
| `DDUBI_DEV_ENDPOINTS_ENABLED` | `true` → 시험 푸시 API 활성 (인증 필수, 본인에게만 발송) | 의도된 값이면 유지, 문서화됨 |
| `/actuator/env` | 401 | 양호 |
| 6379·3000·9090·9100 | 컨테이너는 0.0.0.0 바인딩이지만 외부에서 filtered | 방화벽이 막고 있음. compose 자체는 열려 있음 |

### 1-2. 백엔드 — 설계 결함 (지금은 미발동)

| 심각도 | 위치 | 문제 | 조치 |
|---|---|---|---|
| 높음 | `application.yml:6`, `infra/docker-compose.yml:369` | 프로파일 기본값 `dev`. 환경변수가 빠지면 운영이 dev로 뜬다 | `application-prod.yml` 신설, 기본값 `prod` |
| 높음 | `application-dev.yml:32` | JWT 서명키 리터럴 기본값이 저장소에 있음 | 기본값 제거, dev도 `${JWT_SECRET}` 필수 |
| 높음 | `application-dev.yml:50` | actuator `include: "*"` | dev도 화이트리스트 |
| 높음 | `application-dev.yml:42-44` | `DEBUG` + `jdbc.bind: TRACE` → 바인딩 값(이메일 등) 로그 노출 | prod에서 INFO |
| 중간 | `application-dev.yml:38` | 시험 푸시 API 기본 ON | 기본 false |
| 중간 | `SecurityConfig.java:115` | CORS `localhost:*` 하드코딩 (주석은 "하드코딩 금지") | 환경변수로 외부화 |
| 중간 | `infra/docker-compose.yml` Redis/Grafana/Prometheus | 0.0.0.0 바인딩, 방화벽 스크립트는 수동 | `127.0.0.1:` 바인딩 또는 배포 후 방화벽 검증 |
| 중간 | `nginx default.conf.template:109` | Swagger UI·OpenAPI가 443에 항상 공개, try-it-out 활성 | prod에서 springdoc 끄기 |
| 낮음 | 스케줄러 `matchIfMissing=true` 2건 | 기본 ON, 다중 인스턴스 시 중복 실행 | 문서화 또는 리더 선출 |

양호: 코드 내 시크릿 리터럴 0건, TODO/FIXME 0건, `.env`는 gitignore, 이미지에 비밀값 없음, `MlApplyMode`의 TEST_APPLY 허용 플래그 게이트.

### 1-3. 앱 — 릴리스에 남은 개발 경로

| 심각도 | 위치 | 문제 | 조치 |
|---|---|---|---|
| 중간 | `screens.dart:7858` | 개발자 옵션 카드가 릴리스에서도 무조건 노출 → 누구나 진단 토글 가능 | `kDebugMode` 또는 숨은 제스처 뒤로 |
| 중간 | `app_api.dart:254`, `screens.dart:8358` | 세션 없을 때 `id:'debug'` 가짜 프로필 반환 → 계정 비밀번호 재확인이 `local1234`로 통과 | 세션 없음은 예외로, 우회 분기는 `kDebugMode` |
| 중간 | `screens.dart:5988` | 경로 지도에 "Kakao Map · Mock 좌표" 배지와 고정 좌표 | 서버 좌표로 교체 전까지 미제공 상태 표시 |
| 중간 | `more_page_widgets.dart:37,219,515` | 스탬프 미션 데모 데이터(5개 달성)가 기본값 | 서버 데이터 없으면 빈 상태 |
| 중간 | `app_providers.dart:24,31,79,99` | Mock 전환이 `USE_SCHEDULE_API` 하나에 의존, `kReleaseMode` 가드 없음 | 릴리스에서 false면 빌드 실패 |
| 낮음 | `api_constants.dart:6` | `API_BASE_URL` 기본값 `http://localhost` | 미주입 시 즉시 실패 |
| 낮음 | `client/.env` | 백엔드 전용 `NAVER_CLIENT_SECRET`이 앱 .env에 있음. **실물 APK 검색 결과 값은 포함되지 않음**(참조 안 하는 define은 제거됨) | 그래도 앱 .env에서 제거 |
| 낮음 | `Dockerfile.web:8`, `.gitlab-ci.yml:241,250` | 없는 파일 COPY(빌드 불가, 미사용), CI 수동 빌드 잡에 dart-define 없음 | 삭제 또는 수정 |

정정: 점검 도구가 "R8이 돌지 않는다"고 했으나 **틀렸다**. Flutter는 릴리스에서 기본으로 R8을 켠다. 실물 빌드에 `mapping.txt`(17.9MB)가 생성됐고, 오늘 새벽 고친 카카오 지도 크래시가 바로 R8 클래스 제거 문제였다.

### 1-4. 파이썬 서비스 — 양호

- 환경 분기(`ENV`/`DEBUG`) 코드 0건, mock은 테스트 디렉터리에만, 필수 설정 미주입 시 기동 거부.
- 잔여: `ml-serving/app.py:60-70` 번들 경로가 개발용 상대경로로 되돌아감(compose가 명시해 미발동), `collector/archiver` Dockerfile이 root 실행, `collector/src/config.py:25` `APP_ENV`는 읽기만 하고 안 쓰는 죽은 설정.
- **높음**: `.gitlab-ci.yml`에 ml-serving 시험·빌드 잡이 없다(이미 알려진 사항).

---

## 2. 콘솔 출력

| 영역 | 건수 | 판정 |
|---|---|---|
| 앱 `print` | 0 | 양호 (`avoid_print` 린트 동작) |
| 앱 `debugPrint` + `debugPrintStack` | 18 + 10 = 28, 게이트 0건 | **결함**: 릴리스 logcat에 그대로 찍힘. 43%가 `device_token_service.dart`. 토큰·개인정보 직접 출력은 없음 |
| 백엔드 `System.out` | 2 (`MetropolitanBusReplay` 명령줄 도구) | 운영 경로 아님, 위치만 부적절(src/main) |
| 백엔드 `printStackTrace` | 0 | 양호 |
| 백엔드 민감 로그 | `LoggingEmailSender:23-32`가 수신자 주소와 **인증 코드 본문**을 INFO로 출력(메일 자격증명 없을 때만) | 중간 |
| 파이썬 `print` | 21건 전부 CLI 도구/`main()` 출력 | 양호. `[실패]` 3건이 stderr 아닌 stdout |
| ml-serving 요청 로그 | 건수·시간·결과만, 페이로드·노선 ID 없음, 회귀 시험 있음 | 양호 |
| sparkjobs | 12개 잡 중 9개가 로깅 초기화 없음 → `log.info` 소실 | 중간 |
| 웹 `console.log` | 0 | 양호 |
| 인프라 | Redis 비밀번호가 커맨드라인 인자(`--requirepass`)로 노출, nginx 기본 로그 포맷이 쿼리스트링 기록 | 중간 |

---

## 3. 코드 컨벤션

**공통 원인**: 포매터·린터 강제가 없다. 백엔드 checkstyle/spotless 없음, 앱 `analysis_options.yaml`의 rules 비어 있음(`dart format` 미적용 18/73 파일), 파이썬 `pyproject.toml`/ruff 없음, CI 린트 잡 없음.

| 영역 | 군락 | 뚜렷한 차이 |
|---|---|---|
| 백엔드 | A `auth/user/schedule/global` · B `recommendation/*` · C `route/mapping` | 와일드카드 import 44파일(B·C 집중, A 0건), 한 줄 다중 문장(C 최대 34줄), 완전수식 어노테이션(B), `@DisplayName` 28/89 파일만 사용 |
| 앱 | A 진단·평가 계열 · B `more_page_widgets` · C `screens.dart` 본문 | `screens.dart` 9,696줄에 클래스 100개, `on Object catch` 44 vs 베어 `catch` 7(지도 코드), 매직 컬러 vs 테마, 주석 한/영 188:34 |
| 파이썬 | A ml-serving·`road_impact`·`bus_crosswalk` (작은따옴표·조밀·타입힌트 3%) · B collector·archiver·sparkjobs 나머지 (큰따옴표·타입힌트·`from __future__`) | 같은 디렉터리 안에서도 파일 단위로 갈림. `bus_crosswalk.py`가 두 서비스에 각각 구현돼 정책 불일치 위험 |

잘 지켜진 것: 식별자 영어·사용자 문구 한국어, 앱 `Key` 160개 네이밍 일관, 백엔드 테스트 메서드 camelCase 631개 일관, 들여쓰기(백엔드 4칸, 앱 2칸, 파이썬 4칸, YAML 2칸) 전 영역 일관.

---

## 권장 순서

1. **앱 `debugPrint` 28건** → `kDebugMode` 래퍼 하나로 감싼다. 반나절.
2. **백엔드 `application-prod.yml` 신설 + 기본값 `prod`** → 높음 4건이 함께 해결된다. 서버2 `.env`의 `local`도 `prod`로.
3. **앱 릴리스 개발 경로 4건**(개발자 옵션 카드, `debug` 가짜 프로필, Mock 좌표, 데모 미션).
4. **포매터/린터 CI 게이트**: spotless(백엔드), `dart format --set-exit-if-changed` + lint rules(앱), ruff(파이썬). 컨벤션은 규칙 문서보다 기계 강제가 답이다.
5. ml-serving CI 잡, sparkjobs 공용 로깅 초기화, `LoggingEmailSender` 본문 DEBUG 강등.
