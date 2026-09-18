# 장애 복구·재처리·롤백 절차 검증 기록 (2026-09-07 시작)

절차서: [docs/runbooks/](../runbooks/README.md). 각 시나리오는 절차서를 따라 실제 서버에서 장애를 내고 복구한 결과다.
통과 기준은 "절차서대로 했을 때 복구되고, 데이터 유실이 없고, 절차서와 다르게 해야 한 부분이 없다"이다.

## 요약

| # | 시나리오 | 결과 | 복구 시간 | 유실 | 절차서 수정 |
|---|---|---|---|---|---|
| 1-a | Spark 스트리밍 잡 — `docker kill` | **부분 통과** (자동 복구 안 됨, 수동 재시작으로 복구) | 수동 후 37초 | 없음 | kill/stop 은 재시작 정책 대상이 아님을 명시 |
| 1-b | Spark 스트리밍 잡 — 컨테이너 안 python3 드라이버 kill -9 | **통과** (재시작 정책 자동 복구) | 컨테이너 1초, 잡 재시작 17초 | 없음 | 시뮬레이션 방법 확정 |
| 2 | backend 프로세스 종료(SIGTERM) / 재생성 IP 변경 → nginx 502 | **통과** | 2-a 자동 21초 / 2-b 리로드 후 1초 | 없음 | 시뮬레이션 방법, IP 재사용 설명 추가 |
| 3 | HDFS 데이터노드2 정지 83초 | **부분 통과** — 쓰기·복구 정상, 죽은 직후 WebHDFS 읽기 실패 | 재기동 후 5초 | 없음 | 죽은 직후 10분 약점 기록, 조치 후보 2개 |
| 4 | Kafka 재시작 | **통과** | 16초 | 없음 | 없음 |
| 5 | 재처리 (배치 재실행 A · replay 재생 B) | **통과** (A 55초 2,142행 / B 2,000건 재생 → Redis 갱신) | A 55초 / B 37초 | 없음 | 없음 |
| 6 | 배포 롤백 (표식 배포 → git revert → 재배포) | **통과** | 롤백 2분 59초 (푸시→서버 반영) | 없음 | Draft MR 주의 추가 |

## 1-a. Spark 스트리밍 잡 `docker kill` (11:49~12:01)

- 사전: Kafka city.raw 합계 13,724, 체크포인트 batch 963 합계 13,724, 마지막 수집 사이클 11:46:52
- 11:49:43 `docker kill spark-streaming-weather` → exit 137. **재시작 정책이 동작하지 않음** (이벤트에 kill·die 만 있고 start 없음, RestartCount 0).
  도커는 사람이 API 로 stop/kill 한 컨테이너를 `unless-stopped` 대상에서 제외한다. 검증 방법이 잘못된 것이지 정책 설정 문제가 아니다.
- 11:56:53 수집 사이클 14건 → Kafka 13,738. Spark 는 죽어 있어 미처리.
- 12:00:41 절차서 "수동 재시작" `$DC1 --profile spark up -d spark-streaming-weather` → 16초 뒤 started, 37초 뒤 batch 964 grids 14.
- 검산: 체크포인트 batch 964 합계 13,738 = Kafka 13,738. 유실 0. Redis `weather:latest` TTL 7191 로 갱신.
- 교훈: ① 시뮬레이션은 `docker exec <컨테이너> kill -9 1` 로 해야 재시작 정책이 검증된다 ② 검증 스크립트의 대기 루프는 성공과 시간초과를 구분해 출력해야 한다(이번에 시간초과를 성공으로 오독해 발견이 10분 늦음) ③ 잡이 죽어도 Kafka 가 데이터를 보관하고 체크포인트에서 이어 읽어 유실이 없다는 설계는 확인됨.

## 1-b. Spark 스트리밍 잡 — 프로세스 크래시 (13:28~13:32)

- 시행착오: `docker exec kill -9 1` 과 `pkill -f SparkSubmit` 은 효과 없음. 컨테이너의 PID 1(자바 드라이버)은 같은 네임스페이스 안 신호를 무시한다.
  `pgrep -f weather_latest.py` 는 자바 명령줄까지 매칭해 PID 1 을 골랐다 → `pgrep -x python3` 로 파이썬 드라이버(PID 43)를 지정해야 했다.
