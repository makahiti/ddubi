# City Realtime Silver 계약

## 범위

`city.raw`의 `city.realtime` 이벤트에서 `LIVE_PPLTN_STTS`, 그 안의 `FCST_PPLTN`,
`ROAD_TRAFFIC_STTS`만 정규화한다. 날씨·사고·행사·대중교통 하위 데이터는 다른 잡과
중복 저장하지 않으며 전체 CITYDATA는 각 행의 `rawPayloadJson`으로 추적한다. AI와 GMS는
사용하지 않는다.

## 좌표 근거

서울 열린데이터광장의 공식 `citydata` 샘플을 2026-09-17 확인했다. `START_ND_XY`,
`END_ND_XY`, `XYLIST`는 `126.979..._37.570...`처럼 **경도_위도** 순서의
WGS84(EPSG:4326)이고, `XYLIST`의 점은 `|`로 구분된다. 그러므로 pyproj 변환을 하지 않는다.
모든 점이 서울 방어 범위(경도 126.70~127.30, 위도 37.40~37.75) 안에 있고 두 점 이상일 때만
GeoJSON LineString으로 승격한다. 실패한 행도 원문과 상태를 HDFS에 남긴다.
필드 명세와 샘플의 기준은 [서울시 실시간 도시데이터](https://data.seoul.go.kr/dataList/OA-21285/F/1/datasetView.do)다.

`LINK_ID`는 이 API 안에서만 `SEOUL_CITYDATA_LINK` namespace로 사용한다. 다른 TOPIS 또는
표준 링크와 같은 번호 체계라는 공식 검증 자료가 없으므로 숫자가 같아도 자동 조인하지 않는다.

## 산출물과 결측 정책

- `area-snapshot`: 장소·관측시각별 혼잡도, 인구 최소/최대/중간값, 평균 도로속도
- `road-link-snapshot`: 제공 링크·관측시각별 속도, 상태, 전체 LineString과 대표점
- `population-forecast`: 장소·예측 생성시각·예측시각별 인구 범위와 혼잡도
- `rejected`: AREA_CD, LINK_ID, 예측시각처럼 키를 만들 수 없는 원문과 거절 사유
- `quality`: 배치별 입력·VALID·PARTIAL·REJECTED·중복 수

숫자 결측을 0으로 채우지 않는다. 인구 최소가 최대보다 크면 두 원값을 그대로 두고 중간값은
null로 만든다. 음수 또는 200km/h 초과 속도는 null과 품질 플래그로 남긴다. 알 수 없는 범주는
원문 필드와 함께 정규화 코드 `UNKNOWN`으로 보존한다. 빈 배열과 필드 부재는 서로 다른 플래그다. 행 하나의 오류가 배치를
중단하지 않는다.

제공자 시각을 우선하며 없거나 파싱할 수 없을 때만 `collectedAt`을 사용하고
`timestampBasis=COLLECTED_AT_FALLBACK`을 기록한다. 기본 freshness는 900초이며 `validUntil`,
`dataAgeSeconds`, `isStale`로 표현한다. Redis 보관 TTL 18시간과 현재 사용 가능 시각은 별개다.
area 행은 인구 기준시각을 `observedAt`으로 우선 사용하면서, 서로 갱신시각이 다른 평균 도로속도를
오해하지 않도록 `populationObservedAt`과 `roadTrafficObservedAt`도 각각 보존한다.

중복 키에는 `contentHash`가 포함된다. 같은 키·시각·내용은 제거하지만 같은 시각에 내용이
바뀌면 둘 다 HDFS에 남긴다. Redis 쓰기는 기존 값의 관측시각과 수집시각을 원자적으로 비교하여
늦게 도착한 과거 이벤트가 최신값을 덮지 못하게 한다.

## 저장과 실행

Redis에는 VALID/PARTIAL만 JSON으로 저장한다.

- `city:latest:area:{providerAreaId}`
- `city:latest:road:{linkNamespace}:{linkId}`
- `city:forecast:population:{providerAreaId}:{forecastAt(yyyyMMddHHmmss)}`

HDFS는 `CITY_SILVER_OUTPUT_URI`가 명시된 경우에만 Parquet으로 저장한다. 권장값은
`hdfs://hdfs-namenode:9000/ddubi/silver/city/realtime-v1`이다. Redis와 HDFS는 서로 다른
체크포인트를 사용하므로 HDFS 기동 또는 실행 실패가 Redis를 중단하지 않는다.

체크포인트가 처음 생성될 때 `SPARK_STARTING_OFFSETS`가 적용되고, 이후에는 체크포인트 offset을
이어 읽는다. 기본 `latest`는 새 이벤트만 처리한다. 과거 archive는 다음처럼 별도 버전 경로에
수동 백필하며 Redis와 live checkpoint를 건드리지 않는다.

```sh
sh submit.sh batch/city_realtime_silver_backfill.py \
  --input hdfs://hdfs-namenode:9000/ddubi/archive \
  --output-root hdfs://hdfs-namenode:9000/ddubi/silver/city/realtime-v1 \
  --version 20260917-v1 --start 2026-09-01 --end 2026-09-16
```
