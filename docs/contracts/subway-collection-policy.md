# 지하철 새벽 수집 정책 — 팀 공유

| 상황 | 동작 | 로그 |
|---|---|---|
| KST 03:30 이상, 04:30 미만 | 위치·도착 HTTP 호출 및 예산 예약 생략 | cycle skipped, SKIPPED_NON_SERVICE, resumeAt |
| 그 외 시간에 INFO-200 | 누락/null 목록·건수를 빈 목록/0건으로 정규화 | EMPTY, cycle ok records=0 |
| 일부 호선만 빈 응답 | 빈 호선 0건, 정상 호선 발행 | emptyLines, PARTIAL_EMPTY |
| 인증·HTTP·통신·서버 장애 | 실패 유지 | cycle failed, 안전한 코드·호선 |
| INFO-200인데 데이터/양수 건수가 있음 | 모순된 응답으로 실패 | cycle failed |

위치·도착의 errorMessage/RESULT 결과 형식을 지원한다. RESULT의 운영 발생 여부는 아직 확인하지 못했고 fixture로 검증했다.
INFO-000은 전체 건수와 객체 목록이 유효하고 건수가 일치해야 한다.
지하철 운행 변경 알림(subway_alert)은 계속 수집하며 다른 Collector는 영향을 받지 않는다.
빈 결과로 가짜 열차·도착시간 0을 발행하지 않는다. 기존 Redis는 기존 TTL/validUntil 정책으로 만료된다.

## 보수적인 시간과 특별 운행

보고된 오류 구간 01:20~05:20 전체를 닫지 않고 **03:30~04:30 한 시간**만 기본 생략한다.
[서울시 제야 행사 공식 안내](https://culture.seoul.go.kr/culture/cmmn/file/fileDown.do?atchFileId=3daf832039db4d6580cfc67f3d84c21a&bbsId=&fileSn=2&menuNo=200051)에는
새벽 2시까지 연장 운행한 사례가 있다. 03:30 시작은 그 이후에도 여유를 두는 운영 선택이다.
모든 호선 시간표로 검증된 비운행 구간도, 모든 특별 운행을 보장하는 구간도 아니다.

```text
SUBWAY_SKIP_ENABLED=true
SUBWAY_SKIP_START=03:30
SUBWAY_SKIP_END=04:30
SUBWAY_SKIP_OVERRIDE_DATES=2027-01-01,2027-02-07
```

날짜는 설정 예시이며 실제 특별 운행 일정이 아니다. **실제 새벽의 한국 날짜**를 등록한다.
12/31 밤부터 1/1 새벽까지 이어지는 운행이면 1/1을 넣는다. 등록 날짜에는 평소 주기로 조회한다.
일괄 해제는 `SUBWAY_SKIP_ENABLED=false`를 사용한다. 특별 운행 공지 자동 감지는 구현하지 않았다.
03:30 이후 특별 운행이면 운영자가 해제 날짜를 등록해야 한다.
시간은 HH:MM, 같은 날짜 내 start<end를 검증한다. 잘못된 설정은 오류로 드러낸다.

04:20 위치 수집은 생략하고 **04:30에 재개를 시도**한다. 기존 1시간 주기를 더 기다리지 않는다.
03:20·05:20에는 조회하며 데이터 없음이면 정상 0건으로 처리한다. 기존 예산·중복 호출 제한은 유지한다.
진행 중 요청을 강제로 끊지 않고 새 사이클 시작 시점에 판단한다.

## 적용과 모니터링

main.py→Kafka adapter 장기 실행 Collector에 적용한다. 단독 JSON CLI는 시간 제한 없이
수동 점검할 수 있지만 빈 응답 정규화는 동일하다.
예정 생략은 관측 수집 성공과 구분해 INFO로 기록한다. 서버1 점검도 resumeAt 이전 대기를 정상 표시한다.
재개 시각 이후에도 사이클 기록이 없으면 시간 경과에 따라 경고한다.

서버1 infra 디렉터리에서 코드 배포 후 기존 .env를 유지하며 설정을 추가하고 컨테이너를 재생성한다.

```sh
docker compose -f docker-compose.yml -f docker-compose.server1.yml --profile collect up -d --build --force-recreate collector-subway-location collector-subway-arrival
docker logs collector-subway-location --since 6h 2>&1 | grep -E 'cycle|EMPTY|emptyLines'
```

03:30~04:30 cycle skipped, 이후 cycle ok 또는 실제 사유를 담은 cycle failed를 확인한다.
운영 서버 배포와 새벽 실제 수집 검증은 아직 수행하지 않았다.

## 검증

`python -m unittest discover -s collector/tests -p 'test_subway_*.py' -v`

기존+신규 26개 테스트 통과: 경계시각·UTC→KST·특별 날짜·해제·설정 오류,
adapter 호출 생략·예산 미사용·04:30 재개, 빈 응답, 모순·인증·HTTP 오류 유지, 키 미노출을 검증했다.
Kafka/Redis/HDFS 및 main 장기 실행을 포함한 전체 통합 검증은 별도다.
