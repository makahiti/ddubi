# 추천 기능 전체 수정 작업서

작성일: 2026-09-17  
기준 커밋: `dd094eb62282e2f89193710a2bc1646181268dc3`

## 1. 목적

현재 조건부 출발 기능은 API 호출과 화면 표시는 동작하지만, 기본 경로 API의 대체 소요시간을 실시간 추천처럼 보이게 할 수 있다. 이 작업의 목표는 목표 도착시각에 맞는 출발 안내를 제공하고, 데이터가 부족할 때는 정확한 시각을 추천하지 않도록 FE·BE·DT 계약과 완료 기준을 맞추는 것이다.

공통 원칙:

1. 검증용·불완전 결과를 확정 추천처럼 표시하지 않는다.
2. 실제 적용된 데이터만 실시간 정보로 표시한다.
3. 내부 segment ID와 reason code를 사용자 화면에 노출하지 않는다.
4. 데이터가 부족하면 정확한 출발시각 대신 제한 사유를 안내한다.
5. 수집기 실행, Kafka 유입, 서버 healthy만으로 추천 완료를 판단하지 않는다.

## 2. 현재 확인된 상태

- 테스트 일정의 목표 도착은 19:30이지만 저장된 계획 출발 14:05가 지나자 현재 시각을 `DEPART_NOW`로 표시했다.
- 14:05는 추천 계산값이 아니라 일정 생성 당시 자동 입력된 `현재 + 30분` 값이었다.
- 추천 ETA는 `API_ONLY`, `preview=true`, `productionEligible=false`, `realtimeApplied=false`였다.
- 버스 37개 구간과 지하철 13개 구간의 증거가 누락됐다.
- 지하철 도착 원문은 수집되지만 평가기 시간표가 연결되지 않았다.
- 버스 도착·위치 수집 기본 주기는 28,800초이며 확인 회차는 0건이었다.
- `transport.raw`에는 데이터가 있지만 `segment.observation`, `segment.status`는 0건이었다.
- active 수도권 버스 마스터는 있으나 provider segment mapping은 20건, baseline은 3건이었다.
- 홈은 상세 진단 컴포넌트를 그대로 사용해 내부 코드를 노출한다.
- 로그인 상태 유지 체크박스와 실제 세션 복구가 연결되지 않았다.
- 알림 조회 응답에는 `evaluationId`가 없어 최신 평가 비교가 불가능하다.

## 3. 제품 동작 분리

### 3.1 최신 출발 추천

목표 도착시각을 기준으로 가장 늦으면서 정시 도착 가능한 출발시각을 찾는다.

```text
탐색 시작 = 현재 시각
탐색 종료 = 목표 도착시각 - 도착 여유시간
선택 기준 = 목표 도착을 만족하는 가장 늦은 출발
```

### 3.2 계획 출발 감시

사용자가 직접 확정한 `plannedDepartAt`을 기준으로 유지·앞당김·즉시 출발·지각 예상 여부를 판단한다.

두 기능을 같은 `DEPART_NOW` 문구로 섞지 않는다.

필드 역할:

- `userInputDepartAt`: 사용자가 최초 입력한 출발 계획
- `plannedDepartAt`: 현재 확정된 출발 계획
- `proposedDepartAt`: 서버가 제안한 출발시각
- `actualDepartAt`: 실제 출발시각
- `arriveBy`: 목표 도착시각

`proposedDepartAt`은 사용자가 수락하기 전 `plannedDepartAt`을 덮어쓰지 않는다.

경로 역할:

- `selectedRouteId`: 현재 사용자가 선택한 경로
- `proposedRouteId`: 서버가 제안한 경로
- `fastestPreviewRouteId`: 검증 과정의 최소 ETA 후보

## 4. 공통 품질 판정

다음 중 하나라도 해당하면 확정 추천과 추천 수락을 차단한다.

