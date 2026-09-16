# 서버를 재부팅해야 한다 (커널 업데이트 대기, 이상 동작)

> 상황: `/var/run/reboot-required` 가 있거나(커널·libc 업데이트가 설치됐지만 적용 전), 호스트 자체가 이상하다.
> 검증: **미검증(2026-09-15 작성)**. 9/3 에 양 서버를 재부팅한 경험(방화벽 유지·컨테이너 자동 복귀 확인)을 절차로 옮겼다. 다음 재부팅 때 시간을 적는다.

## 0. 알아 둘 것

- 모든 운영 컨테이너는 `restart: unless-stopped` 라 서버가 켜지면 도커가 알아서 다시 띄운다. 사람이 `compose up` 을 할 필요는 없다.
- 방화벽(iptables DOCKER-USER)은 `netfilter-persistent` 가 저장해 두어 재부팅 뒤에도 남는다. 9/3 에 확인했다.
- 서버2 재부팅 = 사용자 API 가 2~3분 끊긴다. 서버1 재부팅 = 수집·Spark·Kafka 가 3~5분 멈춘다. 수집기는 한 주기를 놓치고, Spark 는 체크포인트에서 이어 읽어 유실이 없다.
- 두 대를 동시에 재부팅하지 않는다. HDFS 복제 2 가 잠깐 한 부만 남는 시간을 겹치지 않게 한다.
- 팀 채널에 "몇 시에 몇 분 끊김" 을 미리 알린다.

## 1. 재부팅 전 확인 (각 서버)

```bash
cat /var/run/reboot-required.pkgs                 # 왜 필요한지
docker ps -a --filter label=com.docker.compose.project=ddubi --format '{{.Names}}\t{{.Status}}' | grep -v ' Up '   # 비어 있어야 함
sudo systemctl is-enabled netfilter-persistent    # enabled
```
서버1 은 아카이버가 파일을 쓰는 중이 아닌 시각(매시 5분 이후)을 고른다. 서버2 는 새벽 백업(04:10)과 브리핑(09:30·17:00)을 피한다.

## 2. 서버2 먼저

```bash
sudo reboot
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

서버2 가 완전히 정상으로 돌아온 뒤 한다.
```bash
sudo reboot
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

## 4. 이때 함께 하면 좋은 것

- 도커 데몬에 `live-restore` 를 켜 두면 다음부터 도커 자체가 업데이트로 재시작돼도 컨테이너가 멈추지 않는다. 재부팅 직전에 넣어 두면 재부팅이 도커 재시작을 대신한다.
  ```bash
  echo '{ "live-restore": true }' | sudo tee /etc/docker/daemon.json
  ```
  파일이 이미 있으면 그 안에 항목만 추가한다.

## 5. 실패 경로

| 증상 | 원인 | 조치 |
|---|---|---|
| ssh 가 5분 넘게 안 됨 | 부팅 실패 | AWS 콘솔에서 인스턴스 상태와 시스템 로그 확인. 서버2면 사용자 API 가 멈춘 상태이니 팀에 알림 |
| 컨테이너가 안 올라옴 | 도커 서비스 미기동 | `sudo systemctl status docker`, `sudo systemctl start docker` |
| 방화벽 규칙이 사라짐 | netfilter-persistent 미저장 | `sudo sh ~/S15P21E202/infra/scripts/firewall.sh <상대 서버 IP> [--web]` 뒤 `sudo netfilter-persistent save` |
| HDFS 가 세이프 모드에 머묾 | 데이터노드 보고 대기 | 5분 기다린다. 서버2 데이터노드가 살아 있는지 본다. 그래도 안 풀리면 [hdfs-datanode-down.md](hdfs-datanode-down.md) |
| 수집기 재시작 반복 | Kafka 보다 먼저 떠서 | Kafka healthy 뒤 스스로 멈춘다. 10분 넘으면 `docker logs collector-weather` |
