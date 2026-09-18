# 버스 관측 후보 매핑 — 2026-09-17

## 목적과 경계
- 기존 실제 이력 72,820 후보를 마스터의 제공자 ID·정류장 순서와 대조한다.
- 서버·DB·BE·FE·공유 Compose 변경 없음. 원본 및 기존 가공 결과 보존.
- 정확 일치는 구조상 일치일 뿐 운영 승인이나 과거 날짜의 마스터 유효성 인증이 아니다.
- 실제 이력에 없는 지역은 제외한 것이 아니라 이번 입력의 범위 밖이다. 도구는 명시적 namespace별 입력을 지원한다.

## 실행 결과
- 마스터: `CSV-V5-34dc30aa763b-KAKAO-REVIEW-V6`, namespace `SEOUL_BUS`.
- 156개 노선, 72,820건 중 정확 ID+순서 후보 68,500건(약 94.07%).
- 정류장 쌍은 있으나 순서 불일치 2,217건, 경로 구간 없음 476건, 노선 없음 1,627건.
- 노선 없음: `100100389` 384건, `100100551` 642건, `100100587` 469건, `113000004` 132건.
- 마스터 확장 검토 구간 790개. 원본 관측 순서와 빈도만 보존하며 좌표·방향·canonical ID를 만들어내지 않는다.
- 날짜별 개편이나 운행계획 의미 때문에 구조 불일치가 발생할 수 있으므로 단순 오타 교정/순서 무시는 금지한다.

## 결과물
- 루트: `C:\Users\SSAFY\Documents\ChatGPT\특화 프로젝트\data_staging\recommendation-20260917\bus-mapping\86f104150b99583861e6`
- `mapped-candidates.jsonl`: 기존 관측 필드와 매핑 상태·버전·제약. 정확 후보에만 canonicalSegmentId 부여.
- `master-extension-candidates.jsonl`: 누락 노선/구간/순서 불일치를 검토할 재료. 정식 마스터가 아님.
- `route-coverage.json`: 노선별 상태 집계.
- `complete.json`: 입력·코드·출력 SHA256과 집계.
- complete SHA256: `e34a8e85c90a6742975ddadc6e7f0b952fb2852edee17ce780e4b1481a877cf8`.
- 코드 수정 전 예비 결과 `78b154d745e9a5e58518`는 보존했으며 통합 대상은 위 최종 경로다.

## 실행과 검증
```powershell
python infra/scripts/bus-map-candidates.py --input <traversals.jsonl> --crosswalk <metropolitan-bus-v6.crosswalk.json.gz> --master <metropolitan-bus-v6.json.gz> --namespace SEOUL_BUS --output-root <별도출력루트>
python -m unittest discover -s infra/scripts/tests -p test_bus_mapping.py
```
- 표준 라이브러리만 사용. 대용량 JSONL은 한 행씩 처리.
- 명시적 namespace로 타 제공자 ID 충돌 방지.
- 마스터와 crosswalk의 SHA256·버전 일치 검사.
- 입력·코드·namespace 해시별 결과 생성. 완료 결과 재사용 전 출력 해시 확인. 미완료 디렉터리 덮어쓰기 금지.
- 회귀 7개 통과: 정확 일치, 중복, namespace 충돌, 순서 불일치, 누락, 방향 모호, 잘못된 입력, manifest 재사용·변조·master 불일치 경계 포함.

## 남은 조건과 다음 행동
- 모든 결과 productionReady=false. 원천 ID 의미·과거 마스터 유효일·원천 가용시각·날씨 검증 미완료.
- 시간대 집계자료와 실제 운행 자료를 결합하지 않았으며 운영 baseline을 생성했다고 주장하지 않는다.
- 다음 행동: 확장 후보 790구간을 보유 노선별 정류장 원본과 대조해 날짜·순서 변경과 실제 누락을 구분한다.
