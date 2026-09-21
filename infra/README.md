# Infrastructure

## 서버 배치

### 서버 1 — 데이터 처리

- Kafka (KRaft) + Kafka UI
- Collector ×9 (collector-<소스명>): 날씨, 버스 도착·위치, 지하철 도착·위치·알림, 도시데이터, 교통 돌발, TOPIS 공지
- Spark Master / Worker ×2 / 스트리밍 드라이버 / 배치 실행기
- Archiver (Kafka → Parquet → HDFS)
- HDFS NameNode / DataNode1
- node-exporter

### 서버 2 — 사용자 서비스

- Nginx + Flutter Web
- Spring Boot Backend / Notification
- MySQL
- Redis
- HDFS DataNode2
- Prometheus / Grafana / kafka-exporter / node-exporter

## 원칙

- 외부 공개 포트는 서버 2의 80/443만 사용합니다. MySQL(3306)·Redis(6379)·Kafka(9092)는 인터넷에 열지 않고 두 서버 보안그룹 사이에서만 허용합니다.
- 주소·비밀번호·API 키는 환경변수로 주입합니다. 실제 `.env`는 Git에 커밋하지 않습니다 (`.env.example`만 갱신).
- 이미지 태그는 버전을 고정합니다 (`latest` 금지). 모든 컨테이너에 `mem_limit`과 로그 로테이션(10m×3)이 걸려 있습니다.
- 데이터 계약은 `docs/contracts/`가 기준입니다. Kafka 토픽은 `scripts/create-topics.sh`가 `topics.yaml`을 읽어 만들고, 브로커의 토픽 자동 생성은 꺼져 있습니다.

## Compose 프로필

| 프로필 | 서비스 | 비고 |
|---|---|---|
| `core` | kafka, kafka-ui | 가장 먼저 띄운다 |
| `data` | mysql, redis | |
| `collect` | collector-weather, -bus-arrival, -bus-location, -subway-arrival, -subway-location, -subway-alert, -city-realtime, -traffic-incident, -topis-notice | 수집기 9개 전부. 배포 잡이 항상 켠다. 키가 비어 있는 수집기는 재시작을 반복하므로 서버 `.env` 의 "수집기 전용" 절을 먼저 채운다. 상세는 [수집 가이드](../docs/data-collection-guide.md) |
| `hdfs` | hdfs-namenode, hdfs-datanode1 | archiver·spark가 HDFS를 쓰므로 함께 필요 |
| `hdfs2` | hdfs-datanode2, hdfs-secondarynamenode | 서버2 전용. 세컨더리 네임노드는 매시간 네임노드 메타데이터 사본을 서버2 볼륨에 만든다(2026-09-14). 로컬에서 복제 2를 채우려면 hdfs와 함께 켠다 |
| `hdfs-standby` | hdfs-namenode-standby | 서버2 전용, **평소 켜지 않는다**. 서버1 네임노드 메타데이터를 잃었을 때 `scripts/failover-namenode.sh` 가 켠다. [절차서](../docs/runbooks/hdfs-namenode-metadata-loss.md) |
| `spark` | spark-master, spark-worker-1/2, spark-streaming-weather, spark-streaming-realtime | |
| `recommendation-data` | spark-streaming-segment-observations, spark-streaming-segment-status | 검증된 실제 구간 통과 생산자와 registry/baseline이 준비된 뒤에만 켠다. 상태 잡은 두 경로가 비어 있으면 기동을 거부한다. |
| `topis-ai` | spark-streaming-topis | GMS·Kakao 키를 설정한 뒤 명시적으로 켜는 TOPIS 공지 전처리 잡 |
| `road-silver` | spark-streaming-traffic-incident | 승인된 OA-13310 동일 ID 링크 마스터가 있을 때 DIRECT/NEARBY를 활성화하는 교통 돌발 Silver |
| `batch` | spark-batch | 일회성 실행기. `run --rm`으로만 쓴다 |
| `archive` | archiver | |
| `monitoring` | prometheus, grafana, node-exporter, redis-exporter·mysqld-exporter, blackbox-exporter | 서버2 몫. 스크랩 대상: 양 서버 node + 서버1 kafka-exporter. blackbox-exporter 는 ml-serving 의 /health/ready 를 대신 찔러 probe_success 지표로 바꾼다(2026-09-15) |
| `exporters` | node-exporter, kafka-exporter, hdfs-exporter | 서버1 몫 (kafka-exporter 는 Kafka 옆에 있어야 함) |

## 새 PC에서 처음 시작하기 (로컬 개발)

전제: Docker Desktop, Git. 파이썬은 컨테이너 안에서만 쓰므로 로컬 설치가 없어도 됩니다.

1. 저장소를 받고 infra 폴더로 이동합니다.
   ```
   git clone <repo> && cd S15P21E202/infra
   ```
