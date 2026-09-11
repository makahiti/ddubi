# 데이터 보관·용량·백업 정책

> 뚜비 파이프라인이 만드는 데이터를 어디에 얼마나 두는지, 언제 지우는지, 용량이 차면 무엇을 먼저 하는지, 무엇이 백업되고 무엇이 안 되는지를 한 곳에 적은 문서입니다. 인프라 담당이 관리하고, 값이 바뀌면 이 문서를 같은 MR 에서 고칩니다. (2026-09-09 작성, 스프린트 "HDFS·아카이버 장기보관 환경 구성")

## 1. 한 장 요약

| 데이터 | 어디에 | 얼마나 두나 | 누가 지우나 | 설정 위치 |
|---|---|---|---|---|
| 수집 원본(raw 이벤트) | **Kafka** 토픽 `city.raw`, `transport.raw` | **21일** 또는 파티션당 용량 상한(city.raw 1GiB, transport.raw **4GiB**, 2026-09-09 상향) 중 먼저 오는 것 | Kafka 가 자동 | `docs/contracts/topics.yaml` → `infra/scripts/create-topics.sh` |
| 수집 원본 아카이브 | **HDFS** `/ddubi/archive/<토픽>/date=/hour=/*.parquet` | **영구** (지우지 않음) | 없음 | `ARCHIVE_URI`, `ARCHIVE_TOPICS`, `ARCHIVE_FLUSH_MINUTES=60`, `ARCHIVE_MAX_BYTES=128MB` |
| 구간 판정 이력 | Kafka `segment.status` | 24시간 (계약 현재값) | Kafka 자동 | topics.yaml — **아카이브 여부는 5절 참고** |
| Spark 체크포인트 | HDFS `/ddubi/checkpoints/<잡>` | 잡이 사는 동안. 잡을 새로 시작하려면 폴더를 지운다 | 인프라 | `SPARK_CHECKPOINT_DIR` |
| 기준선 파일(weather) | HDFS `/ddubi/baseline/weather` | 마지막 배치 결과 1개(덮어씀) | 배치가 덮어씀 | `BASELINE_OUTPUT_URI` |
| 최신값 | **Redis** (`weather:latest:*`, `transit:*`) | 키마다 TTL(수 분~수 시간). 메모리 384MB, 넘치면 TTL 있는 키부터 퇴출 | Redis 자동 | `docs/contracts/redis-keys.yaml` |
| 기준선 서빙 | **MySQL** `baseline_serving` | 활성 버전 + 직전 버전 보호, 활성화 안 된 버전은 48시간 뒤 정리 | backend(activate 때) | `docs/contracts/internal-api.yaml` |
| 회원·일정·추천 등 서비스 데이터 | MySQL | 영구 | 없음 | backend |
| 지표 | Prometheus | **15일** | 자동 | compose `--storage.tsdb.retention.time=15d` |
| 컨테이너 로그 | 각 서버 도커 | 파일 10MB × 3개 (약 30MB/컨테이너) | 도커 자동 | compose `x-logging` |
| MySQL 백업 | 서버2 `~/backups/mysql/` | **7일** | 백업 스크립트 | `infra/scripts/backup-db.sh` (7절) |

## 2. 원칙 — 왜 이렇게 나눴나

1. **원본은 HDFS 에만 영구로 둡니다.** 수집한 그대로의 JSON 을 Parquet 로 묶어 두 서버에 복제(복제 계수 2)해 둡니다. 나머지(Redis 최신값, 기준선, MySQL 기준선 서빙)는 전부 이 원본에서 **다시 계산할 수 있는 결과물**입니다. 결과물이 틀리거나 사라지면 원본으로 재처리합니다 ([runbooks/reprocess-from-archive.md](runbooks/reprocess-from-archive.md)).
2. **Kafka 는 대기열이지 저장소가 아닙니다.** 21일은 "아카이버나 Spark 가 최대 3주 멈춰도 따라잡을 수 있게" 잡은 여유이고, 지나면 지워집니다. Kafka 볼륨을 잃어도 아카이브가 있으면 데이터는 남습니다.
3. **재생성 가능한 것은 짧게, 그렇지 않은 것은 백업합니다.** Redis 는 TTL 로 스스로 비워지고 다음 수집에 채워집니다. MySQL 의 회원·일정은 어디서도 다시 만들 수 없어 백업이 필수입니다.
4. **삭제는 정책으로만 합니다.** 사람이 손으로 지우는 경우는 실험 토픽·시험 데이터 정리뿐이고, 그때도 이 문서의 절차를 따릅니다.

