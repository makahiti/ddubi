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
