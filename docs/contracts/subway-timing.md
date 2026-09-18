# 지하철 시간표·실시간 예상시간 계약

2026-09-10. 지하철은 ML을 호출하지 않는다. `SubwayTimingService`가 시간표, 현재 도착예측,
열차 위치를 사용하고 `MlRoutePreview`가 버스 ML·도보와 같은 타임라인에서 비교한다.
공식 CSV의 날짜별 변환 도구와 로컬 Replay가 있다. 기본 시간표 경로는 미설정이며,
실시간 열차 대응표와 운영 유효성 확인을 완료했다는 의미는 아니다.
실행 방법은 [시간표 생성·실제 응답 검증](../../backend/tools/transit-timing/README.md)을 따른다.

## 계산 흐름

1. 카카오 SUBWAY step의 EXACT 매핑, 마스터 버전, canonical 노선·역·방향과 provider ID를 확인한다.
2. 정규화된 시간표에서 같은 경로를 지나며 승차·하차역에 모두 정차하는 열차를 찾는다.
   급행의 무정차역, 다른 방향, 중간 종착, 버전 불일치는 선택하지 않는다.
3. 검증된 열차 ID가 있으면 Spark Redis 도착·위치를 MGET한다. 없으면 시간표만 사용하고
   `SUBWAY_REALTIME_TRAIN_CROSSWALK_MISSING`을 남긴다. KEYS/SCAN과 외부 API 재호출은 없다.
4. 승강장 도착 전에 떠난 열차는 제외하고 다음 열차를 검토한다. 후보 중 하차역에 가장 일찍 도착하는 열차를 선택한다.
5. 다음 도보·환승·탑승 계산은 앞 구간의 예상 도착시각부터 이어진다.

전체 타임라인에는 지하철 WAIT와 RIDE를 분리한다. WAIT에는 선로 구간 ID를 붙이지 않아
탑승 전에 끝난 선로 통제가 대기시간과 겹친다는 이유만으로 후보를 제외하지 않는다.

시간표 계산:

- `대기 = 승차역 출발 예정시각 - 승강장 도착 예상시각`
- `이동 = 하차역 도착 예정시각 - 승차역 출발 예정시각`

실시간 계산:

- `탑승 기준시각 = 원본 정보 생성시각 + arrivalSeconds`
- `대기 = 탑승 기준시각 - 승강장 도착 예상시각`
- `이동 = 시간표의 하차역 도착시각 - 시간표의 승차역 도착시각`
- 실제 출발시각은 제공되지 않으므로 승차역 정차시간도 이동 추정에 포함한다.
  예측 도착 전에 승강장에 도착해야 탑승 후보로 취급하며 문이 닫힐 시각을 추측하지 않는다.

위치 정보는 경로상 이미 승차역을 지난 열차를 제외하는 근거다. 위치 조회 시각을 실제 역간 출발시각으로
간주하여 속도나 지연 초를 만들어내지 않는다. 실시간 도착예측이 없으면 `SUBWAY_TIMETABLE`로 표시한다.
시간표도 실제 운행/정시 도착 보장은 아니다.

## 시간표 입력

Spring property `ddubi.subway-timing.timetable-path`로 로컬 JSON 경로를 설정한다. 파일은 기동 시 고정된다.
미설정은 `SUBWAY_TIMETABLE_NOT_CONFIGURED`, 손상/검증 실패는 `SUBWAY_TIMETABLE_INVALID`로 반환한다.
수집팀은 공식 자료를 아래 형식으로 변환한 뒤 마스터와 열차 ID 대응을 검증하여 배포해야 한다.
파일 최대 32 MiB. 기계 형식은 [subway-timetable.schema.json](subway-timetable.schema.json).

