# 운영 절차서 (runbooks)

장애가 났을 때 무엇을 확인하고 어떤 순서로 조치하는지 적은 문서입니다. 각 절차는 실제로 장애를 내고 따라 해 본 뒤에만 "검증됨"으로 표시합니다.
검증 기록은 [docs/experiments/2026-09-07-recovery-drill.md](../experiments/2026-09-07-recovery-drill.md)에 있습니다.
데이터를 어디에 얼마나 두고 무엇이 백업되는지는 [docs/data-retention.md](../data-retention.md)에 있습니다.

| 절차서 | 상황 | 검증 |
|---|---|---|
| [spark-streaming-crash.md](spark-streaming-crash.md) | Spark 스트리밍 잡이 죽었다 | 검증됨 (9/7) |
| [backend-down-and-nginx-502.md](backend-down-and-nginx-502.md) | backend 가 죽었다 / 배포 뒤 API 가 502 | 검증됨 (9/7) |
| [hdfs-datanode-down.md](hdfs-datanode-down.md) | HDFS 데이터노드 한 대가 죽었다 | 검증됨 (9/7, 읽기 약점 발견) |
| [hdfs-namenode-metadata-loss.md](hdfs-namenode-metadata-loss.md) | 서버1 네임노드 메타데이터를 잃었다(서버1 사망·볼륨 손실) → 서버2 예비 네임노드로 이어받기 | 격리 시험 검증 (9/14), 운영 연습 예정 |
| [server-reboot.md](server-reboot.md) | 커널 업데이트 등으로 서버를 재부팅해야 한다 | 검증됨 (9/18, 두 서버 실측) |
| [tls-cert-renewal.md](tls-cert-renewal.md) | 인증서 만료 알림이 왔다 / 자동 갱신을 확인·손으로 갱신 | 미검증 (9/15 작성, 첫 갱신 11월) |
| [ml-bundle-swap.md](ml-bundle-swap.md) | ml-serving 모델 묶음을 새 버전으로 바꾼다 | 검증됨 (9/15, 첫 교체) |
| [kafka-restart.md](kafka-restart.md) | Kafka 가 죽었거나 재시작해야 한다 | 검증됨 (9/7) |
| [reprocess-from-archive.md](reprocess-from-archive.md) | 데이터를 다시 계산해야 한다 (재처리) | 검증됨 (9/7) |
| [deploy-rollback.md](deploy-rollback.md) | 잘못된 배포를 되돌려야 한다 | 검증됨 (9/7, revert 롤백 2분 59초) |

## 공통 준비

모든 절차는 서버에 SSH 로 접속한 상태에서 시작합니다.

```
ssh -i ~/.ssh/J15E202T.pem ubuntu@j15e202a.p.ssafy.io   # 서버1 (데이터)
ssh -i ~/.ssh/J15E202T.pem ubuntu@j15e202.p.ssafy.io    # 서버2 (서비스)
```

compose 명령은 서버마다 오버레이 파일이 다릅니다. 절차서에서는 아래 약칭을 씁니다. 접속한 뒤 한 번 정의해 두면 됩니다.

```
cd ~/S15P21E202/infra
DC1="docker compose -f docker-compose.yml -f docker-compose.server1.yml"   # 서버1에서
DC2="docker compose -f docker-compose.yml -f docker-compose.server2.yml"   # 서버2에서
```

## 절차서가 공통으로 따르는 원칙

- **먼저 확인, 그다음 조치.** 증상이 같아도 원인이 다르면 조치가 다릅니다. 확인 단계를 건너뛰지 않습니다.
- **데이터는 Kafka 에 남아 있습니다.** Spark 나 아카이버가 잠깐 죽어도 Kafka 가 21일 보관하므로, 복구 뒤 이어서 읽으면 유실이 없습니다. 급하게 데이터를 살리려고 애쓸 필요가 없습니다.
- **컨테이너 재시작은 안전합니다.** 모든 운영 컨테이너는 `restart: unless-stopped` 라 죽으면 스스로 다시 뜹니다. 사람이 할 일은 "왜 죽었나"를 로그에서 찾는 것입니다.
- **서버 상태의 진실은 develop 브랜치입니다.** 서버에서 파일을 직접 고쳐 급한 불을 끈 경우, 반드시 같은 변경을 develop 에 올려야 합니다. 다음 배포가 서버 파일을 덮어씁니다.
- **복구 뒤에는 기록합니다.** 언제, 무엇이, 왜, 어떻게 복구됐는지 검증 기록 문서에 한 줄 남깁니다.

## 정상 상태를 빨리 확인하는 명령