2. `.env`를 만들고 값을 채웁니다.
   ```
   cp .env.example .env
   ```
   - `KMA_API_KEY`: 기상청 API 허브(apihub.kma.go.kr) 인증키. 허브에서 "예특보 → 동네예보 조회 → 초단기실황조회 API" 활용신청이 필요합니다. URL이 아니라 **키 문자열만** 넣습니다.
   - `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`, `REDIS_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`: 로컬에서는 기본값 그대로 써도 됩니다.
   - 로컬에 MySQL이 이미 설치되어 3306을 쓰고 있으면 `MYSQL_HOST_PORT=3307`처럼 바꿉니다.
3. 정의가 올바른지 확인합니다.
   ```
   docker compose --profile core --profile data --profile hdfs --profile spark --profile archive --profile collect --profile batch config -q
   ```
4. 순서대로 띄웁니다. 처음에는 이미지 다운로드와 빌드로 몇 분 걸립니다.
   ```
   docker compose --profile core up -d
   sh scripts/create-topics.sh                 # topics.yaml 기준으로 토픽 4개 생성
   docker compose --profile data up -d
   docker compose --profile hdfs up -d         # 네임노드는 첫 실행 때 자동 포맷
   docker compose --profile spark up -d        # 첫 실행 때 Kafka 커넥터를 Maven에서 받아 spark-ivy 볼륨에 캐시
   docker compose --profile archive up -d
   ```
5. 수집기를 한 번 돌려 전 구간을 확인합니다.
   ```
   docker compose --profile core --profile collect run --rm -e COLLECTOR_RUN_ONCE=true collector-weather
   ```
   - 로그에 `cycle ok records=14`가 나오면 Kafka까지 들어간 것입니다 (서울 25개 구가 공유하는 격자 14개 단위로 호출).
   - Kafka UI: http://localhost:8085 → Topics → city.raw
   - 기상 Spark 드라이버: `docker logs spark-streaming-weather --tail 5` 에서 `batch=N grids=14`
   - 비기상 Spark 드라이버(기본 UI 4042, executor 480m): `docker logs spark-streaming-realtime --tail 5` 에서 `batch=N ... redisKeys=N`
   - Redis: `docker compose --profile data exec redis redis-cli -a <REDIS_PASSWORD> KEYS "weather:latest:*"` → 격자 14개
   - HDFS: http://localhost:9870 (Utilities → Browse) 에서 `/ddubi/archive`, `/ddubi/checkpoints`
6. 상시 수집을 켜두려면:
   ```
   docker compose --profile core --profile collect up -d collector-weather   # 10분 주기
   ```
   나머지 수집기 8개는 `.env` 의 "수집기 전용" 절에 해당 API 키를 넣은 뒤 이름을 지정해 켭니다. 키가 비어 있으면 그 수집기만 "키 없음" 으로 재시작을 반복합니다.
   버스 수집기 2개와 `spark-streaming-realtime` 은 저장소의 버스 대응표(`backend/transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz`)를
   읽기 전용으로 마운트해 기동 시 한 번 읽습니다(구간 ID `BUS:노선:정류장A:정류장B` 부여). 파일 지문(SHA-256)은 compose 기본값에 있고
   manifest 와 다르면 기동을 거부하므로, 대응표를 새로 만들면 compose 의 기본값(또는 `.env`)을 manifest 값으로 함께 갱신합니다.
   ```
   docker compose --profile core --profile collect up -d collector-subway-arrival collector-city-realtime   # 원하는 것만
   docker compose --profile core --profile collect up -d                                                   # 9개 전부
   ```

   서버2의 대중교통 정적 마스터는 `infra/transit-master`에 공식 XLSX/CSV를 두고 `.env`의
   `DDUBI_TRANSIT_MASTER_*` 경로와 버전을 확인한 다음 import를 한 번만 활성화합니다.
   적재 성공과 활성 버전을 확인한 뒤 `DDUBI_TRANSIT_MASTER_IMPORT_ENABLED=false`로 되돌립니다.
7. 기준선 배치(일회성):
   ```
   docker compose --profile batch --profile spark run --rm spark-batch sh submit.sh batch/weather_baseline.py --days 28
   ```
8. 끌 때:
   ```
   docker compose --profile core --profile data --profile hdfs --profile spark --profile archive down
   ```
   볼륨은 남아 있어 다음에 켜도 데이터가 유지됩니다. 완전히 지우려면 `down -v`.

## 테스트

각 컴포넌트의 테스트는 해당 이미지 안에서 돌립니다 (로컬 파이썬 불필요). 저장소 루트에서:

```
# collector (17 tests)
docker run --rm -v "$(pwd):/repo:ro" -w /repo/collector ddubi/collector:0.1.0 python -m pytest -q -p no:cacheprovider
# archiver (7 tests)
docker run --rm -v "$(pwd):/repo:ro" -w /repo/archiver ddubi/archiver:0.1.0 python -m pytest -q -p no:cacheprovider
# sparkjobs (8 tests) — 이미지의 pyspark 를 PYTHONPATH 에 올리고 pytest 만 설치
docker run --rm -v "$(pwd)/sparkjobs:/jobs:ro" -w /jobs ddubi/spark:0.1.0 sh -c 'export PYTHONPATH=/opt/spark/python:$(ls /opt/spark/python/lib/py4j-*.zip); pip install -q pytest && python3 -m pytest -q -p no:cacheprovider'
```

Windows Git Bash에서는 `-v` 경로 변환 문제로 앞에 `MSYS_NO_PATHCONV=1`을 붙이고 경로를 `c:/Users/...` 형식으로 씁니다.

## 장애 시 로컬 볼륨 임시 전환

HDFS 는 확정된 저장 계층입니다(2026-09-04). 아래는 HDFS 장애로 쓰기가 오래 막힐 때만 쓰는 **임시 조치**입니다.
`.env`에서 두 값만 바꾸고 archiver·spark를 다시 띄우면 서버 로컬 볼륨에 씁니다.

```
ARCHIVE_URI=file:///opt/ddubi/archive
SPARK_CHECKPOINT_DIR=/opt/ddubi/checkpoints
```

전환 중에 쓰인 아카이브는 HDFS 에 없습니다. HDFS 가 복구되면 값을 되돌린 뒤, 그 기간의 로컬 파일을 HDFS 로 옮겨야 원본이 빠지지 않습니다.

## 알려진 제약

