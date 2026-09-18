# FE·인프라 수정 상태와 통합 검증

기준: develop `dd094eb6`. 작업 브랜치: `feature/frontend/S15P21E202-46-fe-infra-correction`.
BE·DT 소스, DB, 서버 설정은 변경하지 않는다. 이 문서는 **전체 기능 완료 선언이 아니다**.

## 구현

- FE-1/2/3/5: 공통 품질 판정, preview/production 미승인/불완전 탐색/ETA 누락/시간 만료/전체 비용 미확인/필수 구간 fallback의 확정 추천 차단. 수락 직전에도 재검사한다. 홈은 요약만 표시한다. missing은 종류별 중복 제거·개수 집계, 신규 내부 코드는 원문 비노출.
- FE-4: 새 일정과 독립 경로 검색의 자동 출발시각은 미확정. 사용자가 시간을 확인하기 전 저장·평가 금지. 기존 일정의 명시적 계획은 유지한다.
- FE-6: 기존 경로를 최종 추천이라고 부르지 않는다. 실시간 고정 문구 제거. BE의 추천 경로 계약이 오면 ID 일치 검증 후 대표 경로를 바꾸어야 한다.
- FE-7: 플랫폼 보안 저장소에 토큰·사용자·디바이스 정보를 저장, 비밀번호는 저장하지 않음. 시작 시 세션 복원·갱신, 로그인 유지 미선택은 메모리만 사용, 재실행 온보딩 반복 방지. 동시 갱신 합치기, 늦은 갱신의 로그아웃 덮어쓰기 방지. **401/403만 세션 삭제; timeout/5xx는 재로그인을 강요하지 않음**. 로그아웃 시 FCM 정리 실패에도 서버 토큰 취소 시도 후 로컬 삭제.
- FE-8: evaluationId 미제공은 최신 여부 확인 불가 안내. optional validUntil 수용. 다른 최신 평가의 만료를 과거 알림의 만료로 오판하지 않는다.
- INFRA-1: 평가용 JSON 경로와 버스/지하철 분해 검증 플래그 전달. 플래그 기본 false 유지. 기존 bus artifact/crosswalk SHA 및 ML URL 연결 유지.
- INFRA-3: 기존 Kafka exporter로 원문/observation/status 최근 증가량·consumer lag, Redis 키·만료 없는 키 기록 규칙 추가. 데이터 없는 지표는 정상 0으로 채우지 않음.
- INFRA-4: post-deploy 통과 문구를 서비스 생존 검사로 한정. 시간표 헤더 점검과 실제 평가 JSON 기반 읽기 전용 판정 도구 추가. 운영 승인은 별도.

## 서버에서 실행하는 읽기 전용 검사

```sh
# 현재 backend의 평가용 JSON 헤더 확인. 전체 추천 검증이 아니므로 exit 2.
python3 infra/scripts/recommendation-readiness.py
# 인증 정보를 제거하고 저장한 실제 평가 응답. 해당 평가 조건만 검사.
python3 infra/scripts/recommendation-readiness.py --evaluation /path/to/evaluation.json
```

헤더 통과는 BE 전체 시간표 검증·적재 성공을 대신하지 않는다. backend는 기동 시 한 번 읽으므로 파일만 바꾸어서는 반영되지 않는다. JSON 경로는 `/opt/ddubi/transit-master` 읽기 전용 마운트 내부를 사용한다. importer CSV를 연결하지 않는다. version·운행 유효기간·trip count를 확인하고 BE 평가 응답에서 실제 적용을 확인한다.

Prometheus 기록 규칙은 기존 디렉터리 마운트에 포함된다. 배포 후 promtool 검사와 reload가 필요하다. `ddubi:recommendation_topic_growth:5m`의 0은 관측 창에서 증가 없음이고, **시계열 부재는 exporter/토픽 증거 없음**이다. Redis 총 키 수나 TTL만으로 특정 일정 segment가 연결됐다고 판단하지 않는다.

## BE·DT 전달 계약이 필요한 잔여 작업

| 항목 | 담당 선행 조건 | FE·인프라 후속 검증 |
| --- | --- | --- |
| 목표 도착만 입력하는 최신 출발 모드 | BE nullable plannedDepartAt·evaluationMode 요청/응답 계약 | 입력 모드 분리, 시간 의미·수락 전 계획 보존 |
| 추천/선택/최단 후보 경로 | BE 역할별 ID 및 경로 객체 | 홈·상세의 대표 경로 일치 |
| 알림 상세 | BE evaluationId·validUntil 저장/응답 및 outbox 동기화 | FCM·알림함·재시도 값 동일성 |
| 시간표·실시간 대응 | DT 유효 JSON·검증 버전·SHA | 마운트·기동 적재·실제 지하철 경로 |
| 버스 수집/observation/status | DT 쿼터 산정·producer·Spark 파이프라인 | 증분 offset·consumer lag·경로 segment Redis TTL/validUntil |
| source age·mapping EXACT·ML/realtime 적용률·평가 p95·재시도 | BE/DT 측정 지표 계약 및 exporter | 수집 연결·대시보드·임계값 알림 |

현재 없는 생산 파이프라인을 Up 상태나 플래그 true로 대신하지 않는다. INFRA-2 전체 상시 실행 및 INFRA-3 전체 지표는 위 선행 구현을 기다린다. 결과 TTL은 BE 증거 유효기간과 DT source age 계약을 따른다. 인프라가 임의로 늘리지 않는다.

## 운영 승인 체크리스트 (미완료)

- 새 커밋 이미지 실행·Flyway 정상·서비스 health.
- 목표 도착 19:30 / 소요 40분 / 여유 10분 최신 출발 모드.
- 계획 유지·앞당김·미완료·데이터 부족·만료 각 상태.
- 버스·지하철·도보 실제 경로별 데이터 원천/age/적용 구간.
- 정식 서명 APK에서 로그인 유지 선택/해제·프로세스 종료·재시작·오프라인·토큰 만료·로그아웃.
- 실제 FCM 생성·조회·재시도·evaluationId/validUntil 일치.

이 브랜치 작업만으로 서버 배포·물리 기기 검증 또는 BE/DT 통합 완료를 주장하지 않는다.
