# TOPIS 공지 중복 전송 방지

## 쉽게 설명하면

수집기는 이전과 같이 15분마다 최대 1,000건을 가져온다. Kafka에 넣기 전에
"이 공지를 전에 성공적으로 보냈나? 내용이 달라졌나?"를 확인한다.

| 비교 결과 | 처리 |
| --- | --- |
| 처음 보는 공지 | Kafka로 전송 |
| 같은 공지 ID, 변경된 내용 | Kafka로 전송 |
| 같은 공지 ID, 같은 내용 | 전송 생략 |
| 같은 공지가 아직 전송 확인을 기다리는 중 | 현재 프로세스에서 전송 생략; 변경 내용도 다음 수집에서 다시 확인 |

예: 최초 1,000건 전송 후 다음 조회의 내용이 모두 같으면 0건 전송한다.
이후 새 공지 2건, 수정 공지 1건이면 해당 3건만 전송한다.

## 구현 위치와 기준

Collector의 `run_cycle`에서 TOPIS만 `notice_dedup.publish_notices`를 사용한다.
기존 제공자 noticeId를 ID로 사용하고, payload.raw 전체를 키 정렬한 JSON으로 표현해
SHA-256 해시를 비교한다. 공백·HTML 차이도 실제 변경으로 취급한다.
수집시각, 생성 eventId, envelope contentHash 등은 비교에서 제외한다.
원본 payload, 기존 이벤트 봉투, Kafka 키·토픽은 변경하지 않는다.
새로운 HTML 정제, 자연어 처리, Spark 전처리는 추가하지 않는다.

공지 ID가 없으면 내용 해시 기반의 별도 식별자를 사용해 동일 내용의 반복을 억제한다.
이 경우 수정 내용을 같은 공지의 변경으로 연결할 수 없으므로 새 공지로 전송한다.
missingId 로그 건수로 이 경우를 확인한다.

Kafka Producer의 delivery callback이 성공을 확인한 뒤 SQLite에 해시를 기록한다.
큐 등록 성공만으로 전송 완료라고 판단하지 않는다. 전송 실패·큐 등록 실패는 기록하지 않는다.
변경 공지 전송 실패 시 기존 성공 해시는 유지해 다음 수집에서 다시 시도한다.
DB 쓰기 오류는 상위로 전파하며 성공한 것처럼 무시하지 않는다.
프로세스 재시작 전 Kafka ACK 후 DB 기록 전 종료 등에서는 일부 재전송이 가능하다.
정확히 한 번 전송을 보장하는 분산 트랜잭션은 아니며, 유실을 피하는 방향이다.

## 기록 저장과 운영 반영

상태 DB: `TOPIS_NOTICE_DEDUP_PATH`.
로컬 기본값은 collector/data/topis_notice_dedup.sqlite3이고 Compose는
`/app/data/topis_notice_dedup.sqlite3`로 주입한다. 기존 collector-budget 영속 볼륨을 재사용한다.
새 운영 .env 항목을 반드시 추가할 필요는 없다. 하나의 TOPIS collector 인스턴스로 운용한다.
여러 인스턴스 간 동시 중복 억제는 지원하지 않는다. 토픽별로 성공 기록을 구분한다.
기록을 지우면 다음 조회의 공지가 다시 최초 전송된다. DB를 자동 초기화하거나 정리하지 않는다.

코드 반영 후 infra에서 다음 명령으로 TOPIS collector를 빌드·재생성한다.

```sh
docker compose --profile collect up -d --build --force-recreate collector-topis-notice
docker compose logs --since 30m collector-topis-notice
```

로그 `topis dedup fetched=... new=... changed=... unchanged=... pending=... missingId=...`
에서 첫 수집 이후 unchanged 증가와 발행 건수 감소를 확인한다.
new/changed는 큐 등록 대상 건수이며 실제 Kafka 성공은 sent/failed/pending으로 확인한다.
기존 Kafka/HDFS 중복 데이터는 삭제하지 않는다. API 요청 수·조회 범위도 그대로다.

## Redis 동작

Redis에는 새 공지와 변경 공지만 도착한다. 기존 공지 TTL 2700초(45분)는 그대로 유지하므로
변경 없이 계속 게시 중인 공지도 만료될 수 있다. Redis 최신 키를 TOPIS 전체 공지 목록으로
해석하면 안 된다. 전체 공지 목록 보관·행사 종료 판단·NLP는 별도 작업이며 이번 범위에 없다.

## 검증

단위 테스트는 실제 Publisher 코드에 가짜 Kafka Producer를 연결해 ACK 성공/실패를 검증한다.
신규/수정/동일 공지, 재시작, topic 분리, 미확인 전송, ID 누락, payload 보존,
이벤트 생성·큐 등록 실패, 1,000건 재조회 시 추가 전송 0건을 확인한다.

```sh
python -m unittest discover -s collector/tests -p test_notice_dedup.py -v
python -m unittest discover -s collector/tests -p test_topis_notice.py -v
```

실제 TOPIS API·운영 Kafka·Redis·HDFS를 연결한 검증과 운영 배포는 수행하지 않았다.
