# 2단계 지하철 참고자료 패키지

## 목적과 BE 계약 경계

전국 568개 원천 운행 패턴을 빠짐없이 묶고, 동일 패턴·서비스별 예정 구간시간과 정류장별 예정 배차간격을 연결한다. BE·DB·운영 마스터 변경은 없다.

대조한 기존 계약은 `docs/contracts/subway-timetable.schema.json` 및 `backend/tools/transit-timing/build_timetable.py`다. BE의 dated timetable은 `publishedAt`, `validFrom`, `validUntil`, 운행별 `serviceDate`와 정적 역·방향 연결을 요구한다. 실시간 trainId는 null이 가능하지만, 현재 원천에는 게시·가용 시각과 현재 운행일 유효성 근거가 없다.

따라서 **본 패키지는 BE dated timetable 입력이 아니다.** `usage=UNDATED_SCHEDULE_REFERENCE`, `productionReady=false`이며 다음 항목을 null로 보존한다.

- `sourcePublishedAt`, `sourceAvailableAt`: 원천 공개·실제 가용 시각 미확인.
- `currentValidFrom`, `currentValidUntil`: 현재 운행 유효기간 미확인.
- `canonicalPatternId`, `canonicalSegmentId`: 운영 승인 전 후보.

`packagedAt`은 로컬 가공 시각일 뿐 과거 모델의 피처 가용시각으로 쓰면 안 된다. source 달력 원문은 패턴에 보존하지만 그 종료일을 현재 유효기간으로 승격하지 않는다. 새 provider·열차 ID를 만들지 않는다.

## 결과 파일

출력: `C:\Users\SSAFY\Documents\ChatGPT\특화 프로젝트\data_staging\recommendation-20260917\stage2-subway`

| 파일 | 용도 |
| --- | --- |
| reference-master-bundle.json | 전국 원천 패턴, 원천 역·좌표·서비스, 검토 상태, 후보 master 방향·역 ID, 가용성 제한 |
| scheduled-segment-reference.jsonl | 패턴·서비스·정차순서별 예정 소요시간 평균/최소/최대 및 예정 표본 수 |
| scheduled-headway-reference.jsonl | 동일 패턴·서비스·승차 정류장의 예정 출발 간격, 서비스일 경과 시간대 |
| quarantine.json | 시각·순서 오류가 있는 원천 운행 전체 격리 |
| summary.json | 수·지역·상태별 커버리지 |
| complete.json | 입력·도구·산출물 체크섬 및 집계 |

## 계산 의미

실제 전체 실행은 2026-09-17 17:50:34~17:51:07 KST, PID 1036, 종료코드 0으로 완료했다. 12,489개 운행 중 12,488개 수용·1개 전체 격리, 전국 568개 패턴 모두 참고시간 있음, 패턴별 참고 구간 23,914개, 예정 배차 시간대 묶음 81,188개, 중복 출발시각 0개. 동일 물리 구간이 패턴별로 반복되므로 23,914개를 고유 구간 수라고 해석하면 안 된다.

- 구간시간은 앞 정차 출발부터 다음 정차 도착까지다. 실제 운행 이력이나 실시간 지연이 아니다.
- 원천 운행 전체를 정렬·검증한 뒤 수용한다. 중간 호출이 잘못되면 해당 운행을 통째로 격리하고 양 끝을 이어 붙이지 않는다.
- 서비스 ID와 전체 정차 패턴을 분리해 평일/주말 또는 다른 분기·급행 자료를 섞지 않는다.
- 배차간격은 같은 패턴에서 고유 출발시각을 정렬한 양의 차이다. 중복 출발시각은 0초 배차로 해석하지 않고 별도 집계한다.
- 시간대는 이전 출발의 운행일 경과 시각 기준이다. 25시는 다음 날 01시를 뜻하지만 임의 날짜를 부여하지 않는다.
- **동일 노선 전체 배차간격이 아니다.** 짧은 회차와 다른 행선지 패턴을 합치지 않아 실제 이용 가능 열차 전체보다 간격이 길 수 있다. 승차 가능한 모든 패턴 통합은 목적지·급행·분기 검증 후 별도 계약으로 수행해야 한다.
- 종착 정류장 출발시각은 승차 배차 계산에서 제외한다.
- 259개 연속 패턴, 46개 건너뛰기/급행 검토, 92개 역 미완결, 65개 단일 master 패턴 미대응, 106개 누락 노선의 검토 상태를 그대로 유지한다.

## 실행·감사

도구: `infra/scripts/subway-build-reference-package.py`.

```powershell
python infra/scripts/subway-build-reference-package.py --gtfs <원본 GTFS> --source <subway-mapping> --review <subway-pattern-review-v2> --output <새 출력 폴더> --job-record <job.json> --stdout-log <stdout.log> --stderr-log <stderr.log>
python -m unittest discover -s tests -p 'test_subway_*.py'
```

입력 두 단계의 출력 체크섬, 동일 원본 GTFS 버전, 전국 패턴 키 완전성을 검사한다. 결과 경로 덮어쓰기를 거부한다. `job.json`은 PID·시작/완료 시각·스크립트·로그·출력·종료코드를 기록하며 원본과 운영 상태를 변경하지 않는다. 프로세스 강제 종료 때 종료코드 null이 남으면 `complete.json`과 프로세스 상태를 함께 확인해야 한다.

테스트 16개 통과: 앞선 매핑/패턴 검사 및 자정 이후 시각, 전체 운행 격리, 중복 출발시각, 서비스/방향 분리, 현행 날짜 조작 방지.

## 다음 행동

BE 담당의 푸시 후 참고자료 전용 reader 계약과 대조한다. dated timetable 환경변수에는 이 패키지를 넣지 않는다. 정적 마스터 후보 등록과 참고 소요시간 적용은 provider 연결·방향 승인 및 출처 표시가 마련된 뒤 통합한다.
