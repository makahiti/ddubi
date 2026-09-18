# 도로 대응 후보 생성과 운영 승인 경계

## 목적과 범위

보유 전국 도로망과 GTFS 버스 형상으로 방향을 고려한 도로 후보를 재현 가능하게 만든다. 전국 입력을 지원하며 지역 필터를 하드코딩하지 않는다. `--max-shapes` 기본값 0은 모든 형상을 처리한다. 초기 실행의 3개 형상 제한은 검증 실행 범위이며 기능/지역 지원 제한이 아니다.

이 도구는 **후보 생성기**다. `RoadImpactIndex` 운영 대응표가 아니며 승인된 대응표나 통제 판정을 생성했다고 해석하면 안 된다. 운영 활성화와 서버 쓰기는 없다.

## 확인된 원본과 기존 계약

- 다운로드 `2025-TM-KA-MR-LLV2 도로망(2024년 기준_지역).zip`의 링크 DBF에는 `LINK_ID`, `UP_FROM_NO`, `UP_TO_NODE`, `DOWN_FROM_`, `DOWN_TO_NO`, `ONEWAY`, `TRAF_ID_P`, `TRAF_ID_N`가 있다. 원본 속성은 후보에 그대로 보존한다.
- 원본 PRJ는 Korean_1985_Katech_TM128, 미터 단위다. 좌표를 경위도로 간주하지 않는다.
- collector/src/sources/kafka_adapter.py의 사고 제공자 namespace는 `SEOUL_ACCINFO_LINK`다. 원본 `TRAF_ID_P/N`와 동일한 체계라는 근거는 확인하지 못했다. 숫자 길이/값 일치만으로 namespace를 전환하지 않는다.
- 원본 `TRAF_ID_P/N` 중 0은 유효 대응 ID로 사용하지 않는다. P/N 방향 코드와 실제 형상 방향의 의미는 별도 검증 필요하다.
- sparkjobs/common/road_impact.py 운영 입력은 버스 crosswalk SHA·masterVersion, 노선/정류장 occurrence, COMPLETE 경로, 방향 검증, 증거·유효기간, namespaced roadLinks를 모두 요구한다. 본 후보를 해당 입력으로 직접 넣을 수 없다.

## 실행

Python 3.12에 `infra/scripts/road-requirements.txt`의 의존성을 별도 가상환경 또는 전용 디렉터리로 설치한다. 전역/번들 환경은 수정하지 않는다.

```powershell
python infra/scripts/road-mapping-candidates.py `
  --roads "C:\Users\SSAFY\Downloads\2025-TM-KA-MR-LLV2 도로망(2024년 기준_지역).zip" `
  --shapes "C:\Users\SSAFY\Downloads\source-gtfs-seoul-bus\shapes.txt" `
  --output "C:\Users\SSAFY\Documents\ChatGPT\특화 프로젝트\data_staging\recommendation-20260917\road-mapping\run-unique" `
  --max-shapes 3
```

- 특정 형상을 처리하려면 `--shape-id`를 반복한다. 전체 입력은 `--max-shapes 0`이다.
- 기본 샘플 간격 40m, 후보 반경 50m, 모호성 점수차 8m는 **검토 후보 생성 파라미터**이지 승인 기준이 아니다.
- 변환은 `allow_ballpark=False`, 알려진 정확도 5m 이내, 필요한 grid 가용성을 요구한다. 기준 미충족 시 실패한다.
- 도로는 ZIP의 SHP/DBF에서 순차 읽고 필요한 형상 bounding box만 SQLite Rtree에 저장한다. 원본 ZIP 전체 해제나 전국 도로 객체 전체 메모리 적재를 하지 않는다.
- GTFS 좌표·sequence 중복/오류가 있는 형상은 조용히 이어 붙이지 않고 제외 사유를 보고한다.
- 후보는 가까운 폴리라인 조각의 거리와 국소 진행방향으로 정렬한다. multipart의 틈을 가짜 도로로 연결하지 않는다.
- 연속 샘플의 기하학적 끝점 연결, 모호한 평행도로, 반경 내 정렬된 후보 없음 상태를 구분한다. 이는 도로 위상/일방통행/고가·지하 검증을 대체하지 않는다.
- 체크섬 일치 `complete.json` 결과만 재사용한다. 손상/다른 설정/중단 출력은 덮어쓰지 않고 새 디렉터리를 요구한다. 부모 디렉터리 아래 형상별 배치 실행으로 완료 배치를 재사용할 수 있다.

