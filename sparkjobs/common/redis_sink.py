"""Spark 마이크로배치의 최신 상태를 TTL이 있는 Redis 문자열 키로 저장한다.

입력은 ``redisKey``와 ``redisValue``를 가진 DataFrame이며, 여러 자료의 갱신주기가 다른
잡은 ``ttlSeconds``도 함께 넘길 수 있다. Redis에는 재계산 가능한 원문을 쌓지 않고 최신
상태만 두며, 원문 보존은 Kafka를 소비하는 Archiver/HDFS가 맡는다.

쓰기 연결은 executor 파티션마다 하나만 열고 Redis pipeline으로 최대 500건을 묶는다.
마이크로배치 재시도 시 같은 키를 ``SET``으로 덮어쓰므로 결과는 멱등적이다. 일부 파티션의
Redis I/O가 실패하면 예외를 Spark로 돌려보내 checkpoint가 진행되지 않게 하며, 다음 재시도에서
이미 성공한 키도 같은 값으로 안전하게 갱신한다.
"""

from __future__ import annotations

from pyspark.sql import DataFrame

KEY_COL = "redisKey"
VALUE_COL = "redisValue"
TTL_COL = "ttlSeconds"
EVENT_TIME_COL = "occurredAtOrder"
COLLECTED_TIME_COL = "collectedAtOrder"
_PIPELINE_CHUNK = 500

# Redis의 GET → 시각 비교 → SET을 하나의 원자 연산으로 묶는다. Spark 마이크로배치는
# 서로 다른 시점에 재시도되거나 늦은 Kafka 이벤트를 받을 수 있으므로, driver에서 먼저 GET한 뒤
# SET하면 그 사이 다른 executor가 값을 바꾸는 경쟁 조건이 생긴다. Lua는 Redis 단일 스레드에서
# 실행되어 그 틈을 없앤다. 기존 값이 옛 계약이거나 손상돼 시각을 읽을 수 없을 때만 새 정상값으로
# 교체하고, 정상값끼리는 occurredAt → collectedAt 순으로 비교한다.
_SET_IF_NOT_OLDER_LUA = """
local current = redis.call('GET', KEYS[1])
if current then
    local decoded_ok, decoded = pcall(cjson.decode, current)
    if decoded_ok and decoded then
        local current_occurred = tostring(decoded['occurredAt'] or '')
        local current_collected = tostring(decoded['collectedAt'] or '')
        local incoming_occurred = tostring(ARGV[3])
        local incoming_collected = tostring(ARGV[4])

        if current_occurred ~= '' then
            if current_occurred > incoming_occurred then
                return 0
            end
            if current_occurred == incoming_occurred and current_collected > incoming_collected then
                return 0
            end
        end
    end
end

redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2]))
return 1
"""


def write_with_ttl(df: DataFrame, ttl_seconds: int, host: str, port: int, password: str | None) -> None:
    """모든 행에 동일한 TTL을 적용한다.

    날씨처럼 한 잡의 모든 키가 같은 갱신주기를 가질 때 사용한다. ``ttl_seconds``는 Redis의
    만료 초 단위이며 1 이상이어야 한다. 연결정보는 driver의 설정 객체가 아니라 기본형만
    closure에 넣어 executor 직렬화 문제와 비밀값의 불필요한 로그 노출을 피한다.
    """
    if ttl_seconds <= 0:
        raise ValueError("ttl_seconds는 1 이상이어야 합니다")

    def write_partition(rows) -> None:
        import redis  # executor 쪽에서 import

        client = redis.Redis(host=host, port=port, password=password, socket_timeout=5, socket_connect_timeout=5)
        pipe = client.pipeline(transaction=False)
        pending = 0
        for row in rows:
            pipe.set(row[KEY_COL], row[VALUE_COL], ex=ttl_seconds)
            pending += 1
            if pending >= _PIPELINE_CHUNK:
                pipe.execute()
                pending = 0
        if pending:
            pipe.execute()
        client.close()

    df.select(KEY_COL, VALUE_COL).foreachPartition(write_partition)