## 3. 용량 — 지금 얼마나 쓰고, 언제 차나

**2026-09-09 실측 (weather 수집기만 가동)**

| 항목 | 값 |
|---|---|
| HDFS 아카이브 | 4.2MB (9/1~9/9), 하루 약 0.5MB. 시간당 파일 1개(약 7~14KB) |
| HDFS 전체 용량 | 663GB(두 서버 디스크 합), 사용 20MB |
| 서버 디스크(/) | 서버1 309GB 중 16GB 사용(6%), 서버2 비슷 |
| Kafka 데이터 | 61MB |

**실시간 수집기가 켜지면** 유입이 크게 늘어납니다. 추정식은 `건/사이클 × 사이클/일 × 건당 크기`입니다. 예를 들어 지하철 열차 위치를 30초마다 300건(건당 약 400B) 받으면 하루 약 350MB 가 Kafka 에 들어오고, Parquet(snappy)로는 대략 1/5~1/10 인 35~70MB/일이 HDFS 에 쌓입니다. 이 추정은 **켜진 뒤 일주일 실측으로 반드시 갱신**합니다. 확인 명령:
```
# Kafka 토픽 크기
docker exec kafka du -sh /var/lib/kafka/data/transport.raw-*
# HDFS 아카이브 하루치
docker exec hdfs-namenode hdfs dfs -du -s -h /ddubi/archive/transport.raw/date=$(date +%F)
```

**dt 팀 수집 계획(2026-09-09) 기준 추정.** 호출당 레코드 수는 API 응답 형태로 어림한 값이며, 켜진 뒤 실측으로 갱신합니다.

| 수집기 | 하루 호출 | 하루 레코드(추정) | Kafka 용량(추정) |
|---|---|---|---|
| 지하철 도착(전체 역, 6분) | 480 | 약 30만 | 약 150MB |
| 실시간 도시데이터(121장소, 6시간) | 484 | 484 (건당 수십 KB) | 30~50MB |
| 버스 도착·위치(노선 100개, 8시간) | 600 | 약 2만 | 10MB 미만 |
| 지하철 위치(16호선, 1시간) | 384 | 약 2만 | 10MB 미만 |
| 지하철 알림·교통 돌발·TOPIS 공지(15분) | 288 | 수천 | 소량 |
| 날씨(14격자, 10분) | 2,016 | 2,016 | 1MB 미만 |
| **합계** | 약 4,250 | **약 35만** | **약 200MB/일** |

**Kafka 의 두 상한 중 어느 것이 먼저 걸리나.** 토픽은 파티션 3개입니다. `transport.raw` 에 하루 200MB 가 들어오면 21일치가 약 4.2GB 인데 파티션당 1GiB(토픽 3GiB)로는 약 15일 만에 용량 상한이 먼저 걸립니다. 그래서 2026-09-09 에 transport.raw 를 **파티션당 4GiB(토픽 12GiB)** 로 올렸습니다. `city.raw` 는 날씨·도시데이터·예보를 합쳐도 21일치 약 1GB 라 1GiB 를 유지합니다. 아카이버가 정상이면 원본은 HDFS 에 있어 상한이 걸려도 유실은 없지만, 재처리로 Kafka 에서 다시 읽을 수 있는 기간이 줄어드는 것을 막기 위한 조치입니다.

**디스크 수명(대략)**: 하루 100MB 가 HDFS 에 쌓여도(복제 2 → 두 서버에 각 100MB) 서버 디스크 293GB 여유는 8년치입니다. 프로젝트 기간 안에는 용량보다 **Kafka 1GiB 상한**과 **Redis 384MB** 가 먼저 문제가 됩니다.

## 4. 용량 감시 — 무엇을 보고 언제 움직이나

Grafana `DDUBI 파이프라인` 대시보드와 알림(Mattermost)으로 봅니다.