- 13:28:45 python3 드라이버 kill -9 → 13:28:46 컨테이너 die(137)·start (재시작 정책, RestartCount 1) → 13:29:01 `started topic=city.raw`.
- 13:31:55 수집기 일회 실행으로 14건 투입(Kafka 13,892 → 13,906) → 13:32:21 batch 976 grids 14. 체크포인트 합계 13,906 = Kafka. 유실 0.
- 판정: **재시작 정책·체크포인트 이어 읽기 모두 정상.** 프로세스가 스스로 죽는 실제 장애에서는 사람이 개입할 필요가 없다. 사람이 stop/kill 한 경우만 수동 재시작이 필요하다(1-a).
- 검증 도구 교훈: `docker logs --since HH:MM:SS` 는 잘못된 형식이라 1줄만 반환한다. 완전한 시각(`2026-09-07T04:28:45Z`)이나 `--since 10m` 을 쓴다. 이 때문에 두 번 "FAIL" 로 오독했다.
- 시간 단축 방법: 수집 사이클(10분)을 기다리지 않고 `docker compose ... run --rm -e COLLECTOR_RUN_ONCE=true collector-weather` 로 실데이터 14건을 즉시 투입할 수 있다. 실제 관측값이라 아카이브·기준선에 해가 없다.

## 2. backend 종료·재생성과 nginx 502 (13:39~13:43)

- 2-a 첫 시도 `kill -9 1` 은 효과 없음(PID 1 은 SIGKILL 무시). `kill -TERM 1` 은 자바가 처리기를 갖고 있어 정상 종료(exit 143) → 도커 재시작 정책이 즉시 재기동(RestartCount 1) → 21초 뒤 healthy, 외부 `/api` 401. Hikari 풀 정상 종료·Spring 재기동 17.7초 로그 확인.
- 2-b 첫 시도: `--force-recreate` 만으로는 새 컨테이너가 같은 IP(172.18.0.9)를 받아 502 가 나지 않음. 도커는 빈 IP 를 재사용한다.
  재시도: backend 정지 → 임시 컨테이너(ip-holder)가 .9 점유 → backend 기동 → 새 IP .11 → `/api` 5회 연속 **502**, nginx 로그 `connect() failed` 5건 → `nginx -s reload` → 1초 뒤 401. ip-holder 제거.
- 판정: **재시작 정책 복구·nginx 리로드 조치 모두 절차서대로 동작.** 502 는 "backend 재생성 + IP 변경"이 겹칠 때만 나며 배포 때 흔히 그렇다(다른 컨테이너도 함께 재생성됨). 근본 조치(nginx resolver 또는 배포 잡 끝 리로드)는 모니터링 확장과 함께 진행.

## 3. HDFS 데이터노드2 정지 (13:51~13:53)

- 13:51:34 서버2 `docker stop hdfs-datanode2`. 45초 뒤에도 네임노드는 Live 2 로 표시, dn2 의 Last contact 만 13:51:33 에 멈춤. 네임노드의 사망 판정은 기본 10분 30초 뒤다.
- **읽기 실패**: replay `--dry-run`(WebHDFS)이 파일 목록 75개를 받은 뒤 첫 파일 읽기에서 `hdfs-datanode2:9864 Connection refused` 로 즉시 죽음. 네임노드가 dn2 를 살아 있다고 믿어 그쪽으로 리다이렉트했고, 클라이언트는 다른 복제본으로 재시도하지 않는다.
- **쓰기 정상**: 수집기 일회 실행 14건 → Spark batch 979 처리, 체크포인트를 HDFS 에 씀. Spark 의 hdfs:// 클라이언트는 죽은 노드를 파이프라인에서 제외한다.
- 13:52:55 dn2 재기동 → 5초 만에 Live 2. fsck 는 그 사이 쓰인 블록 4개를 Under-replicated 로 표시하는데 dfsadmin 의 대기열은 0 → 네임노드가 복제를 계획하지 않는 상태. 14:00 시점에도 4 유지 → 14:05 재확인 시 0 으로 회복(약 10분 소요). 네임노드가 재기동한 노드의 블록 보고를 받고 복제를 채우는 데 시간이 걸린 것으로 판단.
- 판정: 데이터는 안전하고 쓰기는 이어지지만, **아카이브 읽기는 데이터노드가 죽은 뒤 최대 10분 30초 동안 실패할 수 있다.** 조치 후보: 네임노드 사망 판정 간격 단축(hdfs-site.xml `dfs.namenode.heartbeat.recheck-interval` 30000), WebHDFS 읽기 재시도.

