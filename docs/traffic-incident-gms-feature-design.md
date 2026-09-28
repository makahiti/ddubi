# 실시간 돌발 설명문 GMS 전처리 설계

## 결론

`LINK_ID`는 **어느 버스 정류장 사이 구간인지** 연결하는 근거로 쓰고, `ACC_INFO`는 **어떤 통제가 있는지** 추출하는 데 쓴다. `ACC_TYPE`/`ACC_DTYPE`는 서울시 공식 [유형](https://data.seoul.go.kr/dataList/OA-13312/A/1/datasetView.do)·[세부유형](https://data.seoul.go.kr/dataList/OA-13313/A/1/datasetView.do) 코드표로 먼저 해석한다. GMS는 코드나 위치·발생시각을 대신 추측하지 않는다.

TOPIS 공지의 [기존 GMS 구조화 방식](contracts/topis-notice-enrichment.md)을 재사용하되, 돌발 정보에는 Kakao 장소 검색이 기본적으로 필요하지 않다. 위치는 승인된 `LINK_ID`와 공식 geometry로 검증한다. 문장 속 도로명은 위치 검토용 보조값이다.

## 확인한 문장 표본

2026-09-22 OA-13315 실시간 전체 응답 43건(서로 다른 설명문 42건)을 확인했다. 원본은
저장소에 올리지 않고 로컬 `sparkjobs/data/incident-ai-review/live/source.json`에만 보존했다.
사건 유형 코드는 `A04` 39건, `A01` 3건, `A02` 1건이었다. 아래 1~3은 테스트 fixture,
4~6은 당시 실제 응답에서 확인한 문장 형태를 요약한 것이다.

| 표본 | 문장 요지 | 확실히 뽑을 수 있는 값 | 주의할 점 |
|---|---|---|---|
| 1 · fixture | 세종대로 사고, **2개 차로** 통제 | `closedLaneCount=2` | 유형 코드는 공식 코드표로 확인 |
| 2 · fixture | 행사로 인한 도로 통제 | `controlMentioned=true` | 부분/전면, 차로 수는 모름 |
| 3 · fixture | 공사로 인한 부분 통제 | `controlScope=PARTIAL` | 통제 차로 수는 모름 |
| 4 · 실제 응답 요약 | **3,4차로 추돌사고** | `laneNumbers=[3,4]` | 2개 차로 통제로 환산하지 않음 |
| 5 · 실제 응답 요약 | 양방향 **하위 1개 차로** 통제 | `closedLaneCount=1`, `directionText=양방향` | 버스 진행 방향과의 일치 여부는 별도 판단 |
| 6 · 실제 응답 요약 | 양방향 **전면통제** | `controlScope=FULL`, `directionText=양방향` | 차로 총수는 모름; `closedLaneCount=null` |

실제 응답 8건을 GMS에 보내 코드의 원문 근거 검증을 통과했다. 이것은 사람이 정답을 붙인
정확도 평가가 아니다. 모델이 `1차로`를 1개 차로로 잘못 해석한 사례는 후속 규칙이 버리고
`aiQualityFlags`에 남겼다. 사고·공사·집회·완료 표현의 라벨 평가를 거친 뒤 운영 활성화를 결정한다.

## 추출 결과: 사건 1건당 1행

원문 `description`은 그대로 보존한다. GMS 결과는 고정 스키마로 별도 저장한다.

| 필드 | 값 | 근거/용도 |
|---|---|---|
| `providerIncidentId`, `contentHash` | 원천 ID, 본문 개정 해시 | 반복 15분 수집의 중복 GMS 호출 방지 |
| `incidentTypeCode`, `incidentDetailTypeCode` | 원천 값 | 공식 코드표로 유형 분류. GMS가 덮어쓰지 않음 |
| `matchedLinkId`, `locationMatchType` | 기존 검증 결과 | 버스 구간 연결. GMS가 덮어쓰지 않음 |
| `controlScope` | `FULL / PARTIAL / NONE / UNKNOWN` | 문장에 명시된 통제 범위 |
| `closedLaneCount` | 정수 또는 `null` | `2개 차로`처럼 개수가 명시될 때만 입력 |
| `laneNumbers` | 번호 배열 | `2차로`처럼 **차로 번호**가 명시될 때 입력 |
| `directionText` | 원문 방향 표현 또는 `null` | 승인된 링크 방향과 일치 여부를 후속 검증 |
| `detourMentioned` | `true / false` | **문장에 우회가 언급됨**의 의미. `false`는 우회가 없다는 증거가 아님 |
| `evidence` | 추출 값별 원문 부분 문자열 | 원문에 없는 값을 모델이 만들어냈는지 검증 |
| `extractionStatus`, `model`, `promptVersion` | 품질·재현 정보 | 오류/미확인과 실제 0을 구분 |

예: `2차로 통제` → `laneNumbers=[2]`, `closedLaneCount=null`; `2개 차로 통제` → `closedLaneCount=2`, `laneNumbers=[]`.

## 처리 순서

1. **원천 확인:** 기존 Collector → Kafka → Silver는 유지한다. 원천 ID, 링크, 시각, 설명을 보존한다.
2. **구조화 값 우선:** `ACC_TYPE`/`ACC_DTYPE`을 공식 코드표로 분류한다. `startedAt`은 원천 발생시각을 쓴다. `expectedClearAt`은 예측 종료이므로 실제 종료로 바꾸지 않는다.
3. **설명문 추출:** 제공자 사건 ID·설명문·모델·프롬프트 버전의 해시가 처음 나타나거나 수정될 때만 GMS를 호출한다. SQLite 영속 캐시와 배치 호출 상한을 사용한다. GMS가 제안한 유형·차로·통제·시각을 코드가 원문과 다시 대조한다.
4. **검증:** enum/숫자 범위/근거 문자열을 검사한다. `2차로`와 `2개 차로`를 별개 필드로 둔다. 근거가 없는 제안은 해당 필드만 `UNKNOWN`/`null`로 바꾸고 `aiQualityFlags`에 남긴다. 원천 사건 유형 코드를 바꾸지 않는다. 응답 실패 시 `aiExtractionStatus`만 오류로 표시하고 사건 자체는 버리지 않는다.
5. **저장:** 동일한 사건별 정규화 행을 Redis 최신값과 선택적 HDFS 이력에 쓴다. 원문, 추출 값, 모델·프롬프트 버전, 상태를 함께 저장한다. 현재의 Redis **전체 돌발 스냅샷**에도 검증된 추출 값만 전달한다. GMS 결과가 늦으면 기존 링크/건수 집계는 계속 동작한다.
6. **구간 집계:** 승인된 `segmentId → LINK_ID 목록`과 `matchedLinkId`를 연결하고, 예측 기준시각 `asOf` 이전에 관측된 현재 사건만 집계한다. 통과 예정시각 `passageAt`에 실제 활성 상태가 보증되지 않으면 미래까지 영향이 확정된 것처럼 처리하지 않는다.

## ML로 전달할 1차 특성

| 구간별 특성 | 계산 방법 | 현재 데이터로 가능한지 |
|---|---|---|
| `verifiedIncidentCount` | 구간 링크와 일치하는 고유 사건 수 | 이미 구현 |
| `incidentMinAgeMinutes` | 일치 사건 중 가장 최근 발생시각부터 `asOf`까지 | 이미 구현 |
| `textFullClosureCount` | `controlScope=FULL` 사건 수 | v3 온라인 집계 구현; 승인 링크 매핑 필요 |
| `textPartialClosureCount` | `controlScope=PARTIAL` 사건 수 | v3 온라인 집계 구현; 승인 링크 매핑 필요 |
| `maxExplicitClosedLaneCount` | 개수가 **명시된** 사건에서 최대 통제 차로 수 | v3 온라인 집계 구현; 모르면 `null` |
| `busDetourMentionCount` | 원문에 버스 우회가 명시된 사건 수 | v3 온라인 집계 구현 |
| `roadSpeedKph`, `roadCoverageRatio` | 같은 구간의 실시간 도로 속도·관측 비율 | 기존 온라인 조립기에 구현 |
| `incidentSnapshotAgeMinutes`, `incidentAiCoverageRatio` | 원천 신선도와 구간 내 유효한 AI 추출 비율 | v3 온라인 집계 구현 |

한 구간에 사건이 여러 개면 사건 ID로 중복 제거 후 집계한다. `maxExplicitClosedLaneCount=0`은 **확인된 0개**일 때만 쓰고, 문장에 개수가 없으면 `null`이다. 차로 번호는 차로 수로 합산하지 않는다. `accidentCount` 등 유형별 건수는 공식 코드표 검증 후 추가한다.

## GMS 프롬프트와 운영 경계

시스템 지시 예: “입력 `description`에서 **명시된 표현만** 추출하라. `LINK_ID`, 사건 유형 코드, 발생·종료 시각을 새로 만들거나 수정하지 마라. `2차로`는 차로 번호, `2개 차로`는 차로 개수다. 각 값의 근거를 원문 부분 문자열로 반환하라. 모르면 `null`/`UNKNOWN`을 반환하라.”

- **중복/비용:** 매 15분 같은 문장을 재호출하지 않는다. 변경 해시별 영속 캐시·호출량 계측을 둔다. Redis/HDFS를 별도 스트림에서 각각 추출하면 같은 GMS 요청을 두 번 보낼 수 있으므로 추출 결과를 한 번 확정하고 두 저장소에 전달한다.
- **품질:** 표본을 사람이 먼저 라벨링한 뒤 `FULL/PARTIAL`, 차로 번호/개수, 방향, 우회 언급별 오분류를 확인한다. 기준에 못 미치는 필드는 ML에 보내지 않는다.
- **공간 범위:** 문장만으로 정류장이나 링크를 정하지 않는다. 현재 승인된 버스 도로 매핑이 저장소에는 없어 운영 구간 특성의 실제 연결은 별도 승인·배포가 필요하다.
- **시간/학습:** 온라인과 과거 학습 데이터에 같은 추출 버전·코드표·구간 매핑 규칙을 적용한다. 이미 수집된 과거 원문을 새 AI 결과로 소급 적용할 때는 당시 관측·수집시각 기준으로 학습 행을 재생성한다. 새 특성은 현재 LightGBM 모델에 자동 입력되지 않는다.

## 먼저 검증할 실험

1. 실제 OA-13315 `ACC_INFO` 표본을 여러 유형에서 확보하고 개인 식별 정보가 없는지 확인한다. 이번 문서의 표본 1~3은 fixture, 4~6은 TOPIS 화면 요약이라 동일 API 표본으로 간주하지 않는다.
2. 동일 표본에 규칙만 적용한 결과와 GMS 결과를 나란히 비교한다. 특히 `2차로`/`2개 차로`, 완료/예정, 양방향/한 방향을 검사한다.
3. 선택한 새 특성으로 과거 **버스 구간 이동시간** 예측을 재학습·시간순 검증하고, 기존 특성만 쓴 모델 대비 오차가 줄어드는지 측정한다. 효과가 확인된 필드만 운영 특성 계약에 추가한다.

## 현재 구현 실행

- `infra/.env`에 이미 있는 `GMS_API_KEY`를 사용한다. 키와 실시간 응답 원문은 Git에 넣지 않는다.
- 로컬 표본 재검증: `python sparkjobs/tools/verify_incident_ai.py --fetch --env-file infra/.env --output sparkjobs/data/incident-ai-review/live --limit 8`
- Spark 서비스의 `TRAFFIC_INCIDENT_AI_ENABLED` 기본값은 `false`다. 검토 후 `true`로 켜면
  Redis 최신 행과 선택적 HDFS Silver 행에 동일한 AI 컬럼을 기록한다.
- 모델 사용에는 승인된 서울 링크 마스터 및 버스 구간 대응표가 별도로 필요하다.
  현재 저장소의 미승인 후보 연결로 돌발 위치를 확정하지 않는다.
