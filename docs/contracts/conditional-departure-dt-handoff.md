# 조건부 출발 DT → BE/ML 전달 계약

## 고정 fixture

`docs/contracts/fixtures/conditional-departure-dt/`를 소비 테스트의 고정 경로로 사용한다.
모든 예제는 합성 데이터이며 운영 키와 개인정보를 포함하지 않는다.

- `bus-arrival-normal.json`: 원천 필드와 단위 보존 예제
- `source-poll-status-*.json`: 정상 빈 응답, 부분 응답, 실패 구분
- `redis-bus-arrival.json`: Redis 보관 TTL과 소비 freshness 분리 예제
- `transit-coordinates.json`: STOP_POINT/STATION_CENTER/UNKNOWN 좌표 의미 예제
- `package-manifest.json`: 전달 파일 SHA-256과 schema version

## 소비 규칙

- Redis 키 부재나 만료는 정상 운행을 의미하지 않는다.
- `sourceGeneratedAt`과 `collectedAt`을 서로 대체하지 않는다.
- `consumerFreshnessSeconds`가 지나면 Redis TTL이 남아 있어도 실시간값으로 사용하지 않는다.
- `firstArrivalSeconds`는 `mkTm + exps1`의 `exps1`이며 `collectedAt`에 다시 더하지 않는다.
- `completeSnapshot=false`인 poll에서 미등장을 사건 종료나 무운행으로 판단하지 않는다.
- 좌표는 `mappingStatus=EXACT`이고 좌표가 존재할 때만 endpoint 검증 후보로 사용한다.
- `STATION_CENTER`를 `ENTRANCE`로 해석하지 않는다.
- `UNKNOWN/UNMAPPED` 좌표에서는 제휴 도보 호출을 생략하고 기존 WALK로 fallback한다.
- DT는 path endpoint 거리 임계값을 결정하지 않는다. BE/ML의 버전된 endpoint policy가 결정한다.

## 현재 확보 범위와 제한

- 버스/지하철 versioned master와 좌표 원천은 저장소에 존재한다.
- `sparkjobs/tools/export_transit_coordinates.py`가 해당 master를 수정하지 않고 좌표 artifact를 생성한다.
- 도로 master는 기존 manifest에서 `REVIEW_REQUIRED`이므로 FULL/EXACT 차단 근거로 승격하지 않는다.
- 운영 호출 쿼터와 실제 source age 통계는 배포 환경 계측 없이는 확정하지 않는다.
- 실제 제휴 도보 권한과 endpoint 정책은 BE/ML 소유다.

## Source 흐름과 현재 호출 예산

모든 source는 `Collector → raw topic → Spark normalization/latest → Redis` 흐름을 사용하고,
`transport.raw`, `city.raw`, `source.poll-status`는 Archiver가 Parquet로 보존한다.
아래 호출량은 현재 기본 interval과 설정 최대 scope로 계산한 계획값이며 승인 쿼터가 아니다.

| Source | 기본 주기 | 계획 scope | 최대 계획 호출/일 | 상태 근거 |
|---|---:|---:|---:|---|
| bus_arrival | 8시간 | route 100 | 300 | configured route 전체 성공만 complete |
| bus_location | 8시간 | route 100 | 300 | configured route 전체 성공만 complete |
| subway_arrival | 6분 | API 2회/cycle | 480 | API `isComplete`; 정상 0건 허용 |
| subway_location | 60분 | line 16 | 384 | configured line 전체 성공; empty line 보존 |
| subway_alert | 15분 | 전체 page | 최소 96 + 추가 page | `hasMore`이면 PARTIAL |
| city_realtime | 6시간 | area 121 | 484 | requested/received scope 일치 여부 |
| topis_notice | 15분 | 첫 1,000건 | 96 | `hasMore`이면 PARTIAL, content revision dedup |
| traffic_incident | 15분 | 전체 page | 최소 96 + 추가 page | 전체 page 검증 성공만 complete |
| weather | 10분 | grid 14 | 2,016 | grid별 성공/실패, 일부 실패는 PARTIAL |

운영 전에는 실제 승인된 키별 일/초 한도와 retry 포함 호출량을 다시 대조한다.
TTL을 늘려 source age를 숨기거나 승인 전 `API_DAILY_LIMIT`을 상향하지 않는다.

## 실제 master 좌표 검증 결과

2026-09-16 고정 시각으로 exporter를 동일 입력에 두 번 실행한 결과는 다음과 같다.

- 전체 occurrence: 117,690
- BUS: 106,650
- SUBWAY: 11,040
- 좌표 미확보 `UNMAPPED`: 206
- `STATION_CENTER`: 10,956
- artifact version: `coordinates-a3abc5776dd6db0e`
- 동일 입력 결과 SHA-256 일치: `b3284f0f4a15aeda2476470e294e76bdb075804b04d3cb30498bffbcaa7c2bb5`

생성 시각도 artifact 내용에 포함되므로 재현 비교 시 `--generated-at`을 동일하게 고정한다.

## 좌표 artifact 생성

```text
python sparkjobs/tools/export_transit_coordinates.py \
  --bus-master backend/transit-master/bus/metropolitan-bus-v6.json.gz \
  --subway-master backend/transit-master/subway/metropolitan-subway-master.json.gz \
  --subway-coordinates backend/transit-master/subway/metropolitan-subway-coordinates.json \
  --generated-at 2026-09-16T00:00:00Z \
  --output <artifact-output.json>
```

같은 master와 `generated-at`을 사용하면 같은 `artifactVersion`과 같은 JSON 결과가 생성된다.
