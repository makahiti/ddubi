# 도로 링크 영향 보강: 검토용 v1

## 2026-09-17 추가 확인과 live 입력

새 표준링크 XLSX 11,572쌍을 추가 대조했다. 기존 버스 도로 후보 24,052개 중 완전 ID 연결은
13,606개이며 운영 승격은 0개다. 현행 진행방향·AccInfo namespace·완전 통제/적용시간 근거가
여전히 필요하다. 원천 SHA와 결과는 `backend/transit-master/validation/road-link-update-20260917.json`,
재검토 도구는 `backend/tools/road-impact/audit_standard_links.py`다. 아래 9월 11일 수치는 당시 자료 기준이다.

`streaming.segment_status_live --blockages VERIFIED.jsonl`은 이제 각 tick에서 검증된 snapshot을 읽는다.
스키마는 batch evaluate와 같은 `segmentId, masterVersion, eventId, occurredAt, validUntil, source`
문자열과 `confirmed` boolean이다. eventId는 원천 사건을 추적할 수 있는 **UUID 형식**이어야 한다.
시각은 KST 오프셋 포함 ISO 8601, segmentId는 활성 registry의
canonical 구간, masterVersion은 같은 버전이어야 한다. 원시 AccInfo 응답을 여기에 직접 넣지 않는다.
confirmed는 **전체 운행 불가 및 해당 구간·방향·기간이 검증됨**을 뜻하며 단순 사고 발생이 아니다.
이벤트 작성자가 해당 공식 근거/ID 대응 이력을 보존해야 한다.

미설정이면 기존 통제 입력 없음 경로를 사용한다. 설정 파일의 누락·파싱 오류는 배치 실패로 처리하고,
evaluate에서 미래/만료/버전 불일치/미확정 통제를 제외한다. 완성된 batch manifest 재시도는 원래
계산 결과를 재발행하여 멱등성을 유지한다. 이 입력 연결이 실제 사건 → 검증 통제 자동 변환의 완료를 뜻하지 않는다.

```json
{"segmentId":"<검증된 canonical ID>","masterVersion":"<활성 registry 버전>","eventId":"<공식 사건 추적 ID>","occurredAt":"2026-09-17T09:00:00+09:00","validUntil":"2026-09-17T09:10:00+09:00","source":"<공식 근거 출처>","confirmed":true}
```

위 JSON은 필드 설명용이며 실제 사건 데이터가 아니다. 수집기/잡 실행 주기와 배포 설정은 변경하지 않았다.

2026-09-11 원본 로컬 프로젝트에서 구현. 운영 활성화/수집주기 변경/모델 재학습은 하지 않았다.
기존 metropolitan-bus-v6와 canonical ID를 바꾸지 않고 별도 도로 대응표를 붙인다.

## 확보한 실제 자료

`data/road-impact-20260911/sources.json`에 다운로드 URL·시각·바이트·SHA가 있다.
원본 약 165 MB와 전체 후보 JSONL은 git 제외인 data 폴더에 보존한다.