| 볼 것 | 어디 | 정상 | 조치 시작 |
|---|---|---|---|
| 서버 디스크 사용률 | 서버 자원 행 "디스크 사용률" / 알림 "디스크 80% 초과" | 10% 미만 | 70% 에서 5절 순서로 정리 |
| HDFS 용량 사용률 | 데이터 행 "HDFS 용량 사용률" | 1% 미만 | 70% |
| 토픽별 누적 메시지 | 데이터 행 "토픽별 누적 메시지" | 계단형 우상향 | 평평해지면 수집 중단(용량 문제 아님) |
| 아카이버 랙 | 데이터 행 "아카이버 컨슈머 랙" / 알림 "아카이버 저장 정지" | 토픽마다 한 시간 주기로 떨어지는 톱니(높이 = 시간당 유입량, 토픽별 저장 시각이 달라 합계는 0 으로 안 내려옴) | 어느 토픽이든 2시간 동안 커밋 위치가 전진하지 않으면 저장 실패 → HDFS 절차서 |
| Redis 메모리 사용률 | 서비스 행 stat / 알림 "Redis 메모리 90%" | 수 % | 70% 에서 TTL 없는 키 점검(redis-keys.yaml) |
| Redis 퇴출 키 | 서비스 행 "퇴출·만료" | 퇴출 0 | 0 이 아니면 즉시 원인 키 확인 |

**용량이 찰 때 조치 순서** (위에서부터, 데이터 손실이 없는 것부터)
1. 실험·시험 데이터 정리: `city.replay` 토픽 비우기, HDFS `/ddubi/archive` 밖의 실험 경로, Redis `weather:replay:*`.
2. 도커 정리: `docker system prune`(멈춘 컨테이너·안 쓰는 이미지). 재빌드된 옛 이미지가 수 GB 를 차지하기 쉽습니다.
3. Kafka 보존 단축: `retentionHours` 를 21일에서 줄입니다. 아카이브가 정상이면 원본은 HDFS 에 있어 손실이 없습니다. topics.yaml 을 고치고 `create-topics.sh` 를 다시 돌리면 기존 토픽의 보존 설정만 갱신됩니다.
4. 아카이브 압축률 개선(snappy → zstd) 또는 오래된 월을 별도 저장소로 옮기기. 프로젝트 기간에는 필요하지 않을 것으로 봅니다.
5. 디스크 증설. 마지막 수단입니다.

## 5. 새 토픽을 추가할 때 (예: segment.status)

토픽 추가는 "만들기"만이 아니라 아카이브·감시까지 이어져야 원본 보존 원칙이 지켜집니다.

1. `docs/contracts/topics.yaml` 에 토픽을 적습니다(파티션, 보존, 스키마, 발행자·소비자, `archive: true/false`). **이 파일이 진실**이고 서버 토픽은 여기서 만들어집니다.
2. 서버1에서 `sh infra/scripts/create-topics.sh` 를 실행합니다. 없는 토픽은 만들고, 있는 토픽은 보존 설정만 맞춥니다. 자동 생성은 꺼져 있어 코드가 먼저 발행하면 실패합니다.
3. `archive: true` 인 토픽은 `.env` 의 `ARCHIVE_TOPICS` 에 추가하고 아카이버를 재생성합니다(`docker compose ... up -d archiver`). 아카이버는 이 목록만 구독합니다.
4. 확인: 첫 시간 정각 뒤 `hdfs dfs -ls /ddubi/archive/<토픽>/date=오늘` 에 파일이 생기고, Grafana "토픽별 유입"·"누적 메시지" 패널의 정규식에 토픽 이름이 들어 있는지 봅니다(`infra/monitoring/grafana/dashboards/json/ddubi-pipeline.json`).
5. 재처리(replay)도 그 토픽 폴더를 읽을 수 있는지 `--dry-run` 으로 한 번 확인합니다.

**segment.status 에 대한 주의.** 결정사항(2026-09-01)은 "판정 이력은 Kafka `segment.status` → 아카이버 → HDFS 에 남긴다"였는데, 현재 계약 파일은 `archive: false`, 보존 24시간입니다. dt 팀이 판정 잡을 올릴 때 계약을 `archive: true` 로 바꾸고 3번을 함께 해야 이력이 보존됩니다. 그대로 두면 판정은 24시간 뒤 사라집니다.

## 6. 원본이 온전한지 확인하는 법

