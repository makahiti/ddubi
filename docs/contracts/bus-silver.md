# 버스 Silver 전처리 — 팀 공유 안내

## 무엇을 만드는가

버스 C의 A→B 이동시간을 나중에 예측할 수 있도록, 버스 관측을 읽기 쉬운 형태로 정리한다.
이번에는 날씨·도시·교통과 합치지 않고 새 구간 매핑도 하지 않는다. 실제 이동시간 정답도 만들지 않는다.

위치 한 행은 **차량 한 대를 관측한 기록**이다. 도착 한 행은 **노선·정류장에 대한 첫째·둘째 버스 예측 기록**이다.
인천은 원천이 차량별 도착 행이므로 차량 ID도 필수다. 도착 180초는 대기 예상시간이지 A→B 실제 이동시간이 아니다.
같은 차량의 다른 시각 관측은 모두 남긴다. 최신 한 행만 선택하지 않는다.

나중에 연결할 때는 `providerNamespace + providerRouteId + providerVehicleId` 및 원본 시각으로 차량을 추적하고,
검증된 노선·정류장 순서·방향으로 구간을 확정한다. 도착 행의 `firstVehicleId/secondVehicleId`는 차량 연결의 후보다.
ID가 같다는 이유만으로 위치·도착을 무조건 붙이지 않는다. 순환 노선의 순서, 운행일과 시간도 확인해야 한다.
차량 식별자 자체는 이동시간 모델 Feature가 아니며, Gold 단계에서 운행일 범위 가명 ID를 사용한다.

## DataFrame에는 무엇이 들어가는가

컬럼 타입의 기계 기준은 `sparkjobs/common/bus_normalizer.py`의 `COMMON`, `POSITION`, `ARRIVAL`이다.

### 공통 컬럼

| 컬럼 | 의미 |
|---|---|
| silverVersion | 전처리 규칙 버전 `bus-silver-v1` |
| eventId / eventType / source | 원본 이벤트 ID / 위치·도착 구분 / 수집 소스 |
| providerNamespace | SEOUL_BUS / GBIS / INCHEON_API — 숫자 ID가 같아도 다른 기관은 구분 |
| providerRouteId / providerStopId / providerVehicleId | 외부 노선 / 정류소 / 차량 ID, 문자열로 보존 |
| observedAt | 확인할 수 있는 원본시각만, 없으면 null |
| collectedAt | 시스템 수집시각, KST 오프셋을 포함한 ISO 문자열 |
| archiveDate | collectedAt의 KST 날짜. 실제 운행일(serviceDate)이 아님 |
| timestampBasis | 원본 Collector의 provider / received 표시 |
| claimedSegmentIds / claimedMappingJson | 상류가 주장한 구간 ID와 대응표 정보. 이번 전처리가 재검증한 값이 아님 |
| rawPayloadJson | payload 전체 보존. null/오류가 된 값도 확인 가능 |
| missingFields | 해당 데이터셋에서 결과가 null인 컬럼 이름 |
| qualityFlags | 선택값 오류·시각/좌표 문제 이유 |
| rejectionReasons / status | 필수정보 오류 사유 / ACCEPTED 또는 REJECTED |

Spark 출력에는 `rawEventJson`, `kafka_partition`, `kafka_offset`, `kafka_timestamp`도 남긴다.
위치의 선택 필드가 없는 것을 도착의 누락으로 집계하지 않고, 두 데이터셋의 컬럼을 구분한다.

### 위치 전용 컬럼

| 컬럼 | 타입·의미 |
|---|---|
| longitude / latitude | double, 경도 / 위도 |
| providerStopOrder | long, 제공자 정류소 순서 |
| sectionOrder | long, 제공자 구간 순서 |
| sectionDistanceMeters / fullSectionDistanceMeters | double, 제공자 구간 내 거리 / 전체 구간 거리(m) |
| congestionCode | string, 혼잡 원본 코드 |
| providerStateCode | string, 운행 상태 원본 코드 |
| directionCode | string, 방향 원본 코드. 운행 상태와 혼용하지 않음 |
| stopFlag | string, 정차 관련 원본 코드 |

