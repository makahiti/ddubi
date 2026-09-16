# Architecture

2026-09-15 기준 실제 배치입니다. 자세한 운영 방법은 [infra/README.md](../infra/README.md), 보관·백업 정책은 [data-retention.md](data-retention.md), 장애 절차는 [runbooks/](runbooks/) 에 있습니다.

## 데이터 흐름

```text
공공 API ─→ Python 수집기 9개 ─→ Kafka(city.raw, transport.raw) ─→ Spark 스트리밍 2개 ─→ Redis 최신값 ─→ backend 가 읽음
                                                    └──────────→ Python 아카이버 ─→ Parquet ─→ HDFS(복제 2, 두 서버)

Flutter 앱(Android/Web) ─→ nginx(443) ─→ Spring backend ─→ MySQL / Redis
                                              ├─→ 카카오 경로 API (경로 후보와 평시 소요 시간)
                                              ├─→ ml-serving (버스 구간 소요 시간 예측, 실패하면 카카오 값 사용)
                                              └─→ FCM 직접 발송 (별도 알림 서비스 없음, 2026-09-09 결정)
```

- 수집기는 Kafka 에 발행만 합니다. Spark 는 Kafka 를 읽어 Redis 에 최신값을 쓰고, 아카이버는 같은 Kafka 를 읽어 HDFS 에 한 시간 단위로 보관합니다.
- backend 는 Redis 와 MySQL 만 읽습니다. 파이프라인이 MySQL 에 직접 쓰는 일은 없습니다.
- Flutter Web 정적 파일은 아직 nginx 에 배치되지 않았습니다(안내 페이지만 있음).

## 서버 1 — 데이터 (j15e202a, 172.26.15.35)

Kafka(KRaft 단일 노드), 수집기 9개, Spark master·worker 2, Spark 스트리밍 드라이버 2(weather_latest, realtime_latest), 아카이버,
HDFS 네임노드·데이터노드1, kafka-exporter·node-exporter·hdfs-exporter.
크론: 서버2 생존 감시(5분), 네임노드 메타데이터 백업(04:20).

## 서버 2 — 서비스 (j15e202, 172.26.6.220)

nginx(80/443 만 외부 공개), Spring backend, ml-serving, MySQL, Redis, HDFS 데이터노드2, HDFS 세컨더리 네임노드(네임노드 목차 사본을 매시간 보관),
Prometheus·Grafana·blackbox-exporter·redis/mysqld/node-exporter, certbot, GitLab 러너.
크론: MySQL 백업(04:10, 서버1에도 사본), 아침·오후 브리핑(09:30, 17:00).

## 한 대가 죽으면

- 서버1 사망: 수집·Spark·Kafka 가 멈춥니다. 사용자 API 는 서버2에서 계속 돌지만 실시간 값은 Redis TTL 이 지나면 비워집니다. HDFS 목차는 서버2의 사본으로 예비 네임노드를 켜 이어받습니다([절차서](runbooks/hdfs-namenode-metadata-loss.md)).
- 서버2 사망: 사용자 API 가 멈춥니다. 서버1의 감시 크론이 10분 안에 Mattermost 로 알립니다. MySQL 은 서버1에 있는 전날 백업으로 복구합니다.
- 서버가 2대라 자동 이어받기(HA)는 만들 수 없습니다. 데이터는 잃지 않고, 서비스 복구는 사람이 몇 분 안에 합니다.