- **빠진 구간 확인**: 아카이브 파일명 `city.raw-20260909T07-000000001250-<hash>.parquet` 의 숫자는 파티션 0 의 시작 오프셋입니다. 같은 날짜 폴더에서 연속 파일의 이 숫자가 끊기지 않으면 빠진 구간이 없습니다.
- **손상 확인**: `--dry-run` 재생은 모든 파일을 실제로 열어 읽습니다. 파일이 손상됐으면 여기서 드러납니다.
  ```
  $DC1 --profile replay run --rm replay-producer python -m replay --source-topic city.raw --target-topic city.replay --from-date 2026-09-01 --to-date 2026-09-09 --rate 1000 --limit 100000 --dry-run
  ```
- **복제 확인**: `hdfs fsck / | grep -E "Missing|Corrupt|Under-replicated|status"` 가 Missing 0, Corrupt 0, HEALTHY 이어야 합니다. 새 파일의 복제 계수는 `hdfs dfs -stat %r <파일>` 로 2 인지 봅니다(쓰는 클라이언트가 정하므로 새 클라이언트가 생기면 확인).
- **Kafka 와 대조**: 재처리 전 Kafka 오프셋 합계와 아카이브 행 수가 맞는지 필요할 때 비교합니다. 정상 운영에서는 하지 않습니다.

## 7. 백업과 복구 — 무엇이 보호되고 무엇이 아닌가

| 데이터 | 보호 방식 | 잃는 경우 | 복구 |
|---|---|---|---|
| HDFS 아카이브·체크포인트 | 복제 2 (서버1·2 각 1부) | **두 서버 디스크가 동시에** 망가질 때만 | 재처리 불가(원본 소실). 그 위험은 인정하고 감수 |
| Kafka | 없음(서버1 볼륨 1부) | 서버1 볼륨 손실 | 아카이브에서 replay 로 필요한 구간만 재생 |
| Redis | 없음 | 볼륨 손실·flush | 다음 수집 사이클(10분·30초)에 자동 재생성 |
| MySQL 기준선 서빙 | 없음 | 볼륨 손실 | Spark 배치 재실행 → 업로드·activate |
| **MySQL 서비스 데이터(회원·일정 등)** | **일일 논리 백업 7일**(2026-09-09 도입) | 서버2 볼륨 손실 | 아래 절차 |
| Grafana 대시보드·알림 | 깃 파일 프로비저닝 | 볼륨 손실 | 재배포로 복원 |

**MySQL 백업** — `infra/scripts/backup-db.sh` 가 매일 04:10(KST, 크론은 UTC 19:10) 에 `mysqldump --single-transaction`(서비스 중단 없음)으로 `ddubi` 를 gzip 해 서버2 `~/backups/mysql/ddubi-<날짜>.sql.gz` 로 저장하고, 무결성 확인 뒤 7일 지난 파일을 지웁니다. 크론은 서버2 `ubuntu` 사용자에 등록되어 있고 결과는 같은 폴더의 `backup.log` 에 한 줄씩 남습니다. 백업이 서버2 안에만 있으므로 서버2 디스크 전체가 망가지면 백업도 잃습니다. 발표 전 한 번은 다른 곳(개인 PC)으로 내려받아 두는 것을 권합니다.

**MySQL 복구 절차** (서버2)
```
cd ~/S15P21E202/infra
$DC2 --profile serving stop backend                       # 쓰기 중단
gunzip -c ~/backups/mysql/ddubi-YYYYmmdd-HHMM.sql.gz | docker exec -i mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" ddubi'
$DC2 --profile serving start backend
curl -s -o /dev/null -w "%{http_code}\n" https://j15e202.p.ssafy.io/api/health   # 401
```
덤프에 `flyway_schema_history` 가 포함되어 있어 복구 뒤 backend 의 Flyway 검증이 그대로 통과합니다. 백업 시점 이후의 변경은 사라진다는 점을 팀에 알립니다.

## 8. 이 문서를 갱신해야 하는 때

- 토픽·보존·복제 값을 바꿀 때(1절 표와 topics.yaml 을 같은 MR 에서).
- 실시간 수집기를 켠 뒤 일주일 실측이 나왔을 때(3절 표).
- 백업 주기·보존·위치를 바꿀 때(7절).
- 새 저장소(예: 학습 데이터용 예보 스냅샷)가 생길 때 1절 표에 한 줄.
