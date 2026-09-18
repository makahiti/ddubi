# 지하철·도로 참고자료 상세 조회 패키지

## 목적과 경계

2단계에서 생성한 전국 지하철 패턴·예정 구간시간·배차간격 참고자료와 서울 버스 도로 occurrence 검토 결과를 전부 명시적인 referenceId로 조회할 수 있게 한다. 참고자료 준비 상태/예시 조회이며 현재 사용자 경로에 적용한 계산 근거가 아니다.

- 지하철 자료는 과거 GTFS의 예정시간 참고값이다. 현행 운행일·열차 ID·실시간을 보장하지 않는다.
- 도로는 기존 버스 마스터로 가공된 occurrence를 현재 마스터와 다시 정확 대조한다. 버전 문자열만 바꾸거나 현행 검증 자료로 승격하지 않는다.
- 모든 manifest와 detail은 productionReady=false, realtimeApplied=false, referenceOnly=true다.
- 현재 유효기간이나 날씨·지연초·정시확률·확정통제를 만들어내지 않는다. 별도 활성화 API는 없다.

## 생성

```powershell
python infra/scripts/export-transit-reference.py build `
  --subway "<stage2-subway>" --road "<stage2-road>" `
  --current-bus-crosswalk backend/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz `
  --output "<새 stage3-transit-reference>"
```

원본·기존 결과 디렉터리를 덮어쓰지 않는다. 지하철 완료 manifest의 모든 출력 해시와 도로 완료 manifest의 모든 배치 checkpoint 해시를 검증하고, 실제 소비하는 각 배치 occurrence/report 파일 해시를 추가 검증한다. 출력이 기존에 있으면 거부한다. 네트워크·서비스 DB 쓰기는 없다.

### 패키지 구조

```text
complete.json
summary.json
subway/
  manifest.json
  catalog.jsonl
  details/00.json ... ff.json
road/
  manifest.json
  catalog.jsonl
  details/00.json ... ff.json
