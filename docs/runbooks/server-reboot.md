# 서버를 재부팅해야 한다 (커널 업데이트 대기, 이상 동작)

> 상황: `/var/run/reboot-required` 가 있거나(커널·libc 업데이트가 설치됐지만 적용 전), 호스트 자체가 이상하다.
> 검증: **검증됨(2026-09-18, 커널 7.0.0-1011 → 1012·libc6 적용)**. 이 절차 그대로 서버2 → 서버1 순서로 재부팅했다. 실측 시간은 아래와 같다.
>
> | 항목 | 서버2 | 서버1 |
> |---|---|---|
> | 재부팅 명령 → ssh 복귀 | 17초 | 약 30초 |
> | 컨테이너 전부 Up·healthy | 3분 30초 | 1분 10초 |
> | 서비스 재개 | API 끊김 약 2분 | realtime 첫 배치 3분 뒤(Redis 키 2,965개 기록) |
> | 이상 | 없음 | weather 잡 재시작 1회(아래 3절 설명, 정상) |
>
> 두 서버 합쳐 10분이 걸렸고 유실·알림 발동은 없었다.

## 0. 알아 둘 것

- 모든 운영 컨테이너는 `restart: unless-stopped` 라 서버가 켜지면 도커가 알아서 다시 띄운다. 사람이 `compose up` 을 할 필요는 없다.
- 방화벽(iptables DOCKER-USER)은 `netfilter-persistent` 가 저장해 두어 재부팅 뒤에도 남는다. 9/3 에 확인했다.
- 서버2 재부팅 = 사용자 API 가 2~3분 끊긴다. 서버1 재부팅 = 수집·Spark·Kafka 가 3~5분 멈춘다. 수집기는 한 주기를 놓치고, Spark 는 체크포인트에서 이어 읽어 유실이 없다.
- 두 대를 동시에 재부팅하지 않는다. HDFS 복제 2 가 잠깐 한 부만 남는 시간을 겹치지 않게 한다.
- 팀 채널에 "몇 시에 몇 분 끊김" 을 미리 알린다. 특히 누군가 배포 버튼을 누르는 중이면 그 배포 잡이 실패하니, 러너에 잡이 없는지(`docker ps | grep runner-`) 보고 시작한다.
- 이 서버들의 AWS 계정은 SSAFY 것이라 우리에게 콘솔이 없다. ssh 가 돌아오지 않으면 우리가 할 수 있는 조치가 없고 SSAFY 관리자에게 인스턴스 재시작을 요청해야 한다. 그래서 재부팅은 사람이 지켜보는 낮에 한다.

## 1. 재부팅 전 확인 (각 서버)

```bash
cat /var/run/reboot-required.pkgs                 # 왜 필요한지
docker ps -a --filter label=com.docker.compose.project=ddubi --format '{{.Names}}\t{{.Status}}' | grep -v ' Up '   # 비어 있어야 함
sudo systemctl is-enabled netfilter-persistent    # enabled
```
서버1 은 아카이버가 파일을 쓰는 중이 아닌 시각(매시 5분 이후)을 고른다. 서버2 는 새벽 백업(04:10)과 브리핑(09:30·17:00)을 피한다.

**러너 잡 확인은 재부팅 명령과 같은 명령줄에서 한다.** 9/18 에 9분 전에 "잡 없음" 을 확인하고 재부팅했는데, 그 사이 시작된 client-test 잡이 재부팅으로 죽어 GitLab 에 실패로 남았다. 러너는 종료 신호를 받으면 잡이 끝나길 기다리지만 OS 가 먼저 꺼지므로 소용이 없다. 아래 2·3절의 명령처럼 잡이 있으면 재부팅이 실행되지 않게 한 줄로 묶는다. 잡이 있으면 끝날 때까지(길어도 10분) 기다렸다가 다시 시도한다.

## 2. 서버2 먼저

```bash
# 러너에 도는 잡이 있으면 재부팅하지 않고 멈춘다. 잡 컨테이너 이름은 runner- 로 시작한다
[ "$(docker ps --format '{{.Names}}' | grep -c '^runner-')" = 0 ] && sudo systemctl reboot || echo "CI 잡 진행 중 — 끝난 뒤 다시"
```
1~2분 뒤 ssh 로 다시 들어가서:
```bash
uptime; uname -r                                   # 새 커널인지
docker ps -a --filter label=com.docker.compose.project=ddubi --format '{{.Names}}\t{{.Status}}'   # 전부 Up, healthy 는 60~90초 뒤
sudo iptables -S DOCKER-USER | wc -l               # 6 (규칙 5줄 + 체인 선언)
curl -s -o /dev/null -w '%{http_code}\n' https://j15e202.p.ssafy.io/api/health   # 401
bash ~/S15P21E202/infra/scripts/checks/server2.sh | column -t -s'|'
```
서버1 의 감시 크론이 "서버2 응답 없음" 을 보냈다면 곧 "복구" 도 보낸다. 안 보내면 서버2 감시 로그를 본다.