| 필드 | 의미 |
|---|---|
| version / masterDataVersion / source | 시간표 버전, 매핑에 사용한 마스터 버전, 원천·가공 버전 출처 |
| publishedAt | 해당 버전 정보를 확보한 시각. 평가 asOf 이후 자료는 사용할 수 없음 |
| validFrom / validUntil | 운행 적용 시작/끝(끝 제외), 날짜를 포함한 ISO 8601 절대시각 |
| trips[].key / serviceDate | 운행 식별자와 KST 운행일. 평일/주말/공휴일 시간표를 실제 날짜로 전개 |
| lineId / direction | canonical 마스터 노선 ID와 방향 |
| providerLineId / trainId | 실시간 API 노선 ID와 해당 운행 열차 ID. 시간표 TRAIN_NO와 동일하다고 추측 금지. 정적 계산은 null 허용 |
| arrivalDirection / positionDirection | 각각의 API에서 확인한 원문 방향 코드. 서로 같은 코드라고 추측 금지 |
| arrivalDestination / positionDestination / trainType | 각각의 API 원문 행선지와 도착 API의 열차 종류 |
| calls[].stationId / providerStationId | 정적 역 ID와 검증된 실시간 역 ID. 정적 계산은 providerStationId=null 허용 |
| calls[].arrivalAt / departureAt / stops | 실제 날짜가 포함된 예정시각, 정차 여부. 첫 역 도착/끝 역 출발만 null 허용 |
| limitations | 원천 커버리지·최신성·운행일 선택의 제약. estimate.reasons에도 전달 |

모든 call은 순서대로 주어지며, 이전 출발보다 다음 도착이 늦어야 한다.
정적 시간표는 같은 역의 재방문을 절대시각으로 구별해 다음 회차까지 검색한다.
실시간 대응이 있는 trip은 동일 역 재방문 및 동일 운행일·provider 노선·열차 ID 중복을 거절한다.
위치 스냅샷만으로 어느 회차인지 추측하지 않는다.
`trainId=null`이면 arrivalDirection/positionDirection/arrivalDestination/positionDestination/trainType도
모두 null이어야 한다. 부분 대응을 실시간 조회에 사용하지 않는다. `trainId`가 있으면 모든 provider ID와 원문 식별값이 필수다.
첫 역 도착 결측은 0초 정차로 채우지 않으며 해당 역의 실시간 도착 보정은 적용하지 않는다.
운행일 00:00부터 다음날 06:00 미만까지 지원하므로 24시 이후 자료는 다음날 날짜로 변환한다.
최대 2048개 탑승 후보, 한 평가 최대 1024개 Redis 키를 넘으면 일부만 평가하지 않고 `SUBWAY_LOOKUP_BUDGET_EXCEEDED`로 반환한다.
실제 일일 운행은 256회를 넘을 수 있어 정적 후보와 외부 조회 예산을 구분한다. 정적 시간표는 Redis 키를 소비하지 않는다.

다음은 **실제 열차가 아닌 합성 예시**다. 테스트/형식 설명용이며 운영 기본값으로 배포하지 않는다.

```json
{
  "version": "example-only-v1", "masterDataVersion": "example-master", "source": "synthetic-example",
  "publishedAt": "2026-09-09T12:00:00+09:00",
  "validFrom": "2026-09-10T00:00:00+09:00", "validUntil": "2026-09-11T06:00:00+09:00",
  "trips": [{
    "key": "example-trip", "serviceDate": "2026-09-10", "lineId": "EXAMPLE", "direction": "FORWARD",
    "providerLineId": "example-line", "trainId": "example-train",
    "arrivalDirection": "내선", "positionDirection": "0",
    "arrivalDestination": "예시종점", "positionDestination": "예시종점", "trainType": "일반",
    "calls": [
      {"stationId": "A", "providerStationId": "PA", "arrivalAt": "2026-09-10T09:01:00+09:00", "departureAt": "2026-09-10T09:01:00+09:00", "stops": true},
      {"stationId": "B", "providerStationId": "PB", "arrivalAt": "2026-09-10T09:05:00+09:00", "departureAt": "2026-09-10T09:05:00+09:00", "stops": true}
    ]
  }]
}
```

## 실시간 입력·결측

기존 `transit:latest:subway-arrival:{providerLineId}:{providerStationId}:{providerTrainId}` 및
`transit:latest:subway-position:{providerLineId}:{providerTrainId}`를 읽는다. DB/키/TTL 변경은 없다.
Spark observation에 `sourceGeneratedAt`(두 이벤트)과 `arrivalTrainType`(도착)을 선택 필드로 추가했다.
이전 payload는 하위 호환되지만 새 필드가 없으면 실시간 대신 시간표로 fallback한다.

