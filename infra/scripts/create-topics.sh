#!/usr/bin/env sh
# docs/contracts/topics.yaml 을 기준으로 Kafka 토픽을 만들고 보존 설정을 맞춘다.
#
# 사용법 (호스트에서, core 프로필이 떠 있는 상태):
#   sh infra/scripts/create-topics.sh
#
# - 없는 토픽은 만들고, 있는 토픽은 보존 설정(retention.ms / retention.bytes)만 갱신한다.
#   파티션 수는 줄일 수 없으므로 기존 토픽의 파티션은 건드리지 않는다.
# - topics.yaml 형식 전제: 토픽명은 2칸 들여쓰기, 속성은 4칸 들여쓰기 (awk 로 읽는다)
set -eu

# Git Bash(MSYS)가 /opt/... 인자를 윈도우 경로로 바꾸는 것을 막는다. 리눅스에서는 무해.
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TOPICS_FILE="${TOPICS_FILE:-$ROOT/docs/contracts/topics.yaml}"
COMPOSE_DIR="${COMPOSE_DIR:-$ROOT/infra}"
KAFKA_SERVICE="${KAFKA_SERVICE:-kafka}"
BOOTSTRAP="${BOOTSTRAP:-localhost:9092}"   # kafka 컨테이너 내부 기준 주소
KAFKA_BIN=/opt/kafka/bin

# - compose 파일을 -f 로 넘기지 않고 디렉터리로 이동한다 (Git Bash 경로 변환 문제 회피)
# - </dev/null : exec 가 표준입력을 가져가면 while read 루프의 나머지 줄을 삼킨다
kexec() {
  (cd "$COMPOSE_DIR" && docker compose --profile core exec -T "$KAFKA_SERVICE" sh -c "$1" </dev/null)
}

# topics.yaml → "이름 파티션 복제 보존시간 보존바이트" 한 줄씩
parse_topics() {
  awk '
    /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ {
      if (name != "") print name, p, rf, h, b
      name = $1; sub(":", "", name); p = rf = h = b = ""
      next
    }
    /^    partitions:/        { p  = $2 }
    /^    replicationFactor:/ { rf = $2 }
    /^    retentionHours:/    { h  = $2 }
    /^    retentionBytes:/    { b  = $2 }
    END { if (name != "") print name, p, rf, h, b }
  ' "$TOPICS_FILE"
}

echo "topics file : $TOPICS_FILE"
echo "kafka       : $KAFKA_SERVICE ($BOOTSTRAP)"
echo

parse_topics | while read -r name partitions rf hours bytes; do
  if [ -z "$partitions" ] || [ -z "$rf" ] || [ -z "$hours" ] || [ -z "$bytes" ]; then
    echo "!! $name: partitions/replicationFactor/retentionHours/retentionBytes 중 빠진 값이 있습니다" >&2
    exit 1
  fi
  retention_ms=$((hours * 3600 * 1000))

  echo "== $name  (partitions=$partitions rf=$rf retention=${hours}h bytes=$bytes)"
  kexec "$KAFKA_BIN/kafka-topics.sh --bootstrap-server $BOOTSTRAP --create --if-not-exists \
    --topic $name --partitions $partitions --replication-factor $rf \
    --config retention.ms=$retention_ms --config retention.bytes=$bytes"
  kexec "$KAFKA_BIN/kafka-configs.sh --bootstrap-server $BOOTSTRAP --alter \
    --entity-type topics --entity-name $name \
    --add-config retention.ms=$retention_ms,retention.bytes=$bytes" >/dev/null
done

echo
echo "== 현재 토픽 상태"
kexec "$KAFKA_BIN/kafka-topics.sh --bootstrap-server $BOOTSTRAP --describe" | grep -E '^Topic:'
