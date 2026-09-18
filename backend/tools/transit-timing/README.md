# 공식 시간표 생성과 실제 응답 Replay

현재 자료에서 **지하철 대기·이동시간을 별도로 계산**한다. 카카오 총시간의 잔여 구성은 미확정이므로
두 `decomposition-verified` 설정은 false로 유지한다. 실시간 API 키/열차 대응표를 갖춘 운영 검증은 별도다.
수집 주기, Redis TTL, DB 스키마, 인프라 설정은 이 작업에서 변경하지 않는다.

## 1. 확보한 카카오 응답 감사

저장소 루트 PowerShell에서 실행한다. Python 표준 라이브러리만 필요하다.

```powershell
python backend/tools/transit-timing/audit_kakao_times.py `
  --responses 'C:/Users/SSAFY/Desktop/경로' `
  --output backend/target/transit-timing/kakao-audit.json
```

번호.json만 읽고 후보별 전체 초·구간 합·잔여 초와 원문 SHA-256을 기록한다.
이 도구는 잔여가 0이어도 대기 제외를 인증하지 않는다.

## 2. 공식 CSV를 날짜별 시간표로 변환

입력은 공식 서울교통공사 CP949 시간표와 **실제 매핑에 사용하는 같은 버전의** 수도권 마스터다.
마스터 공급 파일은 기존 `backend/tools/subway-master/README.md` 절차로 준비한다.
실시간 역 ID가 없어도 정적 역 ID가 확인되면 시간표 계산이 가능하다.

```powershell
python backend/tools/transit-timing/build_timetable.py `
  --csv 'C:/Users/SSAFY/Desktop/경로/지하철_보강검증_20260910/raw/seoul-timetable-20260616.csv' `
  --master backend/transit-master/subway/metropolitan-subway-master.json.gz `
  --service-date 2026-09-11 --day-type DAY `
  --published-at (Get-Date -Format o) `
  --source-url https://www.data.go.kr/data/15098251/fileData.do `
  --output backend/target/transit-timing/timetable.json