- Spark 워커 메모리(2560m, 컨테이너 상한 3g)를 스트리밍 두 잡(각 768m·코어 1)과 배치 실행기(480m·코어 2)가 나눠 씁니다. 잡이 자원을 못 받으면 마스터 UI(http://localhost:8080)에서 WAITING으로 보이고 드라이버 로그에 "Initial job has not accepted any resources"가 반복됩니다. 잡을 추가하면 executor 메모리 합계가 워커 메모리 안에 드는지 먼저 계산합니다.
- Spark 이미지의 파이썬은 3.8입니다. sparkjobs 코드는 3.8 문법을 지켜야 합니다.
- **설정 파일 마운트와 반영 시점**: 디렉터리로 마운트한 설정(`infra/spark/conf/`, `infra/monitoring/prometheus/`, nginx templates, grafana provisioning)은 배포(git pull) 뒤 컨테이너를 **재시작**하면 새 내용이 보입니다.
  파일 하나를 마운트한 HDFS 설정(`infra/hdfs/*.xml`)은 git 이 파일을 교체하면 컨테이너가 옛 파일을 계속 보므로, 고친 뒤에는 **재생성**이 필요합니다: `docker compose ... up -d --force-recreate hdfs-namenode hdfs-datanode1` (서버2는 `hdfs-datanode2`).
  배포 후에는 바뀐 설정이 컨테이너 안에 실제로 보이는지 `docker exec <컨테이너> cat <경로>` 로 확인합니다.

## 서버 배포·운영 (2026-08-31 이식 완료)

- 서버1(데이터)=j15e202a.p.ssafy.io(172.26.15.35), 서버2(서비스)=j15e202.p.ssafy.io(172.26.6.220)
- 서버에서는 기본 compose 에 서버별 오버레이를 겹쳐 실행합니다 (HDFS 상호 호스트명 해석 + 데이터노드 포트 공개):
  ```
  # 서버1
  docker compose -f docker-compose.yml -f docker-compose.server1.yml --profile <core|collect|spark|batch|archive|hdfs|exporters> up -d
  # 서버2
  docker compose -f docker-compose.yml -f docker-compose.server2.yml --profile <data|hdfs2|monitoring> up -d
  ```
- `.env` 는 깃에 없으므로 scp 로만 배포합니다. `SERVER1_PRIVATE_IP`/`SERVER2_PRIVATE_IP` 가 반드시 설정되어야 하고(오버레이 필수 변수), `REDIS_HOST` 는 서버2 사설 IP 를 가리킵니다.
- **방화벽**: 도커는 ufw 를 우회하므로 `scripts/firewall.sh` 가 iptables DOCKER-USER 체인에 "상대 서버 허용 + 그 외 외부 차단"을 적용합니다.
  ```
  sudo sh scripts/firewall.sh <상대서버_사설IP>        # 서버2에서 nginx 도입 후에는 --web 추가 (80/443 공개)
  sudo netfilter-persistent save                        # 재부팅 유지
  ```
- **관리 화면은 SSH 터널로**: 방화벽 이후 Grafana(3000, 서버2)·kafka-ui(8085, 서버1)·HDFS UI(9870, 서버1)는 외부 차단 상태입니다.
  ```
  ssh -i <pem> -L 3000:localhost:3000 ubuntu@j15e202.p.ssafy.io   # 켜둔 채 브라우저에서 localhost:3000
  ```
- **GitLab 러너는 서버2의 단독 컨테이너**(gitlab-runner, docker executor, privileged)로 돌고 있습니다. compose 소속이 아니며, `docker compose down` 의 영향을 받지 않습니다.

## 모니터링·알림 (2026-09-08 확장)

- **수집**: Prometheus(서버2)가 15초마다 읽습니다. 대상 12개 — prometheus, node-exporter 2대, kafka-exporter, backend(actuator), Spark(master·드라이버 2·executor 2), HDFS(json_exporter 가 네임노드 /jmx 변환), Redis(redis-exporter), MySQL(mysqld-exporter, 전용 최소 권한 계정 `exporter`).
- **대시보드**: Grafana `DDUBI 파이프라인` 한 장. 위에서부터 서비스(서버2) / 데이터 파이프라인(서버1) / 서버 자원 행. 각 패널 설명(ⓘ)에 "정상 모양"과 이상 시 볼 절차서를 적어 두었습니다.
- **알림 규칙**: `monitoring/grafana/alerting/rules.yaml` 11개(backend·MySQL·Redis 다운, Redis 메모리 90%, 수집 중단 30분, 아카이버 랙 252 초과, Spark 잡 부족, HDFS 데이터노드 1대·Missing 블록, 디스크 80%, 수집 대상 DOWN). Grafana → Alerting → Alert rules 에서 상태가 보입니다.
- **알림 채널**: Mattermost 수신 웹훅(`alerting/contact-points.yaml`, Slack 호환 형식). 웹훅 URL 은 서버2 `.env` 의 `GRAFANA_ALERT_WEBHOOK_URL` → grafana 컨테이너 환경변수로 넘어갑니다. 같은 알림은 4시간마다 한 번만 다시 오고, 해결되면 "Resolved" 메시지가 옵니다. 채널을 바꾸려면 웹훅 URL 만 바꿔 grafana 를 재생성합니다.
- **브리핑**: `scripts/daily-brief.py` 가 서버2 크론으로 매일 09:30·17:00(KST) 서버 상황(수집 대상·알림·서비스·데이터·HDFS·자원·컨테이너 재시작·백업)을 같은 Mattermost 채널에 한 메시지로 보냅니다. 판정하지 않고 숫자만 정리하며, 알림 문턱 아래에서 나빠지는 것을 사람이 보게 하는 용도입니다. `--dry-run` 으로 화면 확인.
- **반영 시점**: Prometheus 설정과 Grafana 알림 규칙·수신처는 배포 잡의 post-deploy 가 재적재합니다(알림 파일은 Grafana 가 기동 때만 읽으므로 관리자 API 로 다시 읽게 함, 2026-09-10). Grafana 대시보드 파일은 10초마다 자동으로 다시 읽습니다. Spark 지표 설정(`spark/conf/metrics.properties`)만 Spark 프로세스 재시작이 필요합니다.
- 파일로 프로비저닝한 대시보드·알림은 화면에서 고쳐도 저장되지 않습니다. 저장소 파일을 고쳐 MR 로 올리는 것이 기준입니다.

## HTTPS (Let's Encrypt)

- 인증서는 certbot 컨테이너(serving 프로필)가 **자동 갱신**합니다: 12시간마다 깨어나 만료 30일 전이면
  webroot 방식(80 의 `/.well-known/acme-challenge/`)으로 갱신. nginx 는 인증서 볼륨(certbot-certs)을 읽기 전용으로 공유합니다.
- **최초 발급만 수동 1회** (서버2에서). nginx 가 80 을 점유 중이므로 잠깐 내리고 standalone 으로 발급합니다 (~1분 중단):
  ```
  cd ~/S15P21E202/infra
  docker compose -f docker-compose.yml -f docker-compose.server2.yml stop nginx
  docker run --rm -p 80:80 \
    -v ddubi_certbot-certs:/etc/letsencrypt \
    certbot/certbot:v2.11.0 certonly --standalone \
    -d <도메인> -m <이메일> --agree-tos --no-eff-email
  # 이후 serving 프로필을 다시 올리면 nginx 가 443 으로 기동한다
  ```
- 갱신 리허설: `docker exec certbot certbot renew --dry-run`
- 주의: 인증서가 없는 환경(로컬 등)에서는 443 설정 때문에 nginx 가 뜨지 못합니다.
  로컬 개발은 nginx 없이 backend 만 띄우고 8080 을 직접 호출하세요.
