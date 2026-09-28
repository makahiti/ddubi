# 날씨 원본만 HDFS에 있고 Spark/Silver가 보이지 않을 때

## 확인한 사실과 아직 확인하지 못한 사실

2026-09-16. 사용자 관측: 날씨 Spark 잡은 실행 중이지만 `/ddubi/archive/city.raw`에 수집 원본만 있고 HDFS Weather Silver가 생성되지 않았다.
운영 로그·서버 .env·컨테이너 상태·접속 정보는 이 로컬 작업에서 확보하지 못했다.
따라서 **실제 서버 장애의 직접 원인을 확정하거나 서버 복구를 완료한 상태는 아니다.**

저장소에서 재현한 설정 문제:

1. 수정 전 Compose가 `SILVER_OUTPUT_URI: ${SILVER_OUTPUT_URI:-}`를 사용했다.
   서버 .env에 9/14 추가된 이 변수가 없으면 빈 문자열을 주입한다. `.env.example`은 실제 .env를 자동 갱신하지 않는다.
2. 수정 전 날씨 코드는 이 경우 경고만 남기고 Redis 스트림만 실행했다. **Silver 미생성을 설명하지만 잡 자체가 사라지는 이유는 아니다.**
3. 날씨 스트림은 Kafka city.raw를 읽는다. HDFS archive를 자동으로 읽지 않는다.
   새 checkpoint는 기본 startingOffsets=latest라 기존 Kafka 과거 데이터도 자동으로 모두 처리하지 않는다.
4. Redis와 Silver는 같은 프로세스 안의 독립 쿼리다. Redis 실패는 데이터 누락을 막기 위해 잡을 실패시키지만,
   Silver 설정·기동·실행 실패는 기록한 뒤 Redis를 계속 실행한다.
5. 잡은 spark 프로필에 있어 core/collect/archive만 기동하면 실행되지 않는다.
   사람의 stop/kill, executor 자원 부족, HDFS safe mode/권한/네트워크, Redis/Kafka 문제도 서버 확인이 필요하다.

원본만 있다고 날씨 수집 자체의 성공까지 단정하지 않는다. city.raw에는 도시·돌발 등도 섞인다.
실제 `eventType=weather.observation` 존재 여부는 backfill manifest의 weather 관련 건수로도 확인할 수 있다.

## 팀원이 이해할 데이터 흐름

```text
Collector → Kafka city.raw
            ├─ Archiver → /ddubi/archive/city.raw (원본)
            └─ Weather Spark
                ├─ Redis weather:latest:* (최신값)
                └─ /ddubi/silver/weather (새 관측의 정제 이력)

기존 /ddubi/archive/city.raw
            └─ Weather backfill batch
                └─ /ddubi/silver/weather-backfill/version=<버전>/data (과거 정제 이력)
```

Archiver와 Spark는 별도의 Kafka 소비자다. 원본 저장이 Spark를 대신하거나 성공을 증명하지 않는다.

## 수정 내용

- 운영 Compose의 미설정 URI는 빈 값이고 `WEATHER_REQUIRE_SILVER=false`가 기본이다. 서버 `.env`에 URI가 있을 때만 Silver를 켠다.
- Silver URI 형식·기동·실행 오류는 기록하고 Redis 스트림은 계속 실행한다.
- Redis 스트림의 기동·실행 오류는 잡 전체를 실패시켜 컨테이너 재시작과 checkpoint 재처리를 유도한다.
- 30초 대기 구간마다 각 쿼리의 마지막 batchId·입력 건수·처리시간을 기록한다.
  동일 batchId가 반복되는 로그는 새 처리의 증거가 아니다. 쿼리별 batchId와 commit 파일이 전진하는지 비교한다.
- 과거 아카이브 backfill을 별도 버전 디렉터리로 추가했다. Redis 최신값이나 Kafka 운영 토픽에는 재발행하지 않는다.
- 날씨 수치·단위·결측 처리와 Redis 키/TTL, 기존 체크포인트 경로는 그대로 사용한다.

## 서버1에서 로그 없이도 우선 확인

저장소에서 실행한다. 읽기 전용이고 비밀번호·전체 환경변수를 출력하지 않는다.

```sh
bash infra/scripts/checks/weather.sh
```

| 결과 | 판단 |
|---|---|
| weather 컨테이너 없음 / Exited | spark 프로필 미기동, 중지 또는 배포 실패 확인 |
| URI 없음/빈 값 | 설정에 따라 Silver가 정상적으로 비활성화된 상태. Redis 진행 여부는 별도로 확인 |
| Restarting / oom=true | 메모리 또는 쿼리 실패. 로그와 master 자원 확인 |
| not accepted any resources | worker 코어/메모리 배정 불가 |
| Safe mode ON | 데이터노드 등록·복제 상태 확인. 무조건 forceExit하지 않음 |
| Redis Connection refused | 서버2 Redis/방화벽 확인 |
| Redis commit 전진, Silver commit 없음 | Silver 설정 미반영·기동 실패 또는 최초 배치 대기 |
| 두 commit 전진, 결과 빈 상태 | 해당 시점 날씨 이벤트·정규화 통과 여부와 실제 출력 URI 확인 |

