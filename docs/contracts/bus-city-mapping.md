# 버스 구간 → 도시 영역 대응표

서울 실시간 도시데이터의 `providerAreaId`는 버스 `segmentId`가 아니다. Feature 조립 전에
두 ID를 검증된 정적 대응표로 연결한다.

도시 API에는 모든 영역의 공식 폴리곤이 없으므로 장소 이름을 지오코딩해 임의 반경을 만들지
않는다. City Silver `road-link-snapshot`의 실제 도로 대표 좌표와 버스 구간의 출발·중간·도착
좌표 사이 최소 거리를 사용한다.

- 기본 300m 밖이면 `OUT_OF_COVERAGE`
- 가장 가까운 두 영역의 거리 차이가 기본 50m 미만이면 `AMBIGUOUS`
- 정류장 좌표가 없으면 `UNRESOLVED`
- 같은 `segmentId`의 모든 occurrence가 같은 영역으로 확정될 때만 `RESOLVED`
- 일부 occurrence만 연결되면 `PARTIAL`

서울 실시간 도시데이터는 주요 장소 중심이므로 `OUT_OF_COVERAGE`는 정상 결과다. 이 상태를
가장 가까운 장소로 강제로 채우지 않는다.

동일한 segment mapping DataFrame을 HDFS `mappings/` Parquet과 Redis
`ml:mapping:bus-city:*` Hash의 JSON으로 저장한다. Redis는 staging 행 수를 확인한 뒤 active
포인터를 원자적으로 변경한다. HDFS 장애가 발생해도 Redis 배포는 계속한다.

ML Serving은 요청 시 active 대응표를 읽어 `city:latest:area:{providerAreaId}`를 조회한다.
`observedAt`·`collectedAt`이 요청 기준시각보다 미래이거나 예상 통과시각이 `validUntil` 이후면
해당 도시 Feature를 null로 둔다.

## Redis 최신값으로 만드는 경로 (2026-09-23)

운영 서버1의 `CITY_SILVER_OUTPUT_URI` 가 비어 있어 City Silver `road-link-snapshot` Parquet 이
만들어지지 않는다. 그래서 위 배치를 그대로 돌릴 수 없다. 같은 내용이 Redis
`city:latest:road:{linkNamespace}:{linkId}` 에 링크별 최신값으로 들어오므로, 그것을 근거로 쓰는
`batch/export_bus_city_mapping_from_redis.py` 를 둔다. 판정 규칙(300m·50m·occurrence 합의)과
출력 행·Redis 키·활성 포인터는 Spark 판과 같아서 백엔드가 구분 없이 읽는다. Spark 를 쓰지 않으므로
executor 칸을 차지하지 않는다.

```sh
python batch/export_bus_city_mapping_from_redis.py   --crosswalk backend/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz   --bus-master backend/transit-master/bus/metropolitan-bus-v6.json.gz
```

2026-09-23 실행(서버2 ml-serving 컨테이너 안, 약 12초): 도시 도로 대표 좌표 4,309점 기준으로
구간 104,869개 중 `RESOLVED` 16,803, `AMBIGUOUS` 2,763, `UNRESOLVED` 213, `OUT_OF_COVERAGE` 85,090.
서비스 저장 경로의 버스 구간 1,376개 중 789개(57%), 노선 121개 중 118개가 덮인다.
Redis TTL 은 30일이며 도시 영역 좌표가 바뀌면 지문(`areaReferenceSha256`)이 달라져 새 키로 발행된다.
