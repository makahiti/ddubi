# 버스 구간 예측 HTTP API 상세 명세 v1 — 백엔드 연동용

- 작성 기준: 2026-09-10, API 버전 `1.0.0`, 모델 버전 `20260908-v12`
- Jira: [S15P21E202-46](https://ssafy.atlassian.net/browse/S15P21E202-46)
- 서비스 코드 MR: [!78](https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21E202/-/merge_requests/78), 대상 `dt/dev`
- 기계 계약: [ml-serving.openapi.json](ml-serving.openapi.json)
- 구현 근거: [schemas.py](../../ml-serving/schemas.py), [app.py](../../ml-serving/app.py), [predictor.py](../../ml-serving/predictor.py)
- 이 문서는 현재 실행되는 계약을 설명한다. 별도 표기한 배포 주소·호출 예산·후속 연동 정책은 확정된 운영 설정이 아니다.

## 1. 제공 범위와 현재 상태

Python 서비스는 **인접한 버스 정류장 사이 edge의 시간대별 평균 소요시간을 초 단위로 예측**한다.
GPU에서 학습한 LightGBM을 CPU로 로드하고, 요청 ID·날짜/시간에 맞춰 고정된 참조 자료와
학습 당시 인코더·결측 처리 규칙으로 추론한다. 호출마다 모델을 다시 로드하지 않는다.

| 항목 | 현재 구현 |
|---|---|
| 제공 대상 | `SEOUL_BUS` 출처의 학습 vocab에 등록된 개별 ID |
| 입력 단위 | 인접 edge. 카카오 BUS step 전체나 경로 전체가 아님 |
| 출력 단위 | 시간대별 인접 버스 구간 **평균 초**. 개별 차량 실측값 또는 P50/P80/P95가 아님 |
| 현재 모델 | `20260908-v12`, `lightgbm` |
| 현재 참조 지원 기간 | `2026-09-10`부터 `2026-09-17`까지, 양 끝 날짜 포함 |
| 정상 수치의 상태 | `PREVIEW`, `production_ready=false` |
| ID 조합 검증 | 개별 ID의 vocab 존재만 확인. 노선·정류장·순번 조합 검증은 미완료 |
| 동작 범위 | 추론과 응답 반환. 일정/경로·MySQL 쓰기 없음. 최신 Feature API만 Redis에 짧은 캐시를 기록 |

**HTTP 200 또는 readiness 성공이 운영 추천 사용 가능을 뜻하지 않는다.** 현재 수치를
연동 확인에 사용할 수 있지만 기존 추천 시간에 자동 반영하면 안 된다. ML 서비스 배포만으로
일정 등록부터 최종 추천까지 연결되는 것도 아니다.

일반 도보·버스 대기·지하철 이동/대기·환승은 이 모델의 출력 범위 밖이다.
Backend가 기존 경로 API/정책으로 처리한다. 현재 고정 참조 기반 서비스가 최신 교통·날씨
관측까지 연결한 실시간 보정 서비스라는 뜻은 아니다. Redis 상태·기준선 계약도 변경하지 않는다.

## 2. 접속 정보

| 항목 | 값 |
|---|---|
| Method | `POST` |
| Path | `/v1/bus/predictions:batch` |
| 로컬 Base URL | `http://127.0.0.1:8000` — 모델을 실행한 PC 내부용 |
| 서버 Base URL | **미확정**. 인프라 배포 후 전달 |
| Docker 내부 주소 예시 | `http://ml-serving:8000` — 같은 네트워크에 해당 이름으로 배포했을 때만 유효 |
| 요청 헤더 | `Content-Type: application/json` |
| 응답 | JSON. `Accept: application/json` 사용 가능 |
| 현재 인증 구현 | 애플리케이션 API key/JWT 인증 미구현. 내부 서비스 연결을 전제로 함 |
| API 문서 | `GET /docs`, `GET /openapi.json` |

앱은 Spring을 호출하고 Spring이 Python을 호출한다. Python에 사용자 JWT나 카카오 API 키를
보낼 필요가 없다. 외부 공개, TLS·인증·프록시 정책은 인프라에서 별도 확정해야 한다.

## 3. 요청 필드

요청과 `segments` 원소에 정의되지 않은 추가 필드는 허용하지 않는다(HTTP 422).
필수 필드의 생략 또는 `null`도 422다. `provider_namespace`만 생략 시 기본값이 적용된다.

| JSON 경로 | 타입 | 필수 | 제약·의미 |
|---|---|---|---|
| `request_id` | string | O | 길이 1~100. 요청 상관키. 응답에 그대로 반환 |
| `segments` | array | O | 1~1000개. 빈 배열/1000개 초과는 요청 전체 422 |
| `segments[].segment_key` | string | O | 길이 1~100. **동일 요청 안에서 유일**해야 함 |
| `segments[].provider_namespace` | string | X | 길이 1~100. 기본 `SEOUL_BUS`. 현재 이 값만 지원하며 대소문자 구분 |
| `segments[].provider_route_id` | string | O | 숫자 문자 1~30자리. 서울 provider 노선 ID |
| `segments[].provider_from_stop_id` | string | O | 숫자 문자 1~30자리. 서울 provider 출발 정류장 ID |
| `segments[].provider_to_stop_id` | string | O | 숫자 문자 1~30자리. 서울 provider 도착 정류장 ID |
| `segments[].from_stop_sequence` | integer | O | 0~10000. **학습 원천 노선의 출발 정류장 순번** |
| `segments[].service_date` | string(date) | O | 실제 존재하는 날짜의 `YYYY-MM-DD` 표현 |
| `segments[].hour_slot` | integer | O | 0~23. Asia/Seoul 기준 진입 예상 시간대 |

ID는 숫자 타입으로 보내지 않는다. `"001"`은 선행 0을 유지하며 `1`로 변환하지 않는다.
정류장 ARS 번호, 카카오 raw ID, 내부 canonical `segment_id`를 provider ID 대신 넣으면 안 된다.
정수 필드에 문자열/소수/boolean을 보내면 자동 변환하지 않고 422로 거절한다.
날짜에 Unix timestamp나 `2026-09-10T09:00:00+09:00` 같은 datetime을 보내는 것도 허용하지 않는다.

### 3.1 Backend 매핑 필드 대응

| Backend 매핑 값 | HTTP 필드 | 확인할 사항 |
|---|---|---|
| `routeId` | `provider_route_id` | 해당 출처의 서울 노선 provider ID |
| `fromStopId` | `provider_from_stop_id` | ARS/카카오 ID가 아닌 서울 정류장 provider ID |
| `toStopId` | `provider_to_stop_id` | 학습과 같은 방향의 인접 도착 정류장 |
| `fromOrder` | `from_stop_sequence` | 0/1 기반 등을 임의 보정하지 말고 학습 원천 순번과 대조 |
| 내부 edge/경로 내 위치 | `segment_key` | 호출자가 만든 상관키. canonical ID 자체와 동일할 필요 없음 |
| 출처 정보 | `provider_namespace` | 경기·인천 ID를 `SEOUL_BUS`로 이름만 바꿔 보내지 않음 |
| 구간 진입 예상시각 | `service_date`, `hour_slot` | 서울 시간으로 변환한 날짜와 시간 |

매핑 필드가 존재하는 것만으로 학습 ID 일치를 보장하지 않는다. 실제 노선·정류장·순번 조합
대조는 후속 검증이다. API의 vocab 검사는 서로 무관한 등록 ID 조합까지 검증해 주지 않는다.

### 3.2 step을 edge로 전개하고 일괄 호출하는 방법

1. Backend가 선택한 버스 대안/출처의 인접 edge 목록을 만든다. 여러 차량 대안의 edge를 섞지 않는다.
2. 각 edge에 유일한 `segment_key`를 부여하고, 그 키와 경로·step·edge 순번의 관계를 Backend에 보존한다.
3. 각 edge의 진입 예상 날짜·시간대를 계산해 하나의 배열로 전송한다.
4. 응답 개수·키·순서를 대조한 후 원래 경로에 연결한다. 누락/중복/알 수 없는 키는 정상 결과로 사용하지 않는다.

같은 물리 edge가 여러 후보에 등장해도 요청 가능하지만 `segment_key`는 각각 달라야 한다.
여러 경로도 한 요청에 넣을 수 있다. 서버는 경로 그룹을 알지 못하며 합산·후보 선택을 하지 않는다.
1000개는 검증 한도이고 그 크기의 성능 보장이 아니다. 초과 시 서버가 자동으로 나누지 않는다.

날짜/시간은 일정의 도착 목표를 모든 edge에 복사하지 않는다. 앞 구간의 이동·대기 등을
반영한 진입 예상시각을 사용한다. 23시에서 다음 날로 넘어가면 `service_date`도 바뀐다.
초기 예상시각을 어느 fallback으로 계산할지와 예측 후 재계산 여부는 Backend 정책이며,
이 API는 반복 계산하거나 다음 edge의 시각을 자동 수정하지 않는다.

## 4. 실제 요청·응답 예시

아래는 서버 원본과 대조한 두 표본이다. **독립 검증 표본으로, 연속된 하나의 경로 예시는 아니다.**
계산시각은 예시 생성 시각이며 재호출할 때 달라진다. 수치·결측 목록은 실제 로컬 추론 결과다.

요청: [bus-batch-request.json](../../ml-serving/examples/bus-batch-request.json)

```json
{
  "request_id": "backend-integration-example",
  "segments": [
    {
      "segment_key": "sample-edge-1",
      "provider_namespace": "SEOUL_BUS",
      "provider_route_id": "100100001",
      "provider_from_stop_id": "100000004",
      "provider_to_stop_id": "101000001",
      "from_stop_sequence": 23,
      "service_date": "2026-09-10",
      "hour_slot": 9
    },
    {
      "segment_key": "sample-edge-2",
      "provider_namespace": "SEOUL_BUS",
      "provider_route_id": "100100001",
      "provider_from_stop_id": "100000023",
      "provider_to_stop_id": "100000415",
      "from_stop_sequence": 16,
      "service_date": "2026-09-10",
      "hour_slot": 9
    }
  ]
}
```

HTTP 200 응답: [bus-batch-response.json](../../ml-serving/examples/bus-batch-response.json)

```json
{
  "request_id": "backend-integration-example",
  "model_version": "20260908-v12",
  "model_type": "lightgbm",
  "target": "hourly_mean_bus_segment_seconds",
  "production_ready": false,
  "bundle_production_ready": false,
  "supported_from": "2026-09-10",
  "supported_until": "2026-09-17",
  "computed_at": "2026-09-10T11:04:44.334416+09:00",
  "results": [
    {
      "segment_key": "sample-edge-1",
      "status": "PREVIEW",
      "reason": "MODEL_NOT_PRODUCTION_READY",
      "prediction": {
        "mean_seconds": 145.1247447449537,
        "missing_feature_count": 4,
        "missing_features": [
          "from_board_daily_hour_lag2month",
          "from_alight_daily_hour_lag2month",
          "to_board_daily_hour_lag2month",
          "to_alight_daily_hour_lag2month"
        ],
        "unseen_id_count": 0,
        "history_sample_count": 8,
        "data_insufficient": false,
        "training_ids_verified": false
      }
    },
    {
      "segment_key": "sample-edge-2",
      "status": "PREVIEW",
      "reason": "MODEL_NOT_PRODUCTION_READY",
      "prediction": {
        "mean_seconds": 340.31162658466326,
        "missing_feature_count": 3,
        "missing_features": [
          "from_board_daily_hour_lag2month",
          "from_alight_daily_hour_lag2month",
          "boarding_lag77d"
        ],
        "unseen_id_count": 0,
        "history_sample_count": 8,
        "data_insufficient": false,
        "training_ids_verified": false
      }
    }
  ]
}
```

## 5. 응답 필드

### 5.1 배치 공통 필드

| 필드 | 타입 | 의미 |
|---|---|---|
| `request_id` | string | 요청 상관키를 그대로 반환 |
| `model_version` | string | 로드한 모델 버전. 현재 `20260908-v12` |
| `model_type` | string | 현재 `lightgbm` |
| `target` | string | `hourly_mean_bus_segment_seconds` |
| `production_ready` | boolean | 현재 서비스는 항상 `false` |
| `bundle_production_ready` | boolean | 로드한 manifest의 운영 승인 표시. 현재 묶음은 `false` |
| `supported_from` | string(date) | 참조 지원 시작일, 포함 |
| `supported_until` | string(date) | 참조 지원 마지막 날, 포함. 결과의 만료시각이 아님 |
| `computed_at` | string(datetime) | ISO 8601 계산시각. 서비스는 Asia/Seoul `+09:00` 사용 |
| `results` | array | 요청과 같은 개수·순서의 개별 결과. 배치가 422/503이면 이 배열 없음 |

`computed_at`은 원천 관측/발표시각이 아니다. `supported_until`도 `validUntil`로 사용할 수 없다.
묶음의 승인 표시가 향후 바뀌더라도 현 API가 자동으로 운영 승인 결과를 반환하는 구조는 아니다.

### 5.2 개별 결과 및 prediction

| 필드 | 타입 / nullable | 의미 |
|---|---|---|
| `results[].segment_key` | string / N | 해당 요청의 상관키 |
| `results[].status` | string / N | `PREVIEW` 또는 `UNAVAILABLE` |
| `results[].reason` | string / N | 아래 상태·오류 표의 사유 코드 |
| `results[].prediction` | object / Y | `PREVIEW`이면 객체, `UNAVAILABLE`이면 `null` |
| `prediction.mean_seconds` | number / N | 유한한 0 이상 평균 초. 프리뷰 계약은 0도 허용 |
| `prediction.missing_feature_count` | integer / N | 결측으로 판단된 수치 특성 개수 |
| `prediction.missing_features` | string[] / N | 해당 특성 이름. 결측 없으면 빈 배열 |
| `prediction.unseen_id_count` | integer / N | 수치를 반환하는 현재 경로에서는 항상 0. 미등록 ID는 prediction 자체가 null |
| `prediction.history_sample_count` | integer / N | 0~8. 같은 시간대의 77, 84, 91, 98, 105, 112, 119, 126일 전 관측 개수 |
| `prediction.data_insufficient` | boolean / N | 현재 구현에서는 `history_sample_count == 0`인 경우 true |
| `prediction.training_ids_verified` | boolean / N | 현재 항상 false. 개별 vocab 존재를 전체 조합 검증으로 표시하지 않음 |

`history_sample_count`는 모델 학습 데이터 총량이나 운행 차량 수가 아니다.
`data_insufficient=false`라도 다른 특성 결측이나 ID 조합 문제가 없다는 보장이 아니다.
결측은 원본 모델과 같은 학습 중앙값·결측 표시로 처리하며 0이라는 관측으로 위장하지 않는다.
임의의 confidence, 분위수, 안전 여유시간은 응답에 없다. 실제 0초 프리뷰도 기존 Backend
포트의 양수 조건을 만족하지 않으므로 승인 결과로 바꾸면 안 된다.

## 6. 부분 실패와 HTTP 오류

### 6.1 HTTP 200에서 확인할 상태

| status | reason | prediction | 의미 |
|---|---|---|---|
| `PREVIEW` | `MODEL_NOT_PRODUCTION_READY` | 객체 | 실제 수치 계산 완료, 운영 추천 사용 미승인 |
| `UNAVAILABLE` | `UNSUPPORTED_PROVIDER` | null | `SEOUL_BUS` 이외 출처 |
| `UNAVAILABLE` | `REFERENCE_DATE_UNSUPPORTED` | null | 유효한 날짜지만 현재 묶음 범위 밖 |
| `UNAVAILABLE` | `UNSEEN_PROVIDER_ID` | null | 노선/출발 정류장/도착 정류장 ID 중 하나 이상이 학습 vocab에 없음 |

한 행의 미지원은 다른 행의 계산을 막지 않는다. 모든 행이 미지원이어도 모델이 준비되어
요청 처리가 가능하면 HTTP 200이다. 사유가 겹치면 출처 → 지원 날짜 → ID 순서로 하나를 반환한다.

예: 위 요청의 두 번째 행 `provider_namespace`만 `GBIS`로 바꾸면 첫 번째는 PREVIEW,
두 번째는 아래 결과가 된다. 아래 JSON은 전체 응답이 아닌 두 번째 `results` 원소다.

```json
{
  "segment_key": "sample-edge-2",
  "status": "UNAVAILABLE",
  "reason": "UNSUPPORTED_PROVIDER",
  "prediction": null
}
```

전체 예시: [부분 실패 요청](../../ml-serving/examples/bus-batch-partial-request.json),
[부분 실패 응답](../../ml-serving/examples/bus-batch-partial-response.json).

### 6.2 HTTP 422 — 요청 전체 검증 실패

필수 값 누락/null, 잘못된 날짜/타입/범위, 추가 필드, 빈 배열, 1000개 초과,
중복 `segment_key`, 잘못된 JSON은 요청 전체를 거절한다. 다른 정상 행만 예측하지 않는다.

예: 첫 번째 행의 `hour_slot=24`에 대한 실제 응답:

```json
{
  "error": "INVALID_REQUEST",
  "fields": [
    {
      "location": [
        "body",
        "segments",
        0,
        "hour_slot"
      ],
      "type": "less_than_equal"
    }
  ]
}
```

`fields[].location`은 문자열/정수로 구성된 JSON 위치, `type`은 검증 오류 종류다.
오류 원본 입력값은 응답에 복사하지 않는다. Pydantic 오류 종류는 실패 원인에 따라 달라지므로
문자열 전체에 의존해 정상 처리를 결정하지 않는다. 같은 잘못된 요청의 자동 재시도는 하지 않는다.

### 6.3 HTTP 503 — 모델 준비/처리 실패

응답 형식:

```json
{"detail": "MODEL_NOT_READY"}
```

| detail | 조건 | Backend 처리 |
|---|---|---|
| `MODEL_NOT_READY` | 시작 시 모델/참조 누락, SHA·버전 검증 실패 등으로 로드되지 않음 | 해당 호출의 예측 미사용, readiness 확인 |
| `MODEL_BUSY` | 대기열 초과·대기 기한 만료·종료 중. 직접 Predictor 호출은 동시 계산 시 즉시 거절 | 수신 시 fallback. 병렬 재시도 폭주 금지 |
| `MODEL_UNAVAILABLE` | 추론 중 참조 I/O 실패, 특성 순서 불일치, 비정상 출력 등 | 해당 배치의 예측 미사용 |

모델 처리 중 예외는 배치 전체 503이며 부분 결과를 반환하지 않는다. 서버는 내부 예외/경로를
응답에 노출하지 않는다. 네트워크 단절·프록시 오류 등은 위 JSON과 다른 응답일 수 있으므로
Backend가 HTTP body 없이도 실패를 처리해야 한다.

## 7. timeout·동시 실행·재호출

| 항목 | 현재 구현 또는 연동 확인 제안 |
|---|---|
| 모델 로딩 | 프로세스 시작 시 한 번. 전체 파일 SHA와 지정된 패키지 버전 대조 |
| 동시 추론 | 프로세스당 실행 1개, FIFO 대기 기본 4개. 대기 초과/만료 시 `MODEL_BUSY` 503 |
| 대기 예산 | `ML_QUEUE_WAIT_SECONDS` 기본 5초(0~30). 실행 시간은 포함하지 않음 |
| 대기 개수 | `ML_MAX_WAITING_REQUESTS` 기본 4(0~32). 대기 시간/개수 0이면 혼잡 시 즉시 503 |
| 참조 캐시 | `ML_REFERENCE_CACHE_MIB` 기본 256 MiB(0~4096), 0이면 비활성화. RSS 상한 아님 |
| CPU | 모델 `n_jobs=2`와 추론 시 threadpool 제한 2. 프로세스/배포 자원 한도는 별도 |
| 서버 강제 timeout | 별도 구현 없음. 클라이언트 timeout이 서버 계산 취소를 보장하지 않음 |
| 검증용 클라이언트 전체 timeout | **10초 제안**, 운영 SLA 미확정 |
| 연결 timeout | 기존 200ms 제안은 있었으나 서버 환경 검증/확정 전 |
| 자동 재시도 | 초기 Backend 연동은 **없음 제안**. 서버 자체 HTTP 재시도도 없음 |
| 성능 근거 | 10개 배치 로컬 CPU 약 1.85~2.07초. 다른 실행에서 약 2.21초. 부하별 보장 아님 |
| `request_id` 의미 | 상관키이며 idempotency/cache 키가 아님. 같은 값 재호출을 차단하지 않음 |

같은 모델·참조·입력을 다시 계산하면 같은 수치를 기대하며, `computed_at`은 달라진다.
HTTP 요청은 이벤트 루프의 대기열을 통과한 뒤 별도 스레드에서 계산한다. 대기 취소/만료는
실행하지 않으며, 실행 중 연결 취소는 계산 완료 전 슬롯을 반환하지 않는다. live/ready는
계산과 별개로 응답한다. 이는 파싱된 추론 요청의 입장 제한이며 HTTP 연결/본문 전체 메모리
상한은 아니다. 여러 worker/인스턴스는 각각 별도 큐·모델·캐시를 소유한다.

Backend의 전체 timeout은 대기 예산 + 서버에서 측정한 계산 시간 + 통신 시간을 포함해야 한다.
기본 5초 대기와 Backend 5초 전체 timeout을 함께 사용하면 부족하다.
초기 10초 제안도 SLA가 아니며 최대 배치/부하 측정 후 대기 예산 또는 호출 예산을 조정한다.
클라이언트 timeout 뒤 자동 즉시 재시도는 사용하지 않는다.

참조 캐시는 SHA 검증된 portable 모듈의 I/O에만 적용되며 특성식/묶음 파일은 변경하지 않는다.
필요한 파일·열을 처음 읽을 때 Arrow Table로 보관하고 LRU로 퇴출한다. 한 파일이 예산보다
크면 읽기만 하고 보관하지 않는다. pandas 변환·모델·읽기 중 임시 버퍼는 캐시 상한 밖이다.
실측 결과와 재현 명령은 [성능 검증 기록](ml-serving-capacity-validation.md)을 따른다.

서비스는 요청 결과를 DB에 저장하지 않으며 재호출은 새 추론이다. 전체 1초 예산으로는
측정한 10개 요청을 완료하지 못한다. 최대 배치·동시 부하·서버 응답시간은 배포 후 검증해야 한다.

## 8. 기존 Backend 계약과 연결 범위

2026-09-17: 검색의 `BusPreviewPredictionAdapter`를 실제 HTTP 클라이언트에 연결했다.
검색 응답의 `segments[].busEvaluations[].previewEstimatedSeconds`는 유효한 전체 버스 step
PREVIEW 합계이며 `estimatedSeconds`/`predictionReady`의 운영 승인 의미를 바꾸지 않는다.
이력 표본 0은 수치가 있어도 `ML_HISTORY_INSUFFICIENT`이며 step 전체가 API fallback한다.
미지원 출처, 호출 오류, 예산 초과는 사유를 구분한다. 아래 포트의 미충족 승인 조건은 그대로다.
대안 전개·참조 갱신·실제 배포 상태는 [갱신 계약](transit-data-refresh.md)을 따른다.

현재 `BusPredictionPort`의 승인된 `Batch`와 이 HTTP 응답은 동일한 DTO가 아니다.
HTTP 호출 성공만으로 그 계약에 맞는 운영 결과를 만들 수 없다.

| 기존 포트에서 요구하는 내용 | 현재 HTTP 응답 / 차이 |
|---|---|
| `productionReady=true`, `trainingIdsVerified=true` | 두 조건 모두 충족하지 않음 |
| `target=ADJACENT_BUS_MEAN_SECONDS` | HTTP는 `hourly_mean_bus_segment_seconds`. 필드명·값 자동 일치 아님 |
| `featureVersion` | 별도 필드 없음. 근거 없이 model_version으로 대체하지 않음 |
| `inputDigest` | 반환하지 않음. request_id/segment_key는 검증된 입력 지문이 아님 |
| `featuresAsOf`, `computedAt`, `validUntil` | 계산시각만 있음. 원천 가용시각·유효시각을 임의 생성하지 않음 |
| `segmentId`, `edgeSequence` | 서버는 segment_key를 반환. Backend가 요청 시 보존한 관계로 연결해야 함 |
| edge별 양수 평균·표본 1 이상 | HTTP는 0초/이력 표본 0의 PREVIEW도 가능 |

Backend는 우선 별도 HTTP DTO/클라이언트로 프리뷰 수치와 오류 처리를 확인할 수 있다.
현재 `BusJourneyEvaluator`의 운영 승인 조건을 우회하지 않는다. 미래에 승인된 모델로
추천에 반영하려면 ID 조합, 가용시각, 유효성 및 포트 계약을 함께 확정·검증해야 한다.

현재 계약에서는 한 step의 일부 edge 예측이 실패하면 **step 전체를 카카오 시간으로 fallback**한다.
일부 ML edge와 카카오 균등 분배 edge의 합산은 현재 승인된 정책이 아니다.
균등 분배를 도입한다면 step 전체 fallback 시간을 edge에 배치하는 Backend 정책으로 구분하고,
합계 보존·배분 출처 기록·학습 정답/기준선 표본으로의 재사용 금지를 지켜야 한다.
이는 HTTP 서비스가 구현하는 기능이나 분배 방식이 아니다.

baseline_serving 조회와 카카오 시간 선택은 Backend 영역이다. Redis 최신 원천값의 시각 검증과
구간별 Feature 조립은 아래의 별도 API가 담당한다.
사용자가 확인한 기준선 커버리지 0은 당시 현황이며 이 서비스가 기준선을 적재하지 않는다.
이 API는 카카오 `raw_response`를 받거나 저장하지 않으므로 원본 경로 재현 문제도 해결하지 않는다.

## 9. Redis 최신 Feature 조회 API

`ML_REDIS_LATEST_FEATURES_ENABLED=true`로 명시적으로 켠 경우에만 사용할 수 있다.
`ML_BUS_CROSSWALK_SHA256`이 현재 버스 crosswalk와 다르거나 활성 대응표의 메타데이터가
일치하지 않으면 이전 마스터의 값을 Feature로 사용하지 않는다.

```http
POST /v1/bus/features:latest
Content-Type: application/json

{
  "segmentId": "BUS:100100609:123456:123789",
  "masterVersion": "metropolitan-bus-v6",
  "asOf": "2026-09-18T16:10:00+09:00",
  "passageAt": "2026-09-18T16:13:00+09:00"
}
```

ML Serving은 활성 `segmentId→날씨 격자`, `segmentId→TOPIS 링크`, `segmentId→도시 영역`
대응표를 읽고 Redis의 최신 원천값을 한 번에 조회한다. 미래에 수집된 값, `validFrom` 이전 값,
예상 통과시각에 `validUntil`이 끝난 값은 Feature에 넣지 않는다. 미매핑·만료·결측은 0으로
바꾸지 않고 `null`과 `missingFeatures`로 반환한다. 현재 시점 요청만 결과를 최대 900초 캐시하며,
실제 `validUntil`이 더 빠르면 그 시각까지만 저장한다. 과거 시점 재현 요청은 캐시하지 않는다.

현재 Snapshot을 경로 단위로 읽을 때는 `POST /v1/bus/features:batch`에 `requestId`,
`masterVersion`, 현재 `asOf`, 고유 `segmentIds` 1~1,000개를 보낸다. 이 API는 표준 Key를 만들어
Redis `MGET`을 정확히 한 번 실행하며 입력 순서를 보존한다. 각 결과는 `AVAILABLE`, `MISSING`,
`STALE`, `INVALID` 중 하나다. `AVAILABLE`만 Snapshot을 반환하고 나머지는 `snapshot=null`이다.
미래 통과시각별 결과는 CURRENT Key를 덮어쓰지 않는다. 세부 계약은
[버스 구간 Snapshot](bus-segment-feature-snapshot.md)을 따른다.

- 대응표 또는 일부 최신값 부재: HTTP 200, 해당 Feature는 `null`
- Redis Feature 기능이 꺼짐: HTTP 503 `FEATURE_STORE_NOT_READY`
- Redis 연결/조회 실패: HTTP 503 `FEATURE_STORE_UNAVAILABLE`
- 잘못된 구간 ID·시각 관계: HTTP 422 `INVALID_ENVIRONMENT_POINT`

TOPIS 링크 대응표가 아직 승인되지 않은 구간은 도로·사고 Feature만 결측이며, 날씨·도시·버스
Feature 조립은 계속한다.

## 10. 상태 확인 API

- `GET /health/live`: 프로세스 생존 확인. HTTP 200 `{"status":"UP"}`. 모델 준비를 뜻하지 않음.
- `GET /health/ready`: 모델 로드 성공 및 오늘 KST가 지원 범위 안이면 HTTP 200, 아래 메타데이터 반환.
- 미로드 readiness: HTTP 503 `{"detail":"MODEL_NOT_READY"}`.
- 참조 날짜 미지원: HTTP 503, `status=REFERENCE_DATE_UNSUPPORTED` 및 아래 메타데이터.
- 이 날짜 검사/추가 필드는 로컬 코드 변경이다. 운영 기존 이미지는 참조 묶음만 새로 설치되어 기본 지원 날짜 필드를 반환한다.

```json
{
  "status": "READY_FOR_PREVIEW",
  "model_version": "20260908-v12",
  "model_type": "lightgbm",
  "target": "hourly_mean_bus_segment_seconds",
  "production_ready": false,
  "bundle_production_ready": false,
  "supported_from": "2026-09-10",
  "supported_until": "2026-10-10",
  "reference_status": "CURRENT",
  "reference_days_remaining": 23,
  "missing_reference_file_count": 11
}
```

위 예시는 2026-09-17 KST 기준이며 days_remaining은 날짜에 따라 달라진다.
reference_status는 CURRENT/NOT_STARTED/EXPIRED다. 결측 개수는 파일 단위이며 개별 노선 표본 수가 아니다.
readiness는 로드된 객체와 오늘 지원 범위를 확인한다. 호출마다 모든 참조 파일의 SHA나 실제 추론을
다시 검사하지 않으므로, 실행 중 참조 변경/손상이나 이후 I/O 실패까지 보장하지 않는다.
모델 묶음은 실행 중 바꾸지 않고, 새 묶음 설치·검증 후 프로세스를 재시작한다.

## 10. 복사해서 실행할 예시

프로젝트 루트에서 로컬 서비스가 실행 중일 때:

```powershell
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8000/v1/bus/predictions:batch' -ContentType 'application/json' -InFile ml-serving/examples/bus-batch-request.json -TimeoutSec 10
```

```bash
curl --connect-timeout 2 --max-time 10 \
  -H 'Content-Type: application/json' \
  --data-binary @ml-serving/examples/bus-batch-request.json \
  'http://127.0.0.1:8000/v1/bus/predictions:batch'
```

위 curl의 연결 2초·전체 10초는 수동 점검 예산이며 Backend 운영 설정을 확정하는 값이 아니다.
실제 배포 후 Base URL을 인프라가 제공한 내부 주소로 교체한다.

모델 설치·실행과 서버 원본 대조 명령은 [ml-serving/README.md](../../ml-serving/README.md)를 따른다.
Git에는 모델과 참조 자료가 포함되지 않으므로 코드만 받아서는 readiness가 성공하지 않는다.

## 11. Backend 연동 확인 항목과 후속 작업

- 실제 매핑 결과의 서울 provider ID/방향/순번을 검증 표본과 대조한다.
- 여러 edge를 한 번에 전송하고 반환 개수·키·순서 및 초 단위를 확인한다.
- 미지원 행의 null, 요청 전체 422, 모델 미준비/처리 중/실패 503, 네트워크 timeout을 처리한다.
- 지원 기간 경계와 서울 시간의 날짜 변경을 확인한다.
- PREVIEW가 운영 승인 결과로 변환되지 않고 기존 fallback이 유지되는지 확인한다.
- 배포 후 서버의 실제 응답시간·메모리를 측정해 timeout/동시 호출 수를 정한다.
- 참조 데이터 갱신, 학습 ID 조합 검증, 운영 포트 계약 확정 후 실제 추천 반영을 진행한다.
- 일정 등록 → 카카오 조회 → 매핑 → 모델 호출 → 최종 응답은 별도 통합 검증한다.

기존 검증: pytest **16개 통과**, 실제 10개 표본의 서버 원본 대비 수치 차이 **0초**,
실제 로컬 HTTP 호출 성공. 이번 문서의 정상·부분 실패·422 예시도 실행 구현에서 생성·검증했다.
이 결과는 모델 이식과 HTTP 동작의 근거이며 전체 노선 커버리지나 예측 정확도 보장은 아니다.

## 12. Backend 적용 모드·배치 호출·missing_features 처리·운영 계약 제안 (2026-09-18, 로컬 검증만 완료)

이 절은 Backend 쪽 계약 해석을 고정한다. ml-serving 자체 변경(모델 재학습 포함)은 범위 밖이며, 12.4는 ml-serving 팀에 제안하는 항목이다.

### 12.1 적용 모드 (`DDUBI_RECOMMENDATION_ML_APPLY_MODE`, 기본 `SHADOW`)

| 모드 | 숫자를 시간 계산에 사용 | 응답 표시 | 허용 환경 | `productionReady` |
|---|---|---|---|---|
| `SHADOW` | 아니오. `predictions[]`에 기록만 한다 | `step-N:ML_SHADOW_RECORDED`, `step-N:ML_NOT_APPLIED:SHADOW_MODE`, `step-N:API_FALLBACK` | 운영 기본 | false |
| `TEST_APPLY` | 예. 단 `history_sample_count>0`이고 `data_insufficient=false`인 `PREVIEW` 수치만 | `ML_TEST_APPLIED`, `ML_MODEL_VERSION:`, `ML_MIN_HISTORY_SAMPLES:`, `ML_MAX_MISSING_FEATURES:`, step source `ML_PREVIEW`. 표본 0건·부족이면 `ML_NOT_APPLIED:ML_HISTORY_INSUFFICIENT` 로 step 전체 fallback | 테스트 프로파일만(`application-test.yml` + `ml-test-apply-allowed=true`) | false |
| `PRODUCTION` | 12.4 운영 계약을 모두 만족한 응답만 | `ML_APPLIED`, step source `ML_PRODUCTION`. 미충족 시 `ML_NOT_APPLIED:ML_PRODUCTION_CONTRACT_NOT_MET` | 승인 후 | false(승인 정책 전환은 별도 결정) |

- 어느 모드도 `productionReady`를 true로 바꾸지 않는다. 되돌리기는 값을 `SHADOW`로 바꾸고 backend를 재기동하는 것으로 끝난다(코드 변경·DB 변경 없음).
- `TEST_APPLY`가 실제 평가에 남긴 `ML_TEST_APPLIED` 결과는 탐색이 완료된 경우 앱에서 수동 적용할 수 있다. 이는 휴대폰 QA를 위한 동작이며 응답의 `PREVIEW`·`productionReady=false` 표시는 유지된다. 단순 PREVIEW/SHADOW 결과는 적용할 수 없다.
- step 단위 원자성: 한 step의 edge 중 하나라도 숫자가 없으면 그 step 전체를 경로 API 원본 값으로 되돌리고 `ML_NOT_APPLIED:<원인>`을 남긴다. 부분 적용은 하지 않는다.
- 잘못된 모드 문자열은 기동 실패(fail fast)다.

### 12.2 배치 호출 (`BusPreviewClient.predictBatch`)

1. 평가 시작 시 모든 후보의 지원 BUS edge 입력을 모아 `(노선, 출발정류장, 도착정류장, 구간순번, 진입시각)` 기준으로 중복을 제거한다.
2. `maxBatchSegments`(기본 200, 1~1000) 단위로 나눠 `/v1/bus/predictions:batch`를 부른다. `segment_key`는 요청 안 순번 `e0..eN`이다.
3. 응답 검증: `results` 수가 요청 수와 같아야 하고, 각 `segment_key`가 요청 키 집합에 정확히 한 번씩 있어야 한다. 수·키 불일치나 중복 키는 그 청크 전체를 `ML_INVALID_RESPONSE`로 처리한다(순서는 키로 다시 맞추므로 응답 순서에 의존하지 않는다).
4. 실패 구분: 전체 요청 timeout → 그 청크 모든 입력 `ML_TIMEOUT`. HTTP/형식 오류 → `ML_HTTP_ERROR`/`ML_INVALID_RESPONSE`. 개별 `status=UNAVAILABLE`은 그 결과의 `reason`을 그대로 전달한다(다른 결과는 정상 사용).
5. 결과는 평가 세션 캐시에 넣어 뒤 후보도 같은 값을 쓴다(공정성). 캐시에 없는 edge만 개별 호출하며, 개별 호출은 세션당 `INDIVIDUAL_CALL_LIMIT=8`회·ML 마감시각까지만 허용한다. 넘으면 `ML_BUDGET_EXHAUSTED`(자료 없음과 다른 코드).
6. 예산: 트리거별 세션 예산(첫 계산 30초 / 자동 갱신 15초 / 수동 다시 계산 10초)의 `ml-budget-share`(기본 0.6)만 ML에 쓴다. 나머지는 과거 평균·시간표 계산에 남긴다.

### 12.3 `missing_features` 처리

- 배열이 없으면 `null`(정보 없음), 빈 배열이면 빈 목록(결측 없음)으로 구분해 보존한다.
- 원소는 문자열이어야 하고 이름은 `[A-Za-z0-9_.:-]{1,64}`만 허용한다. 배열이 아니거나 이름이 규칙을 벗어나면 그 결과를 `ML_INVALID_RESPONSE`로 본다.
- 배열 길이는 `missing_feature_count`와 같아야 한다. 다르면 `ML_INVALID_RESPONSE`.
- 최대 32개까지만 보존하고 넘치면 `missingFeaturesTruncated=true`를 남긴다.
- 이름은 진단(`predictions[].estimate.missingFeatures`)에만 쓴다. 로그에는 원문 응답·URL·비밀값을 남기지 않는다.
- 2026-09-18 08시 실측(노선 110000004, 121000096→122000184, 순번 69) 15개: `history_recent_seconds`, `history_median_4obs`, `history_mean_4obs`, `history_median_8obs`, `history_mean_8obs`, `history_std_8obs`, `history_trend_seconds`, `to_board_daily_hour_lag2month`, `to_alight_daily_hour_lag2month`, `passenger_recent`, `passenger_mean_4obs`, `passenger_std_4obs`, `passenger_trend`, `boarding_lag77d`, `bus_runs_lag77d`. 모두 온라인 이력·승객 특성이며 정적 특성은 결측이 아니었다.

### 12.4 운영 계약(PRODUCTION) — Backend가 요구하는 응답 조건 (ml-serving 제안)

Backend `PRODUCTION` 모드는 아래를 모두 만족한 결과만 적용한다. 현재 ml-serving v12 응답은 어느 것도 만족하지 않으므로 운영 모드로 켜도 적용은 0건이고 `ML_PRODUCTION_CONTRACT_NOT_MET`만 남는다.

| 위치 | 조건 |
|---|---|
| 배치 공통 | `production_ready=true`, `feature_version` 문자열, `features_as_of` ≤ 각 결과 `computed_at`, `valid_until` > 현재 시각, `input_digest` = 요청 본문 전체의 SHA-256(hex) |
| 개별 결과 | `status=PRODUCTION`, `prediction.training_ids_verified=true`, `data_insufficient=false`(부족하면 `ML_HISTORY_INSUFFICIENT`로 미적용), 수치 `predicted_seconds` 존재 |
| 커버리지 | 한 step의 모든 edge가 위 조건을 만족해야 그 step에 적용 |

PREVIEW 계약(현행)은 `production_ready=false`, `status=PREVIEW`, `reason=MODEL_NOT_PRODUCTION_READY`, `training_ids_verified=false`를 그대로 요구하며, 두 계약은 `BusPreviewClient.Contract`로 분리돼 있어 조건을 지우거나 boolean을 강제로 바꾸는 방식으로는 운영 적용이 되지 않는다. 검증: `BusPreviewClientTest`(8) · `MlApplyModeTest`(6) 합성 응답으로 통과.