서울은 `gpsX/gpsY`를 좌표로 읽는다. `tmX/tmY`는 투영 좌표일 수 있으므로 위경도로 변환하지 않는다.
경기·인천은 검증된 envelope location이 없으면 이번 버전에서 좌표를 만들지 않는다.
코드를 임의로 ‘정상/혼잡’으로 번역하지 않는다. 원본에 없는 다음 정류소와 방향을 발명하지 않는다.

### 도착 전용 컬럼

| 컬럼 | 타입·의미 |
|---|---|
| arrivalTimeBasis | string, 제공자별 단위 변환 정책 |
| sourceGeneratedAt | string, 서울 mkTm 원본 생성시각 정규화 |
| stopOrder | long, 도착 대상 정류소 순서 |
| detourCode | string, 우회 원본 코드 |
| firstVehicleId / secondVehicleId | string, 첫째 / 둘째 예측 차량 |
| firstArrivalSeconds / secondArrivalSeconds | long, 도착예정 초 |
| firstTravelTimeMinutes / secondTravelTimeMinutes | double, 서울 traTime 원본 분. 도착예정 초 또는 실측 정답으로 쓰지 않음 |
| firstRemainingStops / secondRemainingStops | long, 원본의 남은 정류소 수 |
| firstVehicleSectionOrder / secondVehicleSectionOrder | long, 각 차량의 구간 순서. ‘몇 정거장 전’과 다름 |
| firstFullCode / secondFullCode | string, 만차 관련 원본 코드 |
| firstTerminalArrivalCode / secondTerminalArrivalCode | string, 종점 도착 관련 원본 코드 |
| firstArrivalMessage / secondArrivalMessage | string, 원본 도착 안내문 |

서울 `exps1/2`는 초 그대로 읽고, 경기 `predictTimeSec1/2`를 우선 사용하며 없으면 `predictTime1/2` 분×60을 사용한다.
인천 `ARRIVALESTIMATETIME`은 기존 수집 계약의 초 기준을 사용하며 second 정보는 생성하지 않는다.
이 버전은 저장소의 제공자 계약을 따르며, 실제 제공자 데이터별 필드·단위 검증은 운영 원본 처리 때 필요하다.

## 결측값을 왜 이렇게 처리하는가

| 상황 | 처리 | 타당한 이유 |
|---|---|---|
| 빈 문자열·null·unknown·none | null | 실제 측정값이나 식별자가 아니기 때문 |
| ID `0` | null | 현재 제공자 식별 계약에서 유효 ID로 사용할 수 없으므로 연결 충돌 방지 |
| 도착예정 0초 | 0 유지 | 도착 임박/도착이라는 유효값일 수 있음. 미수집과 구분 필요 |
| 음수·문자·NaN·무한대·정수 필드의 소수 | null + 필드별 오류 사유 | 단위·허용 범위를 만족하지 않는 값을 임의 값으로 고치면 왜곡됨 |
| 좌표 한쪽 없음·범위 오류 | 경도·위도 모두 null + 사유 | 반쪽 좌표로 공간 연결하면 잘못된 위치를 생성함 |
| 좌표가 0 | 쌍 모두 null + 사유 | 대상 수도권에서 적도/본초자오선은 차량 위치가 아님 |
| 혼잡·우회·만차 코드 미제공 | null | ‘정상’, ‘우회 없음’, ‘빈 차량’이라는 증거가 아님 |
| 두 번째 버스 정보 없음 | null | 두 번째 버스가 0초 뒤 도착하거나 존재하지 않는다고 단정할 수 없음 |
| 관측시각이 수신시각으로 대체됨 | observedAt=null, collectedAt 유지 | 수신시각을 실제 관측시각으로 사용하면 미래 시점 결합과 최신성 판단이 왜곡됨 |
| 원본시각이 수집시각보다 미래 | 원본시각 null + 사유, 원문 보존 | 시계 오류 등의 가능성이 있어 as-of 결합의 근거로 쓰지 않음 |
| 필수 ID·수집시각 누락 | Rejected로 분리, 원본 보존 | 어떤 차량/노선/정류소의 어느 수집인지 연결·추적할 수 없음 |
| 선택값만 누락 | 정상 이력에 유지 | 부분 정보도 원본 분석·추후 재처리에 유용함 |

