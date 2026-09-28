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
CSV가 UTF-8 편집기에서 깨져 보이더라도 UTF-8로 다시 저장하지 않는다. 변환기는 공식 원본의
CP949 인코딩을 직접 읽으며, 필수 열·시각·운행 순서·마스터 인접성을 검증한다.

```powershell
$publishedAt=(Get-Date -Format o)
python backend/tools/transit-timing/build_timetable.py `
  --csv 'C:/Users/SSAFY/Desktop/경로/지하철_보강검증_20260910/raw/seoul-timetable-20260616.csv' `
  --master backend/transit-master/subway/metropolitan-subway-master.json.gz `
  --service-date 2026-09-11 --day-type DAY `
  --published-at $publishedAt `
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

원본 CSV와 생성 JSON은 Git에 넣지 않는다. 로컬 산출물은 gitignored인
`backend/target/transit-timing/timetable.json`에 두고, 운영에서는 읽기 전용
`TRANSIT_MASTER_DATA_DIR` 안에 배치한다. JSON 하나는 지정한 운행일 00:00부터 다음날 06:00까지 유효하므로
운행일이 바뀌면 새 산출물을 만들고 현재 구현에서는 Backend를 재시작해야 한다.

Backend 실행 환경에 아래 두 값을 지정하면 파일을 기동 시 읽는다.

```text
DDUBI_SUBWAY_TIMING_TIMETABLE_PATH=C:/Users/SSAFY/Desktop/S15P21E202/backend/target/transit-timing/timetable.json
DDUBI_TRANSIT_MAPPING_SUBWAY_MASTER_RESOURCE=classpath:transit/metropolitan-subway-master.json.gz
```

Docker Compose 운영 환경은 `TRANSIT_MASTER_DATA_DIR`를 `/opt/ddubi/transit-master`에 읽기 전용으로 마운트한다.
예를 들어 호스트의 `${TRANSIT_MASTER_DATA_DIR}/subway/timetable.json`에 배치했다면 다음처럼 지정한다.

```text
DDUBI_SUBWAY_TIMING_TIMETABLE_PATH=/opt/ddubi/transit-master/subway/timetable.json
DDUBI_SUBWAY_TIMING_DECOMPOSITION_VERIFIED=false
```

대기시간 합산 설정은 켜지 않는다. 원천 열차 대응이 없으면 `SUBWAY_TIMETABLE`로 표시하고 Redis를 조회하지 않는다.
원본·날짜가 바뀌면 재생성 및 Backend 재시작이 필요하다. 생성물은 gitignored `target`에 두며 운영 자동 배포는 하지 않는다.

## 3. 정적 운행과 실시간 열차 Crosswalk 검증

위에서 고정한 `$publishedAt`을 재사용한다. Crosswalk는 기본 시간표의 버전을 참조하므로,
적용 시간표를 다시 만들 때도 날짜·요일·출처를 포함해 같은 인자를 사용해야 한다.

먼저 Archiver가 저장한 `transport.raw` Parquet에서 실시간 도착·위치 관측을
`docs/contracts/subway-train-observations.schema.json` 형식으로 내보낸다.

```powershell
python backend/tools/transit-timing/export_train_observations.py `
  --archive 'file:///opt/ddubi/archive' `
  --service-date 2026-09-11 `
  --output backend/target/transit-timing/train-observations.json
```

HDFS라면 `--archive 'webhdfs://hdfs-namenode:9870/ddubi/archive' --hdfs-user <사용자>`처럼
지정한다. 아카이브 루트 대신 `transport.raw` 디렉터리나 단일 Parquet 파일도 받을 수 있다.
실행 환경에는 Archiver와 같은 `pyarrow`, `fsspec` 패키지가 필요하다.
도구는 운행일과 다음날 파티션을 읽되, 운행일 00:00부터 다음날 06:00 사이 관측만 남긴다.
아카이버 재시작으로 중복된 `eventId`는 제거하고, 같은 ID의 내용이 다르면 그 ID 전체를 제외한다.

`matchedAt`은 수집시각이 아니라 해당 열차가 그 역에 도착했거나 있었다고 판단한 절대시각이다.
도착 이벤트는 원본 `recptnDt + barvlDt`를 사용한다. 위치 이벤트는 서울 API에서 역 도착을 뜻하는
검증된 `trainSttus=1`만 사용하고 원본 `recptnDt`를 넣는다. 제공시각이 없어 Collector 수신시각으로
대체된 이벤트와 진입·출발 상태는 증거에서 제외한다. 다른 제공자의 상태 코드를 추가하려면 의미를
공식 문서로 확인한 뒤 `--position-at-station-code`를 명시한다. 제외 사유와 건수는
`train-observations.report.json`에서 확인한다.

상태 코드 근거: [서울시 지하철 실시간 열차 위치정보 API](https://data.seoul.go.kr/dataList/datasetView.do?infId=OA-12601&serviceKind=1&srvType=A)

그다음 생성한 관측 파일로 Crosswalk를 만든다.

```powershell
python backend/tools/transit-timing/build_train_crosswalk.py `
  --timetable backend/target/transit-timing/timetable.json `
  --observations backend/target/transit-timing/train-observations.json `
  --generated-at (Get-Date -Format o) `
  --output backend/target/transit-timing/train-crosswalk.json
```

도구는 노선·역 순서·시각 이동량과 도착/위치 양쪽 식별값을 비교한다. 조건을 통과한 정적
후보가 하나일 때만 `EXACT`, 둘 이상이면 `AMBIGUOUS`, 증거 부족이나 후보 없음은 `UNMAPPED`다.
결과의 `.report.json`에서 상태·실패 사유와 `exactCoverage`를 검토한다. 실제 시간표에는
`EXACT`만 적용된다.

```powershell
python backend/tools/transit-timing/build_timetable.py `
  --csv 'backend/transit-master/subway/raw/서울교통공사_서울 도시철도 열차운행시각표.csv' `
  --master backend/transit-master/subway/metropolitan-subway-master.json.gz `
  --service-date 2026-09-11 --day-type DAY `
  --published-at $publishedAt `
  --source-url https://www.data.go.kr/data/15098251/fileData.do `
  --train-crosswalk backend/target/transit-timing/train-crosswalk.json `
  --output backend/target/transit-timing/timetable.json
```

버전·마스터·운행일이 다르거나 한 실시간 열차가 여러 정적 운행에 `EXACT`로 연결되면 전체
적용을 실패시킨다. 정적 열차코드와 실시간 열차번호가 같다는 이유만으로 연결하지 않는다.
상세 계약은 `docs/contracts/subway-train-crosswalk.md`에 있다.

## 4. 테스트

```powershell
python -m unittest discover -s backend/tools/transit-timing -p 'test_*.py' -v
```

실제 생성 파일을 Backend 로더와 시간표 계산기에 통과시키는 선택 실행 테스트:

```powershell
./backend/mvnw.cmd -q -f backend/pom.xml `
  '-Dtest=SubwayTimingServiceTest#generatedArtifactLoadsAndCalculatesTimetableWaitAndRide' `
  '-Dtransit.timetable.path=C:/workspace/S15P21E202/backend/target/transit-timing/timetable.json' test
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
