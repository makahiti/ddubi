"""Weather stream configuration and lifecycle, testable without Spark."""
import logging
from urllib.parse import urlsplit

log = logging.getLogger(__name__)


def silver_configuration(env):
    target = env.get("SILVER_OUTPUT_URI", "").strip().rstrip("/")
    required = env.get("WEATHER_REQUIRE_SILVER", "false").strip().lower()
    if required not in ("true", "false"):
        raise ValueError("WEATHER_REQUIRE_SILVER must be true or false")
    if not target:
        if required == "true":
            raise RuntimeError("SILVER_OUTPUT_URI is required: production Weather Silver must not be silently disabled")
        return None
    uri = urlsplit(target)
    if uri.scheme not in ("hdfs", "file") or not uri.path or (uri.scheme == "hdfs" and not uri.netloc):
        message = "SILVER_OUTPUT_URI must be hdfs://host:port/path or file:///absolute/path"
        if required == "true":
            raise ValueError(message)
        log.error("%s; Weather Silver is disabled while Redis remains enabled", message)
        return None
    if "?" in target or "#" in target:
        message = "SILVER_OUTPUT_URI cannot contain query or fragment"
        if required == "true":
            raise ValueError(message)
        log.error("%s; Weather Silver is disabled while Redis remains enabled", message)
        return None
    return target + "/weather"


def _progress(query):
    progress = query.lastProgress
    if progress:
        log.info("query=%s batch=%s inputRows=%s durationMs=%s", query.name,
                 progress.get("batchId"), progress.get("numInputRows"), progress.get("durationMs"))


def supervise(start_redis, start_silver=None):
    """Redis is required; optional Silver failure is reported without stopping Redis."""
    redis_query = None
    silver_query = None
    try:
        redis_query = start_redis()
        if start_silver is not None:
            try:
                silver_query = start_silver()
            except Exception:
                log.exception("Weather Silver failed to start; Redis remains active")
        while True:
            if redis_query.awaitTermination(30):
                error = redis_query.exception()
                if error is not None:
                    raise RuntimeError("required Redis stream failed: %s" % error)
                raise RuntimeError("required Redis stream stopped unexpectedly")
            _progress(redis_query)
            if silver_query is not None:
                try:
                    if not silver_query.isActive:
                        error = silver_query.exception()
                        if error is not None:
                            log.error("Weather Silver stream failed; Redis remains active: %s", error)
                        else:
                            log.error("Weather Silver stream stopped unexpectedly; Redis remains active")
                        silver_query = None
                    else:
                        _progress(silver_query)
                except Exception:
                    log.exception("Could not inspect Weather Silver stream; Redis remains active")
                    silver_query = None
    except Exception:
        log.exception("Required Weather Redis stream failed during startup or execution")
        raise
    finally:
        for query in (silver_query, redis_query):
            if query is None:
                continue
            try:
                if query.isActive:
                    query.stop()
            except Exception:
                log.exception("Could not stop query=%s", query.name)
