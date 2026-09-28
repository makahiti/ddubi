"""날씨·도시·TOPIS·돌발 스트리밍 작업을 한 Spark 앱에서 돌린다.

각 작업 파일의 prepare(spark, settings) 로 스트림을 준비하고 한 번에 감독한다(common/light_runtime.py).
쿼리 이름·체크포인트 경로는 작업별로 따로 돌던 때와 같아서, 합친 뒤에도 읽던 자리부터 이어서 읽는다.
그래서 **작업별 컨테이너와 동시에 켜면 안 된다**(같은 체크포인트를 두 앱이 쓰게 된다).

    sh submit.sh streaming/light_streaming.py

돌릴 작업은 LIGHT_STREAMING_JOBS(쉼표 구분)로 고른다. 기본은 네 작업 모두.
"""

from __future__ import annotations

import importlib
import logging
import os
import sys

from common.light_runtime import LIGHT_JOBS, parse_jobs, supervise_all
from common.settings import Settings
from pyspark.sql import SparkSession

log = logging.getLogger(__name__)


def main() -> int:
    logging.basicConfig(level=logging.INFO, stream=sys.stdout, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    jobs = parse_jobs(os.environ.get("LIGHT_STREAMING_JOBS", ",".join(LIGHT_JOBS)))
    settings = Settings.from_env()
    spark = (
        SparkSession.builder.appName("ddubi-light_streaming")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .getOrCreate()
    )
    spark.sparkContext.setLogLevel("WARN")
    try:
        prepared = []
        for name in jobs:
            # streaming 폴더를 패키지 이름으로 불러온다. 익스큐터도 PYTHONPATH(작업 폴더)에서 같은 이름으로 찾는다.
            module = importlib.import_module("streaming." + name)
            start_redis, start_silver = module.prepare(spark, settings)
            prepared.append((name, start_redis, start_silver))
        log.info("light streaming jobs=%s", jobs)
        supervise_all(prepared)
    finally:
        spark.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
