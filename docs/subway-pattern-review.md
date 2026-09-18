# 지하철 원인 분류·방향·분기 패턴 후속 검증

## 실행 결과 — 2026-09-17

`subway-review-patterns.py`로 기존 `subway-mapping`의 체크섬을 검증하고 현재 마스터와 같은 버전임을 확인한 뒤, 전국 568개 패턴을 재검토했다. 기존 출력은 보존했고 최신 결과는 다음 디렉터리다.

`C:\Users\SSAFY\Documents\ChatGPT\특화 프로젝트\data_staging\recommendation-20260917\subway-pattern-review-v2`

| 패턴 상태 | 수 |
| --- | ---: |
| 단일 마스터 방향·연속 정차 패턴이 뒷받침하는 후보 | 259 |
| 중간 정차를 건너뛰는 순서 일치: 급행·분기 별도 검토 | 46 |
| 역 매핑이 아직 불완전 | 92 |
| 역은 매핑되나 하나의 마스터 패턴으로 연결되지 않음 | 65 |
| 현재 마스터에 노선 자체 없음 | 106 |

후보는 승인된 운행 패턴이 아니다. 여러 master servicePatternId가 동일 순서를 지원할 수 있으므로 evidence를 모두 보존한다. 반대 방향, 정차 순서 교차, 불완전 매핑은 전체 패턴 일치로 처리하지 않는다. 급행처럼 건너뛰는 정차를 일반 인접 구간으로 승격하지 않는다.

## 역 매핑 원인 분리

경로×원천 역 항목 기준이며 고유 역 수가 아니다.

- 기존 이름·좌표 일치: 1,311.
- 정확히 말미 `역` 한 글자 차이 + 동일 노선 + 유일한 300m 이내 좌표 후보: 340.
- 이름 차이 + 좌표 + 같은 방향 패턴의 앞뒤 두 역 일치: 10.
- 마스터 좌표 없음: 7.
- 같은 이름이나 좌표 거리 임계값 초과: 8.
- 이름·주변 좌표 모두 대응 없음: 26.
- 노선 자체 없음: 744.

구체적 근거 예: 당고개→불암산은 2.83m와 앞뒤 인접 역이 지지하고, 총신대입구(7호선)→이수는 7.04m와 인접 역이 지지한다. 이는 로컬 두 스냅샷 간 명칭 차이 후보이며 개명 시점이나 현재 운행 효력까지 확인했다는 뜻은 아니다. 도봉·도봉산의 약 1.1km 차이와 공항철도 인천공항2터미널의 약 3.2km 차이는 임계값을 늘려 덮지 않았다.

## 누락·확장 후보 계약

`source-master-expansion-candidates.jsonl`에 309개 미완결 패턴을 생성하고 계약 검사했다. 그중 106개는 없는 노선 패턴이다. 광주 4, 대구·경북 25, 대전 8, 부산·울산·경남 51, 수도권 18로 전국 범위를 유지한다.

- 원천 routeId·serviceId·station ID·순서·좌표·달력 원문 유지.
- `namespace=GTFS_SOURCE_ONLY`, `canonicalPatternId=null`, `productionReady=false` 강제.
- 최소 2개 정차, 누락 없는 원천 역, 연속 출력 순서를 검사.
- 현재 유효기간·provider 열차 ID·canonical ID를 생성하지 않음.
- `pattern-review.jsonl`의 `reviewCandidateStationIds`는 후속 검토 ID이며 원본 master 후보를 덮어쓰지 않음.
- `station-gap-diagnostics.jsonl`에 원래 상태·세부 원인·거리·인접 근거를 보존.
- `complete.json`에 입력/코드/출력 체크섬과 집계 기록. 출력 경로 재사용·덮어쓰기는 거부.

## 재현

```powershell
python infra/scripts/subway-review-patterns.py --source <subway-mapping> --master <metropolitan-subway-master.json.gz> --coordinates <metropolitan-subway-coordinates.json> --output <새 출력 폴더>
python -m unittest discover -s tests -p 'test_subway_*.py'
```

실제 bundled Python으로 총 11개 테스트 통과. 방향 반전·분기 순서·급행 건너뛰기·순환역 재방문·좌표 원인·말미 역 구분·후보 승인 방지·기존 체크섬 테스트 포함.

## 다음 행동

259개 전체 패턴 후보 및 46개 급행 검토 후보를 BE 참고시간 연결 계약과 대조한다. 원본 좌표 충돌과 106개 없는 노선 패턴의 provider 연결은 별도 승인 전까지 운영 마스터에 넣지 않는다. 서버·기존 마스터 수정 없음.
