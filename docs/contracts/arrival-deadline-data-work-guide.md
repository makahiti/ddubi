# DATA 작업 가이드 — 원천 확보·정규화·매핑·실시간 서빙·재현

- 상태: 구현 전 작업 명세. 외부 데이터는 보유·권한·최신성 증거가 없으면 확보 미확인이다.
- 책임: 필요한 자료를 찾고 확보하여 Collector→Kafka→Spark→Redis/Parquet 및 Spring 내부 API로 전달한다. 후보 경로 생성·최종 추천·사용자 알림 전송은 BE/ML, 화면과 위치 동의는 FE 책임이다.
- 필수 기준: [원천별 공식 출처·필드·확보 상태](arrival-deadline-data-guide.md), [공통 구현 계획](arrival-deadline-implementation-plan.md), [ML 데이터 결정](ml-data-decision.md), [BE/ML 인계](arrival-deadline-be-ml-work-guide.md).

## 1. 최종적으로 만들어야 하는 결과

BE/ML이 ‘이 일정의 버스 승차 정류장에 언제 도착하는가’, ‘이 열차가 실제로 어느 운행인가’, ‘이 도로 사건이 이 방향의 버스 구간에 적용되는가’를 판단할 수 있는 검증된 자료를 제공한다. 외부 응답을 Redis에 넣기만 하는 것으로 완료하지 않는다.

```text
공식 출처·권한 확인 → 실제 샘플·원본 확보 → raw Kafka/아카이브
→ 타입·시간·코드·ID 정규화 → 정적/실시간 매핑 검증
→ 최신 예측·사건·구간 상태 → Redis + 변경 이벤트 → BE/ML
원본/실제 fact → Parquet → 기준선 배치 → Spring upload/activate
```

## 2. 작업 목록과 시작 순서

| 작업 | 무엇을 하는가 | 완료 산출물 |
|---|---|---|
| DA01 확보 목록 | 코드 존재와 실제 자료 보유를 분리 | 출처/권한/샘플/범위/미확보 목록 |
| DA02 정적 마스터 | 버스·지하철·도로·달력 연결 | 버전/SHA/적용일/coverage artifact |
| DA03 수집 | 승인 쿼터 내 원천을 지속 조회 | raw 이벤트·poll 상태·성공/실패 지표 |
| DA04 정규화 | raw를 계약 컬럼으로 변환 | Silver/latest와 rejected 데이터 |
| DA05 사건·운행 | 사건 범위와 실제 경로·운행 대응 | incident lifecycle·impact·trip prediction |
| DA06 실측·기준선 | 진짜 정답 확보 후 통계 계산 | ACTUAL_TRAVERSAL facts·분위수·상태 |
| DA07 ML 전달 | 학습/서빙 입력이 같은 뜻이 되게 공급 | feature source dictionary·정답/이력 manifest |
| DA08 서빙·Replay | 장애·재처리에도 같은 뜻 유지 | Redis/Kafka 계약·재현 fixture·운영 인계 |

DA01을 먼저 진행한다. 확보가 안 된 항목은 그대로 막힘을 기록하고 해당 부분의 합성 fixture/정규화 코드만 개발한다. ‘사용자가 가지고 있을 것’이라는 전제로 샘플 경로나 실제 수치를 채우지 않는다.

## 3. DA01 — 어떤 데이터를 어디에서 가져와야 하는가

아래 링크는 공식 출처 또는 기존 계약의 원천이다. 상세 raw 컬럼/코드와 현재 확인 상태는 데이터 가이드 0절 및 S01~S14 본문을 같이 따른다. 실제 키 요청은 키 값을 공유하지 않고 승인 여부·쿼터만 인계한다.