- `quality.preview == true`
- `quality.productionEligible == false`
- `decision`이 `SEARCH_INCOMPLETE`, `DATA_INSUFFICIENT`, `UNKNOWN`
- `estimatedArrivalAt == null`
- `quality.costCoverage != COMPLETE`
- 필수 대중교통 구간이 fallback으로만 계산됨
- 적용 근거의 `validUntil`이 지남

품질 상태는 다음처럼 구분한다.

| 상태 | 사용자 의미 |
|---|---|
| 운영 가능 | 추천 출발과 예상 도착 표시 |
| provider-only | 기본 경로 소요시간만 확인됨 |
| 일부 실시간 | 적용된 구간만 실시간임을 표시 |
| 탐색 미완료 | 최신 출발시각을 확정하지 못함 |
| 데이터 부족 | 교통정보가 부족해 추천 불가 |
| 만료 | 최신 결과 재조회 필요 |

---

# FE 작업

## 5. 잘못된 추천 노출 차단

preview, production 불가, 탐색 미완료, 데이터 부족, 비용 구성 불완전, stale 결과에서는 추천 출발시각과 `DEPART_NOW`를 확정 안내로 표시하지 않는다.

대체 문구:

```text
정확한 출발 추천을 준비하고 있어요.
현재는 기본 경로 소요시간만 확인됐어요.
```

`realtimeApplied=false`이면 `실시간 이동 상황을 반영했어요` 문구를 표시하지 않는다. 부분 적용이면 `일부 구간에만 실시간 정보를 반영했어요`로 표시한다.

## 6. 홈 화면 간소화

홈 표시 항목:

- 일정명
- 목표 도착시각
- 추천 상태
- 운영 가능한 경우에만 추천 출발·예상 도착
- 마지막 확인시각
- 상세보기

홈 제거 항목:

- 전체 missing/fallback 목록
- segment ID
- 후보 ID
- 정책·모델 내부 코드
- 탐색 횟수와 내부 유효성 정보

## 7. 내부 코드 집계

- `BUS:*` → `일부 버스 구간의 교통정보가 부족해요. (N개 구간)`
- `SUB:*` → `일부 지하철 구간의 운행정보가 부족해요. (N개 구간)`
- 시간표 결측 → `지하철 시간표 정보를 확인하지 못했어요.`
- 알 수 없는 코드 → `일부 교통정보를 확인하지 못했어요.`

알 수 없는 코드를 사용자 화면에 그대로 반환하지 않는다.

## 8. 출발시각 입력

새 일정에서 `현재 + 30분`을 사용자 확정값으로 자동 저장하지 않는다.

1. 초기값은 안내용으로만 표시한다.
2. 사용자가 날짜 또는 시간을 직접 선택해야 `departureConfirmed=true`가 된다.
3. 확인하지 않은 상태에서는 저장을 차단한다.
4. 안내 문구는 `출발 예정시각을 직접 확인해 주세요.`로 통일한다.
5. BE가 최신 출발 추천 모드를 제공하면 출발 계획 미입력 선택지를 추가한다.

## 9. 결정 문구

| decision | 문구 |
|---|---|
| KEEP_DEPARTURE | 현재 출발 계획을 유지해도 돼요 |
| ADVANCE_DEPARTURE | 계획보다 일찍 출발하는 것이 좋아요 |
| DEPART_NOW + missed | 계획한 출발시각이 지났어요. 현재 경로를 다시 확인해 주세요 |
| DEPART_NOW + not missed | 목표시간에 맞추려면 지금 출발해야 해요 |
| LATE_EXPECTED | 현재 확인된 경로로는 정시 도착이 어려워요 |
| DATA_INSUFFICIENT | 교통정보가 부족해 정확한 추천을 만들지 못했어요 |
| SEARCH_INCOMPLETE | 아직 최신 출발시각을 확정하지 못했어요 |

`bestKnownDecision`은 보조 설명에만 사용한다.

## 10. 경로 표시