**평균 대치, 0 채우기, 전후 관측 보간, 이전 값 전달을 하지 않는다.** 차량 위치·도착은 빠르게 바뀌고 현재 수집 간격은 기본 8시간이다.
관측 사이를 임의로 채우면 실제 운행을 만든 것처럼 보일 수 있다. 모델용 대치는 Gold 단계에서 학습 데이터만으로 별도 정책을 고정한다.
부재와 잘못된 값은 `missingFields`와 `qualityFlags`를 함께 보면 구분할 수 있다.

필수 공통 정보는 eventId, source, providerNamespace, providerRouteId, collectedAt이다.
위치는 providerVehicleId, 도착은 providerStopId가 추가 필수이며 인천 도착은 providerVehicleId도 필수다.
다른 이벤트는 IGNORED로 집계한다. 깨진 JSON은 유형을 알 수 없으므로 버스라고 단정하지 않고 원본과 함께 격리한다.

## 중복·충돌 규칙

같은 eventId와 동일 원본 JSON 문자열의 반복만 중복으로 분리한다. JSON 표현이 다르거나 내용이 다른데 eventId가 같으면
`EVENT_ID_CONTENT_CONFLICT`로 모든 서로 다른 버전을 격리한다. 하나를 임의로 선택하지 않는다.
같은 차량이라도 eventId가 다른 관측은 유지한다. 다른 eventId의 비슷한 본문을 의미 중복으로 제거하지 않는다.

## 어떻게 실행하고 제출하는가

Spark 컨테이너에서 **새 버전 경로**로 실행한다. 운영 원본이나 기존 결과를 덮어쓰지 않는다.

```sh
sh submit.sh batch/bus_silver.py \
  --input hdfs://namenode:9000/ddubi/archive/transport.raw \
  --output hdfs://namenode:9000/ddubi/silver/bus/version=20260916-v1 \
  --start 2026-09-01 --end 2026-09-16
```

URI는 예시다. 실제 Archiver 경로·네임노드 주소를 확인해서 사용한다. 날짜 필터는 원본 파일의 Kafka 도착 날짜(date 파티션) 기준이다.
출력은 `position/`, `arrival/`, `rejected/`, `duplicates/` Parquet와 `quality/`, `manifest/`, `complete/` JSON이다.
position/arrival은 providerNamespace와 수집일 archiveDate로 파티션한다. `complete/`가 있는 실행만 제출 완료로 간주한다.
실패한 실행은 일부 파일이 있을 수 있으므로 새 version 경로로 다시 실행한다.
manifest의 `inputRows = uniqueBusAndInvalidRows + duplicateRows + ignoredRows`와
accepted+rejected=uniqueBusAndInvalidRows를 확인한다. 품질 출력은 건수이며 비율은 해당 입력/정상 건수를 분모로 계산한다.

`quality/collection-intervals`에는 제공자·노선·정류소·차량별 수집 간격의 표본 수, 최소·최대·중앙값을 저장한다.
이는 수집시각 간격이며 실제 이동시간이 아니다. 한 건뿐이면 간격 표본 수는 0, 통계는 null이다.
정류소 식별자가 바뀌는 위치 소스는 그룹이 나뉠 수 있으므로 전체 차량 추적 통계로 해석하지 않는다.
운영 부하·실제 원본 전체 처리 검증은 아직 완료하지 않았다.

소량 검토용 JSONL은 다음처럼 CSV와 JSONL을 함께 만들 수 있다. CSV의 null은 문자 `null`로 보이지만 JSONL/Parquet의 값은 실제 null이다.
CSV는 사람이 확인하는 파일이고, ID 타입 보존 및 재처리에는 JSONL/Parquet를 사용한다.

```sh
python sparkjobs/tools/export_bus_silver.py --input sparkjobs/examples/bus-silver-input.jsonl --output new-review-output
python -m unittest discover -s sparkjobs/tests -p test_bus_silver_core.py -v
```

`sparkjobs/examples/bus-silver-review/`는 **가상 입력**을 처리한 예시이며 실제 운행 데이터를 제출한 결과가 아니다.
원본 8건 = 정상 4건 + 거절 2건 + 중복 1건 + 비버스 제외 1건이다.
정상 위치에도 원본 관측시각이 없을 수 있고, 정상 도착에도 잘못된 선택값이 null로 남을 수 있다.
실제 HDFS 원본은 로컬에 없어 이번 구현에서 운영 일괄 처리는 수행하지 않았다.
# 실시간 HDFS·Redis 저장 (realtime-v1)