가장 빠른 방법은 배포 잡이 끝에 돌리는 확인 스크립트를 직접 실행하는 것입니다. kafka·네임노드(서버1), nginx·backend·`/api`(서버2)를 확인하고 죽은 컨테이너를 보고합니다.
```
cd ~/S15P21E202/infra && bash scripts/post-deploy.sh server1    # 서버1에서 (서버2에서는 server2)
```

세부 항목을 따로 볼 때:
```
# 서버1
docker ps -a --format "{{.Names}}\t{{.Status}}"                     # 전부 Up 이어야 함 (spark-streaming-replay 는 Exited 가 정상)
docker exec hdfs-namenode hdfs dfsadmin -report | grep -E "Live datanodes|Under replicated"   # Live 2, Under replicated 0
docker logs spark-streaming-light --since 15m 2>&1 | grep "batch="    # 10분 안에 grids=14 배치가 있어야 함
docker logs collector-weather --since 15m 2>&1 | grep "cycle ok"        # sent=14 failed=0 pending=0

# 서버2
docker ps -a --format "{{.Names}}\t{{.Status}}"
curl -s -o /dev/null -w "%{http_code}\n" https://j15e202.p.ssafy.io/api/health   # 401 이면 backend 정상 (502·000 이면 비정상)

# 서버1이 서버2를 지켜본 기록 (5분마다, 정상일 때는 줄이 안 남고 실패·복구만 남는다)
tail -n 5 ~/check-server2.log; cat ~/.check-server2.last          # .last 가 10분 넘게 오래됐으면 감시 크론이 멈춘 것
```

서버2가 통째로 죽으면 서버2에 있는 Grafana 알림도 함께 죽습니다. 그때는 서버1의 `check-server2.sh` 가 10분 안에 Mattermost 로 "서버2 응답 없음" 을 보내는 것이 유일한 알림입니다(2026-09-15 도입).

## 서버에서 자동으로 도는 것(크론): 무엇이 언제 도는가

서버 시계는 UTC 라 crontab 에는 UTC 로 적혀 있고, 아래 표는 한국 시각으로 옮겼습니다. 등록·갱신은 `infra/scripts/install-cron.sh` 가 합니다(`bash install-cron.sh server1` 또는 `server2`). 로그는 각 서버 홈에 남습니다.

| 서버 | 한국 시각 | 무엇 | 로그 | 실패하면 어떻게 보이나 |
|---|---|---|---|---|
| 서버1 | 5분마다 | `check-server2.sh`: 서버2 `/api/health` 감시, 실패·복구만 Mattermost 로 | `~/check-server2.log`, `~/.check-server2.last` | `.last` 가 10분 넘게 오래됨 |
| 서버1 | 04:20 | `backup-namenode.sh`: 네임노드 메타데이터 tar 사본 | `~/backups/hdfs-name/backup.log` | 브리핑 "네임노드 백업" 26시간 넘음 WARN |
| 서버1 | 토 04:40 | `docker-prune.sh`: 태그 없는 이미지·빌드 캐시 정리 | `~/docker-prune.log` | 디스크 알림 |
| 서버2 | 03:30 | `refresh-subway-timetable.sh`: 그날치 지하철 시각표 생성·교체 뒤 **backend 재기동(1~2분 끊김)** | `~/subway-timetable.log` | 로그에 "만들지 않는다"·오류. 이미 그날치가 있으면 아무것도 안 함 |
| 서버2 | 04:10 | `backup-db.sh`: MySQL 덤프 7일 보관 + 서버1 사본 | `~/backups/mysql/backup.log` | 브리핑 운영 행 WARN |
| 서버2 | 09:30 · 17:00 | `daily-brief.py`: 인프라 브리핑 Mattermost 발송 | `~/brief.log` | 브리핑이 안 오거나 🔴 "브리핑 실패" |
| 서버2 | 토 04:40 | `docker-prune.sh` | `~/docker-prune.log` | 디스크 알림 |

03:30 시각표 교체 뒤 backend 가 다시 뜨는 1~2분 동안 서버1 감시가 🔴 "서버2 응답 없음" 을 보낼 수 있습니다. 03:35 안에 🟢 복구가 따라오면 정상입니다.

## 배포 뒤, 또는 전체를 한 번에 볼 때

`infra/scripts/checks/` 의 스크립트 세 개가 서버1·서버2·바깥에서 볼 것을 한 줄씩 PASS/WARN/FAIL 로 찍습니다. 읽기만 하므로 언제 돌려도 됩니다. 설명은 [infra/scripts/checks/README.md](../../infra/scripts/checks/README.md).
```
bash ~/S15P21E202/infra/scripts/checks/server1.sh | column -t -s'|'    # 서버1
bash ~/S15P21E202/infra/scripts/checks/server2.sh | column -t -s'|'    # 서버2
bash infra/scripts/checks/external.sh | column -t -s'|'                # 개발 PC
```
