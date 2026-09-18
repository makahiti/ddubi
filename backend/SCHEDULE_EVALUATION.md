# 일정 ML 검증 실행 메모

동작 계약: [schedule-reevaluation.md](../docs/contracts/schedule-reevaluation.md).
모델 계약: [ml-serving-http.md](../docs/contracts/ml-serving-http.md).

기존 MySQL·Redis·카카오 경로 설정과 Flyway V5를 사용한다. 이번 변경은 추가 DB migration이 없다.
검증용 ML은 운영용 BusPredictionPort 승인 조건과 별개이며 출발 변경/알림/운영 recommendation 저장에 사용하지 않는다.

| Spring property | 기본값 | 의미 |
|---|---|---|
| ddubi.recommendation.ml-preview-base-url | 빈 문자열 | Python 서버 주소. 미설정이면 API_ONLY와 ML_NOT_CONFIGURED |
| ddubi.recommendation.ml-preview-timeout-ms | 5000 | HTTP 연결/요청 제한, 1~5000ms |
| ddubi.recommendation.monitor-enabled | true | DB 감시기 실행 |
| ddubi.recommendation.scheduler-delay-ms | 15000 | due 목록 확인 간격(ms) |
| ddubi.recommendation.candidate-ttl-seconds | 900 | 카카오 후보 캐시(초) |
| ddubi.recommendation.kafka-enabled | false | segment.status 소비 활성화 |
| ddubi.subway-timing.timetable-path | 빈 문자열 | 운행일별 공식 시간표·실시간 ID 대응 JSON 경로. 시작 시 고정 |
| ddubi.subway-timing.decomposition-verified | false | 실제 카카오 대기 제외·승강장 접근/환승 포함을 검증한 경우만 true |
| ddubi.bus-waiting.decomposition-verified | false | 버스 대기 제외·접근/환승 시간 분해 검증 후 true. 대기 후 ML 진입시각 사용 |

이전 search-step-seconds/max-advance-seconds/max-evaluations 설정은 이번 검증 흐름에서 사용하지 않는다.
후보 비교는 같은 출발시각 기준이며 조기 출발은 계산하지 않는다. ML 비교의 요청 64회/20초 한도는
MlRoutePreview에 격리되어 있다. 각 edge 순차 호출이므로 운영 적용 전에 호출량/응답시간 측정이 필요하다.

## 로컬 모델 연결

저장소 루트에서 모델이 준비된 Python 환경으로 실행한다. 모델/참조 파일 설치는 ml-serving/README.md를 따른다.

```powershell
& ./ml-serving/.venv/Scripts/python.exe -m uvicorn app:app --app-dir ml-serving --host 127.0.0.1 --port 8000
```

백엔드를 실행할 PowerShell에 아래 환경변수를 설정한다. 서로 다른 터미널에 설정한 값은 공유되지 않는다.

```powershell
$env:DDUBI_RECOMMENDATION_ML_PREVIEW_BASE_URL = 'http://127.0.0.1:8000'
$env:DDUBI_RECOMMENDATION_ML_PREVIEW_TIMEOUT_MS = '5000'
```

별도 컨테이너에서 실행한다면 localhost를 복사하지 말고 해당 네트워크의 실제 모델 서비스 주소를 지정한다.
/health/ready의 READY_FOR_PREVIEW를 확인한 뒤 일정 등록 또는 POST recommendations를 호출한다.
응답 preview.fastest가 최소시간 후보이며 candidates의 mlSteps가 0이면 모델 시간이 적용되지 않은 것이다.
모델 오류/주소 누락도 API 예상시간으로 결과가 나올 수 있으므로 READY만 보고 ML 성공이라고 판단하지 않는다.

## 테스트

버스·지하철 공통 대기는 `preview.waitPredictions`에서 초 단위 값, 출처, 차량, 실제 반영 여부를 확인한다.
버스에는 새 Spark `arrivalTimeBasis=SEOUL_EXPS_SECONDS_V1` 및 상태·순번·제공시각이 필요하다.
기존 traTime 분→도착 초 오독을 수정했으며 새 기준 표시가 없는 값은 사용하지 않는다.
대기 제외 확인 설정에서 WAIT 결측이면 후보 ETA는 정보 부족이다. [대기시간 계약](../docs/contracts/transit-waiting.md) 참조.
검증: `./mvnw -Dtest=BusWaitingServiceTest,SubwayTimingServiceTest,MlRoutePreviewTest,ScheduleEvaluationIntegrationTest,RouteTimelineEvaluatorTest test`.