- 대표 추천은 `proposedRouteId`를 기준으로 한다.
- 기존 선택과 제안 경로가 다르면 경로 변경임을 명시한다.
- production 불가 상태에서는 `추천 경로` 대신 `확인된 경로`라고 표시한다.
- provider 첫 후보를 최단 또는 추천으로 단정하지 않는다.

## 11. 로그인 상태 유지

1. refresh token 안전 저장
2. 앱 시작 시 세션 복구
3. refresh 성공 시 홈 이동
4. refresh 실패 시 저장 토큰 삭제 후 로그인 이동
5. 로그인 유지 미선택 시 프로세스 재시작 후 세션 제거
6. 로그아웃 시 저장 토큰과 device token 정리

비밀번호는 저장하지 않는다.

## 12. 알림 화면

BE 응답에 `evaluationId`가 추가되면 알림 평가와 최신 평가 비교, 만료 표시, 새 평가 표시와 최신 상세 이동을 연결한다. 계약 전에는 ID가 없는 알림을 최신이라고 추정하지 않는다.

## 13. FE 테스트

- preview에서 추천 출발시각 강조 없음
- `realtimeApplied=false`에서 실시간 문구 없음
- unknown reason 원문 미노출
- 다수 missing segment 집계
- `SEARCH_INCOMPLETE` 확정 추천 금지
- 출발시각 미확인 저장 차단
- 앱 재실행 로그인 복구
- refresh 실패 로그인 이동
- 선택 경로와 제안 경로 표시 구분

---

# BE 후속 작업

이번 문서는 `be/dev`에 직접 반영하지 않지만 다음 항목은 BE 수정 없이는 최종 해결되지 않는다.

## 14. 계산 모드와 탐색

- `LATEST_DEPARTURE`와 `MONITOR_PLANNED_DEPARTURE` 분리
- 최신 출발 추천에서 `plannedDepartAt`을 탐색 상한으로 사용하지 않음
- 목표 도착과 도착 여유시간을 만족하는 가장 늦은 후보 선택
- 탐색 미완료 시 `proposedDepartAt` 확정 금지

## 15. Production eligibility

현재 고정된 preview·production 값을 실제 품질 정책으로 교체한다.

```text
productionEligible =
  search.complete
  AND estimatedArrivalAt != null
  AND costCoverage == COMPLETE
  AND required transit timing available
  AND mapping coverage satisfied
  AND evidence not stale
  AND no mixed model version
```

## 16. 응답 계약

- 선택 경로·제안 경로·검증 최단 후보 분리
- segment ID 대신 결측 category와 개수 제공
- 실제 적용한 bus master, crosswalk, timetable, feature snapshot, model 버전 제공
- `CROSSWALK_VERSION_NOT_REPORTED`, `FEATURE_SNAPSHOT_VERSION_NOT_REPORTED` 무조건 추가 제거
- 알림 저장·조회에 `evaluationId`, `decision`, `validUntil` 제공

---

# DT·데이터 작업

## 17. 지하철 시간표 연결

1. 공식 시간표 원본 확보
2. 운행일·방향·열차·역 순서를 검증한 JSON artifact 생성
3. 정적 역 ID와 provider station ID 대응
4. 정적 열차번호와 실시간 train ID 대응
5. version, publishedAt, validFrom, validUntil 기록
6. 평가기 컨테이너 경로에 read-only 마운트
7. `DDUBI_SUBWAY_TIMING_TIMETABLE_PATH` 설정
8. 검증 전 `DDUBI_SUBWAY_TIMING_DECOMPOSITION_VERIFIED=false` 유지

평가기에는 importer용 CSV가 아니라 평가 계약에 맞는 JSON을 제공한다.

## 18. 버스 canonical mapping

매핑 키:

```text
provider
providerRouteId
fromStopId
toStopId
fromStopOrder
serviceDate
```

노선명이나 정류장명만으로 매핑하지 않는다. 방향·순서가 확인된 `EXACT`만 추천과 ML에 사용한다.

상태:

- EXACT
- UNVERIFIED
- UNSUPPORTED
- AMBIGUOUS