```

날짜/요일은 예시다. 검증할 실제 운행일을 지정하고 공휴일이면 END를 명시한다.
DAY=평일, SAT=토요일, END=일요일/공휴일이며 자동 공휴일 판단은 하지 않는다.
`published-at`은 해당 입력을 확보한 시각이다. 재생 시각 이후 자료는 계산에 쓰지 않는다.
24·25시를 다음날 날짜로 전개하고 첫 역 도착/끝 역 출발 결측은 null로 유지한다.
같은 역을 재방문하는 순환선은 시간순으로 보존한다. 실시간 열차번호는 추측하지 않아 모두 null이다.

출력의 `.report.json`에는 채택/격리 운행 수, 격리 key와 이유, 원본/산출물 해시가 있다.
현재 변환기는 일반열차만 지원한다. 급행을 일반열차로 바꾸거나 통과역 시간을 보간하지 않는다.
적용 날짜를 지정하는 것은 원본 개정판의 현재 유효성을 검증하는 행위가 아니다.
부분 커버리지·원천 최신성 미확인·수동 달력 선택은 `limitations` 및 응답 `estimate.reasons`에 남는다.

Backend 실행 환경에 아래 두 값을 지정하면 파일을 기동 시 읽는다.

```text
DDUBI_SUBWAY_TIMING_TIMETABLE_PATH=C:/Users/SSAFY/Desktop/S15P21E202/backend/target/transit-timing/timetable.json
DDUBI_TRANSIT_MAPPING_SUBWAY_MASTER_RESOURCE=classpath:transit/metropolitan-subway-master.json.gz
```

대기시간 합산 설정은 켜지 않는다. 원천 열차 대응이 없으면 `SUBWAY_TIMETABLE`로 표시하고 Redis를 조회하지 않는다.
원본·날짜가 바뀌면 재생성 및 Backend 재시작이 필요하다. 생성물은 gitignored `target`에 두며 운영 자동 배포는 하지 않는다.

### 2.1 산출물 분리·manifest·GTFS 입력 (2026-09-18)

`--output timetable.json` 기준으로 아래 파일이 함께 생긴다. 같은 입력·마스터면 `artifactSha256`이 같다(재현 확인).

| 파일 | 내용 |
|---|---|
| `timetable.report.json` | 채택/격리 운행 수, 사유별 수, `dayTypesPresent`/`missingDayTypes`, 입력·마스터·산출물 SHA-256 |
| `timetable.manifest.json` | 도구 버전, 생성 시각, 입력 파일별 SHA-256, 마스터 SHA-256·버전, 산출물 SHA-256, `serverReady=false`(사람 확인 전 연결 금지) |
| `timetable.quarantine.unmapped.json` | 마스터에 없는(또는 여러 provider ID 에 대응되는) 역이 있는 운행 |
| `timetable.quarantine.master-mismatch.json` | 마스터 인접 순서·노선 대응이 어긋난 운행 |
| `timetable.quarantine.duplicates.json` | 같은 역·같은 시각 행이 반복된 운행 |
| `timetable.quarantine.time-reversal.json` | 시각 역전·중간 결측·시각 형식 오류 운행 |
| `timetable.quarantine.express.json` | 급행(미지원) 운행 |
| `timetable.quarantine.other.json` | 위 분류에 없는 사유(등록 누락 방지) |
| `timetable.missing-service-days.json` | 요청한 운행일 종류와 입력에 없는 종류. 요청 종류가 입력에 없으면 변환 자체를 멈춘다(`NO_ROWS_FOR_DAY_TYPE`) |

GTFS 입력은 `--csv` 대신 `--gtfs <폴더>`를 준다. `gtfs_timetable.py`가 결정적 규칙(route_id `RR_ACC1_S-1-<호선>-<패턴>[DUIO]`,
stop_id `RS_ACC1_S-1-<역사코드>`)으로만 대응하고, 규칙 밖 노선·역은 `timetable.gtfs-report.json`에 미매핑으로 남긴다(이름 유사도 사용 안 함).
달력이 요일을 구분하지 않으면(`dayTypeDistinguishable=false`) 산출물을 만들지 않고 종료 코드 2로 멈춘다.
`--allow-undistinguished-day-type`으로 강제 전개하면 `SUBWAY_GTFS_CALENDAR_WITHOUT_DAY_TYPE` 제한사항이 붙으며 서버 연결 대상이 아니다.

```powershell
python backend/tools/transit-timing/build_timetable.py `
  --gtfs 'C:/Users/SSAFY/Downloads/대중교통GTFS(2025년 기준)/202503_GTFS_DataSet' `
  --master backend/transit-master/subway/metropolitan-subway-master.json.gz `
  --service-date 2026-09-18 --day-type DAY --published-at (Get-Date -Format o) `
  --source-url 'https://www.ktdb.go.kr/ 대중교통GTFS(2025년 기준)' `
  --output backend/target/transit-timing/gtfs/timetable.json
```

