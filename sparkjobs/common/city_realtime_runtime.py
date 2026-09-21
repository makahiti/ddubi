"""필수 Redis 스트림과 선택 HDFS City Silver 스트림의 장애 영역을 분리한다."""

import logging

log = logging.getLogger(__name__)


def supervise(start_redis, start_silver=None):
    redis_query = silver_query = None
    try:
        redis_query = start_redis()
        if start_silver:
            try:
                silver_query = start_silver()
            except Exception:
                log.exception("City Silver failed to start; Redis remains active")
        while True:
            if redis_query.awaitTermination(30):
                error = redis_query.exception()
                raise RuntimeError("required city Redis stream stopped" + (": " + str(error) if error else ""))
            if silver_query is not None:
                try:
                    if not silver_query.isActive:
                        log.error("City Silver stopped; Redis remains active: %s", silver_query.exception())
                        silver_query = None
                except Exception:
                    log.exception("Could not inspect City Silver; Redis remains active")
                    silver_query = None
    finally:
        for query in (silver_query, redis_query):
            try:
                if query is not None and query.isActive:
                    query.stop()
            except Exception:
                log.exception("Could not stop city query")