- eventType·노선·역·열차·방향·행선지·종류를 확인한다. 방향/행선지가 충돌하는 열차는 시간표로도 선택하지 않는다.
- occurredAt과 sourceGeneratedAt이 같고, 발생/수집이 asOf 이전이며, validUntil 및 발생 후 120초 안이어야 한다.
- 정보 생성시각이 없으면 수집시각으로 채워서 실시간이라고 하지 않는다.
- 현재 trainId 정보를 다른 운행일의 같은 번호에 적용하지 않는다.
- 승강장 도착과 예측 탑승시각 모두 validUntil 안일 때만 실시간 보정을 사용한다. 미래 일정은 시간표다.
- arrivalSeconds는 정수 1~1800초만 지원한다. 0은 즉시 탑승 보장으로 해석하지 않는다.
- 원본 순서 역전은 Spark 최신값 경계에서 처리한다. Backend는 평가 내부에서 같은 키를 한 번만 읽어 고정한다.
- Redis 장애/결측/만료는 시간표 fallback 이유로 남는다. 시간표도 없으면 API 시간과 계산 불가 이유를 유지한다.
  단, 대기 제외가 확인된 설정에서 필요한 WAIT가 없으면 최종 ETA는 정보 부족으로 반환한다.
  API 이동시간을 남기는 것과 대기 없이 도착 가능하다고 판정하는 것은 구분한다.

원본 생성시각을 고려해야 한다는 근거: [서울시 지하철 실시간 도착정보](https://data.seoul.go.kr/dataList/OA-12764/A/1/datasetView.do).
위치 원천: [서울시 지하철 실시간 열차 위치정보](https://data.seoul.go.kr/dataList/OA-12601/A/1/datasetView.do).

## 전체 경로 반영과 응답

카카오 총시간에는 위치가 확인되지 않은 대기·접근시간이 있을 수 있다. 새 대기를 단순 가산하지 않는다.
`ddubi.subway-timing.decomposition-verified` 기본값은 false다. 실제 응답에서 **step은 대기를 제외하고,
승강장 접근/환승 도보는 누락 없이 별도 포함됨**을 검증한 제공자 설정에만 true로 둔다.
true여도 `카카오 총시간 - step 합 != 0`이면 합계를 교체하지 않는다. 이 검증을 설정만으로 대신할 수 없다.

- `preview.subwayPredictions[]`: candidateKey, stepKey, platformAt, estimate, applied, reason.
- estimate: waitSeconds, rideSeconds, boardingAt, arrivalAt, tripKey, source, timetableVersion,
  arrivalEventId, observedAt, positionEventId, reasons. 시간은 초/ISO 8601.
- `applied=false`, `SUBWAY_TIME_COMPONENTS_UNRESOLVED`: 별도 계산은 있으나 전체 ETA는 API 유지.
- `preview.candidates[].subwaySteps`: 실제 시간표/실시간 적용 step 수. mlSteps에 합치지 않는다.
- 버스와 함께 보는 공통 대기 결과는 `preview.waitPredictions`다. [대기시간 계약](transit-waiting.md)을 따른다.
- quality: SUBWAY_PREVIEW 또는 MIXED_PREVIEW. 기존 ML_PREVIEW/API_ONLY도 유지.
- PREVIEW·productionReady=false, 자동 출발 변경/알림/수락 차단은 유지한다. 새 migration은 없다.

09:00 승강장 도착 → 09:01 탑승 → 09:05 하차라면 waitSeconds=60, rideSeconds=240,
arrivalAt=09:05다. 환승 도보가 45초라면 다음 승강장 진입은 09:05:45이며 그 이전 열차는 선택하지 않는다.

운영 연결 전 남은 작업: 최신 시간표·운행 달력 검증 및 정기 배포, 실시간 열차 ID crosswalk 공급, 실제 카카오 시간 분해 확인,
API 한도 내 수집 주기 조정(현재 주기가 120초 신선도보다 길면 대부분 시간표 fallback), 실제 경로로 정확도/호출량 검증.
