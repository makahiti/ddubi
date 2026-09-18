# 전국 지하철 로컬 자료 매핑 — 2026-09-17

## 범위와 현재 상태

- 추가 다운로드 없이 기존 202503 GTFS와 `metro-20260910-b192e5734f46814a` 수도권 마스터를 대조했다.
- 전국 도시철도 36개 노선명, 91개 방향·운행 경로, 12,489개 운행을 모두 처리했다. 지역 제외 없음.
- 지역별 경로 수: 수도권 67, 부산·울산·경남 12, 대구·경북 8, 광주 2, 대전 2. 이는 행정구역별 역 수가 아니라 노선명 기준 서비스 권역 분류다.
- 운영 서버·DB·기존 마스터를 변경하지 않았다. 모든 산출물은 `productionReady=false`인 검토 후보다.

## 실제 결과

| 구분 | 결과 |
| --- | ---: |
| 원천 운행 정차 패턴(경로·서비스·역 순서 구분) | 568 |
| 노선·역명·좌표가 유일하게 맞는 역 매핑 후보 | 1,311 |
| 기존 노선에는 있으나 이름 대응이 없는 항목 | 376 |
| 이름만 대응하거나 좌표가 충돌하는 항목 | 15 |
| 현재 마스터에 노선 자체가 없는 항목 | 744 |
| 경로·서비스·출발역·도착역별 참고 구간 | 2,531 |
| 양 끝 역 매핑과 방향별 마스터 인접 패턴이 함께 맞는 구간 후보 | 1,202 |
| 유효한 예정 이동시간 표본 | 314,965 |
| 시각/소요시간 조건 위반 인접 호출 | 1 |

역 매핑 수는 **경로×원천 정류장 항목 수**이며 고유 역 수가 아니다. 구간 수도 같은 물리 구간이 서비스별로 포함된다. 표본은 예정 시간표 표본이지 실제 도착 관측이 아니다.

기존 사전 검사 307,760건과 차이가 있는 이유: 이번 도구는 각 운행을 `stop_sequence`로 정렬하고, 값이 10→20처럼 건너뛰어도 연속된 정차 호출로 처리한다. 총 정차 327,455행에서 운행 첫 호출 12,489개를 제외한 314,966쌍 중 시간 조건 위반 1건을 제외했다. 중복 순서가 있는 운행은 전체 격리한다.

현재 마스터에 없는 17개 노선명: 광주1호선, 김포도시철도, 대경선, 대구1·2·3호선, 대전1호선, 동해선, 부산1·2·3·4호선, 부산김해경전철, 서해선, 신림선, 우이신설경전철, 의정부경전철. 이 노선의 원천 ID·좌표·운행별 순서를 후보에 보존했다. 새 canonical ID나 실시간 열차 ID는 만들지 않았다.

## 사용법

도구: `infra/scripts/subway-map-local-gtfs.py` (Python 표준 라이브러리만 사용).

```powershell
& 'C:\Users\SSAFY\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' infra/scripts/subway-map-local-gtfs.py `
  --gtfs 'C:\Users\SSAFY\Downloads\대중교통GTFS(2025년 기준)\202503_GTFS_DataSet' `
  --master 'C:\Users\SSAFY\Documents\S15P21E202\backend\transit-master\subway\metropolitan-subway-master.json.gz' `
  --coordinates 'C:\Users\SSAFY\Documents\S15P21E202\backend\transit-master\subway\metropolitan-subway-coordinates.json' `
  --output 'C:\Users\SSAFY\Documents\ChatGPT\특화 프로젝트\data_staging\recommendation-20260917\subway-mapping'
```

입력·도구 체크섬, 좌표 임계값, 산출물 체크섬이 동일한 완료 디렉터리는 재사용한다. 불완전·변경된 디렉터리는 덮어쓰지 않고 중단한다. 다른 출력 경로로 다시 실행해야 한다.

## 산출물 계약

- `station-mapping-candidates.jsonl`: 원천 경로/역, 권역, 후보 마스터 역 ID, 이름·거리 근거, 미매핑 사유.
- `reference-edge-candidates.jsonl`: 서비스별 예정 평균 소요시간·예정 표본 수, 후보 양 끝 역, 방향별 masterPatternEvidence. `canonicalSegmentId=null` 유지.
- `master-pattern-candidates.jsonl`: 전국 모든 원천 운행 패턴. 기존 노선 존재 여부, 정차 순서·좌표·원천 ID·달력 원문 포함. `directionLabel`은 원본이며 `direction`은 원본에 없는 경우 null.
- `summary.json`: 집계와 제한 사항.
- `complete.json`: 입력·코드·출력 SHA-256과 완료 집계. 이 파일이 있어야 처리 완료다.

기존 `subway-timetable.schema.json`은 게시시각·실제 유효기간·검증된 master/provider ID가 필수다. 이번 자료에는 현재 유효성 근거가 없으므로 해당 운영 계약으로 위장 변환하지 않는다. 캘린더 종료일 2030은 현재 시간표 검증을 의미하지 않는다.

## 매핑 안전 기준과 검증

- 서울1~9호선 이름 접두어만 제거하고 용인경전철→에버라인만 명시 대응. 부산1호선과 서울1호선을 합치지 않는다.
- 괄호 속 부역명·공백 정규화 후 이름 일치와 기본 300m 이내 좌표의 유일 후보를 요구한다. 임계값은 CLI로 명시 가능하며 완료 manifest에 기록한다. 이는 승인 기준이 아니라 검토 후보 기준이다.
- 같은 이름·거리 후보가 둘 이상이면 모호 상태. 좌표 없음·범위 밖·NaN은 매핑 확정 금지.
- 방향별 인접 순서는 원래 master servicePatternId와 함께 기록한다. 반대 방향만 존재하면 구간 후보로 승인하지 않는다.
- 테스트 5개: 지역 구분, 이름/좌표/다중 후보, 비정상 좌표, 자정 이후 시각, 실제 실행 형태의 전국 후보·방향 반전·완료 재사용·변조 탐지.

## 다음 행동

통합 담당이 후보 구간의 제공자 ID·방향·급행/분기 패턴 및 원천 최신성 제약을 검토한 뒤, 참고 소요시간 적용과 신규 마스터 버전 등록 경로를 연결한다. 미검증 자료를 정시 도착·막차 보장에 사용하지 않는다.
