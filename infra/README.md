# Infrastructure

## 서버 배치

### 서버 1 — 데이터 처리

- Kafka (KRaft) + Kafka UI
- Collector ×5 (bus, subway, traffic, weather, event)
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
| `collect` | collector-weather | 배포 잡이 항상 켜는 기상 수집기 |
| `collect-realtime` | collector-bus/subway/traffic/event | 구현 완료. API 키와 명시적 수집 범위를 준비한 뒤 별도 활성화 |
| `hdfs` | hdfs-namenode, hdfs-datanode1 | archiver·spark가 HDFS를 쓰므로 함께 필요 |
| `hdfs2` | hdfs-datanode2 | 서버2 전용. 로컬에서 복제 2를 채우려면 hdfs와 함께 켠다 |
| `spark` | spark-master, spark-worker-1/2, spark-streaming-weather, spark-streaming-realtime | |
| `batch` | spark-batch | 일회성 실행기. `run --rm`으로만 쓴다 |
| `archive` | archiver | |
| `monitoring` | prometheus, grafana, node-exporter | 서버2 몫. 스크랩 대상: 양 서버 node + 서버1 kafka-exporter |
| `exporters` | node-exporter, kafka-exporter | 서버1 몫 (kafka-exporter 는 Kafka 옆에 있어야 함) |

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
   비기상 수집기는 `.env`에 필요한 API 키와 범위를 먼저 설정한 뒤 대상 서비스만 켭니다.
   범위가 없는 서비스를 한꺼번에 시작하면 호출량이 커지거나 설정 오류로 재시작할 수 있으므로
   운영 배포 잡에서는 자동 활성화하지 않습니다.
   ```
   docker compose --profile core --profile collect-realtime up -d collector-bus
   docker compose --profile core --profile collect-realtime up -d collector-subway
   docker compose --profile core --profile collect-realtime up -d collector-traffic
   docker compose --profile core --profile collect-realtime up -d collector-event
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

## HDFS 되돌리기 (kill switch)

HDFS가 불안정하면 `.env`에서 두 값만 바꾸고 archiver·spark를 다시 띄우면 로컬 볼륨으로 돌아갑니다.

```
ARCHIVE_URI=file:///opt/ddubi/archive
SPARK_CHECKPOINT_DIR=/opt/ddubi/checkpoints
```

## 알려진 제약

- Spark 워커 메모리(1536m)를 스트리밍 실행기(1g)와 배치 실행기(480m)가 나눠 씁니다. 배치가 자원을 못 받으면 마스터 UI(http://localhost:8080)에서 WAITING으로 보입니다. 워커 메모리를 늘리면 `BATCH_EXECUTOR_MEMORY`도 함께 올립니다.
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
