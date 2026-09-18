#!/bin/bash
# 서버1에서 실행하는 읽기 전용 날씨 점검. 인증정보나 전체 환경변수는 출력하지 않는다.
set -u
export TZ=Asia/Seoul

printf '\n[컨테이너 상태]\n'
docker ps -a --format '{{.Names}} {{.Status}}' | grep -E '^(collector-weather|archiver|spark-master|spark-worker-[12]|spark-streaming-weather|hdfs-namenode) ' || true
printf '\n[날씨 드라이버 상태]\n'
docker inspect spark-streaming-weather --format 'status={{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} restarts={{.RestartCount}} started={{.State.StartedAt}} error={{.State.Error}}' 2>&1 || true
printf '\n[선택한 실행 설정 - 인증정보 제외]\n'
docker inspect spark-streaming-weather --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -E '^(SILVER_OUTPUT_URI|WEATHER_REQUIRE_SILVER|WEATHER_SILVER_TRIGGER|WEATHER_SILVER_WATERMARK|SPARK_STARTING_OFFSETS|SPARK_CHECKPOINT_DIR|SPARK_EXECUTOR_MEMORY|SPARK_CORES_MAX)=' || true
printf '\n[배포 코드의 Silver·실패 격리 지원 여부]\n'
docker exec spark-streaming-weather sh -c 'grep -nE "silver_configuration|silver stream started|SILVER_OUTPUT_URI|WEATHER_REQUIRE_SILVER" /opt/ddubi/sparkjobs/streaming/weather_latest.py /opt/ddubi/sparkjobs/common/weather_runtime.py' 2>&1 || true
printf '\n[최근 기동·쿼리 진행·오류]\n'
docker logs spark-streaming-weather --tail 250 2>&1 | grep -E 'ERROR|Traceback|Exception|Caused by|not accepted any resources|starting topic|started topic|Silver|silver stream|Redis remains active|query=|batch=|SILVER_OUTPUT_URI' | tail -80 || true
printf '\n[마스터 애플리케이션과 워커 가용 자원]\n'
docker exec spark-master python3 -c 'import json,urllib.request; d=json.load(urllib.request.urlopen("http://localhost:8080/json/", timeout=5)); print(json.dumps({k:d.get(k) for k in ("status","cores","coresused","memory","memoryused","activeapps")},ensure_ascii=False))' 2>&1 || true
printf '\n[HDFS 세이프 모드와 데이터노드 상태]\n'
docker exec hdfs-namenode hdfs dfsadmin -safemode get 2>&1 || true
docker exec hdfs-namenode hdfs dfsadmin -report 2>&1 | grep -E 'Live datanodes|Dead datanodes|Missing blocks|Under replicated' || true
printf '\n[아카이브·Silver·독립 쿼리 체크포인트]\n'
for path in /ddubi/archive/city.raw /ddubi/silver/weather /ddubi/checkpoints/weather_latest/commits /ddubi/checkpoints/weather_latest_silver/commits; do
  printf '\n%s\n' "$path"
  docker exec hdfs-namenode hdfs dfs -ls "$path" 2>&1 | tail -8 || true
done
printf '\n아카이브 존재만으로 Spark 진행을 증명할 수 없다. 날씨 수집 주기 후 다시 실행해 Redis·Silver 체크포인트를 각각 비교한다. Silver가 실패해도 Redis 체크포인트는 계속 전진해야 한다.\n'
