# 일정 경로 검증 — transit-prev-v3

> 2026-09-22 후속 설계 수정: 사용자가 출발 예정시각과 일정 시작시각을 모두 입력한다. 예상 도착이 일정 시작보다 빠르면 출발을 유지하고, 같거나 늦을 때만 일정 시작 10분 전을 목표로 출발을 다시 탐색한다. [계산 정책](arrival-deadline-calculation.md), [파트별 구현 계획](arrival-deadline-implementation-plan.md), [원천·컬럼 가이드](arrival-deadline-data-guide.md)를 따른다. 아래는 기존 검증 흐름이며 후속 설계의 구현·배포 완료를 뜻하지 않는다. PREVIEW 추천 행 저장은 허용되고, 명시적 `TEST_APPLY` 결과는 수동 QA 적용도 허용한다. 자동 알림과 운영 승인은 별도다.

## 현재 동작

사용자 일정 등록 → 카카오 후보 경로 조회/매핑 → 버스 인접 구간 ML 호출 → 후보별 총 소요시간 계산 → 최소시간 후보 하나 반환.
현재는 **검증용 PREVIEW**다. 실제 모델은 버스 인접 구간의 시간대별 평균 초만 예측한다.
지하철은 시간표·실시간 도착·위치로 별도 계산하고 시간 분해가 검증된 후보에 적용한다.
버스 대기는 실제 도착정보로 별도 계산하여 검증된 시간 분해 조건에서 ML 이동 앞에 적용한다.
조건 미충족 대기·환승·일반 도보는 카카오 시간을 보존한다. Redis 관측 소요시간으로 버스 이동시간을 교체하지 않는다.
공통 waitPredictions와 결측 대기로 인한 정보 부족 판정은 [대기시간 계약](transit-waiting.md)을 따른다.
Redis 구간 상태는 통제/위험 근거이며 버스 모델 HTTP 입력에는 포함되지 않는다.

## 계산 규칙

- 같은 출발시각으로 모든 후보를 평가한다. 입력이 이미 과거면 현재시각으로 평가하고 결과의 departureAt에 남긴다.
- 확정된 단일 canonical 버스 경로 중 서울 provider ID와 순번이 확인된 인접 edge만 요청한다.
  경기·인천 namespace, 모호한 매핑, 비연속 순번은 임의로 서울 ID로 바꾸지 않는다.
- Python `/v1/bus/predictions:batch`에 노선/출발·도착 정류장/순번/KST 진입 날짜·시간대를 보낸다.
  앞 edge의 예측 초(올림)를 반영해 다음 edge의 날짜·시간대를 결정한다. 자정을 넘으면 날짜도 바뀐다.
- 한 BUS step의 **전체 edge**가 예측되어야 카카오 step 시간을 ML 합으로 교체한다.
  일부 실패는 해당 BUS step 전체를 카카오 시간으로 되돌리고 부분 예측은 applied=false로 보존한다.
- 카카오 총시간에서 step 합을 뺀 미배치 대기·환승 시간은 한 번만 보존한다.
  이 잔여시간 때문에 진입 가능한 시간대가 둘 이상이면 ENTRY_HOUR_UNCERTAIN으로 API fallback한다.
- 지하철은 [지하철 계산 계약](subway-timing.md)의 시간 분해 검증 조건을 충족하면 대기+이동으로 교체한다.
  나머지 구간은 API 시간을 사용한다. 총시간이 step 합보다 작으면 분해를 믿지 않고 API 총시간을 사용한다.
- 계산 가능한 후보 중 통제로 제외되지 않은 최소 도착시각 한 건을 preview.fastest에 반환한다.
  출발시각이 같으므로 최소 소요시간과 동일하다. 동률은 candidate key 순이다.
  목표 도착에 모두 늦어도 최소시간 후보를 LATE_ESTIMATE로 반환한다. 모두 차단/계산 불가이면 null이다.
- 모델의 참조 날짜 범위·요청 상관키·결과 키·예측 대상·수치·계산시각을 검증한다.
  모델 mean_seconds는 0~86400초의 유한값만 받는다. 이는 비정상 응답 방어 상한이며 학습 정확도 기준이 아니다.
- 한 비교 실행은 외부 요청 64회/20초 한도(진행 중 요청 timeout만큼 초과 가능), 동일 ID·날짜·시간대 캐시를 사용한다.
  API가 배치 형식이어도 다음 edge의 진입시간이 앞 예측에 의존하여 현재는 edge당 한 건씩 순차 호출한다.
  최대 1000개 배치 지원을 처리 성능 보장으로 해석하지 않는다. 신규 지각/통제로 후보를 갱신하면 한 번 더 비교할 수 있다.

## 결과와 출처

기존 POST `/api/v1/schedules/{scheduleId}/recommendations` 및 GET `/recommendations/latest`를 사용한다.
인증과 일정 소유권을 검사하며 평가 결과는 schedule_evaluation.result_json에 저장한다.

