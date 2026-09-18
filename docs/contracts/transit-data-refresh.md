# 교통 마스터·ML 참조 갱신 및 소비 계약

2026-09-17, S15P21E202-74. 이 문서는 원천 → 변환 → API 소비 → 배포 경계를 기록한다.
파일 보유, 정적 매핑 성공, 실시간 연결, ML 운영 승인은 각각 별개의 상태다.

## 1. 반영 위치와 운영 상태

| 대상 | 이번 결과 | 운영 반영 |
|---|---|---|
| 버스 마스터·공통 crosswalk | `METRO-BUS-20260917-SEOUL-0902`, 출처별 노선 1,586개, 정류장 순번 107,185개, edge 105,588개 | **운영 DB 활성화 및 양 서버 파일·Collector/Spark SHA 적용 완료** |
| 지하철 마스터 | 기존 `metro-20260910-b192e5734f46814a` 유지. 19개 호선, 429개 패턴 | 기존 운영 마스터 설정 확인. 신규 교체 없음 |
| 버스 ML 모델 | `20260908-v12`, 모델·인코더·특성식 유지 | 기존 운영 모델 유지. 재학습 아님 |
| 버스 ML 추론 참조 | 지원 운행일 2026-09-10~2026-10-10 | **운영 ML 서버 교체 및 실제 HTTP 검증 완료** |
| BE 검색 ML 어댑터·대안 평가·캐시 재매핑·진단 API | 구현 및 관련 테스트 | 로컬 변경. BE 배포 전 |
| ML readiness 날짜 검사·로컬 묶음 포인터 | 구현 및 테스트 | 로컬 변경. 운영 앱 코드는 교체하지 않음 |
| 지하철 시간표 | 9월 17일 검토용 4,290 trips 생성 | 최신 운행 유효성 미확인, 운영 미활성 |
| 도로 영향 | 새 표준링크 대응 감사, live 잡의 검증 통제 입력 연결 | 검증된 운영 도로 대응표 0개. 자동 통제 제외 완료 아님 |

FE, 저장소의 infra/Docker/CI 파일은 변경하지 않았다. 운영 ML의 `ML_BUNDLE_DIR`을 갱신한 뒤,
사용자의 운영 전체 적재 요청으로 양 서버 마스터 파일과 데이터 SHA 환경값도 갱신했다.
버스 수집기 2개와 Spark realtime을 재기동했으며 기존 호출 예산·checkpoint·Kafka/HDFS 원본은 보존했다.
운영 ML 상태는 `READY_FOR_PREVIEW`, `production_ready=false`다.

### 운영 적재 검증 (2026-09-17 17:57~18:00 KST)

- 서비스 서버 `j15e202.p.ssafy.io`: 배포 중인 앱 JAR의 `MetropolitanBusMasterService`를
  별도 Spring 트랜잭션 관리 프로세스에서 실행했다. 웹·스케줄러·알림 소비자는 추가 기동하지 않았다.
  활성 DB를 새 버전으로 전환한 뒤 서비스로 다시 읽어 노선 1,586개·순번 107,185개를 확인했다.
  기존 Backend는 기동 상태를 유지하며 기존 주기적 catalog 갱신을 사용한다.
- 데이터 서버 `j15e202a.p.ssafy.io`: 같은 crosswalk SHA를 버스 도착/위치 수집기에 적용했고,
  기존 인덱스 캐시 구현 확인 후 Spark의 비활성 crosswalk 설정도 활성화했다.
  기동된 main/Silver 스트림 및 Spark 작업 성공 16개·진행 2개·실패 0개를 확인했다.
  두 수집기와 Spark 재시작 횟수 0, OOM/Traceback 0이었다.
- Redis 버스 최신값 제한 표본 1,097개 중 새 버전 773개:
  `CONFIRMED/NONE` 757, `UNRESOLVED/PROVIDER_CROSSWALK_NOT_FOUND` 13,
  `AMBIGUOUS/MULTIPLE_INCOMING_EDGES` 3. 나머지 324개는 전환 전 `CROSSWALK_NOT_CONFIGURED` 값이었다.
  새 값의 최신 수집시각은 17:59:05 KST였다. 과거 값을 삭제해 매핑률을 높이지 않는다.
  이는 표본/검증 시각의 결과이며 전체 노선 커버리지 또는 현재 신선도 보장이 아니다.
