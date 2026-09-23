# 버스 구간 도로 확인표와 돌발 자동 갱신

## 현재 준비 상태

2026-09-22 저장소의 `backend/tools/road-impact/validation/mapping-audit.json`에는
`operationalMappings=0`으로 기록되어 있다. `backend/transit-master/road/`에도 승인된 도로
crosswalk 및 OA-13310 링크 마스터가 없다. 따라서 **실제 최종 확인표와 Redis 운영 매핑은 아직 없다.**
감사 후보의 `LINK_ID` 겹침을 실제 버스 영향으로 발표하지 않는다.

## 승인 자료가 준비되면

1. 공식 LINK_ID와 AccInfo `LINK_ID`의 동일성, 버스 진행 방향, 경로 전체, 유효기간을 검토한다.
   검토 결과를 `backend/tools/road-impact/promote.py`의 입력으로 승격하고 OA-13310 링크
   마스터를 승인한다. SHA와 버전은 실제 생성 결과를 사용한다.
2. `sparkjobs/batch/export_bus_road_mapping.py`를 `--output`과 함께 실행한다.
   `review-table/` CSV의 `segmentId`, `mappingStatus`, `topisLinkIds`, occurrence 연결률,
   `evidenceRefs`, 적용기간을 검토한다. `RESOLVED`만 사고 역조회 대상이다.
3. Redis의 `ml:mapping:bus-road:active:{masterVersion}`가 새 대응표를 가리키고,
   `:meta`에 버스 crosswalk SHA와 승인 링크 마스터 버전이 일치하는지 확인한다.
4. Collector, Kafka, `spark-streaming-traffic-incident`, Redis, `ml-serving`을 실행한다.
   돌발 Silver의 GMS 추출은 `TRAFFIC_INCIDENT_AI_ENABLED=true`일 때 동작한다.
   구간 특성 API는 `ML_REDIS_LATEST_FEATURES_ENABLED=true`일 때 읽을 수 있다.
5. 별도 `ml-incident-feature-refresh` 서비스를 실행한다. 기본 15초마다 Redis의 완전한
   돌발 스냅샷을 확인한다. 새 회차면 승인된 도로 대응표에서 사고 링크가 포함된 구간을 찾아
   `OnlineFeatureAssembler`를 호출한다. 사고가 사라진 구간도 다시 계산한다.

```sh
docker compose --env-file infra/.env -f infra/docker-compose.yml --profile road-silver up -d \
  spark-streaming-traffic-incident ml-incident-feature-refresh
```

## 운영 확인

| 확인 | 기대 결과 |
|---|---|
| 새 전체 돌발 회차 | Worker 로그 `status=UPDATED`, `updated`는 영향 구간 수 |
| 같은 회차 반복 | `UNCHANGED`, GMS 캐시와 Redis 특성 중복 갱신 없음 |
| 사고가 사라진 다음 회차 | 이전 영향 구간도 재조립되고 사고 건수가 갱신됨 |
| 링크 마스터 또는 매핑 누락 | `MAPPING_UNAVAILABLE`/`MAPPING_NOT_VERIFIED`; 후보로 대체하지 않음 |
| 해당 구간 최신값 | `ml:feature:bus-segment:bus-segment-feature-v3:{masterVersion}:{segmentId}`의 `provenance.incident`와 `verifiedIncidentCount` 확인 |

최종 확인표를 만드는 배치와 자동 갱신 Worker는 **코드로 준비**되어 있다. 승인 자료 배포,
실제 서비스 실행, 운영 Redis/HDFS 적재 및 종단 검증은 아직 완료되지 않았다.

## 후보 매핑 임시 활성화

공식 `RESOLVED` 대응표를 만들기 전에는 엄격한 후보만 별도 `PROVISIONAL` 대응표로 발행할 수 있다.
정식 포인터 `ml:mapping:bus-road:active:{masterVersion}`는 바꾸지 않는다. 후보는
`ml:mapping:bus-road-provisional:active:{masterVersion}`에서만 조회한다. 같은 구간에 유효한
`RESOLVED`가 있으면 Backend와 ML 모두 정식을 먼저 사용한다.

후보 발행기는 다음 조건을 모두 만족하는 occurrence만 사용한다.

- `SINGLE_CONNECTED_CANDIDATE_PATH`
- 모든 경로 표본에 방향 허용 후보가 존재
- 근접 후보가 둘 이상인 표본이 0개
- 선택 경로의 방향별 교통 링크 ID가 정확히 하나
- 같은 `segmentId`의 모든 occurrence가 같은 링크 경로

```sh
python batch/export_bus_road_provisional_mapping.py \
  --candidates /data/occurrence-candidates.jsonl \
  --candidates-sha <SHA256> \
  --master-version <버스 masterVersion> \
  --bus-crosswalk-sha <버스 crosswalk SHA256> \
  --as-of 2026-09-23T00:00:00+09:00 \
  --valid-until 2026-10-23T00:00:00+09:00 \
  --output /data/provisional-bus-road.json
```