```

manifest는 1MiB 이하, 각 shard는 8MiB 이하로 제한한다. 메모리에 전체 원본을 모으지 않고 임시 SQLite로 레코드를 분배한다. lookup은 정확한 ID에 해당하는 shard 하나만 읽는다. 누락된 ID를 유사 노선/역으로 대체하지 않는다.

## BE 계약

각 `manifest.json`:

```json
{
  "schemaVersion": 1,
  "artifactType": "TRANSIT_REFERENCE_LOOKUP",
  "kind": "SUBWAY",
  "sourceVersion": "<sha256>",
  "productionReady": false,
  "realtimeApplied": false,
  "referenceOnly": true,
  "counts": {"recordCount": 0},
  "limitations": ["REFERENCE_ONLY_NOT_ROUTE_EVIDENCE"],
  "exampleReferenceIds": [],
  "entriesIndex": {
    "algorithm": "SHA256_PREFIX_2",
    "shards": {"00": {"path": "details/00.json", "sha256": "<sha256>", "sizeBytes": 0}}
  }
}
```

referenceId는 `SUBWAY_PATTERN_<64hex>`, `SUBWAY_SEGMENT_<64hex>`, `SUBWAY_HEADWAY_<64hex>`, `ROAD_OCCURRENCE_<64hex>`다. 마지막 64자리의 첫 2자리가 shard bucket이다. shard는 `{schemaVersion:1,kind,entries:{referenceId:detail}}` 구조다. 설정 manifest SHA→선언된 상대경로·크기·SHA→정확 entry key 순서로 검증한다.

BE 설정:

- `DDUBI_REFERENCE_PACKAGES_SUBWAY_PATH`, `DDUBI_REFERENCE_PACKAGES_SUBWAY_SHA256`
- `DDUBI_REFERENCE_PACKAGES_ROAD_PATH`, `DDUBI_REFERENCE_PACKAGES_ROAD_SHA256`

BE는 global data-status에 manifest 준비 상태와 bounded counts·limitations를 제공하며, `exampleReferenceIds`의 실제 최대 3개 ID를 `sampleReferenceIds`로 노출할 수 있다. 예시는 사용자 경로와 무관함을 FE가 명확히 표시해야 한다. 상세는 `/api/v1/routes/reference-packages/{kind}/{referenceId}`로 조회한다.

## 상세와 검색

공통 detail 필드: referenceId, kind, recordType, label, routeId, sourceVersion, sourceMasterVersion, referenceOnly, productionReady, productionEligible, realtimeApplied, currentnessVerified, sourcePeriod, limitations, data. 검증되지 않은 sourcePeriod는 null이다.

- 지하철: sourcePatternId, region, parentReferenceId(구간/배차간격), 원본 예정값·표본 수와 미검증 조건. `declaredCalendar`의 종료일은 현재 유효기간이 아니다.
- 도로: currentMasterVersion, masterCompatibility, 원본 edge·shape occurrence, 후보 연결 상태·표본수·국가표준링크 후보. 대량 원시 sample geometry는 원본에 유지하고 sourceEvidence의 **상대경로와 SHA**로 추적한다. 패키지 상세/manifest에는 로컬 절대경로나 인증정보를 넣지 않는다.

masterCompatibility:

- EXACT_MATCH_CURRENT_MASTER: namespace·노선·정류장쌍·순번이 유일하게 일치하고 segmentId·direction도 동일.
- MISSING_CURRENT_MASTER: 현재 마스터에 해당 occurrence 없음.
- OCCURRENCE_FIELDS_CHANGED: 같은 occurrence 키의 방향 또는 segmentId 변경.
- AMBIGUOUS_CURRENT_MASTER: occurrence 키가 중복.

어느 상태도 운영 승인이나 시간적 유효성 인증이 아니다. 이전 마스터 버전은 sourceMasterVersion으로 보존한다.

```powershell
python infra/scripts/export-transit-reference.py search --package "<package>/road" --field routeId --value 100000020 --limit 10
python infra/scripts/export-transit-reference.py lookup --package "<package>/road" --reference-id "<실제 ID>" --manifest-sha256 "<manifest SHA>"
```

검색은 routeId/sourcePatternId/recordType/region/masterCompatibility 정확 일치이며 최대 100개, 초과 여부를 반환한다. 상세 ID를 경로 API의 canonical ID로 오인하지 않는다.

## 검증

Python 회귀 테스트는 `tests/test_road_transit_reference_export.py`다. 전체 축소 fixture 생성·행수 정합성·새/이전 마스터 분리, 정확 상세조회, 검색 제한, SHA 손상, 경로 이탈, 잘못된 종류의 ID, 운영 승격 입력 거부를 검증한다.

실제 전체 생성의 완료 여부·행수·출력 해시는 결과 root의 complete.json/summary.json으로 확인한다. 서버 설정·배포·DB 적재는 별도 승인 범위다.

## 실제 전체 생성·검증 결과

생성 경로: `C:\Users\SSAFY\Documents\ChatGPT\특화 프로젝트\data_staging\recommendation-20260917\stage3-transit-reference`

검증 도구:

```powershell
python infra/scripts/verify-transit-reference.py --root "<stage3-transit-reference>" --output "<새 검증결과.json>"
```

모든 출력 SHA, 각 shard 크기·SHA·kind, 전체 referenceId 형식·bucket·중복, 운영승인/실시간 false, catalog 전건 일치, parentReferenceId 존재 여부를 검증한다. 각 recordType별 실제 ID로 lookup도 수행한다.

- 지하철 총 **105,670개**: 패턴 568, 예정 구간시간 23,914, 배차간격 bucket 81,188. 전국 입력 범위 유지.
- 도로 **40,264개** occurrence 전건 보존. 새 `METRO-BUS-20260917-SEOUL-0902` 마스터에 namespace·노선·양끝 정류장·순번·segmentId·direction이 전부 일치했다. 이 결과는 현재 마스터 membership 검사이지 원본의 운행일·통제 유효성 승인이 아니다.
- 도로 후보: 단일 연결 5,361, 복수 연결 21,229, 후보 누락 11,232, 방향 단절 2,442. 실패/모호 후보도 조회 가능하고 승인으로 바꾸지 않았다.
- 각각 256 shard, 총 **512개 및 145,934개 record 전건 검증** 통과.
- 최대 shard: 지하철 839,951 bytes, 도로 357,004 bytes. 모두 1MiB 미만이며 계약 상한 8MiB 미만.
- 지하철 manifest SHA256: `a90f06ab4d059981aa36aff743fb100908ec651e0cde3b6ec65d6945669a53f9`
- 도로 manifest SHA256: `ae9431562b135c42aa79abc94e459ef79c338573e16eda81a319ef02c7e1c1bd`
- 검증 결과: 위 결과 폴더와 같은 상위의 `stage3-transit-reference-validation.json`.
- Python 회귀 4개 통과. BE 실제 HTTP/상세 조회 검증은 BE 담당 결과와 별도로 확인한다.

검증된 실제 종류별 ID 예시(현재 사용자 경로와 무관한 보유자료 예시):

- `SUBWAY_PATTERN_01655eef04559ad9945876ca3d08fdaa9f2825dd04c77f69a61f8081332b2761`
- `SUBWAY_SEGMENT_000025a8cb29a7459a99e4219be64fadbf4af60bb09989c144aade4ae1d73b29`
- `SUBWAY_HEADWAY_00004373df50ee9cce2c0f7bf11693f6276b056844c886c5b23696e4ed6705d4`
- `ROAD_OCCURRENCE_00010f148f12be7644d368b92aa1431205694b72be254780a653b6e7b921328b`
