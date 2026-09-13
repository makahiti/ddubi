# 데이터 담당 후속 보강 및 검증 — 2026-09-13

수집기 코드는 기존 구현을 유지하고 운영 대상·주기를 변경하지 않았다. 작업은 원본 로컬 checkout에서
진행했다. 아래의 로컬 통과와 운영 데이터 확보 상태를 구분한다.

| 항목 | 이번 결과 | 남은 실제 적용 조건 |
| --- | --- | --- |
| 매핑 캐시 FK | 검증 파일 버전/STAGING 등록과 캐시 저장을 단일 트랜잭션으로 수정. 동시 저장, 원본 충돌 거절, EXACT 재조회 통과 | 배포 후 실제 매핑 오류 지표 관찰 |
| Spark 대응표 | v6 전체 인덱스 캐시와 실제 Kafka/Redis 32배치 검증. 2 GiB 힙에서 OOM/재시작 없음 | 운영 Spark 접속 및 실제 유입 부하·lag 관찰, 인프라의 임시 OFF 해제 |
| 버스↔도로 | 공식 파일 재다운로드 및 SHA/방향 연결 감사 완료 | AccInfo ID 동일성, 원천 적용 기간과 검토 근거 확보 |
| 학습 전처리 | 실제 시작/완료시각과 소요초 일치, 출처/중복 검증, 분할별 품질 보고 추가 | 실제 정답·시점별 예보/사건 이력 확보 |
| 환경 ML 재학습 | GPU 0 접근과 학습 자료 준비 상태 확인. 학습 전 검증 경계 보강 | 검증 자료 부족으로 새 학습 실행 안 함 |
| 전체 데이터 경계 | fixture Kafka→Parquet→Spark→Redis/Kafka, 기준선→Spring API→MySQL 실제 실행 | 운영 원본으로 동일 경계와 추천 재평가의 연쇄 검증 |

## 로컬 실행 결과

- Backend 전체 package: 355개 중 352통과/3조건부 건너뜀, 실패 0. 이후 캡처 재생 1개와 변경된 캐시
  테스트 3개를 별도 실행하여 4개 모두 통과했다. 캡처 재생은 전체 실행에서 건너뛴 테스트 하나다.
- 저장 응답 20개 → 288후보 → 248지하철 구간 중 111개 시간표 계산. 등록·평가·HTTP 재조회 통과.
  실시간 카카오/열차 연동 성공을 의미하지 않고 미검증 대기 추정은 적용하지 않았다.
- Spark 전체 77개 통과(Python 3.8/Spark 3.5.3). fakeredis 2.28.1의 Lua에는 cjson이 없어
  도로 Lua 테스트 3개가 실패했으며, cjson을 포함한 2.38.0에서 통과해 요구 하한을 2.38로 정했다.
- Collector/도로 감사 82개 통과, 로컬 TCP 소켓을 요구하는 선택 프로토콜 테스트 1개 건너뜀. 실제 Kafka 연결은 위 통합 도구로 별도 검증했다.
- ML 전체 46개 통과. 모델 정확도를 측정하는 실제 재학습 실행과 구별한다.
- Kafka 기준선 fixture 6행은 Parquet에 원문을 보존하고 유효성 검증했다. 과거 40/50/60초 표본의
  P50/P80/P95는 50/60/60, 최신 90/100/110초 표본은 observedSeconds=100, delaySeconds=50,
  status=slow로 Redis에 저장하고 Kafka에 발행했다.
- 위 기준선은 **Spring 내부 API**로 업로드·활성화하고 MySQL에서 같은 값/표본수 3을 읽어 확인했다.
  Spark에서 서비스 DB에 직접 쓰지 않았다. 도구 버전 문자열도 서버의 20자 제한에 맞췄다.
- 반복 배치 상세는 [대응표 검증](bus-crosswalk-cache-validation.md)을 따른다.

증거는 ignored `.local-tools/hardening-backend-all.log`, `hardening-captured-tests.log`, `hardening-captured-final.log`,
`hardening-spark-tests-v4.log`, `hardening-ml-tests-final.log`,
`realtime-hardening-20260913/report.json`, `realtime-hardening-20260913-v2/report.json`,
`segment-hardening-20260913-v3/report.json`, `road-source-audit-20260913/mapping-audit.json`에 있다.
원본 경로 응답/인증정보는 Git에 추가하지 않는다.

## 도로 후보와 운영 대응표 구분

공식 [BIS 노선 경유 노드](https://t-data.seoul.go.kr/dataprovide/trafficdataviewfile.do?data_id=58)를
포함한 기존 다운로드 도구의 원천 파일을 다시 받아 동일 SHA를 확인했다. 다운로드 성공 날짜를
과거 구간의 적용 시작일로 사용하지 않는다.

서울 구간 40,441개 중 BIS 방향 연결 후보는 24,052개(59.47%)다. 이 후보 중 TOPIS 경로 전체 연결
17,465개, 부분 연결 6,587개다. 나머지 실패는 도로 링크 누락/모호 10,995개, 방향 연결 단절 4,190개,
반복 정류장 순번 미확정 1,204개다. **운영 승인 매핑은 0개**이며 후보 파일을 운영 승인 파일로
이름만 바꾸지 않았다. 사고 피드의 SEOUL_ACCINFO_LINK와 동일한 ID 체계 및 적용 기간을 증명해야 한다.

## ML 서버에서 확인한 자료와 중단 조건

열린 JupyterLab에서 GPU 0과 기존 data/normalized·raw·extracted 및 v12 source_usage.json을 확인했다.
기존 목표는 HOURLY_MEAN_BUS_SEGMENT다. 날씨는 당시 이용 가능 시각 미검증으로 excluded,
사건은 reference_only, 실제 대기/통과 정답은 미확보로 기록되어 있었다. OA-21285 폴더의 안내 파일은
과거 관측 이력을 대신하지 못한다. 찾은 범위에서 새 시점별 사건·예보 이력과 검증 실측 정답을 확보하지 못했다.

서버 `j-moo/experiments/runs/20260913-readiness/readiness.json`에
`DATA_REQUIREMENTS_NOT_MET`, `trainingStarted=false`, `productionReady=false`를 기록했다.
기존 모델을 덮어쓰지 않았으며 GPU 1을 사용하지 않았다. 실제 학습을 재개하려면 다음 자료가 필요하다.

1. 발표시각·수집시각을 보존한 과거 날씨 관측/예보 및 사고·통제·집회 이력.
2. 버전·방향·유효기간과 근거가 검증된 구간↔격자/도로 매핑.
3. 선택한 목표에 맞는 정답. ACTUAL_TRAVERSAL은 실제 진입/이탈 기록과 출처가 필요하다.
4. 운영 Spark 접속 정보와 원본 아카이브 위치. ML 서버 접속만으로 운영 Spark의 OFF/lag/OOM을 검증하지 않는다.

자료 확보 후 새 label-v2 준비 → 누수/결측/시간 분할 확인 → GPU 0 학습 → 미래기간 비교 →
운영 적용 검증을 이어간다. 현재 상태를 담당 범위 전체 완료 또는 운영 ML 검증 완료로 보고하지 않는다.