### 공식 노선 링크 후보로 발행하기 (2026-09-23)

`backend/tools/road-impact/audit.py` 가 BIS 구간링크·TOPIS 표준링크 공식 자료로 만든
`mapping-candidates.jsonl`(구간 occurrence 24,052건)은 GTFS 형상 후보와 형식이 다르다. 이 파일은
`export_bus_road_official_provisional_mapping.py` 로 발행한다. `topisServiceLinkIds` 를 도로 링크로
쓰고, `unmappedBisLinkIds` 가 남은 occurrence(경로 일부 누락)는 버린다. 결과 키·활성 포인터·TTL 은
위 발행기와 같다(PROVISIONAL 전용 포인터, 정식 RESOLVED 는 건드리지 않음).

```sh
python batch/export_bus_road_official_provisional_mapping.py   --candidates /data/mapping-candidates.jsonl   --candidates-sha <SHA256>   --master-version METRO-BUS-20260917-SEOUL-0902   --bus-crosswalk-sha <버스 crosswalk SHA256>   --as-of 2026-09-23T00:00:00+09:00   --valid-until 2026-10-23T00:00:00+09:00   --output /data/official-provisional-bus-road.json
```

2026-09-23 실측(Redis 미기록 dry-run): 발행 13,606구간, 보류 10,301(경로 일부 누락). 서버2 저장 경로의
버스 구간 1,376개 중 452개(33%), 노선 121개 중 88개가 덮인다. 같은 날 서울 돌발 35건의 `LINK_ID` 중 21건이
이 표의 링크와 일치했고 `bisLinkIds` 는 4건만 일치했다 — AccInfo 와 같은 체계는 `topisServiceLinkIds` 다.
`--master-version` 은 저장 경로 edge 의 `mapping_version`(DB `route_segment_edge`)과 같아야 백엔드가 읽는다.

발행 결과를 확인한 뒤 세 서비스에 같은 설정을 전달한다. 기본 배포는 후보 매핑과 최신
Feature 읽기를 활성화하고, 서버1의 `road-silver`와 서버2의 `road-feature`를 상시 실행한다.
후보 대응표가 없거나 만료되면 임의 링크로 대체하지 않고 `MAPPING_UNAVAILABLE`로 남는다.

```env
ROAD_PROVISIONAL_MAPPING_ENABLED=true
ROAD_PROVISIONAL_TRAFFIC_LINK_MASTER_VERSION=provisional-accinfo-input-v1
ML_REDIS_LATEST_FEATURES_ENABLED=true
```

후보 모드의 돌발 Silver는 공식 링크 마스터가 없을 때 AccInfo 원문의 `LINK_ID`를 후보 링크로
보존한다. ML snapshot의 `provenance.mappings.road.status`와 일정 상세 API의
`roadBlock.mappingStatus`는 `PROVISIONAL`이다. 앱은 이를 `후보 매핑 · 추정 정보`로 표시한다.
후보 artifact TTL 기본값은 7일이며 다시 발행하지 않으면 자동 만료된다.

`PROVISIONAL` 사건은 일정 상세의 설명과 경로 위험 신호로 사용한다. 후보 매핑만으로 경로를
통행 불가 처리하지 않는다. 경로의 강제 제외는 정식 `RESOLVED` 매핑과 전체 통제 근거가 모두
확인된 별도 상태에서만 적용한다.

## 추천 계산에서 후보 근거 쓰기 (2026-09-23)

정식 도로 근거(`ddubi.road-impact.path`)는 사람이 버스 진행 방향을 확인한 승인 파일을 요구한다. 그
검토가 끝나기 전까지 사고 정보를 받아 두고도 추천에 전혀 닿지 못했다. ML 예측을 미승인 상태로 쓰되
결과에 표시하는 것과 같은 방식으로, 후보 대응표로도 근거를 만들 수 있게 한다.

```env
ROAD_PROVISIONAL_EVIDENCE_ENABLED=true   # 기본 false
```

- 근거는 Redis 후보 포인터 `ml:mapping:bus-road-provisional:active:{masterVersion}` 와 이미 15분마다
  갱신되는 `traffic:latest:incident-snapshot:all-current` 로만 만든다. Spark 잡을 새로 띄우지 않는다.
- 만들어지는 원인은 `POTENTIAL_IMPACT`·`confirmed=false` 다. 경로를 막지 않고 예상 시각도 바꾸지 않는다.
  AI 안내가 "선택 경로 주변에 교통 영향 가능성이 있어요" 로 표현한다.
- 승인 근거 파일이 설정돼 있고 결과를 내면 **그쪽이 언제나 이긴다**. 후보 근거는 그때 쓰이지 않는다.
- 스냅샷이 부분 수집이거나 만료됐으면 근거 자체를 비운다. "사고 없음" 으로 바꾸지 않는다.
