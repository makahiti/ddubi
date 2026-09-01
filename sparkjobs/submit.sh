#!/usr/bin/env sh
# Spark 잡 제출 (spark 컨테이너 안에서 실행. 작업 디렉터리 /opt/ddubi/sparkjobs)
#
#   sh submit.sh streaming/weather_latest.py [잡 인자...]
#
# - Kafka 커넥터(spark-sql-kafka-0-10_2.12)는 --packages 로 받는다. 첫 실행 때 Maven 에서 내려받고
#   spark-ivy 볼륨(SPARK_IVY_DIR)에 캐시된다.
# - 체크포인트·이벤트로그 경로는 환경변수로 받는다 (1단계 로컬 볼륨 → 3단계 HDFS 로 값만 바뀐다).
#   잡은 SPARK_CHECKPOINT_DIR 아래에 잡 이름별 디렉터리를 만들어 쓴다 (common/settings.py).
set -eu

if [ "$#" -lt 1 ]; then
  echo "usage: $0 <job.py> [arguments...]" >&2
  exit 1
fi
JOB="$1"; shift
JOB_NAME="$(basename "$JOB" .py)"

: "${KAFKA_BOOTSTRAP_SERVERS:?KAFKA_BOOTSTRAP_SERVERS 가 필요합니다}"
: "${SPARK_CHECKPOINT_DIR:?SPARK_CHECKPOINT_DIR 가 필요합니다}"

SPARK_HOME="${SPARK_HOME:-/opt/spark}"
JOBS_DIR="${JOBS_DIR:-/opt/ddubi/sparkjobs}"
SPARK_MASTER_URL="${SPARK_MASTER_URL:-spark://spark-master:7077}"
# 이미지 태그(예: 3.5.3-python3)에서 Spark 버전만 떼어 커넥터 버전을 맞춘다
SPARK_CORE_VERSION="${SPARK_VERSION%%-*}"
SPARK_KAFKA_PACKAGE="${SPARK_KAFKA_PACKAGE:-org.apache.spark:spark-sql-kafka-0-10_2.12:${SPARK_CORE_VERSION:-3.5.3}}"
SPARK_IVY_DIR="${SPARK_IVY_DIR:-/opt/ddubi/ivy}"
SPARK_EVENTLOG_ENABLED="${SPARK_EVENTLOG_ENABLED:-false}"
SPARK_EVENTLOG_DIR="${SPARK_EVENTLOG_DIR:-/opt/ddubi/spark-events}"

# spark-submit 은 잡 파일이 있는 폴더(streaming/)만 sys.path 에 넣으므로 common/ 을 찾도록 루트를 추가한다.
# executor 쪽은 아래 spark.executorEnv.PYTHONPATH 가 같은 역할을 한다.
export PYTHONPATH="$JOBS_DIR${PYTHONPATH:+:$PYTHONPATH}"

echo "submit job=$JOB master=$SPARK_MASTER_URL package=$SPARK_KAFKA_PACKAGE checkpoint=$SPARK_CHECKPOINT_DIR/$JOB_NAME"

exec "$SPARK_HOME/bin/spark-submit" \
  --master "$SPARK_MASTER_URL" \
  --deploy-mode client \
  --name "ddubi-$JOB_NAME" \
  --packages "$SPARK_KAFKA_PACKAGE" \
  --conf spark.jars.ivy="$SPARK_IVY_DIR" \
  --conf spark.driver.host="$(hostname)" \
  --conf spark.driver.bindAddress=0.0.0.0 \
  --conf spark.driver.memory="${SPARK_DRIVER_MEMORY:-512m}" \
  --conf spark.executor.memory="${SPARK_EXECUTOR_MEMORY:-1g}" \
  --conf spark.executor.cores="${SPARK_EXECUTOR_CORES:-1}" \
  --conf spark.cores.max="${SPARK_CORES_MAX:-2}" \
  --conf spark.sql.shuffle.partitions="${SPARK_SHUFFLE_PARTITIONS:-6}" \
  --conf spark.sql.streaming.checkpointLocation="$SPARK_CHECKPOINT_DIR" \
  --conf spark.eventLog.enabled="$SPARK_EVENTLOG_ENABLED" \
  --conf spark.eventLog.dir="$SPARK_EVENTLOG_DIR" \
  --conf spark.executorEnv.PYTHONPATH="$JOBS_DIR" \
  --conf spark.ui.prometheus.enabled=true \
  "$JOBS_DIR/$JOB" "$@"