진단 스크립트의 경로는 기본 운영 경로다. 실제 출력·checkpoint URI가 바뀌었다면 선택 환경값 기준으로 확인한다.
컨테이너 Up만으로 Spark 쿼리가 정상이라고 판단하지 않는다.

## 새 관측 스트림 복구

수정 코드를 서버에 배포한 뒤 **서버1 infra 디렉터리**에서 진행한다. 기존 .env를 예제 파일로 덮어쓰지 않는다.
실제 .env에 다음 항목이 비어 있다면 기존 내용은 보존하면서 수정한다.

```text
SILVER_OUTPUT_URI=hdfs://hdfs-namenode:9000/ddubi/silver
WEATHER_REQUIRE_SILVER=false
WEATHER_SILVER_TRIGGER=10 minutes
WEATHER_SILVER_WATERMARK=2 days
```

Master/worker, Kafka, HDFS, Redis가 정상인 것을 확인한다. stopped master/worker는 다음 명령으로 기동한다.
server1 오버레이를 생략하면 다른 서버의 datanode 호스트명을 찾지 못할 수 있다.

```sh
docker compose -f docker-compose.yml -f docker-compose.server1.yml --profile spark up -d spark-master spark-worker-1 spark-worker-2
docker compose -f docker-compose.yml -f docker-compose.server1.yml --profile spark up -d --force-recreate spark-streaming-light
```

단순 restart는 기존 컨테이너의 환경변수를 변경하지 않는다. URI 변경에는 recreate가 필요하다.
checkpoint, Kafka 토픽, HDFS 원본을 삭제하거나 startingOffsets를 무작정 earliest로 바꾸지 않는다.
기존 checkpoint에는 startingOffsets 변경이 적용되지 않는다.

날씨 수집 10분 + Silver 처리 주기 10분을 고려해 다음 사이클을 기다리고 확인한다. 실제 소요시간은 기동 시점·부하에 따라 다르다.

```sh
docker logs spark-streaming-light --since 25m 2>&1 | grep -E 'started topic|silver stream started|query=|batch=|ERROR|Exception'
docker exec hdfs-namenode hdfs dfs -ls -R /ddubi/silver/weather
docker exec hdfs-namenode hdfs dfs -ls /ddubi/checkpoints/weather_latest_silver/commits
```

`silver stream started`와 쿼리 commit 전진, partition 내부 Parquet 생성을 함께 확인한다.
디렉터리만 있거나 Redis batch만 전진하는 상태는 Silver 복구 완료가 아니다.
Silver 오류 중에도 Redis batch가 전진하면 서비스용 최신 날씨는 유지되는 상태다.

## 이미 아카이브에만 있는 날씨 복구

이번 새 배치를 사용한다. 날짜는 **원본 Kafka 도착 날짜의 파티션** 기준이며 시작·끝을 포함한다.
실제 운영 처리 기간을 확인해 예시 날짜를 바꾼다.

```sh
docker compose -f docker-compose.yml -f docker-compose.server1.yml --profile batch run --rm spark-batch \
  sh submit.sh batch/weather_silver_backfill.py \
  --input hdfs://hdfs-namenode:9000/ddubi/archive/city.raw \
  --output hdfs://hdfs-namenode:9000/ddubi/silver/weather-backfill/version=20260916-v1 \
  --start 2026-09-01 --end 2026-09-16
```

실제 유효 날씨가 0건이면 정상 제출로 표시하지 않고 건수를 포함한 오류를 낸다.
동일 source·격자·관측시각·내용의 반복은 제거하고, 같은 시각의 값 수정 revision은 모두 유지한다.
Streaming watermark가 없는 배치이므로 오래된 원본도 정제할 수 있다.
`complete/`와 manifest의 건수 정합성, `data/` Parquet 및 실제 행의 컬럼을 확인한다.
아카이브 = 봉투 파싱 실패 + 비날씨 + 날씨 유효성 제외 + 중복 + 최종 행 수이다.
파싱 실패/제외 건수는 기록하지만 원본을 별도 거절 파일로 복제하지 않는다. 원본은 아카이브에 그대로 있다.

**backfill을 `/ddubi/silver/weather`에 직접 append하지 않는다.** Spark 파일 스트림의 `_spark_metadata`가
배치로 추가한 파일을 추적하지 않아 읽을 때 누락·불일치가 생길 수 있다.
학습용 이력은 운영 stream 경로와 선택한 backfill 버전의 `data/`를 각각 읽어 union하고,
source·격자·관측시각·내용 해시 기준으로 중복 제거한다. Spark는 두 경로를 자동 통합하지 않는다.
이 union 소비 경로는 후속 학습/Feature 결합 단계에서 연결해야 한다. 여러 겹치는 backfill 버전을 무조건 전부 읽지 않는다.

## 검증 범위

로컬에서 설정, Silver 실패 격리, Redis 필수 실패, 진행 로그 관련 테스트를 실행했다. Python 문법과 diff도 확인했다.
Spark 기반 backfill 정규화 테스트는 추가했지만, 현재 로컬에 pyspark 실행 환경이 없어 실행하지 못했다.
SSH 접속 방법을 전달받기 전에는 서버 재생성, HDFS 출력·실제 Kafka→Silver 검증을 수행하지 못한다.
따라서 이 문서는 **코드 수준 수정 및 운영 복구 절차**이며, 운영 복구 완료 보고가 아니다.