## 산출물

- `roads.sqlite`: 원본 속성과 multipart 형상, 공간 인덱스.
- `candidates.jsonl`: 형상·형상 edge·진행거리별 상위 후보 3개와 방향/거리/연속성 상태. 항상 productionReady=false.
- `report.json`: 변환 연산·정확도, 범위, 상태 수, 원본 ID 계약의 미검증 조건, 형상별 커버리지.
- `complete.json`: 입력/스크립트/설정 fingerprint, 출력 해시, 완료 요약.

## 2026-09-17 실제 검증

결과: `C:\Users\SSAFY\Documents\ChatGPT\특화 프로젝트\data_staging\recommendation-20260917\road-mapping\smoke-3-shapes-v1`

- 전국 도로 링크 629,716건 stream 검사, 선택 형상 영역 14,570건 공간 인덱스.
- 변환: Inverse of Korean 1985 to WGS 84 (1), 라이브러리 보고 정확도 1m. 원본 자체 위치 정확도의 보장은 아니다.
- 형상 3개: SB_SHAPE_100000020, SB_SHAPE_100000024, SB_SHAPE_100000025.
- 샘플 총 1,753개: 단일 우세 후보 1,053, 복수 모호 후보 479, 반경 내 정렬된 후보 없음 221.
- 별도 연속성 진단: 동일 링크 784, 기하학적 끝점 연결 204, 불연속 후보 33, 미평가 732. 샘플 분류 수와 합산하면 안 된다.
- **운영 승인 0건.** 후보 없음 221건을 임의의 최근접 링크로 채우지 않았다.
- candidate SHA256: `71975642dbc4f122eef6e5d3d8bb7ae23dce53dbca861955289cc09e208747b1`.
- report SHA256: `c5997639972e2b10513e09ae962dc21cf0495f124c78c14eb09444e15a88db5b`.
- 테스트 7개 통과: 거리·방향, multipart 틈, 평행도로 모호성, 장거리 샘플링, 방향별 연결, GTFS 오류 거부, 합성 SHP 전체 처리·검토용 출력·체크섬 재사용·손상 거부.

## 후속: 원본 설명자료와 정류장 occurrence 검증

### 실제 원문 근거

같은 ZIP 내부 `★2024년기준_GIS DB 설명자료.hwp`를 로컬에서 직접 읽었다. 문서 SHA256은 `9673f6dc0342470fd2f7d8dfc81c0ca7d0383cbe8d43e995c16f0ed070c3a8df`다. `road-source-manual.py --roads <ZIP>`으로 필요한 문단만 재현 가능하게 추출한다. 아래 index는 HWP BodyText 문단 추출 순서이며 페이지 번호가 아니다.

- LINK 테이블(index 484~491): `UP_ITS_ID / TRAF_ID_P`는 국가표준링크 ID(정방향), `DOWN_ITS_ID / TRAF_ID_N`은 국가표준링크 ID(역방향).
- ② 노드 방향(index 507~509): 링크 그래픽 시작→종료가 UP이고 반대가 DOWN. 실제 도로의 명칭상 상행/하행과 다르다. 일방통행은 주행방향을 UP에 기록하고 DOWN을 입력하지 않는다.
- ⑧ ONEWAY(index 678~680): 1=일방, 0=양방. 물리적으로 분리된 고속도로·터널·교량의 양선 링크도 각각 일방으로 본다.
- ㉒ TRAF_ID_P/N(index 777~779): 국토교통부 국가교통정보센터 국가표준노드링크와 일치하는 링크에 방향별 ID를 입력한 필드다.

따라서 **원본 필드의 방향 의미는 확인했다.** 이는 원본의 현행 유효성이나 SEOUL_ACCINFO_LINK 동일성을 증명하지 않는다. 첫 실행 report의 `ROAD_DIRECTION_CODES_UNVERIFIED`는 당시 상태이며 산출물을 덮어쓰지 않았다.

### 기존 공식 BIS 경로 재사용 조사

`backend/tools/road-impact/audit.py`와 `docs/contracts/road-impact.md`에는 공식 BIS 노드→구간→도로→TOPIS 방향 연결 구현이 이미 있다. 동일 버스 occurrence를 처리하므로 원본이 있으면 이 경로가 GTFS 기하 후보보다 우선이다.

