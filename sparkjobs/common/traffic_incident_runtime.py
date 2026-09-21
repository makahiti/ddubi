"""Keep required incident Redis query alive when optional HDFS Silver fails."""

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
                log.exception("Traffic incident Silver failed to start; Redis remains active")
        while True:
            if redis_query.awaitTermination(30):
                error = redis_query.exception()
                raise RuntimeError(
                    "required traffic incident Redis stream stopped" + (": " + str(error) if error else "")
                )
            if silver_query is not None:
                try:
                    if not silver_query.isActive:
                        log.error("Traffic incident Silver stopped; Redis remains active: %s", silver_query.exception())
                        silver_query = None
                except Exception:
                    log.exception("Could not inspect traffic incident Silver; Redis remains active")
                    silver_query = None
    finally:
        for query in (silver_query, redis_query):
            try:
                if query is not None and query.isActive:
                    query.stop()
            except Exception:
                log.exception("Could not stop traffic incident query")
