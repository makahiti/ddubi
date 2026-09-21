# 추천 안내 카드 API 계약 (2026-09-22)

## 목적

`GET /api/v1/schedules/{scheduleId}/recommendations/latest/guide`는 최신 평가의 출발·도착·경로·요금을
구조화해 반환한다. GMS는 서버가 확정한 사실을 자연스러운 한국어로 편집할 뿐 시각, 요금, 경로 순서,
변경 유형 또는 지연 판정을 만들지 않는다. GMS 실패 시에도 같은 사실로 서버 템플릿을 반환한다.

## 변경 유형

서버는 선택 경로와 제안 경로, 기존/제안 출발시각, 기존/새 ETA를 비교해 하나를 확정한다.

| `changeType` | 의미 |
| --- | --- |
| `ROUTE_CHANGED` | 선택 경로와 다른 경로를 제안 |
| `DEPARTURE_TIME_CHANGED` | 같은 경로에서 출발시각 변경을 제안 |
| `ETA_UPDATED` | 경로·출발은 같고 예상 도착만 변경 |
| `NO_CHANGE` | 현재 선택 경로와 출발시각 유지 |

`recommendationReason`은 위 판정과 확인된 교통 사실을 사용자 문장으로 설명한다. TOPIS 사고·통제 원인이
평가의 `causes`에 있으면 `delay.reason`과 `delay.source`를 함께 사용한다. 아직 확인되지 않은 원인은
`confirmed=false`로 유지하고 GMS 문장에도 “가능성” 또는 “확인 중” 표현을 강제한다.

```json
{
  "schemaVersion": "recommendation-guide-v1",
  "departureAt": "2026-09-22T08:00:00",
  "estimatedArrivalAt": "2026-09-22T08:46:00",
  "durationSeconds": 2760,
  "fare": { "minimumWon": 1650, "maximumWon": 1650 },
  "headline": "08:00 출발 · 08:46 도착 예정",
  "changeType": "ROUTE_CHANGED",
  "recommendationReason": "도로 통제 영향 구간을 피해 다른 경로를 제안해요.",
  "routeSummary": "도보로 역까지 이동한 뒤 지하철을 이용하세요.",
  "routeSteps": [],
  "delay": {
    "status": "CONFIRMED",
    "delaySeconds": 420,
    "reason": "TOPIS 도로 통제",
    "source": "TOPIS",
    "confirmed": true
  },
  "generatedBy": "GMS:gpt-5.5-pro"
}
```

`fare`는 제안 평가에 사용한 경로의 값이다. 저장 경로 목록의 요금은 각 `route` 행에 별도로 보존하며,
서로 다른 경로의 요금을 화면에서 섞어 쓰지 않는다.