| 자료 | 제공처·출처 | 가져와야 할 값 | 누가 소비하는가 / 없을 때 |
|---|---|---|---|
| 버스 도착 S03 | 서울 [버스도착정보조회](https://www.data.go.kr/data/15000314/openapi.do) | busRouteId,stId,staOrd,mkTm,exps1/2,vehId1/2,full1/2,deTourAt | BE 대기. 없으면 실시간 대기 제외 |
| 버스 위치 S04 | 서울 [버스위치정보조회](https://www.data.go.kr/data/15000332/openapi.do) | vehId,구간/정류장 순서,위치/정차/혼잡,원천시각 | BE 차량 검증·관측 이력. 실제 통과 정답과 구별 |
| 지하철 도착 S06 | 서울 [OA-12764](https://data.seoul.go.kr/dataList/OA-12764/A/1/datasetView.do) | subwayId,statnId,btrainNo,recptnDt,barvlDt,방향/행선지/상태 | BE 승차 ETA. 미제공 하차역 시각은 null |
| 지하철 위치 S07 | 서울 [OA-12601](https://data.seoul.go.kr/dataList/OA-12601/A/1/datasetView.do) | trainNo,현재 역,노선,방향,종착역,상태,recptnDt | BE 운행 검증. trainNo를 정적 열차코드로 간주 금지 |
| 지하철 공지 S08 | 서울 [getNtceList OA-22718](https://data.seoul.go.kr/dataList/OA-22718/A/1/datasetView.do) | noftOcrnDt,noftTtl,noftCn,lineNmLst,stnSctnCdLst | 사건 이유 후보. 구간 미확정이면 노선 전체 차단 금지 |
| 도로 돌발 S09 | 서울 [AccInfo OA-13315](https://data.seoul.go.kr/dataList/OA-13315/A/1/datasetView.do) | acc_id,link_id,acc_type/dtype,발생/예상해제,설명,원천 좌표 | 영향 후보. FULL/방향/수단 근거 부족 시 POTENTIAL_IMPACT |
| 도로 소통 S14 | 서울 [OA-13291](https://data.seoul.go.kr/dataList/OA-13291/A/1/datasetView.do) | 필요한 값은 링크 ID·속도·통행시간·관측시각. raw 필드/단위 확인 선행 | 구간 악화 관측. 미확보면 임의 지연분 생성 금지 |
| 일반 교통 공지 S12-N | TOPIS [NoticeList OA-22861](https://data.seoul.go.kr/dataList/OA-22861/A/1/datasetView.do) | bdwr_seq,제목/본문,작성/수정시각,원문 참조 | 우회/통제 설명. 모든 사건/종료 보증 아님 |
| 날씨 S11/S11-F | 기상청 [실황·예보](https://www.data.go.kr/data/15084084/openapi.do) | 격자,발표/관측/예보 대상시각,category,value | weather bucket/검증된 feature. 현재 실황을 미래 실측으로 대체 금지 |
| 장소 혼잡 S12-C | 서울 [도시데이터 OA-21285](https://data.seoul.go.kr/dataList/OA-21285/F/1/datasetView.do) | 장소 코드,인구 혼잡/범위,지역 속도,각 원천시각 | 지역 위험 보조. edge 속도와 구별 |
| 시간표 S05 | [서울교통공사 운행시각표](https://www.data.go.kr/data/15098251/fileData.do) | 호선,역사코드,요일종류,방향,급행,열차코드,도착/출발시간,시행일 | BE 정적 trip. 전 수도권 지원으로 간주 금지 |
| 공휴일 | [한국천문연구원 특일 정보](https://www.data.go.kr/data/15012690/openapi.do) | 날짜,공휴일 여부,명칭,조회/공개 근거 | dayType. 특별운행은 운영자 자료 별도 |
| 버스/역 마스터·도로 연결 S10 | 로컬 `backend/transit-master/` 출처 manifest와 데이터 가이드 0.3/7.2절 공식 BIS/TOPIS 파일 | namespace,노선/정류장/역 ID,순서,방향,좌표,도로 링크/노드,적용기간 | BE/Spark 동일 매핑. 파일 존재와 운영 적재를 구별 |

카카오 대중교통·제휴 도보는 **BE가 요청하는 외부 연동**이다. DATA는 endpoint 검증에 필요한 마스터를 전달하고 공용 수집기로 사용자별 카카오 호출을 복제하지 않는다. FE 위치·실제 이용 기록도 별도 동의와 계약 없이는 DATA 수집 대상에 넣지 않는다.

**바로 다운로드할 출처가 확정되지 않은 항목:** 정적↔실시간 열차 ID 대응, 모든 역의 미래 예측 출발시각, 차량별 실제 통과시각, 요일/시간대별 확정 배차계획, FULL/차종/방향/실제 종료 근거, 개인 탑승·환승 정답. 해당 운영자/제공기관에 요청할 필드와 응답을 기록하고 제공 불가면 기능을 제한한다. 현재 API의 비슷한 필드로 대체 확보했다고 표시하지 않는다.

### 확보 절차

1. source별 공식 페이지·서비스명·지원 지역·이용조건·owner를 기록한다.
2. API 활용신청 또는 제휴 확인 후 승인 쿼터·초당 제한·지원 operation을 확인한다. 파일은 최신 시행일과 원본 SHA를 확보한다.
3. 마스킹한 정상/빈/오류/부분 페이지 샘플과 실제 수집일을 남긴다. 원천시각이 없으면 수집시각만 있다는 한계를 기록한다.
4. source-acquisition-register의 상태를 미확인→샘플 확보→운영 검증으로 증거와 함께 갱신한다. 이 레지스터는 추가 구현 산출물이며 현재 존재를 가정하지 않는다.
5. DATA→BE/ML에 범위·누락·수집 간격·원천 age·매핑률을 전달한다. 실시간이라는 명칭만으로 1분 최신성을 보장하지 않는다.

## 4. DA02 — 마스터와 운행·도로 매핑

**파일:** `backend/transit-master/bus/`, `subway/`, `road/`의 versioned artifact; `backend/tools/transit-timing/build_timetable.py`, `backend/tools/road-impact/audit.py`, `promote.py`. `build_train_crosswalk.py`는 추가안이다. DATA는 자료/빌더를 담당하고 Java resolver·서비스 DB 적재는 BE와 연결한다.

1. 버스는 namespace+routeId+정류장 순번을 기준으로 노선 순서를 만든다. 같은 정류장이 재등장하는 순환노선은 occurrence를 보존한다.
2. 지하철은 정적 역 ID와 실시간 역 ID를 명시 매핑한다. 같은 역 이름/노선 번호만으로 EXACT를 만들지 않는다.
3. 시간표의 운행일·요일종류·방향·급행·정차순서와 24시 초과 시각을 검증하여 runtime artifact를 만든다. 공휴일/특별운행 미확보 날짜는 지원 한계로 기록한다.
4. 열차 crosswalk는 운영자 근거가 확인된 serviceDate+trip+direction+destination에서만 EXACT다. 운행 자료가 없으면 UNMAPPED로 남긴다.
5. 도로는 공식 노선→정류장 구간→BIS 링크→방향 노드→TOPIS/AccInfo namespace의 증거 사슬을 만든다. 링크 ID 숫자가 같아도 namespace 검증 없이는 연결하지 않는다.
6. manifest에 원본 URL/SHA, builder version, validFrom/Until, masterVersion, coverage, rejected 사유를 넣는다. BE와 Spark에 같은 checksum을 전달한다.

**실패/완료:** 끊긴 노드·중복 순번·여러 매핑 후보는 격리한다. EXACT 성공률과 미매핑 예제, 동일 원본의 재현 가능한 artifact, 두 소비자 loader 통과가 완료 기준이다. 로컬 미추적 환승 자료는 검토/채택 전 공용 기준에 편입하지 않는다.

## 5. DA03 — 수집 루프·쿼터·poll 상태

**파일:** `collector/src/main.py`, `config.py`, `request_budget.py`, `sources/kafka_adapter.py`, 원천별 `bus_arrival.py`, `bus_location.py`, `subway_arrival.py`, `subway_location.py`, `subway_alert.py`, `traffic_incident.py`, `topis_notice.py`. 추가안 `source_poll_status.py`, `demand_registry.py`.

1. BE의 demand를 source/provider scope로 합친다. 필요한 노선·역·기간·우선순위만 받고 사용자 ID/일정 제목은 받지 않는다.
2. source별 due와 실제 키 그룹의 rolling 호출 예산을 함께 검사한다. 재시도/페이지 호출도 예산에 포함한다.
3. HTTP 상태와 응답 내부 결과코드, 모든 페이지/전체건수를 검사한다. HTTP 200 오류 본문을 성공 데이터로 발행하지 않는다.
4. raw envelope에 eventId/source/eventType/occurredAt/collectedAt/provider ID를 보존하고 Kafka에 발행한다. source timestamp가 없으면 received-only로 표시한다.
5. Kafka ACK 후 성공 cursor/dedup 상태를 갱신한다. 기존 TOPIS dedup·재시도·버스 Silver 흐름을 중복 구현하지 않는다.
6. poll 상태에 성공/정상 빈 전체/부분/실패/쿼터제한/정책 생략을 기록한다. exact enum은 공통 schema에서 단일 확정하고 producer와 consumer가 같은 enum을 사용한다.
7. 동일 사건 본문을 dedup하더라도 현재 목록에서 관측됐다는 heartbeat는 별도로 보존한다. 생략/실패를 사건 해제로 전환하지 않는다.

**예산 예제:** 100개 scope를 60초마다 하루 종일 1페이지 수집하면 144,000회/일이다. 승인 쿼터가 이를 못 받치면 활성 scope/시간대를 줄이거나 한도 확대를 요청한다. TTL 증가로 실시간 문제를 숨기지 않는다.

**완료 시험:** 키 그룹 공유·429·5xx·정상 빈 응답·부분 페이지·ACK 실패·동일 본문·운행 생략. source별 실제 poll 간격/age/누락과 budget을 인계한다.

## 6. DA04 — raw에서 Silver/latest로 변환

**파일:** `sparkjobs/common/bus_normalizer.py`, `common/bus_stream_sink.py`, `batch/bus_silver.py`, `streaming/realtime_latest.py`; schema는 `docs/contracts/events/`, `topics.yaml`, `redis-keys.yaml`과 함께 맞춘다.

| 원천 | 변환 | 검증 |
|---|---|---|
| 버스 mkTm+exps1/2 | sourceGeneratedAt+arrivalSeconds→절대 ETA | collectedAt에 초를 더하지 않음; 0의 원천 의미 검증 |
| 지하철 recptnDt+barvlDt | sourceGeneratedAt+arrivalSeconds→절대 ETA | 수집 지연 중복 가산 금지 |
| 버스/열차 ID | 문자열 namespace+ID 보존 | 선행 0·다른 지역 ID 혼합 금지 |
| 정류장/역 순서 | stopOrder/stopSequence/occurrence | sectOrd를 남은 역 수로 복사 금지 |
| 날씨 category/value | 단위 검증 후 기온/강수/PTY 등 | 결측 강수=0, 결측 PTY=DRY 금지 |
| incident 시각/좌표 | 원천 시간·예상 종료·원천 CRS 보존 | 예상 종료=실제 종료, 투영좌표=WGS84 오독 금지 |

1. 명시 schema로 파싱하고 필수 ID/타입/시간/범위 오류를 rejected에 남긴다. raw를 버리지 않는다.
2. eventId 중복과 같은 원천 identity의 수정 revision을 구별한다. source time이 옛 값이면 latest를 되돌리지 않는다.
3. `observedAt`, `collectedAt`, `timestampBasis`, `validUntil`, missingFields/qualityFlags를 보존한다. 원천 관측시각 미확보는 현재시각으로 채우지 않는다.
4. 기존 버스 Silver 필드와 호환 구조를 재사용한다. Redis 보관 TTL과 BE 소비 freshness는 별도로 관리한다.
5. 동일 normalized fixture를 Kafka/Redis 소비 계약에 통과시킨다. 새 컬럼은 schema가 추가 필드를 허용하는지 먼저 확인한다.

**예제:** mkTm=08:00,exps=120,collectedAt=08:00:30이면 ETA=08:02다. 최신성은 08:00 기준이며 수집만 새로 됐다고 08:02:30으로 바뀌지 않는다.

## 7. DA05 — 지하철 예측·사건 영향·해제

**파일:** 기존 `common/subway_crosswalk.py`, `streaming/road_incidents.py`, `common/road_impact.py`; 추가안 `common/trip_stop_predictions.py`, `common/incident_lifecycle.py`, `streaming/transit_incidents.py`.

1. 정적 trip/stop에 실시간을 left join하되 serviceDate/trip/stopSequence와 EXACT crosswalk를 검사한다. 없는 하차/출발 예측은 null이다.
2. 동일 trip/stop의 예정과 예측을 비교할 때만 시간표 대비 지연을 계산한다. 이전 ETA 증가와 시간표 지연은 별도 지표다.
3. incidentKey는 원천+사건 ID, revision은 의미 있는 내용 변경으로 만든다. 매 poll UUID를 새 사건 identity로 쓰지 않는다.
4. 원천 코드/공지 근거에서 FULL/PARTIAL, 방향, 대상 수단, 적용 기간을 추출하되 불명확하면 UNKNOWN이다. 원문 자연어 추정만으로 자동 차단하지 않는다.
5. 검증된 artifact로 영향 segmentIds/trip/stops를 전개하고 mappingVersion/evidenceRef를 붙인다. 실제 통과시각과 사건 기간 겹침은 BE 계산에서도 다시 확인한다.
6. 명시 종료 또는 원천이 보증하는 완전 활성 목록 계약이 있을 때만 ENDED로 간다. TTL 만료·예상종료 경과·poll 실패는 STALE/UNKNOWN이다.
7. 변경 영향 이벤트에는 eventId,incidentKey/revision,영향 범위,원천시각,매핑 버전을 담아 BE를 깨운다. 사건별 지연초는 독립 근거가 없으면 null이다.

**완료 시험:** 반대 방향·시간 밖·일부 차로 통제, 같은 사건 다중 edge, 링크 이동/수정, 늦은 revision, 전체 목록 일부 실패, 공지 정정, 하차 예측 누락. 원인 텍스트와 수치 지연을 구별한 fixture를 BE에 전달한다.

## 8. DA06 — 실제 통과 정답·기준선·구간 상태

**선행 조건:** 실제 vehicle/trip의 인접 stop 통과시각 원천 확보. 현재 위치·ETA만 보유하면 신규 ACTUAL_TRAVERSAL 기준선은 막힌 상태다.

**파일:** `sparkjobs/common/segment_facts.py`, `streaming/segment_observations.py`, `batch/segment_baseline.py`, `streaming/segment_status.py`, `segment_status_live.py`; 기존 `docs/contracts/segment-observation.schema.json`을 재사용한다.

1. 동일 차량/운행의 인접 정류장 시작/종료 실제시각을 검증한다. 누락·역전·비인접·차량 교체는 정답에서 제외하고 이유를 남긴다.
2. durationSeconds=종료-시작으로 fact를 만들고 masterVersion,eventId,source,시각,weatherBucket을 붙인다. 결측 날씨를 DRY로 만들지 않는다.
3. fact를 eventId로 dedup하고 segmentId×dow×hourSlot×weatherBucket×dataVersion으로 집계한다. dow는 1=일요일이다.
4. p50/p80/p95, sampleCount,표본기간·계산시각을 산출한다. 표본 부족은 부족 상태로 전달하고 평균값을 P80으로 이름만 바꾸지 않는다.
5. Spring 내부 API로 청크 업로드하고 전체 성공 후 activate한다. 일부 실패하면 기존 버전을 계속 사용한다. Spark가 MySQL에 직접 쓰지 않는다.
6. 최신 구간 상태는 검증된 최근 실측 window와 같은 조건의 baseline을 비교한다. 결측/stale이면 unknown이며 지연은 0 이상의 초다.
7. 상태 delaySeconds는 위험/변경 trigger다. 이미 실시간/ML로 계산한 BE 이동시간에 중복 가산하지 않도록 소비 계약에 적는다.

**완료 시험:** 손계산 fixture, 분위수 순서, sampleCount, 중복/늦은 fact, 기준선 전량 업로드 실패·활성화 실패, 날씨/버전 혼합, Redis stale. 실제 fact 미확보는 fixture 테스트 통과와 별도로 표시한다.

## 9. DA07 — ML에 전달할 데이터 패키지

1. 원본/정규화/fact/파생 feature를 다른 데이터 종류로 표시하고 각각 sourceRef·SHA·기간·행 수·단위를 기록한다.
2. ML 요청 범위의 각 feature에 raw 필드, join key, event/공개/수집시각, 집계 window, 결측률, 예측 시 확보 가능 여부를 적는다.
3. 시간대별 버스 평균 소요와 개별 차량 실측 소요를 다른 target으로 인계한다. 지하철 시간표와 ETA를 실제 운행 정답으로 주지 않는다.
4. 날씨 예보는 발표시각별 이력을 유지한다. 과거 예측 시각에 알 수 없었던 미래 실측·사후 집계를 학습 입력으로 섞지 않는다.
5. 승하차 통계·유동인구·추가 환경 feature는 [ML 데이터 결정](ml-data-decision.md)의 목표 입력일 뿐 확보 완료가 아니다. 확정 출처·샘플·갱신 지연·join 규칙이 없으면 미확보 목록으로 전달하고 모델 입력 채택을 보류한다.
6. 시간 순 분할 가능한 날짜와 식별자를 보존한다. DATA가 임의 무작위 분할하여 미래 누출을 만들지 않는다.

**BE/ML 수령물:** source dictionary, raw/normalized schema, dataset manifest, rejected/coverage 보고서, 손계산 샘플, 실제값/예측값 구분, 추가 확보 요청. 실제 개인정보/키를 테스트 fixture에 넣지 않는다.

## 10. DA08 — 서빙·재처리·운영 인계

1. Kafka topic/key/schema, Redis key/TTL/validUntil, 내부 API 버전을 먼저 합의한다. 새 enum/필드를 한 소비자만 바꿔 배포하지 않는다.
2. Redis 최신값 저장과 변경 이벤트 발행의 실패 순서를 정의한다. 재시도는 deterministic eventId·멱등 쓰기로 복구하고 exactly-once를 임의 주장하지 않는다.
3. `archiver/src/config.py`, `replay.py`는 원본 보존·재생 범위와 새 토픽 처리 필요를 검토한다. 파싱 실패 raw도 보존하고 저장 완료 후 offset commit한다.
4. Replay manifest는 asOf·원본 refs·마스터/모델/시간표/정책/기준선 버전을 고정한다. ONLINE_REPLAY는 당시 observedAt까지 들어온 자료만 사용한다. 현재 Redis 조회로 과거 결과를 재현하지 않는다.
5. Spark schema/join/window/watermark/checkpoint와 상태 크기를 기록한다. Python 3.8을 지키고 driver 전체 collect/toPandas로 처리하지 않는다.
6. 운영팀에는 승인 쿼터·필요 topic/checkpoint·Redis 메모리/TTL·artifact SHA·지표·보존·복구 순서를 전달한다. `infra/`, Docker, CI 배포 파일을 DATA 작업에서 직접 바꾸지 않는다.

## 11. 인계 예제와 완료 기준

합성 handoff 예제(실제 확보 자료가 아님): `source=S03, route=sample-route, stop=sample-stop, sourceGeneratedAt=08:00, collectedAt=08:00:30, firstArrivalSeconds=120, predictedArriveAt=08:02, mappingStatus=EXACT, validUntil=정책에 따른 시각`. BE는 플랫폼 도착 08:01이면 대기 60초를 평가하며, 필수 원천시각/매핑이 없으면 fallback한다.

각 source 인계에는 다음을 묶는다.

- 공식 URL/서비스/권한·쿼터와 확보 상태
- 정상·빈·오류·부분·stale·중복·순서역전 raw 및 normalized fixture
- 원천→컬럼 변환, 시간/단위/코드/ID namespace
- artifact version/SHA/coverage와 미매핑 사유
- 최신성·수집간격·결측·거부율·호출 예산 실측
- BE 소비 schema/Redis 예제/변경 이벤트/미확보 제한
- 동일 manifest Replay 결과와 장애 복구 시험

구현 시 관련 범위에서 `collector`, `sparkjobs`, `archiver`의 `python -m pytest -q`를 각각 실행하고 schema/소비자 계약 테스트를 포함한다. 정확도와 처리 성능을 별도 보고한다. 실제 데이터가 없으면 ‘코드·fixture 검증 완료, 운영 데이터 미확보’라고 나누어 완료 상태를 적는다.