이번 노트북의 Downloads, 제품 저장소와 작업 worktree, 별도 작업벤치에서 `tdata-62-10255.csv`, `tdata-58-10251.csv`, `tdata-63-10256.csv`, `tdata-7-10200.csv` 및 구간링크/경유노드/표준링크 이름을 검색했으나 찾지 못했다. 기존 감사 JSON과 sources manifest만 확인했다. **기존 24,052/17,465 후보 수는 과거 문서 결과이며 이번 현행 재실행 결과가 아니다.** 신규 다운로드나 외부 요청은 하지 않았다.

### 현재 보유 자료로 만든 occurrence 검토 도구

```powershell
python infra/scripts/road-occurrence-review.py `
  --candidates "<첫 실행 디렉터리>\candidates.jsonl" `
  --gtfs "C:\Users\SSAFY\Downloads\source-gtfs-seoul-bus" `
  --bus-crosswalk backend/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz `
  --bus-namespace SEOUL_BUS --gtfs-id-prefix SB_ `
  --output "<새 occurrence 검토 디렉터리>"
```

- 첫 단계 candidate/report 체크섬 및 동일 shapes 원본 SHA를 확인한다.
- GTFS prefix와 provider namespace를 명시적으로 받고, 노선·양끝 정류장·양끝 순번이 crosswalk의 정확히 한 edge와 일치해야 한다. 노선명 추정/다른 namespace 대체/순환노선 순번 생략은 없다.
- 정류장의 `shape_dist_traveled`가 유일한 shape vertex와 일치하고 그 vertex와 정류장 좌표가 30m 이내여야 한다. 임계값은 검토용 연결 조건이며 운영 정확성 인증이 아니다.
- shape 거리의 단조 증가, 중복 순번, 좌표 오류를 거부한다.
- ONEWAY 역주행 후보를 제외하고 원본 UP/DOWN 노드와 해당 방향 국가표준링크 ID를 기록한다. 0은 유효 국가표준링크 ID로 만들지 않는다.
- 기존 top-3 후보만 필터하므로 밖의 허용 방향 후보가 존재할 수 있다. 2단계 전체 처리 전 후보 생성 단계에 방향 조건을 넣고 다시 탐색해야 한다. 현재 결과로 전체 경로 승인을 하지 않는다.

실제 결과: `data_staging/recommendation-20260917/road-mapping/occurrence-review-v2` (작업벤치 아래).

- 동일 형상 3개, crosswalk `CSV-V5-34dc30aa763b-KAKAO-REVIEW-V6`.
- 방향 허용 후보 표본 2,829건, 일방통행 역주행 후보 표본 267건 제외. 서로 다른 도로 수가 아니라 **샘플별 후보 수**다.
- 정확한 정류장 occurrence 검토 연결 113개, 동일 서비스 패턴 중복 182개 통합.
- 운영 승인 0개. 국가표준링크 후보를 runtime AccInfo ID로 복사하지 않는다.
- 추가 6개 포함 도로 테스트 총 13개 통과.

## 남은 작업과 다음 행동

남은 항목: 방향 필터를 후보 생성 단계에 반영, 전체 구간 연속 경로와 고가·지하 검증, provider namespace 연결 근거, 원본 유효기간 검증. 검증되지 않은 상태는 통제 미확인으로 유지한다.

다음 행동은 **2단계 전체 가공 전에 후보 생성에서 일방통행·노드 방향을 적용하고 occurrence별 연속 경로 검증을 추가하는 것**이다. 원본 추가 요청이나 운영 적용은 선행하지 않는다.

## 2단계: 전체 형상 배치 실행

`infra/scripts/road-batch-pipeline.py`가 최초 후보 탐색에서 일방통행 반대 방향을 제외한다. 방향 필터를 통과한 도로 중 top-3를 저장한다. multipart는 임의 연결하지 않고 별도 검토 사유로 제외한다.

정류장 occurrence의 모든 형상 edge에 표본이 있는지 확인하고, 후보 사이의 다음 조건을 동적 계획법으로 검사한다.

- 같은 링크·방향에서는 도로상 진행거리가 5m 허용치를 넘어 뒤로 움직이지 않아야 한다.
- 다른 링크로 전환할 때는 원본 종료/시작 노드가 같고, 실제 방향별 끝점 좌표도 5m 이내여야 한다. 노드 ID만 같은 다른 도엽의 링크를 연결하지 않는다.
- 연결 가능한 후보 경로가 둘 이상이면 모호 상태로 남긴다. 후보 없음, 형상 표본 누락, 방향 단절도 각각 표시한다.
- 단일 연결 후보도 현행 운행·고가/지하·AccInfo namespace·원본 유효성 승인을 뜻하지 않는다. productionReady=false, approvedMappings=0을 유지한다.

### 사전 검증

기존 3형상 공간 인덱스를 해시와 경계로 검증 후 재사용했다. `stage2-road-smoke`의 2개 배치 완료 후 같은 명령을 다시 실행해 산출물을 재가공하지 않고 체크포인트를 읽는 것을 확인했다.

- occurrence 113개: 단일 연결 후보 6, 복수 연결 후보 71, 도로 후보 누락 29, 방향 단절 7.
- 원래 1차 top-3에서 역방향만 사후 제거한 결과와 달리, 방향 허용 후보를 처음부터 다시 탐색한다.
- 기존/추가 도로 테스트 총 19개 모두 통과. 공간 라이브러리를 포함해 건너뛴 테스트 없음.

### 전체 실행과 재시작

2026-09-17 17:52:49 KST에 숨김 독립 Python 프로세스 PID **35632**를 시작했다. 실행 확인 당시 전국 도로 인덱스가 끝났고 **전체 보유 718개 형상, 10개씩 72배치**의 첫 배치를 처리 중이었다. 시작 당시 RSS 약 208MB. 완료 여부는 아래 complete.json으로 재확인해야 한다.

루트: `C:\Users\SSAFY\Documents\ChatGPT\특화 프로젝트\data_staging\recommendation-20260917\stage2-road`

- `job.json`: PID·시작 시각·실제 명령 배열·스크립트·로그·출력 경로.
- `stdout.log`, `stderr.log`: 독립 프로세스 로그. 인터넷이나 대화 연결이 끊겨도 로컬 프로세스가 계속 실행된다. PC 종료/절전까지 계속 실행된다는 뜻은 아니다.
- `identity.json`: 원본·코드 SHA와 설정. 다른 입력·코드로 같은 결과 루트를 재사용하면 실패한다.
- `index-complete.json`: 전체 대상 영역 공간 인덱스 체크포인트. 도로 ZIP을 배치마다 재스캔하지 않는다.
- `batch-NNNNN.json`: 완료 배치의 형상 ID, 결과 경로·모든 파일 SHA, 처리 수.
- `status.json`: 인덱스 준비/처리 중 배치/완료 상태.
- `complete.json`: 전체 배치 성공 뒤에만 생성, exitCode=0 및 각 배치 체크포인트 해시.
- `failed-PID.json`: 예외와 exitCode=1. 이 파일이 있거나 프로세스만 종료된 것으로 완료 판정하지 않는다.

중단 후 같은 worktree의 스크립트와 동일 원본·설정·출력 루트로 재실행한다. 완료한 인덱스/배치는 해시 검증 후 재사용한다. 미완료 attempt 디렉터리는 지우거나 덮어쓰지 않고 새 attempt를 만든다. 로그 파일은 재실행별 새 이름을 주어 기존 로그를 보존한다. SQLite exclusive lock으로 동일 출력의 중복 실행을 차단하며, 프로세스 종료 시 잠금이 해제된다.

실행 형태:

```powershell
python -u infra/scripts/road-batch-pipeline.py `
  --roads "<원본 도로 ZIP>" --gtfs "<서울 버스 GTFS 폴더>" `
  --bus-crosswalk "<버스 crosswalk.gz>" --bus-namespace SEOUL_BUS `
  --gtfs-id-prefix SB_ --batch-size 10 --max-shapes 0 `
  --output "<stage2-road>" --stdout "<stdout 로그>" --stderr "<stderr 로그>"
```

독립 실행 시 `Start-Process -WindowStyle Hidden`과 출력 리다이렉션을 사용하고 `PYTHONPATH`에 작업 전용 `.road-deps`를 연결한다. 해당 인자는 로그 위치 기록용이며 실제 리다이렉션은 실행기가 한다.

다음 행동: **전체 complete.json과 배치 체크섬을 확인하고, 단일 연결 후보·모호·누락·단절의 실제 커버리지를 평가한다.** 운영 승인이나 namespace 연결은 별도 근거 없이 진행하지 않는다.
