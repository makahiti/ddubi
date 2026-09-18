# 버스 2단계 기준값 연결

## 입력과 의미
- 실제 후보 72,820건과 주간·월간 `daily.sqlite` 전체를 처리한다. 원본 ZIP을 다시 집계하지 않는다.
- 실제 이력과 제공자 시간대 평균은 별도 결과로 유지한다. 시간대 평균을 차량별 p80/p95나 도착확률로 변환하지 않는다.
- 제공자 namespace는 보유 서울 자료에 맞는 `SEOUL_BUS`. 다른 지역을 폐기한 것이 아니라 해당 입력의 제공자 범위다.
- 실제 유효 daily 키의 기간은 주간 2026-08-17~23, 월간 2026-06-01~07-03이다. ZIP 파일명상의 마지막 날짜와 다를 수 있다.

## 실행 설계
- `infra/scripts/bus-build-reference.py`, Python 표준 라이브러리만 필요.
- SQLite 입력은 읽기 전용 ATTACH, SQL 조인·집계. JSON 수백만 행을 메모리에 읽지 않는다.
- 날짜 범위가 겹치지 않으면 파일별 독립 체크포인트. 겹치면 일자·노선·정류장·순서·시간 키로 중복 제거 후 집계한다.
- 중복 키의 값이 다르거나 원본 conflict가 있으면 그 키 전체를 제외한다.
- 동일 canonical 구간이 노선에서 반복될 수 있어 원본 순서도 집계 키에 남긴다.
- 파트 결과를 다시 복사하지 않고 `hourly-query.json`의 읽기 전용 ATTACH와 합산 SQL로 전체 기준값을 조회한다. 합산 평균은 totalSeconds 합 / observedDates 합이다.
- `observedDates`는 시간대 집계가 있는 날짜 수다. 개별 버스 운행 표본 수와 다르다.
- actual-reference에는 실제 후보의 평균·중앙값·운행 표본 수·관측 날짜 수를 별도로 제공한다.
- 실제 표본 충분 기준 기본값 20개/3일은 명시적 검토 정책이며 운영 승인 기준이 아니다. CLI로 변경 가능하다.

## 원본 보존·재시작·상태
- 입력·코드·정책 해시별 출력 폴더. 완료 파트는 체크섬을 확인하고 재사용.
- 중단된 `.partial.sqlite`는 보존하고 다음 PID의 별도 파트로 재처리한다. 원본이나 완료 파트를 덮어쓰지 않는다.
- 독립 프로세스 실행이므로 인터넷 연결과 무관하게 로컬 처리를 계속한다. 노트북 절전·종료나 프로세스 종료에는 중단될 수 있다.
- 출력 루트의 `job-<PID>.json`에 PID·시작시각·명령·스크립트·로그·출력·상태를 기록한다.
- 정상/예외 종료는 job의 COMPLETE/FAILED 및 exitCode로 남긴다. 강제종료는 RUNNING이 남을 수 있어 실제 PID 생존과 함께 확인한다.
- `complete.json`이 생성되고 체크섬이 일치해야 전체 완료다. 진행 로그만으로 완료 판정하지 않는다.

## 실제 실행 식별
- PID 17644, 2026-09-17 17:51:56 KST 시작.
- 루트: `C:\Users\SSAFY\Documents\ChatGPT\특화 프로젝트\data_staging\recommendation-20260917\stage2-bus`
- 실행 폴더: `2cd67ac5e4fb69e36ba4`.
- 로그 `stdout.log`, `stderr.log`, 상태 `job-17644.json`.
- 전체 명령과 절대 입력 경로는 job에 기록됨. 같은 인자로 재실행하면 완료 파트를 재사용한다.

## 산출물과 안전 경계
- `hourly-part-*.sqlite` 및 파트 complete: 매핑된 시간대 기준 합계·날짜수, 미매핑 노선별 키 수.
- `hourly-query.json`: 파트 합산 조회 SQL·자료 종류·정책 설명.
- `actual-reference.jsonl`: 실제 관측 후보의 별도 통계.
- `master-additions-candidate.json`: 원본으로 확인된 누락 4개 노선 조립 후보.
- 신규 노선의 방향·승하차 허용은 근거가 없어 null이며 canonical ID를 만들지 않는다. 운영 crosswalk에 자동 연결하지 않는다.
- 기존 마스터와 일치한 경우도 유효일·원천 가용시각·날씨 검증 전 productionReady=false다.
- 운영 DB 쓰기, 업로드 API 호출, 모델 활성화, 서버 배포 없음.

## 검증
- `python -m unittest discover -s infra/scripts/tests -p "test_bus*.py"`
- 16개 테스트 통과. 날짜 겹침 경계, 겹친 키 중복 제거·값 충돌·원본 conflict, 미매핑 집계, 실제 이력 중복, 요일/KST·표본 부족·운영 false 포함.
- 다음 행동: 전체 complete 결과의 매핑 범위와 부족 사유를 통합 담당에게 전달하고 BE 조회 계약에 맞는 적재 승인 조건을 확인한다.