def write_with_row_ttl(df: DataFrame, host: str, port: int, password: str | None) -> None:
    """행별 ``ttlSeconds``를 사용해 갱신주기가 다른 최신 상태를 한 배치에서 저장한다.

    버스·지하철은 수십 초, 도로·도시 데이터는 수분마다 갱신되므로 하나의 고정 TTL을 쓰면
    빠른 자료가 지나치게 오래 남거나 느린 자료가 정상 수집 사이에 사라진다. 호출자는 계약에
    선언된 양의 TTL만 전달해야 하며, 이 함수도 executor에서 다시 검증해 잘못된 무기한 키가
    생성되는 것을 막는다.
    """

    def write_partition(rows) -> None:
        import redis  # executor 쪽에서 import

        client = redis.Redis(host=host, port=port, password=password, socket_timeout=5, socket_connect_timeout=5)
        pipe = client.pipeline(transaction=False)
        pending = 0
        for row in rows:
            ttl_seconds = int(row[TTL_COL])
            if ttl_seconds <= 0:
                client.close()
                raise ValueError("ttlSeconds는 1 이상이어야 합니다")
            pipe.set(row[KEY_COL], row[VALUE_COL], ex=ttl_seconds)
            pending += 1
            if pending >= _PIPELINE_CHUNK:
                pipe.execute()
                pending = 0
        if pending:
            pipe.execute()
        client.close()

    df.select(KEY_COL, VALUE_COL, TTL_COL).foreachPartition(write_partition)


def write_latest_with_row_ttl(df: DataFrame, host: str, port: int, password: str | None) -> None:
    """기존 Redis 값보다 오래되지 않은 관측만 행별 TTL과 함께 원자적으로 저장한다.

    입력에는 ``occurredAtOrder``와 ``collectedAtOrder``가 ISO 8601 Asia/Seoul 문자열로 있어야
    한다. 같은 micro-batch 안의 정렬만으로는 다음 micro-batch에 늦게 도착한 과거 이벤트를
    막을 수 없으므로 Redis의 현재 JSON과 다시 비교한다. 과거 이벤트를 거절할 때는 기존 값과
    TTL을 그대로 유지하여 오래된 Replay가 최신 상태의 수명을 인위적으로 연장하지 않게 한다.

    Lua 실행은 키별로 원자적이며 Spark 재시도에도 멱등적이다. Redis I/O나 JSON 쓰기가 실패하면
    executor 예외를 통해 batch를 실패시켜 checkpoint가 앞서 나가지 않는다. 기존 값이 손상됐거나
    구 계약이라 ``occurredAt``이 없으면 새 정상 관측으로 한 번 교체해 자동 복구한다.
    """

    def write_partition(rows) -> None:
        import redis  # executor 쪽에서 import

        client = redis.Redis(host=host, port=port, password=password, socket_timeout=5, socket_connect_timeout=5)
        pipe = client.pipeline(transaction=False)
        pending = 0
        for row in rows:
            ttl_seconds = int(row[TTL_COL])
            occurred_at = row[EVENT_TIME_COL]
            collected_at = row[COLLECTED_TIME_COL]
            if ttl_seconds <= 0:
                client.close()
                raise ValueError("ttlSeconds는 1 이상이어야 합니다")
            if not occurred_at or not collected_at:
                client.close()
                raise ValueError("최신값 비교에는 occurredAtOrder와 collectedAtOrder가 필요합니다")

            pipe.eval(
                _SET_IF_NOT_OLDER_LUA,
                1,
                row[KEY_COL],
                row[VALUE_COL],
                ttl_seconds,
                occurred_at,
                collected_at,
            )
            pending += 1
            if pending >= _PIPELINE_CHUNK:
                pipe.execute()
                pending = 0
        if pending:
            pipe.execute()
        client.close()

    df.select(KEY_COL, VALUE_COL, TTL_COL, EVENT_TIME_COL, COLLECTED_TIME_COL).foreachPartition(write_partition)