- [BIS 구간링크](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=62)
- [BIS 노선별 경유노드](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=58)
- [BIS 공간 링크](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=63)
- [BIS 노선별 좌표](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=56): 이번 파일은 화면 표출 좌표이므로 위경도 조인에서 제외.
- [TOPIS 표준링크](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=7)
- [서울 교통소통 표준링크 매핑](https://data.seoul.go.kr/dataList/OA-15061/S/1/datasetView.do): 2025-03-06 XLSX, 보조 대조 자료.

## 실제 연결 감사

서울 v6 40,441 edge 중 BIS 방향 연속 링크 후보 24,052개(59.47%).
TOPIS까지 전체 연결 후보 17,465개(전체 서울 edge의 43.19%), 부분 연결 6,587개.
도로 링크 누락/모호 10,995개, 방향 연결 단절 4,190개, 정류장 occurrence 미해결 1,204개.
이는 파일 간 후보 연결률이며 현행 운행 경로·사고 영향 정확도나 서비스 적용률이 아니다.

노선 ID·양끝 정류장 ID·정류장 순번으로 출발/도착 노드를 찾고, 사이 노드의 구간 ID를
BIS 구간링크에 조인한다. 링크 시작/종료 노드의 방향 연속성을 검사한다. TOPIS 표준 ID와
BIS 링크 ID가 같더라도 시작/종료 노드까지 같은 경우만 연결한다. 숫자 접두어나 이름으로
맞추지 않는다. CSV 각 셀의 BOM은 정제하고 원본은 유지한다.

재현 (프로젝트 루트):

```powershell
python backend/tools/road-impact/audit.py --directory data/road-impact-20260911 --bus-crosswalk backend/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz
```

결과: `mapping-audit.json`, `mapping-candidates.jsonl`, `mapping-rejected.jsonl`.
구간은 노선별 fromOrder까지 유지한다. 같은 canonical ID의 순환 노선 occurrence를 합치지 않는다.

## Collector와 공통 전처리

Collector 사고 payload에 `providerLinkNamespace=SEOUL_ACCINFO_LINK`를 추가하고 기존 Kafka key를
유지한다. `raw.providerFields`에 원본 XML 필드명·값을 추가 보존한다. 이 namespace는 AccInfo 출처를
뜻한다. TOPIS 서비스 링크로의 검증은 별도 대응표 근거에 남겨야 한다. 구형 payload는 출처를
추측하지 않고 EVENT_LINK_UNRESOLVED로 처리한다.

`sparkjobs/common/road_impact.py`는 검증된 별도 도로 artifact만 받는다. SHA·버스 버전·edge
membership·도로 방향 검증 여부·적용기간 중복을 확인한다. 기준시각 이후 수집값을 제외하고
사고별 마지막 관측을 선택한다. 동일 시각 상충 응답, stale, 누락, 미매핑은 UNKNOWN이다.
예상 해제시각을 실제 해제시각으로 간주하지 않는다. 양방향 도로는 서로 다른 링크로 판정한다.

출력 POTENTIAL_IMPACT는 해당 링크에 유효 관측이 연결됐다는 위험 후보다. blocked와
지연초는 null이다. NO_MATCHING_RECORDED_EVENT 역시 무사고 보장이 아니다. 공지·집회 문자열만으로
차로 전체 통제나 지연 분을 만들어내지 않는다. 현재 제공 코드만으로 통제 범위가 불명확하다.

## 경로 요청·ML 경계

기존 카카오 매핑이 만든 edge와 순번을 유지하고 별도 enrichment에서 도로 목록을 붙인다.
도로 영향으로 canonical ID를 재발급하거나 기존 ML 인코더를 바꾸지 않는다.
검토 CLI `sparkjobs/batch/preview_road_impact.py`는 requestId, asOf,
maxObservationAgeSeconds, segments[{edge, passageStart, passageEnd}]와 city.raw JSONL을 받는다.
반환은 productionReady=false와 구간별 판정·원본 사건 ID·버전·SHA다. 이는 **검토 CLI 계약**이며
현재 v12 `/v1/bus/predictions:batch`에 이 JSON을 보내면 안 된다.

ML 적용 전에는 과거 시점에 이용 가능했던 날씨·돌발·소통 이력을 같은 전처리로 결합하고
날짜순 7:3 검증 및 재학습을 거쳐야 한다. 새로 다운로드한 현재 마스터가 과거에도 유효했다고
가정하지 않는다. 도로 사고를 모델 지연초로 바로 더하지 않는다.

## 남은 검증

실제 후보를 운영 artifact로 승격하려면 AccInfo 링크 namespace 대조, 버스 노드→구간의
진행방향 표본 검토, 자료 기준일/실제 유효기간, 변경·해제 이력, 수집 최신성 정책을 확인한다.
현재 candidate 파일은 REVIEW_REQUIRED이며 runtime artifact와 형식도 분리되어 있다.
Redis/segment.status로 자동 발행하거나 다른 노선을 자동 선택하는 연결은 아직 하지 않았다.
검증 완료 후 Backend는 해당 구간을 지나는 일정의 예상 통과시각과 겹치는 후보만 재평가한다.

## 검증과 JSON 예시

Collector 전체: 72 passed, 1 skipped(기존 선택적 라이브 검사).
도로 영향: 12 passed. 실제 자료 연결 감사: 4 passed.
검토 CLI 실행과 Spark 변경 파일의 Python 3.8 문법을 확인했다.
JSON 예시는 `backend/tools/road-impact/examples/request.json`, `output.json`이며
실제 사고가 아닌 synthetic fixture임을 requestId에 명시했다.

```powershell
# 고정 원천 재현. 공식 사이트 파일이 바뀌면 SHA 불일치로 멈춘다.
python backend/tools/road-impact/download.py --destination data/road-impact-replay
# 검토 CLI는 sparkjobs 디렉터리에서 모듈로 실행한다.
cd sparkjobs
python -m batch.preview_road_impact --bus-crosswalk BUS.gz --bus-sha BUS_SHA --road-crosswalk REVIEWED.json --road-sha ROAD_SHA --request REQUEST.json --events EVENTS.jsonl --output OUTPUT.json
```
