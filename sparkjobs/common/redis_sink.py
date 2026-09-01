"""DataFrame (redisKey, redisValue) → Redis SET ... EX ttl.

파티션 단위로 연결을 열고 파이프라인으로 묶어 보낸다. 실행은 executor 에서 일어나므로
이 함수에 넘기는 값은 직렬화 가능한 기본형만 쓴다 (Settings 객체를 통째로 넘기지 않는다).
"""

from __future__ import annotations

from pyspark.sql import DataFrame

KEY_COL = "redisKey"
VALUE_COL = "redisValue"
_PIPELINE_CHUNK = 500


def write_with_ttl(df: DataFrame, ttl_seconds: int, host: str, port: int, password: str | None) -> None:
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
