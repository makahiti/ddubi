# 외부 학습 원천 확보와 검증

대용량 원본은 Git에 커밋하지 않는다. 원본은 로컬 다운로드 폴더에서 검증한 뒤
GPU 서버 또는 HDFS의 버전이 고정된 raw 경로로 옮기고, 감사 JSON의 파일명·크기·
SHA-256·기간·행 수를 함께 보존한다.

## 2026-09-03 확보 원천

| 구분 | 파일 | 행 수(헤더 제외) | 데이터 기준일/기간 | 상태 |
|---|---|---:|---|---|
| 도로속도 | `1시간 소통정보 (구간별).csv` | 608,246 | 2026-08-29~09-02 | 링크 5,115개 |
| 버스 정류장 이력 | `배차 정류장별 이력.csv` | 73,806 | 2026-08-30~09-01 | 차량운행 970건과 전부 조인됨 |
| 버스 차량운행 이력 | `배차 차량운행별 이력.csv` | 970 | 2026-08-30~09-01 | 차량 ID 797개, 누락 0건 |
| 사고·통제 후보 | `AccInfo112_202407~202507.csv` | 53,791 | 2024-07-18~2025-07-31 | CP949, 사고종류 11개 |

T-DATA 버스 파일 두 종류는 다음 키로만 안전하게 차량 ID를 연결한다.

```text
(기준일, 노선ID, 운행순번, 운행계획ID)
```

미리보기의 첫 행 기준일은 서로 달랐지만, 전체 파일 감사 결과 두 파일 모두 같은 3일을
포함하고 73,806개 정류장 행이 전부 차량운행 키 하나에 연결됐다. 이 키로 차량 ID를
부여한 뒤 `actual_to_passed_at - actual_from_passed_at`으로 연속 정류장 구간의
pass-event 후보를 생성한다.

단, 정류장 도착·출발시각이 특정 초 단위에 지나치게 정렬돼 있으면 실측 통과시각이
아니라 배차 계획시각일 가능성이 있다. 감사 보고서의 `schedule_like_timing_signal`과
시간 분포를 확인하고, 제공기관 설명 또는 실시간 위치 Replay로 실제값 여부를 검증하기
전에는 이를 최종 학습 정답으로 확정하거나 `actual_*` 필드로 승격하지 않는다. 현재
생성물은 `provider_*_timestamp`, `candidate_travel_time_seconds`,
`timing_semantics=unverified`로 명시한다.

## 실행

```bash
python sparkjobs/preparation/external_training_data.py \
  --traffic-hourly "/raw/1시간 소통정보 (구간별).csv" \
  --bus-stop-history "/raw/배차 정류장별 이력.csv" \
  --bus-run-history "/raw/배차 차량운행별 이력.csv" \
  --incident-112 "/raw/AccInfo112_202407~202507.csv" \
  --report "/audit/external-source-audit.json"
```

같은 기준일의 버스 파일이 준비된 경우에만 다음 옵션을 추가한다.

```bash
--pass-events-output "/prepared/bus-pass-event-candidates.csv"
```

생성기는 차량이 동일하고 정류장 순번이 연속이며 통행시간이 0초 초과 7,200초 이하인
구간만 출력한다. 이 파일은 `segment_id` 매핑 전의 provider 원본 정답이다.

도로속도와 112 사고 후보도 함께 정규화하려면 다음 옵션을 추가한다.

```bash
--traffic-output "/prepared/traffic-hourly.csv" \
--incident-output "/prepared/incidents-112.csv"
```

도로의 `시간코드=01`은 00:00~01:00 구간으로 저장한다. 두 과거 파일에는 실제
공개·수집시각이 없으므로 `available_at`은 빈 값으로 남기며, 팀원이 만든 매핑을
적용하기 전까지 `segment_id`도 채우지 않는다. 따라서 이 파일은 품질 확인과 과거
특성 후보 생성에는 쓸 수 있지만, `available_at <= prediction_base_at`을 입증해야 하는
엄격한 과거 재현 실험에는 그대로 사용하면 안 된다.

## 단기예보 실시간·과거 아카이브

`collector/src/sources/weather_forecast.py`는 하루 8개 발표 회차 중 공개 지연 15분이
지난 최신 회차를 읽고 `city.raw`의 `weather.forecast`로 발행한다. 각 레코드는 다음
두 시각을 구분한다.

- `occurredAt`, `payload.issuedAt`: 예보가 생산된 회차
- `payload.validAt`: 예보가 설명하는 미래 시각

실시간 실행 시 `COLLECTOR_SOURCE=weather_forecast`를 사용한다. 현재 승인된 API가
`getVilageFcst`까지 포함하는지는 실제 키로 1회 실행하여 확인해야 한다. 403 또는
활용신청 오류가 나오면 격자 단기예보 API를 별도로 신청하고, 성공 전에는 수집 완료로
표시하지 않는다.

기상청이 보유한 과거 발표 회차는 날짜 범위를 작게 나눠 다음처럼 받는다. 인증키는
명령행이나 Git 파일에 쓰지 않고 환경변수로만 전달한다. 기본값은 하루 8회 발표본이며,
이미 완료된 `issued_at=YYYYMMDDHH.jsonl`은 건너뛴다.

```bash
export KMA_API_KEY='...'
python collector/src/download_weather_forecast_archive.py \
  --from-date 2024-01-01 \
  --to-date 2024-01-31 \
  --output-dir /home/j-j15e202/ddubi-data/raw/weather_forecast/archive
```

각 파일은 발표시각과 유효시각을 함께 보존한다. 수년치를 한 번에 요청하지 말고 API
일일 쿼터와 응답을 확인한 뒤 월 단위로 이어서 수집한다.

운영에서는 GPU 서버가 아니라 Kafka/collector가 있는 EC2(서버1)의 `collect`
프로필에서 `collector-weather-forecast`를 함께 실행한다. GPU 서버는 이렇게 쌓인
archive를 전처리·학습·replay할 때만 사용한다.

## 시간 누수 계약

- 기상 입력은 예측 당시 이미 발표된 단기예보 snapshot만 사용한다.
- 도로속도·사고·통제·행사 입력에는 `available_at <= prediction_base_at`을 적용한다.
- 현재 파일에 없는 발표시각·수집시각을 임의 생성하지 않는다.
- 실제 차량 ID를 날짜 무시 조인이나 임의 값으로 채우지 않는다.