## 19. 버스 도착·위치 수집

28,800초 기본 주기를 변경하기 전에 API 일일 한도, 대상 노선 수, 요청 수, 재시도 예산을 산정한다. 전체 노선이 불가능하면 활성 일정과 최근 경로 노선을 우선 수집한다.

필수 운영 정보:

- 429·timeout backoff
- 마지막 성공시각
- 수집 건수
- 다음 허용 호출시각
- cooldown 사유
- 데이터 age

## 20. Segment observation/status

```text
버스 위치·도착 원문
→ identity 검증
→ canonical route/stop/order 매핑
→ 실제 구간 통과 이벤트
→ segment.observation
→ segment.status
→ Redis segment:status:{segmentId}
```

차량 현재 위치만으로 구간 통과를 확정하지 않는다.

필수 필드:

- eventId
- segmentId
- vehicleId
- observedAt
- enteredAt
- exitedAt
- source
- mappingVersion
- quality
- validUntil

완료 시 Kafka offset, Redis key·TTL, 경로 segment 일치와 BE 평가 snapshot 포함 여부를 확인한다.

## 21. Baseline과 ML

필수 축:

- canonical segment ID
- 요일
- KST 시간대
- 날씨 bucket
- 운행일
- 표본 수
- p50/p80/p95
- 데이터 범위
- artifact/model version

누수 방지 조건:

```text
feature_available_at <= prediction_base_at
```

적용 전 route ID, from/to stop, 순서, 날짜 범위, KST 진입시간과 모델 버전을 확인한다.

## 22. 도로·도보

도로:

- canonical bus segment와 road link crosswalk
- 원본 사건 ID와 시간 범위 유지
- 인접 사고를 확정 차단으로 단정하지 않음
- 해제 미확인과 만료 상태 구분

도보:

- 출발지 접근 도보
- 환승 도보
- 목적지 이탈 도보
- 도보 API 미적용 시 provider fallback 명시
- `unassigned` 시간으로 누락을 숨기지 않음

## 23. DT 모니터링

- 원천별 마지막 성공시각과 건수
- Kafka 유입 증가량
- consumer lag
- segment observation/status 생성량
- Redis key 수와 TTL
- mapping EXACT 비율
- ML 적용률
- 실시간 적용률
- fallback·stale 비율

컨테이너 `Up`만으로 정상 처리하지 않는다.

---

# 통합 검증과 완료 기준

## 24. 필수 시나리오

1. 목표 도착 19:30, 이동 40분인 일정에서 현재 즉시 출발이 아니라 합리적인 최신 출발을 제안한다.
2. 사용자가 확정한 계획이 안전하면 유지로 표시한다.
3. 지연 시 앞당김 제안과 기존 계획을 구분한다.
4. 시간표·매핑 부족 시 정확한 시각 추천을 차단한다.
5. 탐색 예산 소진 시 `SEARCH_INCOMPLETE`로 남기고 확정 추천·수락·알림을 차단한다.
6. 앱 재실행 후 로그인 유지 설정대로 이동한다.
7. 알림 재시도 후 푸시와 알림함 본문·평가 ID가 일치한다.

## 25. 최종 완료 조건

- 목표 도착에 맞는 합리적인 최신 출발 제공
- 계획 감시와 최신 출발 추천 구분
- 추천 경로와 화면 경로 일치
- 실시간 적용 여부와 실제 증거 일치
- 데이터가 없을 때 정확한 추천 차단
- 원시 내부 코드 미노출
- 로그인 유지 정상
- 알림 상세·재시도 정상
- 버스·지하철·도보 실제 경로 검증
- 앱 재실행과 서버 재평가 후 결과 일관성 유지

## 26. 완료 보고 형식

- 기준 커밋
- 변경 파일
- 실행한 테스트
- 사용한 데이터·artifact·model 버전
- 적용 성공 케이스
- fallback 케이스
- 미해결 의존성
- 실제 기기 또는 재현 캡처

