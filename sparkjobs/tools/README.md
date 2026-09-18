# 격리 데이터 흐름 검증 도구

운영 데이터 검증이나 운영 설정 변경 도구가 아니다. 명시적 fixture를 실제 Kafka/Spark/Redis에
통과시키고 assertion 실패를 종료 코드로 반환한다. 출력은 새 디렉터리만 허용한다.
반복 실행 시 출력 디렉터리 이름도 유일하게 지정한다. 원본 파일과 실행 결과는 커밋하지 않는다.

## 실행 조건

- Spark 3.5.3/Python 3.8 + Kafka connector `org.apache.spark:spark-sql-kafka-0-10_2.12:3.5.3`.
- Python 의존성: `sparkjobs/requirements.txt`, `collector/requirements.txt`의 실행 의존성.
- 검증 전용 Docker network에서 Kafka `ddubi-hardening-kafka:9092`(토픽 자동 생성 활성화),
  Redis `ddubi-hardening-redis:6379`, 구간 상태용 Redis `ddubi-flow-redis:6379`에 접근 가능해야 한다.
- 위 DNS는 코드에 고정되어 있다. 별도 격리 컨테이너를 마련하며 운영 호스트를 이 이름으로 연결하지 않는다.
- 저장소를 `/work`에 마운트하고 v6 `backend/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz`를
  준비한다. 제품의 수집 대상/주기·인프라 설정을 수정하지 않는다.
- 이번 검증은 heap 2 GiB, 컨테이너 4 GiB, local[2]에서 실행했다.

## 반복 실시간 처리

검증용 컨테이너 안에서 다음을 실행한다.

```sh
/opt/spark/bin/spark-submit --master 'local[2]' --driver-memory 2g \
  --conf spark.ui.enabled=false --conf spark.jars.ivy=/tmp/ivy \
  --packages org.apache.spark:spark-sql-kafka-0-10_2.12:3.5.3 \
  /work/sparkjobs/tools/verify_realtime_pipeline.py \
  --output /work/.local-tools/realtime-audit-unique --batches 30
```

Publisher/Collector 대응표 → Kafka → 공통 봉투 → 운영 process_batch → Redis를 실행한다.
정상 배치 뒤 늦은 데이터와 버전 불일치를 추가하며 정상 query 종료/동일 checkpoint 재개를 확인한다.
`report.json`의 힙은 배치 종료 시 사용량이며 peak/RSS가 아니다. OOM과 재시작 횟수는 해당
컨테이너의 `State.OOMKilled`, `State.ExitCode`, `RestartCount`로 별도 확인한다.

## 구간 관측·기준선·상태 처리

같은 spark-submit 옵션에서 스크립트를 `verify_segment_pipeline.py`로 바꾸고 별도 `--output`을 지정한다.
`--batches` 옵션은 제거한다. Kafka 관측 fixture 6개를 원문 포함 Parquet에 보존한 뒤 운영의 validate,
aggregate, evaluate, select_history, Redis/Kafka 쓰기를 실행한다. 재실행의 과거 fixture는 eventId로 격리한다.

결과 `baselineRows`는 **로컬 검증용 Spring**의 `BaselineUploadClient.upload_and_activate`에 보내야
MySQL 경계까지 확인할 수 있다. 이 스크립트는 Backend 인증정보를 내장하지 않고 자동으로 활성화하지 않는다.
이번 실행에서는 localhost 전용 Spring/격리 MySQL을 사용해 업로드·활성화 응답과 DB 읽기를 확인했다.
`baselineVersion`은 서버 계약의 20자 한도 안에 있으며 실제 서비스 DB에 fixture를 보내지 않는다.

## 자동 테스트

- `cd sparkjobs && python -m pytest tests -q`
- `python -m pytest collector/tests backend/tools/road-impact/test_audit.py -q`
- `python -m pytest ml-serving/tests -q`

Spark 테스트용 fakeredis는 cjson이 포함된 2.38 이상이 필요하다.
이번 수치와 운영 미검증 조건은 [후속 보강 결과](../../docs/contracts/data-role-hardening-20260913.md)를 따른다.
