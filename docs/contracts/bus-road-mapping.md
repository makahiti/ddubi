# 버스 구간 → TOPIS 링크 대응표

## 목적

버스의 `segmentId`와 TOPIS의 `LINK_ID`는 서로 다른 ID다. 검증된 대응표가 있어야 구간별
실시간 속도·통행시간·사고를 조회할 수 있다. 하나의 버스 구간은 여러 TOPIS 링크를 순서대로
지날 수 있으므로 `segmentId → LINK_ID 배열`로 저장한다.

## 입력 제한

배치는 다음 세 입력의 SHA와 버전을 모두 검증한다.

1. `canonical-adjacent-v2` 버스 crosswalk
2. 방향·전체 경로·유효기간을 검토해 `promote.py`로 만든 도로 crosswalk
3. `reviewStatus=APPROVED`, `accInfoLinkIdVerified=true`, `sourceDataset=OA-13310`인 링크 마스터

후보 JSONL, 감사 결과, 도로명 유사도와 가까운 좌표만으로 운영 대응표를 만들지 않는다.
승인 링크 마스터에 없는 LINK_ID나 `SEOUL_ACCINFO_LINK`가 아닌 namespace가 한 건이라도 있으면
전체 실행을 중단한다.

## 공식 API 검증

`audit.py`가 만든 후보의 `LINK_ID`는 OA-13311 `LinkVerInfo`에서 현재 Vertex를 다시 확인한다.
`verify_link_api.py`는 `TRAFFIC_INCIDENT_API_KEY`를 env 파일에서만 읽고 URL·로그·캐시에 남기지
않는다. 성공한 링크를 한 파일씩 저장하므로 중단 후 같은 명령을 실행하면 완료 링크를 건너뛴다.
같은 키를 실시간 Collector도 사용하므로 호출 수는 반드시 당일 잔여 예산 안에서 지정한다.

```sh
python backend/tools/road-impact/verify_link_api.py \
  --candidates data/road-impact-current/mapping-candidates.jsonl \
  --env-file infra/.env \
  --cache-dir data/road-impact-current/api-cache/link-vertex \
  --report data/road-impact-current/link-api-verification.json \
  --max-requests 50
```

`complete=true`가 되기 전에는 승인 링크 마스터를 만들지 않는다. OA-15060의 최신 공개 일괄
파일은 대량 비교 자료로만 사용한다. 현재 API보다 갱신 시점이 오래됐거나 후보 링크가 빠질 수
있으므로 일괄 파일만으로 `accInfoLinkIdVerified=true`를 부여하지 않는다.

## segmentId 집계

내부 조인은 `providerNamespace + providerRouteId + providerFromStopOrder`로 수행한다. 순환 노선의
같은 정류장 쌍을 구분하기 위해서다. 같은 segmentId의 모든 occurrence가 동일한 도로 경로로
검증된 경우만 `RESOLVED`로 확정한다.

| 상태 | 의미 | topisLinkIds |
|---|---|---|
| `RESOLVED` | 모든 occurrence가 같은 승인 경로 | 링크 배열 |
| `UNMAPPED` | 승인 경로가 하나도 없음 | null |
| `PARTIAL` | 일부 occurrence만 승인 경로가 있음 | null |
| `AMBIGUOUS` | 같은 segmentId에 서로 다른 경로가 있음 | null |

## 저장

동일한 segment mapping DataFrame을 사용한다.

- HDFS: `edges/`, `mappings/` Parquet과 품질 집계, 완료 표식
- Redis: `ml:mapping:bus-road:*` Hash의 field=`segmentId`, value=JSON

Redis는 staging Hash의 행 수와 segmentId 유일성을 확인한 뒤 Lua에서 최종 Hash와 active 포인터를
한 번에 바꾼다. 실패하면 이전 active 포인터가 유지된다. HDFS 저장 실패와 Redis 저장은 분리한다.
