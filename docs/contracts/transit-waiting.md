# 버스·지하철 대기시간 — transit-prev-v3

일정 출발 → 도보 → 정류장/승강장 도착 → 탈 수 있는 차량 선택 → 대기 → 이동 → 다음 환승 순서로 계산한다.
사용자 여유시간 입력이나 고정 가산 시간은 추가하지 않는다. DB migration, 새 외부 API 호출, 알림 전송 변경은 없다.

## 버스

`BusWaitingService`는 기존 `transit:latest:bus-arrival:{서울노선ID}:{승차정류소ID}`를 읽는다.
현재 지원은 canonical/provider ID와 순번이 일치하는 서울 BUS 매핑이다. 경기·인천 ID를 서울로 변환하지 않는다.
노선·정류소 외에 `staOrd`도 비교하여 같은 정류소를 반대 방향으로 재방문하는 경우를 혼동하지 않는다.

공식 [서울 버스도착 API](https://www.data.go.kr/data/15000314/openapi.do) 명세에서
`exps1/2`는 지수평활 도착예정시간(초), `traTime1/2`는 여행시간(분)이다.
Spark의 기존 `firstArrivalSeconds/secondArrivalSeconds`를 exps 기반으로 수정했다.
새 값에는 `arrivalTimeBasis=SEOUL_EXPS_SECONDS_V1`을 넣고 이 표시가 없는 예전 Redis 값은 거절한다.
traTime은 별도 `firstTravelTimeMinutes/secondTravelTimeMinutes`로 보존한다.

계산식은 `차량 예상 도착 = 원본 제공시각 mkTm + exps 초`, `대기 = 차량 예상 도착 - 사용자 정류장 도착`이다.
조회 시각에 남은 초를 다시 더하지 않는다. 제공시각 누락을 현재시각으로 채우지 않는다.
소수초가 있는 mkTm도 KST 절대시각으로 읽는다.

- 도착 전에 떠나는 첫 번째 버스는 건너뛰고 두 번째를 확인한다. 둘 다 놓치면 대기는 null이다.
- 0초·음수·잘못된 타입·누락 차량 ID를 즉시 탑승으로 간주하지 않는다.
- 우회, 만차, 최종 정류소 도착 상태 또는 상태 미확인은 사용하지 않는다.
- 같은 차량 ID가 중복된 두 예측은 모호한 입력으로 거절한다.
- 미래/만료 관측을 거절한다. 발생 후 120초 이내와 validUntil을 확인하고 정류장 도착·탑승도 유효범위 안이어야 한다.
- Redis 연결 실패/미수집/필수 필드 결측을 이유로 반환한다. 배차간격으로 가상의 세 번째 버스를 만들지 않는다.
- 한 평가에 최대 256키를 읽고 성공/결측/오류를 평가 내부에서 고정한다. API 재시도·KEYS/SCAN은 없다.
- Redis 장애가 확인되면 같은 평가의 다른 노선에서도 재호출하지 않는다. 이전에 확보한 값은 고정한 채 사용한다.

실제 대기를 전체 경로에 적용하려면 `ddubi.bus-waiting.decomposition-verified=true`가 필요하다.
이는 카카오 step의 대기 제외 및 접근·환승 도보 포함을 실제로 검증한 환경에만 설정한다.
또한 총시간 잔여가 0이고 BUS step 전체 ML 이동시간이 성공해야 한다.
대기 후 탑승시각으로 ML 입력 날짜·시간대를 결정한다. ML 일부 실패 시 대기도 함께 미적용 처리하고
BUS step 전체를 API 시간으로 복귀한다. 분해 미확인 대기를 API 총시간 위에 더하지 않는다.
대기 제외가 확인된 설정에서 WAIT 적용에 실패하면 API 이동시간만으로 ETA를 만들지 않는다.
결측 WAIT를 남겨 `INSUFFICIENT_DATA`, ETA=null로 반환하며 최소시간 후보에서 제외한다.
지하철에서도 대기 제외가 확인된 설정에서 시간표/탑승열차가 없으면 같은 원칙을 적용한다.

## 지하철

[기존 지하철 계산 계약](subway-timing.md)의 시간표·실시간 도착·위치 검증을 사용한다.
대기는 `탑승 기준시각 - 승강장 도착시각`이며 도보·환승시간 이후의 시각으로 다음 열차를 선택한다.
지하철 대기에도 ML을 사용하지 않는다. 실제 시간표가 없으면 대기 값은 null이다.
전체 반영에는 기존 `ddubi.subway-timing.decomposition-verified`와 잔여시간 0 조건이 필요하다.

두 교통수단의 WAIT는 RIDE와 분리하여 선로·도로 통제를 대기 위치의 통제로 오인하지 않게 한다.

## 응답·화면

`preview.waitPredictions[]`에 두 교통수단을 같은 구조로 제공한다.

```json
{
  "candidateKey": "candidate-0", "stepKey": "step-2", "mode": "BUS",
  "stationAt": "2026-09-11T09:00:10+09:00", "seconds": 30,
  "boardingAt": "2026-09-11T09:00:40+09:00", "vehicleId": "102",
  "source": "BUS_REALTIME_ARRIVAL", "eventId": "example-event",
  "observedAt": "2026-09-11T08:59:30+09:00", "applied": true, "reasons": []
}
```

위는 합성 예시다. seconds=null은 정보 부족, 0은 실제 계산된 대기 0초다.
applied=false이면 별도 대기 추정은 있어도 전체 ETA에는 반영하지 않았다.
`candidates[].busWaitSteps`는 반영한 버스 WAIT 수이며 지하철은 기존 subwaySteps와 waitPredictions로 확인한다.
버스 ML+실시간 대기 후보는 MIXED_PREVIEW로 표시한다. 지하철 상세 subwayPredictions는 유지한다.
화면은 대기 분·초, 시간표/실시간 출처, 반영 여부 또는 정보 부족을 표시한다.
이전 저장 결과의 새 배열 누락은 빈 목록, busWaitSteps 누락은 0으로 읽는다.

## 연결 및 제한

Spark 잡을 새 코드로 재시작하면 다음 수집 이벤트부터 새 형식이 나온다. 이전 Redis 값을 삭제하거나 TTL을 늘리지 않는다.
기본 버스 수집 주기는 8시간으로 대기 예측의 120초 신선도보다 길다. 따라서 **현재 기본 설정은 상시 실시간 대기를 제공하지 못한다.**
활성 일정 노선에 대한 수집 범위·주기를 API 호출 한도에 맞춰 수집 담당과 조정해야 한다.
지하철은 공식 운행일별 시간표/열차 ID 대응표 공급도 필요하다. 실제 데이터가 없다는 이유로 0분 대기를 반환하지 않는다.
실측 정시율·탑승 성공률과 Flutter 빌드는 별도 검증이 필요하다. 현재 응답은 검증용 PREVIEW다.