- Backend와 ML 컨테이너 healthy, 지하철 master SHA는 양 서버에서 기존 검증본과 같았다.
- 양 서버의 `~/ddubi-data-release/20260917/backup`에 기존 마스터·환경·컨테이너 설정을
  복구용으로 보존했다. 환경/설정 사본은 mode 0600 또는 디렉터리 0700으로 보호한다.
  DB의 이전 버전도 삭제하지 않았다. 관리 실행 임시 DB 환경 파일은 완료 후 제거했다.

이 작업은 **데이터 적재/설정 전환**이다. 로컬 BE 변경 코드와 ML readiness 코드의 운영 배포는
별도이며 아직 하지 않았다. 다음 정식 배포에는 이 마스터 변경과 같은 SHA 환경값을 함께 반영해야 한다.

## 2. 버스 원천과 변환

원본은 바탕화면 `DDUBI_ALL_LINK_DATA_20260903/OA-1095/서울시버스노선별정류소정보(20260902).xlsx`.
출처는 [서울시 버스노선별 정류소 정보 OA-1095](https://data.seoul.go.kr/dataList/OA-1095/S/1/datasetView.do), 기준일은 파일의 2026-09-02다.
수집일과 실제 운행 유효기간이 같다고 가정하지 않는다.

| 원본 열/위치 | 저장 값 | 검증·의미 |
|---|---|---|
| `ROUTE_ID`, 두 번째 열 노선명 | `namespace=SEOUL_BUS`, `routeId`, `routeName` | ID 문자열 보존. GBIS/인천의 같은 번호 노선과 자동 통합 금지 |
| 세 번째 열 순번 | `stops[].sequence` | 1 이상 정수, 노선 내 중복 금지. 누락 순번 재번호 금지 |
| `NODE_ID` | `stops[].stopId` | 공식 정류장 ID. ARS와 구분 |
| `ARS_ID` | `mobileCode` | 안내용 5자리 값. canonical ID나 ML 정류장 ID로 사용하지 않음 |
| 여섯 번째 열 정류소명 | `stopName` | 표시/후보 탐색용. 이름만으로 확정하지 않음 |
| 일곱·여덟 번째 열 X/Y | `longitude`, `latitude` | 경도/위도 순서, 한국 영역 범위 검사 |
| 원천 미제공 | `direction=null`, `boardingAllowed=null` | 임의 상·하행 또는 승차 가능 판정 금지 |

`backend/tools/update_seoul_bus_master.py`는 기존 공식 서울 710개 노선의 ID/순번 열이
동일한지 확인한 뒤 빠져 있던 8개만 보충한다. 기존 1,578개 출처별 노선과 수동 검토
만료일은 보존한다. 기존 노선의 topology가 달라지거나 원천에서 사라지면 중단한다.

추가 노선: `0017/100100124`, `9401/100100389`, `2016/100100522`,
`673/100100551`, `705/100100587`, `4211/100100604`, `9401-1/113000004`, `8332/124000040`.
새 노선에도 누락 순번이 있다. 공통 crosswalk는 실제 연속 순번 사이만 만들며 구멍을
직접 연결하지 않는다. 새 묶음 전체의 순번 단절은 11개다.

| 파일 | SHA-256 |
|---|---|
| 원본 XLSX | `b54816e9b7e77bcf49dd7562b993db06a093daa666351f115ea4d1dd0dd8b1df` |
| `backend/transit-master/bus/metropolitan-bus-v6.json.gz` | `6999b0e67eb1af391de563291ce9cf4251b5ec271307e3f24de55d22d17318e2` |
| `backend/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz` | `8014da5e1453d426789fa396622a0f0e4069ac484db76860348af517adc1f246` |

파일명의 v6는 기존 설정 경로 호환용이며 본문 version이 실제 버전이다.
원천·기존 파일 SHA·노선별 누락 순번은 같은 디렉터리의 `.json.sources.json`에 있다.
변경 전 마스터는 그 파일의 `baseSha256`과 일치하는 것을 사용한다.

```powershell
# Python 3.12 + openpyxl. BASE.gz는 source 기록의 baseSha256과 대조한 변경 전 파일.
python backend/tools/update_seoul_bus_master.py --base BASE.gz --source 'C:/Users/SSAFY/Desktop/DDUBI_ALL_LINK_DATA_20260903/OA-1095/서울시버스노선별정류소정보(20260902).xlsx' --output data/master-refresh/new.json.gz --version METRO-BUS-20260917-SEOUL-0902
python backend/transit-master/manage.py check
```

운영 활성화는 BE의 기존 `MetropolitanBusMasterService` 관리 적재를 사용한다.
먼저 BE master와 Collector/Spark crosswalk의 **같은 버전·SHA 조합**을 준비하고,
관리 적재 완료 후 import 설정을 끈다. 서비스 DB에 별도 스크립트로 직접 쓰지 않는다.
구체 환경값은 [공통 배포 안내](../../backend/transit-master/distribution.md)를 따른다.

## 3. 카카오 매핑 → 대안 선택 → ML

1. 기존 카카오 파서는 BUS/SUBWAY/WALK 및 차량 목록·정류장·형상을 보존한다.
2. 마스터 매핑기는 출처별 노선, 양 끝 정류장, 공식 순번, 중간 경유 순서와 형상을 대조한다.
3. 버스 한 step에 여러 검증 대안이 있으면 `BusAlternativeExpansion`이 각각 완전한 여정으로
   펼친다. `CONFIRMED + VERIFIED` 및 `BusCanonicalPath.select`의 검증을 모두 통과해야 한다.
   다른 노선의 빠른 edge를 섞어 하나의 경로로 만들지 않는다.
4. 원본 후보당 최대 32개 조합을 평가한다. 초과 시 `BUS_ALTERNATIVE_COMBINATIONS_EXCEEDED`로
   원본 fallback을 유지하고 탐색이 완전하다고 표시하지 않는다. 미확인 선택지도 별도 fallback으로 남긴다.
5. 선택한 버스의 canonical edge 목록으로 ML·통제·시간 계산을 수행한다. 도보 증거,
   평가 응답, 저장되는 차량 표시는 같은 선택을 가리킨다. 원본 vehicles는 재매핑용으로 보존한다.
6. 내부 후보 key는 원본 순번과 선택된 edge의 ID·공식 순번·방향으로 만든다.
   순환 노선에서 같은 정류장 쌍을 다시 지나는 occurrence를 합치지 않는다.
7. 캐시된 카카오 응답도 사용 시 `RouteMappingRefresher`로 현재 resolver를 거친다.
   마스터 변경·수동 반려가 이전 매핑 캐시에 가려지지 않으며 원래 fetchedAt은 연장하지 않는다.

### 리뷰 후 보완: 캐시 전환과 실행 기본값

- 매핑 갱신/대안 전개로 후보가 바뀌면 새 edge와 선택 후보를 저장한다. 이 저장 필요 여부와
  카카오를 실제로 재조회했는지는 별도로 관리한다. 재매핑한 평가에서 통제·지각을 처음 발견하면
  후보 TTL 전이라도 카카오를 한 번 조회하며, 이미 조회했거나 이전 평가도 위험했던 경우에는 반복하지 않는다.
- 기존 선택은 canonical ID·공식 순번·방향까지 일치하는 후보를 우선한다. 일치 후보가 없고
  이전 미매핑 구간이 새로 EXACT가 된 경우, 원본 차량·정류장·형상이 동일한 후보가 단 하나일 때만
  선택을 이어받는다. 기존 확정 edge의 변경/소실, 순환 노선의 순번 변경, 여러 후보로 분기하는
  경우에는 `SELECTED_ROUTE_UNAVAILABLE`을 유지한다. 이 보완은 임의 차량 선택을 허용하지 않는다.
- 사용자 요청에 따라 `infra/docker-compose.yml`의 마스터/대응표 SHA 기본값 두 개도 위 파일과
  일치하도록 수정했다. 운영 환경변수와 별도로 새 체크아웃의 기본 기동도 같은 자료를 검증한다.
  운영 서버 재시작이나 코드 배포는 이 후속 수정에 포함되지 않는다.
- 회귀 검증은 Workflow의 선택 유지·모호성·순환 occurrence·신규 통제 재조회·TTL 재조회와
  압축 파일/manifest/Compose 기본 SHA의 일치를 확인한다. DB 트랜잭션 검증은 별도 통합 테스트 범위다.

`/routes/search`의 `BusPreviewPredictionAdapter`는 `BusPredictionPort`를 실제
`BusPreviewClient`에 연결한다. 요청 단위로 동일 입력을 캐시하고 최대 64개 고유 입력,
5초의 **새 호출 시작 예산**을 사용한다. 마지막 HTTP 호출 시간까지 포함한 전체 5초 보장은 아니다.
인접 edge의 진입시각은 앞 edge의 예측 초를 더해 KST 날짜·시간대로 갱신한다.
검색의 기준시각은 기존 계약대로 fetchedAt이다. 사용자 일정의 미래 출발시각 예측은
`/routes/evaluate` 또는 일정 평가를 사용한다. 검색 수치를 미래 일정 예측으로 표시하지 않는다.
v12가 제공하지 않는 featureVersion/featuresAsOf/validUntil은 null로 보존하며 운영 승인 근거를 만들지 않는다.

ML은 `SEOUL_BUS`만 지원한다. GBIS/인천 ID를 서울 ID라고 바꾸지 않는다.
`history_sample_count=0`이면 숫자가 있어도 `ML_HISTORY_INSUFFICIENT`로 구분한다.
유효 PREVIEW 수치는 `previewEstimatedSeconds`로 제공하되 승인된 `estimatedSeconds`는
기존 API fallback, `predictionReady=false`를 유지한다. PREVIEW는 자동 알림/운영 승인 근거가 아니다.
일정 평가의 기존 PREVIEW 흐름과 검색의 승인 여부를 혼동하지 않는다.

실제 `1~20.json` 288개 후보, 729개 step 재현 결과:

- 버스 281개: EXACT 148, PARTIAL 133. 대안 768개 중 검증 726, 형상 충돌 8, 모호 8, 미해결 26.
- 지하철 248개: 정적 EXACT 248. 실시간 대응 FULL 217, NONE 31.
- WALK 200개는 교통 마스터 매핑 대상이 아니다.
- 입력 SHA 불변, 동일 입력 재실행 결과 동일.
- 새 공식 서울 출처가 추가되면서 기존 GBIS와 겹치는 4개 대안은 검증 상태에서 모호 상태로
  바뀌었다. 성공률을 높이려고 임의 출처를 선택하지 않았다. 정적 EXACT는 실시간 또는 ML 성공을 뜻하지 않는다.

수치와 입력별 SHA: [검증 요약](../../backend/transit-master/validation/update-20260917.json).
같은 20개 응답으로 새 대안 전개도 실행했다. 288개 원본 → 701개 평가 후보,
선택된 BUS step occurrence 608개, 조합 한도로 원본 유지 11개, 미해결 유지 46개였다.
후보 key 중복 없음, 전개 멱등성, 캐시 재매핑 후 동일 결과를 검증했다.
이는 전개 결과 검증이며 701개 후보 전체의 실제 ML HTTP 성공을 뜻하지 않는다.

## 4. ML 참조 데이터의 정확한 출처와 열

원본 ZIP은 `Desktop/DDUBI_ALL_LINK_DATA_20260903` 아래 OA별 디렉터리에서 사용했다. 이번 추가 범위는
2026-07-13~07-19와 07-20~07-26 주간 파일이다. 기존 GPU와 같은 정규화를 검증한 후
추론 참조에 추가했으며 학습 모델이나 특성을 바꾸지 않았다.

| 공식 출처 | 조인 키 | 시간대 원본 → 정규화 | v12 참조 시차 |
|---|---|---|---|
| [OA-21217](https://data.seoul.go.kr/dataList/OA-21217/S/1/datasetView.do) 구간 운행시간 | `기준_날짜`, `노선_ID`, `출발_정류장_ID`, `도착_정류장_ID`, `출발_정류장_순번`(순서 별칭 허용) | `운행시간_0시`~23시 → `h00`~`h23`, 초 | 예측일 77,84,91,98,105,112,119,126일 전 |
| [OA-21218](https://data.seoul.go.kr/dataList/OA-21218/S/1/datasetView.do) 재차인원 | 같은 날짜·노선·양끝 ID, `정류장_순서` | `재차인원합_0시`~23시 → `h00`~`h23` | 77,84,91,98일 전 |
| [OA-21219](https://data.seoul.go.kr/dataList/OA-21219/S/1/datasetView.do) 승차승객 | 날짜·노선·`정류장_ID` | `승차_승객_수_0시`~23시 → `h00`~`h23` | 77일 전 |
| [OA-21220](https://data.seoul.go.kr/dataList/OA-21220/S/1/datasetView.do) 운행횟수 | 날짜·노선·`정류장_ID` | `버스운행횟수_0시`~23시 → `h00`~`h23` | 77일 전 |

ID는 문자열, 순번은 Int32, 시간대 값은 float32로 보존한다. 운행시간 0 또는 86,400초 초과는
결측, 승객·횟수 0은 유효 관측이다. 같은 키의 완전 중복만 합치고 상충 관측은 모두 격리한다.
각 출처에서 기존 GPU 일별 파일과 최소 1일 이상 **전 행·전 열 일치**가 없으면 갱신을 중단한다.
이번 결과는 중첩 12개 source/day 전부 일치, 신규 일별 parquet 39개다.

추가 결측 1개 `aux_daily/OA-21220/2026-07-17.parquet`는 해당 공식 주간 파일에서 날짜 행이
없었다. 이전 결측 10개를 포함해 총 11개를 `missing_files`에 명시했다. 파일을 가짜 0으로
채우지 않는다. 날짜 범위가 지원되더라도 개별 노선의 학습 vocab·이력 표본이 없을 수 있다.

```powershell
ml-serving/.venv/Scripts/python.exe ml-serving/tools/extend_reference_bundle.py --base artifacts/ml-serving/20260908-v12-ref-20260915-through-0930 --raw-root C:/Users/SSAFY/Desktop/DDUBI_ALL_LINK_DATA_20260903 --output artifacts/ml-serving/new-reference --end 2026-10-10 --weeks 2026.07.13-07.19 2026.07.20-07.26
ml-serving/.venv/Scripts/python.exe ml-serving/tools/verify_bundle.py --bundle artifacts/ml-serving/new-reference --golden ml-serving/examples/v12-server-golden.json --output data/ml-reference-check
```

`--raw-root` 바로 아래 OA별 폴더와 해당 주간 ZIP이 있어야 한다. 실행 환경의 실제 원본
중첩 경로는 `reference-update-provenance.json`의 파일명으로 확인한다. 기존 출력 폴더는 거절한다.
모든 원천 ZIP SHA, 날짜별 행 수·상충 수는 생성 묶음의 provenance에 기록된다.

운영 설치 경로: `/home/ubuntu/ddubi-models/20260908-v12-ref-20260917-through-1010`.
전송 ZIP SHA: `b90a463753a277d75b5a9eb306cdf367409e5a61719d10c9790cdcc74e1b123f`.
서버에서 묶음 191개 파일 무결성을 검사한 뒤 기존 런타임으로 격리 검증하고 전환했다.
실제 운영 HTTP의 golden 10건은 기존 예측과 차이 0초, 10월 1일 신규 예측은 PREVIEW/표본 8개였다.
이 검증은 모델 이식 동등성이고 예측 정확도 개선이나 운영 승인 검증이 아니다.

로컬 기본 포인터는 Git 제외 `artifacts/ml-serving/active-bundle.json`의
`{"directory":"20260908-v12-ref-20260917-through-1010-r2"}`다.
명시 `ML_BUNDLE_DIR`이 우선한다. 원본 자료·모델은 Git에 포함하지 않으므로 다른 개발 PC에는
별도로 검증 묶음을 설치해야 한다. 운영의 이전 묶음과 private 환경 백업은 복구용으로 보존했다.

## 5. 실제 준비 상태 확인

인증된 `GET /api/v1/routes/data-status`는 `ApiResponse.data` 안에 다음을 제공한다.

| 필드 | 의미 |
|---|---|
| `checkedAt` | 진단 기준 UTC instant |
| `bus.status/activeVersion/providers` | 실제 메모리 활성 버전, 출처별 노선·정류장 순번 수. Git 파일 버전 아님 |
| `subway.status/activeVersion/lines/patterns/staticStations/realtimeMappedStations` | 활성 정적 자료와 실시간 ID 대응 범위 |
| `subwayTimetable.status` | 미설정 사유, LOADED, NOT_YET_APPLICABLE, EXPIRED, MASTER_VERSION_MISMATCH |
| `subwayTimetable.validFrom/validUntil/trips/realtimeVerifiedTrips/limitations/decompositionVerified` | 파일이 존재하는 것과 유효 운행일·실시간 결합·시간 교체 가능성을 구분 |
| `busMl.status` | CONFIGURED_NOT_PROBED 또는 ML_NOT_CONFIGURED. 연결 성공을 뜻하지 않음 |
| `busMl.supportedNamespaces/productionReady/waitingDecompositionVerified` | 서울만 지원, PREVIEW 및 대기 시간 분해 승인 상태 |

DB 읽기 실패는 `MASTER_LOOKUP_FAILED`로 표시한다. 비밀 URL·파일 경로·사용자 경로는 반환하지 않는다.
ML 연결 여부는 별도로 `/health/ready`와 실제 예측을 검사한다. 새 로컬 ML 코드는
`reference_status`, `reference_days_remaining`, `missing_reference_file_count`를 추가하며
오늘 KST가 지원 범위 밖이면 readiness 503이다. 기존 운영 이미지에는 이 코드 변경이 아직 없다.

## 6. 지하철·도로통제에서 남은 원천 검증

지하철 시간표는 `Desktop/경로/지하철_보강검증_20260910/raw/seoul-timetable-20260616.csv`,
[공공데이터포털 15098251](https://www.data.go.kr/data/15098251/fileData.do)의 다운로드 파일이다.
원천 SHA `405d0cc2957ad0d66d3b7fbce8b1d570e90f29e002de10569f4b578178736404`.
기존 `build_timetable.py`로 날짜·운행일 유형·정적 역 패턴을 대조해 검토용 파일을 만들었다.
급행 511, 시간 역전 9, 중간 시각 누락 1 trips는 제외했다. 실시간 train 대응 검증은 0개다.
운영 timetable path와 bus/subway decomposition 설정은 미설정이었다.
현재 운행 시간표·운휴일 달력·기관별 범위·실시간 train 대응·카카오 시간 분해를 검증한 뒤 활성화해야 한다.
이번에 만든 하루 시간표를 계속 유효한 운영 시간표로 사용하지 않는다.

도로는 `서울시 표준링크 매핑정보_2026년3월 기준.xlsx`의 서비스 링크/표준 링크 11,572쌍을
추가 대조했다. 기존 방향별 버스 도로 후보 24,052개 중 완전 ID 연결은 13,606개다.
**현행 버스 진행방향, AccInfo 링크 namespace 동등성, 완전 통제 여부·적용시간**이 미검증이므로
운영 mapping 0개를 유지한다. 원천 SHA와 부족 근거는
[도로 연결 감사](../../backend/transit-master/validation/road-link-update-20260917.json)에 있다.
새 `audit_standard_links.py`의 숫자는 ID 연결률이며 통제 영향 정확도가 아니다.

Spark live 잡은 이제 검증된 `--blockages` snapshot을 읽어 기존 evaluate에 전달한다.
잘못된 파일은 배치를 실패시켜 정상 상태로 덮지 않는다. 원시 사고 코드나 집회 문자열을
`confirmed=true`로 변환하는 코드는 만들지 않았다. 세부 입력은 [도로 영향 계약](road-impact.md)을 따른다.

## 7. 검증 범위와 다음 배포 조건

- BE: 대안 선택·순환 occurrence, 계산/저장 edge 일치, 캐시 재매핑, ML 어댑터/결측, 도보 key,
  시간표·대기·통제·조건부 출발·마스터 매칭 관련 126개 테스트 통과.
  Docker 부재로 DB 통합 테스트 1개는 건너뛰었다. 저장 엔티티 검증을 실제 DB 통합 완료로 해석하지 않는다.
- ML: 전체 49개 테스트 통과, 기존·신규 묶음 및 운영 HTTP golden 10개 차이 0초.
- 데이터 도구: 새 마스터 보충 2개, 시간표 도구 5개 테스트 통과. 공통 manifest 검사 통과.
- Spark: live 통제 입력·구간 상태·버스 crosswalk·도로 영향 30개 통과, 선택적 1개 생략.
  Windows Python 3.12 worker 종료 문제는 별도 Python 3.10 + Spark 3.5.1 환경에서 재검증해 통과했다.

운영 마스터 활성화와 Collector/Spark의 동일 버전 crosswalk 교체는 완료했다. BE 변경 코드 배포는 남아 있다.
추가로 모호한 버스 출처 증거, 지하철 현재 시간표·실시간 대응, 검증된 도로 통제 대응표가 필요하다.
현재 데이터만으로 모든 버스 ML·지하철 실시간·도로통제가 해결됐다고 표시하지 않는다.