## 4. Kafka 재시작 (13:53~13:54)

- 13:53:49 `docker restart kafka` → 16초 뒤 healthy. city.raw 오프셋 13,948 보존.
- 수집기 일회 실행 14건 `sent=14 failed=0 pending=0` → Kafka 13,962. Spark 14초 뒤 batch 980. 아카이버는 재연결 중 FAIL 로그 3줄 뒤 정상 소비(랙 28→42). 실시간 잡 오류 0. kafka-exporter 재시작 없음. 상시 수집기 재시작 없음(연결 실패 로그만).
- 판정: **절차서대로 동작. 사람이 할 일은 healthy 확인뿐.**

## 5. 재처리 (13:55~13:56)

- 실험 잡 `spark-streaming-replay` 를 운영 잡 2개가 도는 채로 기동 → 19초 뒤 started (자원 재배분 덕에 운영 잡을 멈추지 않아도 됨. 코어는 남은 2개를 받음).
- 9/6 하루치 2,000건을 300건/초로 city.replay 에 재생(10초) → 27초 뒤 batch 18 grids 14 → 서버2 Redis `weather:replay:*` 14키가 9/6 23:00 관측값으로 갱신, TTL 7196. 운영 키 `weather:latest:*` 무영향. 실험 잡 정지.
- 판정: **아카이브 → Kafka → Spark → Redis 재처리 경로 정상.** 기준선 배치 재실행(A)은 오전에 55초·2,142행으로 확인.

## 6. 배포 롤백 (14:25~14:36)

- 무해한 변경: `infra/nginx/html/index.html` 의 `<head>` 아래 HTML 주석 `<!-- rollback-drill 2026-09-07 -->`. 화면에 보이지 않고, 정적 파일이 디렉터리 마운트라 컨테이너 재시작 없이 반영된다.
- 배포: 14:25:49 푸시 → 14:26:49 develop 머지 → 14:29:29 서버2 git pull → 14:29:33 배포 잡 완료. 서빙 페이지에 표식 확인. **3분 44초.**
- 롤백: 14:33:16 `git revert` 커밋 푸시 → 14:35:29 머지 → 14:36:12 서버2 git pull → 14:36:15 완료. 서빙 페이지에서 표식 사라짐. nginx·backend 재생성 없음, `/api` 401 유지. **2분 59초.**
- 걸림: revert MR 이 Draft 상태로 만들어져 자동 머지가 "Merge request must not be draft" 로 막혔다. "Mark as ready" 로 해소. 왜 Draft 가 됐는지(GitLab 프로젝트 설정 또는 제목 규칙)는 확인 항목.
- 판정: **절차서의 "정식 롤백"(revert → MR → 배포)이 그대로 동작한다.** 소요 시간의 대부분은 사람이 MR 을 머지하고 버튼을 누르는 시간이고, 실제 서버 반영은 pull 후 수 초다. 컨테이너 재생성이나 DB 마이그레이션이 얽힌 롤백은 이번 범위 밖(절차서에 판단 기준만 있음).

## 총평

- 여섯 시나리오 모두 절차서대로 복구됐고 데이터 유실은 없었다. 자동 복구(재시작 정책·체크포인트·Kafka 보관)는 설계대로 동작한다.
- 남은 약점은 하나다: **데이터노드가 죽은 직후 최대 10분 30초 동안 WebHDFS 읽기가 실패**한다. 네임노드 사망 판정 간격 단축 또는 읽기 재시도가 조치 후보.
- 절차서 수정 사항: 장애 시뮬레이션 방법(stop/kill 은 재시작 정책 대상 아님, PID 1 은 신호 무시, Spark 는 python3 드라이버·backend 는 SIGTERM), `docker logs --since` 형식, 수집기 일회 실행으로 검증 시간 단축, nginx 502 조건, Draft MR.
- 다음 연결: 모니터링 확장(스프린트 2번)에 이 검증에서 "무엇을 봐야 상태를 알 수 있었나"를 반영 — backend `/api` 상태, Spark 배치 시각, HDFS Live 노드 수·Under-replicated, Kafka health.
