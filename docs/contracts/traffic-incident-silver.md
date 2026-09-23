# Traffic Incident Silver 전처리 계약

## 현재 확인 결과

저장소에는 공식 자료 다운로드 이력과 조인 감사 코드가 있지만 운영 승인된 도로 링크 마스터는 없다.
`backend/tools/road-impact/validation/mapping-audit.json`의 `operationalMappings`도 0이다. 기존 후보는
버스 방향 연결 후보일 뿐, AccInfo `LINK_ID`와 같은 번호 체계·적용기간이 운영 검증되지 않았다.
따라서 이 후보를 이번 Silver 운영 조인에 사용하지 않는다.

좌표계는 TOPIS 공식 노드 예시로 검증했다. `GRS80TM_X/Y=(202051.8241,452892.967)`을
EPSG:5181(KGD2002 / Central Belt)에서 EPSG:4326으로 변환하면
`(longitude=127.0232274, latitude=37.5755812)`가 되어 공식 WGS84 예시와 일치한다.
코드는 `pyproj`와 `always_xy=True`로 이 변환만 수행하고 원본 TM 좌표와 CRS를 함께 보존한다.

검증 근거:

- [TOPIS 공식 노드정보 API 예시](https://t-data.seoul.go.kr/category/dataviewopenapi.do?data_id=1006): 같은 지점의 GRS80TM·WGS84 좌표 쌍
- [EPSG 공식 5181 정의](https://epsg.org/crs_5181/KGD2002-Central-Belt.html): KGD2002 / Central Belt, GRS 1980
- [서울 열린데이터광장 OA-13310](https://data.seoul.go.kr/dataList/catalogView.do?currentPageNo=1&infId=OA-13310&srvType=A): 소통·돌발 API용 링크정보

운영 `DIRECT/NEARBY` 조인에는 다음 자료가 추가로 필요하다.

1. AccInfo와 같은 ID임을 확인한 서울시 `OA-13310 LinkInfo` 전체 마스터
2. 링크 시작·종료 노드와 WGS84 선형 geometry를 만드는 공식 TOPIS 링크/노드 자료
3. 다운로드 일시·원본 SHA·버전·적용기간 및 표본 대조 결과
4. 검토자가 승인한 `traffic-link-master.schema.json` 형식의 JSON 또는 JSON.GZ artifact와 파일 SHA

`reviewStatus=APPROVED`, `accInfoLinkIdVerified=true`, `sourceDataset=OA-13310`, 시간대가 있는
`validFrom/validUntil`을 모두 만족하지 않으면
잡이 해당 파일을 거부한다. 마스터가 설정되지 않으면 잡은 멈추지 않고
`linkMasterStatus=UNAVAILABLE`, `locationMatchType=UNMATCHED`로 저장한다.

## 변환 우선순위

```text
1. 공식 LINK_ID + 승인 마스터
2. EPSG:5181 → WGS84 좌표와 링크 geometry 거리
3. description의 도로명·방향·차로·자치구는 보조 정보
```

- `DIRECT`: 같은 공식 LINK_ID이며 좌표가 없거나, 변환 좌표가 링크에서 기본 200m 이내다.
- `NEARBY`: LINK_ID를 연결하지 못했지만 변환 좌표가 유일한 링크에서 기본 100m 이내다.
  반대 방향처럼 가까운 링크가 10m 이내 차이로 둘 이상이면 자동 선택하지 않는다.
- `REGIONAL_CONTEXT`: 구조화 위치를 못 찾고 `종로구 일대` 같은 넓은 지역 표현만 있다.
- `CONFLICT`: LINK_ID의 공식 geometry와 변환 좌표가 허용 거리보다 멀다. `matchedLinkId`는 null이다.
- `UNMATCHED`: 위 조건 어디에도 해당하지 않는다.

도로명이 같다는 이유만으로 링크를 선택하지 않는다. description은
`descriptionRoadName`, `descriptionDirection`, `descriptionLaneControl`, `descriptionRegion`만 채운다.

## 주요 컬럼과 예시

원본 `traffic.incident`의 `payload.raw`는 다음 순서로 한 행이 된다.

```text
LINK_ID/GRS80TM_X/Y/ACC_INFO
  → 원본 ID 보존
  → EPSG:5181 좌표 변환
  → 승인 링크 마스터의 ID·geometry와 대조
  → description 보조 필드 추출
  → 상태·결측·품질 배열을 붙인 동일 Silver 행
```

| 컬럼 | 예시 | 의미 |
|---|---|---|
| `providerIncidentId` | `202609090001` | 제공자 사건 ID |
| `inputLinkId` | `1000000100` | 원본 AccInfo LINK_ID |
| `matchedLinkId` | `1000000100` | 검증 후 사용할 공식 링크, 충돌 시 null |
| `locationMatchType` | `DIRECT` | 위치 판정 |
| `grs80tmX/Y` | `202051.8241/452892.967` | 원본 투영좌표 |
| `sourceCrs` | `EPSG:5181` | 원본 좌표계 |
| `latitude/longitude` | `37.575581/127.023227` | 변환 좌표 |
| `linkCoordinateDistanceMeters` | `3.2` | 공식 링크 geometry와 거리 |
| `startedAt` | `2026-09-17T09:00:00+09:00` | 실제 발생시각 |
| `expectedClearAt` | null | 예상 해제시각, 실제 종료 증거가 아님 |
| `lifecycleStatus` | `ACTIVE_OBSERVED` | 최근 API 응답에서 관측됨 |
| `validUntil` | 수집시각+45분 | 이후에는 종료가 아니라 UNKNOWN 취급 |
| `processingStatus` | `VALID/PARTIAL/REJECTED` | 전처리 사용 가능 상태 |

전체 DataFrame 컬럼은 다음과 같이 묶인다.

| 묶음 | 컬럼 |
|---|---|
| 추적 | `silverVersion`, `eventId`, `source`, `providerIncidentId`, `contentHash`, `collectedAt`, `rawPayloadJson` |
| 시간·상태 | `startedAt`, `expectedClearAt`, `validUntil`, `lifecycleStatus`, `processingStatus` |
| 사고 분류 | `incidentTypeCode`, `incidentDetailTypeCode`, `description` |
| 링크 | `inputLinkNamespace`, `inputLinkId`, `matchedLinkId`, `matchedRoadName`, `matchedDistrictName`, `matchedStartNodeId`, `matchedEndNodeId`, `locationMatchType`, `linkMasterStatus`, `linkMasterVersion`, `linkMasterEvidenceRef` |
| 좌표 검증 | `grs80tmX`, `grs80tmY`, `sourceCrs`, `latitude`, `longitude`, `coordinateStatus`, `linkCoordinateDistanceMeters`, `nearbyCandidateCount` |
| 설명 보조값 | `descriptionRoadName`, `descriptionDirection`, `descriptionLaneControl`, `descriptionRegion` |
| 품질 | `missingFields`, `qualityFlags`, `rejectionReasons` |
| 설명문 AI | `aiExtractionStatus`, `aiIncidentKind`, `aiIncidentDetail`, `aiControlScope`, `aiClosedLaneCount`, `aiLaneNumbers`, `aiDirectionText`, `aiBusDetourMentioned`, `aiStatusMention`, `aiTimeExpressionsJson`, `aiControlStartAt`, `aiControlEndAt`, `aiEvidenceJson`, `aiExtractedAt`, `aiModel`, `aiPromptVersion`, `aiInputHash`, `aiQualityFlags` |

예를 들어 공식 링크와 좌표가 일치한 한 행은 아래처럼 보인다. 나머지 추적·원문 컬럼도 같은 행에 존재한다.

| providerIncidentId | inputLinkId | matchedLinkId | latitude | longitude | locationMatchType | descriptionRoadName | descriptionDirection | descriptionLaneControl | expectedClearAt | lifecycleStatus | processingStatus |
|---|---|---|---:|---:|---|---|---|---|---|---|---|
| `202609170001` | `1000000100` | `1000000100` | 37.575581 | 127.023227 | `DIRECT` | 세종대로 | 종로 방향 | 2개 차로 통제 | null | `ACTIVE_OBSERVED` | `VALID` |

결측값은 0이나 임의 좌표로 채우지 않는다. 좌표 변환 실패, 마스터 미매칭, 예상 해제시각 누락은
null과 `qualityFlags/missingFields`에 남긴다. 사건 ID·발생시각·수집시각 누락만 `REJECTED`다.
모든 상태는 HDFS에 저장하며 Redis에는 아직 유효한 `VALID/PARTIAL`만 저장한다.
GMS 설명문 추출은 `TRAFFIC_INCIDENT_AI_ENABLED=true`일 때만 호출한다. 같은 원문은
SQLite 영속 캐시를 통해 Redis와 HDFS 처리에서 재사용한다. 원문에 없는 모델 값은
버리고 `aiQualityFlags`에 기록한다. AI 호출 실패 또는 미사용 시 기존 Silver 행은
계속 저장되며 AI 특성은 결측으로 남는다.

## Kafka, 체크포인트와 저장

현재 `SPARK_STARTING_OFFSETS=latest`다. 새 체크포인트로 처음 켜면 이후 Kafka 데이터만 처리한다.
재시작하면 Redis 체크포인트 `traffic_incident_silver`, HDFS 체크포인트
`traffic_incident_silver_silver`에서 각각 이어간다. 두 스트림은 독립적이어서 HDFS 시작·실행 장애가
Redis를 멈추지 않는다.

- Redis: `traffic:latest:incident-silver:{providerIncidentId}` JSON. 마지막 관측+45분에 만료되며,
  키가 없거나 만료되면 `UNKNOWN`이다. `ENDED`를 추론하지 않는다.
- HDFS: `TRAFFIC_INCIDENT_SILVER_OUTPUT_URI` 아래 Parquet. 상태·위치판정·날짜로 파티션한다.
- 과거 원본: `traffic_incident_silver_backfill.py`를 수동 실행하고 Redis는 갱신하지 않는다.

```sh
docker compose --profile spark --profile road-silver up -d spark-streaming-traffic-incident

sh submit.sh batch/traffic_incident_silver_backfill.py \
  --input hdfs://hdfs-namenode:9000/ddubi/archive \
  --output hdfs://hdfs-namenode:9000/ddubi/silver/traffic-incident-backfill/v1 \
  --start 2026-09-10 --end 2026-09-17
```