## 3. 서버1

서버2 가 완전히 정상으로 돌아온 뒤 한다. 러너는 서버2에 있으므로 서버1 재부팅 전에도 서버2의 러너에 잡이 없는지 본다(배포 잡이 서버1에 SSH 로 들어와 있을 수 있다).
```bash
# 서버2에서: docker ps --format '{{.Names}}' | grep -c '^runner-'   → 0 이어야 한다
# 서버1에서:
sudo systemctl reboot
```
2~3분 뒤:
```bash
uptime; uname -r
docker ps -a --filter label=com.docker.compose.project=ddubi --format '{{.Names}}\t{{.Status}}' | grep -v ' Up '   # spark-streaming-replay 만 Exited
docker exec hdfs-namenode hdfs dfsadmin -report | grep -E 'Live datanodes|Missing'   # Live 2, Missing 0 (세이프 모드 해제까지 1~2분)
docker logs spark-streaming-realtime --since 5m 2>&1 | grep -c 'batch='   # 1 이상
bash ~/S15P21E202/infra/scripts/checks/server1.sh | column -t -s'|'
```
Kafka 가 뜨기 전에 수집기가 먼저 뜨면 잠깐 재시작할 수 있다. 5분 안에 재시작이 멈추면 정상이다.

기동 직후 로그에 아래 경고들이 보이는 것은 정상이다. 전부 기다렸다가 스스로 이어 가는 재시도 설계다(9/18 실측).
- 수집기: `publisher kafka 대기 중 (시도 n회)` 가 서너 번 찍히고 Kafka 가 뜨면 첫 사이클을 정상 발행한다.
- 아카이버: `storage init ... 실패` 가 몇 번 찍힌다. 네임노드가 아직 이름 해석이 안 되거나 세이프 모드일 때 나는 것이고, 재시작 없이 다시 붙는다.
- **weather 잡은 한 번 재시작할 수 있다.** 네임노드가 세이프 모드일 때 체크포인트 폴더를 만들려다 `SafeModeException` 으로 종료하고, `unless-stopped` 정책이 1초 뒤 다시 띄운다. 9/18 에 14:49:22 종료 → 14:49:23 재기동 뒤 오류 0 이었다. realtime 은 세이프 모드가 풀린 뒤 시작돼 재시작이 없었다. 재시작 횟수가 1 에서 멈추고 두 스트림의 배치 번호가 오르면 정상이다.

## 4. 이때 함께 하면 좋은 것

- 도커 데몬에 `live-restore` 를 켜 두면 다음부터 도커 자체가 업데이트로 재시작돼도 컨테이너가 멈추지 않는다. 재부팅 직전에 넣어 두면 재부팅이 도커 재시작을 대신한다. **두 서버 모두 2026-09-18 재부팅 때 켰다**(`docker info --format '{{.LiveRestoreEnabled}}'` 가 true).
  ```bash
  echo '{ "live-restore": true }' | sudo tee /etc/docker/daemon.json
  python3 -m json.tool /etc/docker/daemon.json      # 문법이 틀리면 도커가 안 뜨므로 재부팅 전에 꼭 검사한다
  ```
  파일이 이미 있으면 그 안에 항목만 추가한다.

## 5. 실패 경로

| 증상 | 원인 | 조치 |
|---|---|---|
| ssh 가 5분 넘게 안 됨 | 부팅 실패 | AWS 콘솔은 SSAFY 계정이라 우리가 못 본다. SSAFY 관리자에게 인스턴스 재시작을 요청한다. 서버2면 사용자 API 가 멈춘 상태이니 팀에 알림 |
| 컨테이너가 안 올라옴 | 도커 서비스 미기동 | `sudo systemctl status docker`, `sudo journalctl -u docker -n 30`. `daemon.json` 문법 오류가 가장 흔한 원인이라 `python3 -m json.tool /etc/docker/daemon.json` 로 확인하고 고친 뒤 `sudo systemctl restart docker` |
| 방화벽 규칙이 사라짐 | netfilter-persistent 미저장 | `sudo sh ~/S15P21E202/infra/scripts/firewall.sh <상대 서버 IP> [--web]` 뒤 `sudo netfilter-persistent save` |
| HDFS 가 세이프 모드에 머묾 | 데이터노드 보고 대기 | 5분 기다린다. 서버2 데이터노드가 살아 있는지 본다. 그래도 안 풀리면 [hdfs-datanode-down.md](hdfs-datanode-down.md) |
| 수집기 재시작 반복 | Kafka 보다 먼저 떠서 | Kafka healthy 뒤 스스로 멈춘다. 10분 넘으면 `docker logs collector-weather` |
