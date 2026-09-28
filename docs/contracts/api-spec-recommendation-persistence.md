# 명세 개정 — 추천 저장과 `recommendationId` (REC-001)

> 요청: 프론트, 2026-09-14 (연동 질의 4·5번) · 구현: 백엔드
> 대상 화면: 추천 결과 / 최신 추천 / 추천 근거

## 왜 바꾸나

프론트가 `recommendationId` 를 받지 못해 **추천 근거 화면을 아예 열 수 없었다.**

원인은 서버가 추천을 하나도 저장하지 않고 있었기 때문이다. 저장 조건이
"검증용(PREVIEW) 결과가 아닐 것" 이었는데, 예측 서비스가 미승인 상태
(`production_ready=false`)라 **모든 결과가 PREVIEW** 였다. 즉 조건이 영원히 거짓이었다.

이제 **ETA를 계산해 낸 결과는 저장한다.** 검증용이라는 사실은 숨기지 않고 응답과 DB 행에
함께 남긴다.

---

## 1. PREVIEW 표시와 테스트 적용은 분리한다 (2026-09-22)

| | 지금 | |
| --- | --- | --- |
| 추천 DB 저장 · `recommendationId` 반환 | ✅ 열림 | 기록일 뿐이라 되돌릴 수 있다 |
| **추천 수락** (`POST .../recommendations/accept`) | ✅ `TEST_APPLY`에서 수동 QA 가능 | 응답에는 계속 `PREVIEW`·`EXPERIMENTAL_ESTIMATE` 표시 |
| **자동 알림** (FCM) | ❌ 발송 안 함 | 사용자 **폰이 울린다** |

`TEST_APPLY`는 운영 승격이 아니라 팀의 휴대폰 테스트를 위한 명시적 시험 모드다. 실제 평가 사유에
`ML_TEST_APPLIED`가 있고 탐색이 완료된 경우에만 `guidance.canAccept=true`다. 단순 PREVIEW 호출이나
SHADOW 결과는 적용되지 않는다. `productionReady=false`와 시험 출처는 그대로 내려 보내 화면에서 구분한다.

앱은 `guidance.canAccept`가 true면 시험 제안도 수동 적용 버튼을 활성화할 수 있다. 자동 알림과 운영
계약은 별도이며, 이 변경만으로 `production_ready=true`가 되지 않는다.

---

## 2. `POST /api/v1/schedules/{scheduleId}/recommendations`

응답 `data` 에 두 가지가 추가됐다.

```jsonc
{
  "data": {
    "recommendationId": 12,        // ← 저장 실패면 null
    "persistence": {
      "saved": true,
      "reason": null,              // saved=false 일 때만 채워진다
      "preview": true,             // ← 화면에 "검증용" 표시
      "status": "PREVIEW",
      "productionReady": false,
      "source": "API_ONLY",        // 계산 출처
      "fallbackUsed": true,
      "mlSteps": 0, "apiSteps": 2, "subwaySteps": 0, "busWaitSteps": 0,
      "reasons": ["step-1:ML_NOT_CONFIGURED", "VALIDATION_ONLY", ...]
    }
    // ... 기존 필드 그대로
  }
}
```

### `source` — 이 숫자가 어디서 나왔나

| 값 | 뜻 |
| --- | --- |
| `API_ONLY` | 전부 경로 API 시간. **현재 사실상 항상 이 값이다** |
| `ML_PREVIEW` | 버스 구간을 예측 모델로 계산 |
| `SUBWAY_PREVIEW` | 지하철 구간을 시간표로 계산 |
| `MIXED_PREVIEW` | 섞임 |

`fallbackUsed` 는 "경로 API 시간을 그대로 쓴 step 이 있는가" 다.
**도보 구간도 경로 API라 지금은 거의 항상 true** 다. 출처를 구분하려면 이 값이 아니라
`source` 와 step 개수를 보는 편이 정확하다.

### `reasons` — 데이터 부족 사유

선택된 후보의 계산 사유 **전체**다. 서버가 "부족한 것만" 골라내지 않는 이유는, 분류 규칙이
프론트·백엔드 양쪽에 생기면 언젠가 갈라져서 **화면에 보이는 근거와 저장된 근거가
달라지기** 때문이다. 자주 나오는 값:

| 코드 | 뜻 |
| --- | --- |
| `VALIDATION_ONLY` | 검증용 계산이다. 항상 붙는다 |
| `step-N:ML_NOT_CONFIGURED` | 예측 서비스가 아직 안 붙어 있다 |
| `step-N:BUS_WAIT_MISSING` | 버스 대기시간을 알 수 없다 |
| `SUBWAY_TIMETABLE_NOT_CONFIGURED` | 지하철 시간표 미설정 |
| `ROAD_POTENTIAL_IMPACT:*` | 그 구간에 통제·사고 기록이 있다 |

## 3. 저장하지 못하는 경우

`recommendationId` 가 `null` 이고 `persistence.saved` 가 `false` 다.

| `persistence.reason` | 뜻 | 화면 |
| --- | --- | --- |
| `ETA_UNAVAILABLE` | 도착 시각을 계산하지 못했다 | "정보가 부족해 계산할 수 없습니다" + `reasons` |
| `NO_CANDIDATE` | 계산 가능한 후보가 하나도 없다 | 같음 |
| `ROUTE_NOT_STORED` | 선택 후보의 경로가 저장돼 있지 않다 (내부 오류에 가깝다) | 재시도 안내 |

**"아직 계산 중" 과 구분해 주세요.** 둘 다 `recommendationId` 가 null 이지만 전혀 다른
상황입니다 — 계산 중이면 최신 추천 조회의 `state` 가 `PENDING` 이고, 여기 오는 건
계산은 끝났는데 근거가 없는 경우입니다.

## 4. `GET /api/v1/schedules/{scheduleId}/recommendations/latest`

`data.result` 안에 위와 **똑같은** `recommendationId` · `persistence` 가 들어 있다.
화면을 다시 그릴 때 추천을 새로 생성할 필요가 없다.

## 5. `GET /api/v1/recommendations/{recommendationId}/explanation`

요약 필드가 추가됐다. **원문(`reasons`, `segmentStatusSnapshot`)은 그대로 나간다.**

```jsonc
{
  "data": {
    "recommendationId": 12,
    "departAt": "2026-09-14T14:37:43",
    "eta": "2026-09-14T14:48:13",
    "durationMin": 11,
    "confidence": "LOW",
    "dataCheckedAt": "2026-09-14T14:27:43",
    "policyVersion": "sched-v1",

    "preview": true,               // ← 검증용 표시
    "previewStatus": "PREVIEW",
    "productionReady": false,
    "source": "API_ONLY",
    "fallbackUsed": true,
    "reasonCodes": ["step-1:ML_NOT_CONFIGURED", "VALIDATION_ONLY", ...],

    "reasons": "{...}",            // 저장된 원문 JSON 문자열 (그대로)
    "segmentStatusSnapshot": "{...}"
  }
}
```

`preview` 이하가 **전부 null** 이면 이 정보를 남기기 전에 만들어진 옛 추천입니다.
그 경우에도 조회는 성공하고 원문 근거는 나갑니다 — 검증용 배지만 표시하지 마세요.

소유권은 **추천이 매달린 일정**으로 판단합니다. 남의 추천 ID를 찍으면 `403` 입니다.

## 6. `confidence` 는 항상 `LOW` 입니다

미승인 모델로 계산한 값이라 행에도 그렇게 남깁니다. 규약 4장 규칙 8의 등급
(HIGH/MID/LOW)이 실제로 갈리는 건 예측 서비스와 `baseline_serving` 이 붙은 뒤입니다.

## 스키마 변경 없음

`recommendation` 테이블은 그대로입니다. 검증용 표시는 기존 `reasons`(JSON) 안에
`persistence` 객체로 들어갑니다. **마이그레이션 없음, 로컬 DB 초기화 불필요.**

## 관련 문서

* `docs/contracts/recommendation-data-provenance.md` — 값의 출처와 균등 분배의 한계
* `docs/contracts/ml-serving-http.md` — `production_ready=false` 의 의미