공식 원천(2026-09-18 확인): 공공데이터포털 15098251 「서울교통공사_서울 도시철도 열차운행시각표_20260616」(https://www.data.go.kr/data/15098251/fileData.do, 열: 호선·역사코드·역사명·방향·도착시간·출발시간 등, 영업일 기준 23시 초과 표기).
서울열린데이터광장 OA-15492(첫차·막차)·OA-110/OA-108(역코드 막차 API)은 전체 시각표가 아니라 보조 검증용이다.
KTDB 국가 GTFS(2025 배포, 2024년 기준)는 달력이 매일 동일한 service 하나라 운행일 종류 근거가 없고 개정판보다 오래되어 서버용 시간표로 쓸 수 없다.

## 3. 테스트

```powershell
python -m unittest discover -s backend/tools/transit-timing -p 'test_*.py' -v
```

Java 17, Maven, 실행 중인 Docker가 필요하다. 아래는 저장소 루트 실행 예시다.
`mvn`이 없으면 기존 `backend/mvnw.cmd` 또는 설치된 Maven 경로를 사용한다.

```powershell
mvn -q -f backend/pom.xml `
  '-Dtest=SubwayTimingServiceTest,MlRoutePreviewTest,BusWaitingServiceTest,RouteTimelineEvaluatorTest,ScheduleEvaluationIntegrationTest' test

mvn -q -f backend/pom.xml '-Dtest=CapturedTransitFlowIntegrationTest' `
  '-Dtransit.replay.enabled=true' `
  '-Dtransit.replay.timetable=C:/Users/SSAFY/Desktop/S15P21E202/backend/target/transit-timing/timetable.json' `
  '-Dtransit.replay.master=classpath:transit/metropolitan-subway-master.json.gz' `
  '-Dtransit.replay.bus-master=C:/Users/SSAFY/Desktop/S15P21E202/backend/transit-master/bus/metropolitan-bus-v6.json.gz' `
  '-Dtransit.replay.responses=C:/Users/SSAFY/Desktop/경로' test
```

Replay는 현재 이후의 날짜로 생성한 시간표가 필요하다. 자동 CI는 외부 파일이 없으므로 기본 실행에서 제외된다.
테스트는 격리 MySQL/Redis에 Spring 관리 서비스로 버스 마스터를 적재한다. 운영 DB를 사용하지 않는다.
실제 마스터 INSERT가 약 20 MiB이므로 로컬 테스트 컨테이너의 `max_allowed_packet`은 64 MiB다.
외부 CI/운영 DB의 해당 설정은 인프라 담당자가 확인해야 하며 이 도구가 변경하지 않는다.
20개 캡처의 288개 후보를 실제 Mapper/Resolver/평가기로 재생하고 최소 도착 후보를 검증한다.
첫 캡처는 JWT 인증 → 일정 등록 HTTP → 평가 → DB 저장 → latest HTTP 응답 보존 → 캐시 재사용까지 검사한다.

결과는 `backend/target/transit-timing/flow-report.json`, 화면이 읽을 예시는 `schedule-preview.json`이다.
이 테스트는 카카오 요청을 캡처로 대체한다. 실시간 수집 정상성·실제 ML 성공률·Flutter 화면 렌더링 검증은 아니다.
ML 미설정과 실시간 관측 결측은 실제 fallback으로 처리한다. 운영 모델 성공을 가짜 값으로 만들지 않는다.

## 2026-09-10 검증 결과

- 카카오 20개 파일: 후보 288개, step 729개. 잔여 양수 287개, 0초 1개, 음수 0개, 최대 1,809초.
- 공식 CSV 424,264행 중 DAY 160,136행/4,811운행. 4,290운행 채택, 521운행 격리(급행 511, 시간 역전 9, 중간 시각 결측 1).
- 2026-09-11 DAY 09:00 출발 Replay: 지하철 248개 step 중 111개에서 대기/이동시간 계산. 나머지는 명시적 fallback.
- 날짜와 원본 개정판이 달라지면 수치가 달라질 수 있다. 수도권 전체 시간표 지원으로 해석하지 않는다.

후속 필요 자료: 대기/접근/환승 분해 근거, 시간표 개정 유효기간·공휴일 달력, 검증된 실시간 열차 crosswalk,
서울교통공사 CSV 범위 밖 운영사 및 급행 운행표. 지하철 역 대응표만으로 열차 crosswalk를 대신할 수 없다.

근거: [카카오 공식 REST 규격](https://developers.kakao.com/docs/ko/kakaomap/rest-api),
[서울교통공사 시간표와 시각/요일 코드 설명](https://www.data.go.kr/data/15098251/fileData.do).