```json
{
  "preview": {
    "status": "PREVIEW",
    "productionReady": false,
    "fastest": {
      "journeyKey": "candidate-1",
      "departureAt": "2026-09-11T00:00:00Z",
      "estimatedArrivalAt": "2026-09-11T00:10:00Z",
      "assessment": "ON_TIME_ESTIMATE",
      "tentative": true
    },
    "candidates": [],
    "predictions": [],
    "budgetExhausted": false
  }
}
```

위는 핵심 필드만 보인 축약 예시다. 실제 candidates에는 각 Result, quality, mlSteps, apiSteps, subwaySteps가 있다.
quality는 ML_PREVIEW(전 요소 ML), SUBWAY_PREVIEW(전 요소 지하철 시간표/실시간), MIXED_PREVIEW(여러 출처), API_ONLY다.
지하철의 별도 계산·적용 여부는 subwayPredictions에 보존한다. 지하철 ML은 호출하지 않는다.
입력 시간표·실시간 유효성·대기 중복 방지 조건은 [지하철 계산 계약](subway-timing.md)을 따른다.
predictions에는 provider 입력, 실제 진입 예상시각, 모델 버전/계산시각/결측 수/표본 수,
초 단위 결과, 실패 이유, applied가 저장된다. 모델 실패를 0초 또는 ML 성공으로 표시하지 않는다.
ML_NOT_CONFIGURED, ML_TIMEOUT, ML_HTTP_UNAVAILABLE, ML_INVALID_RESPONSE,
UNSEEN_PROVIDER_ID, REFERENCE_DATE_UNSUPPORTED 등을 구분한다.

selected는 기존 선택 경로의 평가이며 최단 후보와 다를 수 있다. 최종 검증 후보는 preview.fastest다.
suggestion.proposed는 기존 응답 형태와의 호환을 위해 같은 최단 후보를 담는다.
새 PREVIEW 평가에서는 recommendation 테이블에 운영 추천을 만들지 않고 알림도 생성하지 않는다.
수락 API는 PREVIEW를 INVALID_STATE로 거절한다. 출발시각을 자동으로 바꾸지 않는다.
클라이언트는 검증용 최소시간 후보와 구간별 ML/API 사용량을 표시하고 적용 버튼은 숨긴다.

기존 상태 PENDING/READY/STALE/FAILED/STOPPED/DEPARTURE_REQUIRED를 유지한다.
READY는 계산 완료이며 모델 운영 승인과 다르다. 카카오 전체 실패는 FAILED, ML 실패만 있으면
API_ONLY 등 대체 결과를 반환한다. 버스 배차를 보장하지 않으며 조기 출발 탐색은 이번 검증 흐름에서 실행하지 않는다.

## 저장·재계산·이벤트

V5의 planned_depart_at, nullable percentile_used, schedule_evaluation 구조를 그대로 사용한다. 추가 migration은 없다.
사용자 시간은 기존 API와 같은 KST LocalDateTime, 결과/모델 계산시각은 ISO 8601 절대시각, 기간은 초다.
120초 lease와 입력 updated_at 검증으로 늦은 결과 덮어쓰기를 막는다. 외부 호출은 DB 트랜잭션 밖이다.
최초 평가 후 출발 60~30분 전 10분, 30~10분 전 5분, 10분 이내 1분 간격으로 평가한다.
카카오 후보 캐시 기본은 900초, 목록 감시기는 15초다. 출발 입력 없는 기존/반복 일정은 입력 후 평가한다.
출발 확인·취소·완료·목표 도착시간 경과 시 감시를 종료한다.

이벤트는 이미 매핑된 구간 ID와 시간 교집합을 사용한다. blocked는 해당 이용시각에 겹치는 후보를 제외한다.
집회/사고 원문에서 영향 도로·방향·canonical 구간 ID를 만드는 앞단은 여전히 후속 작업이다.
행사 존재만으로 몇 분 지연을 만들지 않는다. 영향 일정 트리거는 기존 선택 route의 route_segment_edge로 조회한다.
POST /internal/recommendations/segment-changes와 opt-in Kafka segment.status 소비 계약은 유지한다.

## 제한과 검증

현재 v12는 production_ready=false, training_ids_verified=false다. 서버 마스터의 경로 인접성 검증과
모델 학습 원천의 ID 조합 검증은 다르다. 모델 정확도와 실제 사용자 경로 매핑 검증은 별도로 필요하다.
실시간 교통/날씨 특성을 모델에 연결한 상태가 아니다. 지원 날짜 밖은 새 참조 묶음을 만들어야 한다.

실행 설정과 테스트는 [백엔드 실행 메모](../../backend/SCHEDULE_EVALUATION.md)를 따른다.
실제 카카오 수집→실시간 이벤트→화면/FCM의 운영 전체 검증과 Flutter 빌드는 별도 미검증이다.