`streaming/realtime_latest.py`는 Kafka 버스 원문에 기존 `bus_normalizer`와
배치 `prepare` 규칙을 적용한다. 전처리한 하나의 DataFrame을 캐시한 뒤 아래로 분기한다.

- HDFS: `BUS_SILVER_OUTPUT_URI/batches/batch=<Spark batchId>/data`에 Parquet 이력 저장.
  기본값은 `hdfs://hdfs-namenode:9000/ddubi/silver/bus/realtime-v1`.
  eventType/providerNamespace/archiveDate로 파티션을 나눈다.
  정상·제외·중복 행을 모두 보존한다. 분석 시 `status='ACCEPTED' AND repeatIndex=1`을 선택한다.
- Redis: 정상 행에서 노선·차량 또는 노선·정류장별 최신 행을 선택해 JSON 저장.
  기존 `transit:latest:bus-position:*`, `transit:latest:bus-arrival:*` 키를 유지한다.
  서울 외 출처에는 namespace를 붙이고 인천 도착은 차량 ID도 붙인다.
  JSON 최상위에는 Silver 표준 컬럼 전체(원문 payload 포함)를 동일하게 제공한다.
  기존 조회용 observation/location/providerIds 및 검증된 매핑 필드를 함께 제공한다.
  결측값은 JSON에서도 명시적 null이다. 품질·결측 사유도 HDFS와 같다.

매핑은 기존 realtime crosswalk 검증을 유지하며, upstream 매핑 주장만으로 확정하지 않는다.
관측시각이 없으면 observedAt/occurredAt/validUntil은 null로 유지한다.
이때 `_latestOrderAt`만 수집시각을 사용해 최신 수집 결과를 갱신한다. 이 필드는
관측시각이나 학습 정답이 아니다. 소비자는 validUntil이 null이거나 지났으면 현재 관측으로
사용하지 않아야 한다. Redis 보관 TTL은 기존 86400초, 관측 신선도는 120초다.

HDFS 저장 후 Redis를 저장하고 둘 다 성공하면 해당 배치의 `complete` 디렉터리를 기록한다.
저장 실패는 Spark로 전파되어 checkpoint가 진행되지 않는다. 재시도는 같은 batchId의
Parquet 디렉터리만 다시 작성하고 Redis는 Lua로 과거값 덮어쓰기를 막는다.
두 저장소를 하나의 원자적 트랜잭션으로 묶지는 않으므로 복구 중에는 일시적 차이가 있다.
외부 분석은 complete가 있는 배치를 대상으로 읽어야 한다.
이력은 `batches/batch=*/data`를 읽고 배치 간 반복 eventId는 추가로 중복 제거한다.
실시간 충돌 검증 범위는 하나의 micro-batch이며 배치 작업은 전체 입력 범위다.

**checkpoint를 삭제하거나 새로운 스트림으로 교체하면 출력 URI도 새로운 버전으로 변경해야 한다.**
batchId가 다시 0부터 시작하므로 같은 출력 URI를 재사용하면 기존 이력이 덮어써진다.
여러 잡이 같은 출력 URI에 쓰지 않는다. URI 미설정/공백/잘못된 scheme은 시작 단계에서 실패한다.

운영 반영: 저장소 코드를 서버에 반영한 뒤 infra에서
`docker compose --profile spark up -d --force-recreate spark-streaming-realtime`.
기존 checkpoint는 유지한다. 이후 로그의 busSilverRows/busRedisKeys, HDFS complete,
Redis JSON의 silverVersion/missingFields를 확인한다. 기존 archive나 과거 Silver는 자동 재처리하지 않는다.
전체 payload를 Redis에 추가하므로 배포 후 Redis used_memory/evicted_keys도 확인한다.

검증: 공통 규칙 unittest 10개 통과. Spark 테스트에는 저장소 간 컬럼 값 일치,
동일 batch 재처리, Redis 실패 전파, 관측시각 결측 처리를 추가했다.
로컬에는 PySpark가 없어 Spark 통합 및 운영 Kafka/HDFS/Redis 검증은 아직 수행하지 못했다.