지하철은 별도 ML 서버를 사용하지 않는다. [지하철 입력·계산 계약](../docs/contracts/subway-timing.md)의
시간표를 수집팀이 공식 자료와 검증된 ID 대응으로 생성하여 `DDUBI_SUBWAY_TIMING_TIMETABLE_PATH`에 지정한다.
현재 저장소에는 실제 운행표가 없어 기본적으로 `SUBWAY_TIMETABLE_NOT_CONFIGURED`가 나온다.
도착·위치는 기존 Spark 최신값 Redis 키를 사용하며 Spark 재시작 후 새 선택 필드를 제공해야 한다.
원본 생성시각·열차 종류가 없는 이전 payload는 시간표로 fallback한다.

`preview.subwayPredictions[].estimate`로 대기/이동/탑승열차/출처를 확인하고,
`applied` 및 `candidates[].subwaySteps`로 전체 ETA 반영 여부를 확인한다.
카카오 step의 대기·환승 구성 확인 전에는 `DDUBI_SUBWAY_TIMING_DECOMPOSITION_VERIFIED`를 true로 설정하지 않는다.
잔여시간이 있으면 true여도 중복 대기를 피하기 위해 API 시간을 유지한다. 추가 DB 변경은 없다.

Java 17, Maven, Docker 실행 환경에서 backend 폴더 기준:

```shell
./mvnw -Dtest=SubwayTimingServiceTest,MlRoutePreviewTest,BusPreviewClientTest,ScheduleEvaluationIntegrationTest,RouteEvidenceAssemblerTest,RouteTimelineEvaluatorTest,ScheduleMonitoringPolicyTest,RouteSearchServiceTest,BusJourneyEvaluatorTest test
```

실제 로컬 모델을 준비한 뒤 별도 smoke 테스트(기본 테스트 실행에서는 비활성):

```shell
./mvnw -Dtest=BusPreviewLiveModelTest -Dddubi.ml.smoke-url=http://127.0.0.1:8000 test
```

이 테스트는 2026-09-10 golden의 독립 버스 edge 2개와 Java→Python HTTP 수치 일치를 확인한다.
두 표본이 하나의 실제 경로라는 뜻이나 모델 정확도 검증 완료를 의미하지 않는다.
Flutter는 client에서 flutter analyze/test 및 일정 상세의 PREVIEW·fallback 표시를 별도로 확인한다.

## 검증 결과 (2026-09-10)

계산·HTTP 계약·MySQL/Redis 통합 및 기존 경로 회귀 34개 통과. 별도 실제 모델 smoke 1개도 통과했다.
Java에서 로컬 Python v12를 호출한 두 평균값은 145.1247447449537초, 340.31162658466326초로
기존 golden과 일치했다. 첫 호출의 HTTP/2 upgrade 실패를 피하기 위해 HTTP/1.1을 명시했고,
실제 모델 응답시간을 고려해 기본 요청 timeout을 5초로 설정했다. 실패 시 API fallback은 유지한다.
Flutter 빌드 및 실제 사용자 카카오 경로·운영 이벤트 전체 연동은 아직 미검증이다.

지하철 변경 검증: Backend 관련 39개(지하철 14, 기존 ML 6, 타임라인 9, 증거 조립 3,
MySQL/Redis 통합 7), Spark 최신값 변환 15개가 통과했다. 시간표 JSON 예시의 schema 검증과
계약 JSON/YAML 문법 검사도 통과했다. 시간표·Redis fixture는 합성 데이터이며 실제 열차 정확도 검증이 아니다.
실제 시간표 공급, 실시간 ID 대응, 카카오 시간 분해 확인 및 Flutter 빌드는 남아 있다.

버스·지하철 공통 대기 추가 검증: Backend 51개(버스 대기 14, 지하철 15, ML 비교 6,
일정 DB 통합 7, 타임라인 9)와 Spark 최신값/Collector 대응표 21개 통과.
버스 대기→ML 이동→환승 도보→지하철 대기의 합성 fixture 250초를 손계산과 대조했다.
실제 교통 API 정확도 및 Flutter 빌드는 이번 검증에 포함되지 않는다.
