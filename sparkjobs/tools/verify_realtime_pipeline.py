"""격리 Kafka/Redis에서 실제 대응표와 운영 배치 함수를 반복 검증한다.

실측 운행 데이터가 아닌 명시적 fixture를 Collector Publisher로 보낸다. 버전/SHA 검증,
Redis 최신값, 역순 이벤트, 체크포인트 재개, JVM 힙과 배치 시간을 보고한다. 운영 endpoint에
실수로 쓰지 않도록 검증용 컨테이너 DNS만 허용한다. 운영 수집 설정은 바꾸지 않는다.
"""

import argparse
import gzip
import hashlib
import json
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "sparkjobs"))
sys.path.insert(0, str(ROOT / "collector/src"))
from publisher import Publisher
from bus_crosswalk import BusCrosswalk
from common.bus_crosswalk import load_crosswalk
from common.kafka_source import read_events_stream
from common.settings import Settings
from streaming.realtime_latest import process_batch
from pyspark.sql import SparkSession, functions as F
import redis


def main():
    """동일 파일/SHA를 양쪽에 적용하고 ACK 이후의 결과를 검증한다. 실패는 종료 코드로 전파한다."""
    p = argparse.ArgumentParser()
    p.add_argument("--output", required=True)
    p.add_argument("--batches", type=int, default=30)
    a = p.parse_args()
    if not 2 <= a.batches <= 300:
        raise ValueError("batches must be 2..300")
    out = Path(a.output)
    out.mkdir(parents=True, exist_ok=False)
    broker = "ddubi-hardening-kafka:9092"
    topic = "audit-transport-" + out.name
    if len(topic) > 249 or any(
        c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-"
        for c in topic
    ):
        raise ValueError("output directory name must be Kafka topic safe")
    cache = redis.Redis(host="ddubi-hardening-redis", decode_responses=True)
    publisher = Publisher(broker, "ddubi-hardening-fixture")
    if not publisher.wait_ready(30):
        raise RuntimeError("isolated broker unavailable")
    artifact = ROOT / "backend/transit-master/metropolitan-bus-v6.crosswalk.json.gz"
    sha = hashlib.sha256(artifact.read_bytes()).hexdigest()
    collector = BusCrosswalk(artifact, sha)
    edges = json.loads(gzip.decompress(artifact.read_bytes()))["edges"]
    edge = next(e for e in edges if e["namespace"] == "SEOUL_BUS")
    spark = (
        SparkSession.builder.appName("ddubi-hardening-realtime")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .config("spark.sql.shuffle.partitions", "2")
        .getOrCreate()
    )
    spark.sparkContext.setLogLevel("ERROR")
    crosswalk = load_crosswalk(spark, artifact, sha)
    settings = Settings(
        broker,
        "ddubi-hardening-redis",
        6379,
        None,
        str(out / "checkpoint"),
        "earliest",
        100,
    )
    metrics = []
    query = None
    kst = timezone(timedelta(hours=9))
    epoch = datetime.now(kst)
    run_id = out.name + ":"
    prefix = "transit:latest:bus-position:" + edge["routeId"] + ":" + run_id + "audit-"

    def consume(frame, batch_id):
        started = time.perf_counter()
        process_batch(frame, batch_id, settings, crosswalk)
        heap = (
            spark._jvm.java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage()
        )
        metrics.append(
            dict(
                batchId=batch_id,
                seconds=time.perf_counter() - started,
                heapUsedBytes=heap.getUsed(),
                heapMaxBytes=heap.getMax(),
            )
        )

    def start():
        return (
            read_events_stream(spark, settings, topic)
            .filter(F.col("eventId").startswith(run_id))
            .writeStream.foreachBatch(consume)
            .option("checkpointLocation", settings.checkpoint_for("audit"))
            .start()
        )

    def send(batch, old=False, mismatch=False):
        stamp = (epoch + timedelta(seconds=(-100 if old else batch))).isoformat()
        for i in range(100):
            payload = dict(
                providerNamespace="SEOUL_BUS",
                providerRouteId=edge["routeId"],
                providerVehicleId=run_id + "audit-" + str(i),
                providerStopId=edge["toStopId"],
                providerFromStopId=edge["fromStopId"],
                providerToStopId=edge["toStopId"],
                raw={"stationSeq": "2"},
            )
            mapping = collector.resolve("bus.position", payload)
            assert mapping["segmentIds"] == [edge["segmentId"]]
            if mismatch:
                mapping["masterVersion"] = "fixture-other-version"
            payload["canonicalMapping"] = mapping
            event = dict(
                eventId=run_id + "audit-%s-%s-%s" % (batch, old, i),
                eventType="bus.position",
                source="seoul.bus_location",
                region="SEOUL",
                occurredAt=stamp,
                collectedAt=stamp,
                segmentIds=mapping["segmentIds"],
                payload=payload,
            )
            publisher.publish(topic, run_id + "audit-" + str(i), event)
        if publisher.flush(30) or publisher.failed:
            raise RuntimeError("fixture delivery failed")
        query.processAllAvailable()

    try:
        publisher.publish(topic, run_id + "init", {})
        if publisher.flush(30) or publisher.failed:
            raise RuntimeError("fixture topic initialization failed")
        query = start()
        for batch in range(a.batches):
            send(batch)
            value = json.loads(cache.get(prefix + "0"))
            assert value["eventId"] == run_id + "audit-%s-False-0" % batch
            assert value["segmentIds"] == [edge["segmentId"]]
            assert value["canonicalMapping"]["crosswalkSha256"] == sha
        before = cache.get(prefix + "0")
        send(a.batches, old=True)
        assert cache.get(prefix + "0") == before, "late event replaced newest value"
        query.stop()
        query = start()
        send(a.batches + 1, mismatch=True)
        value = json.loads(cache.get(prefix + "0"))
        assert value["segmentIds"] == []
        assert value["canonicalMapping"]["reason"] == "CROSSWALK_VERSION_MISMATCH"
        query.stop()
        query = None
        report = dict(
            scope="isolated Docker / fixture producer / actual Kafka Spark Redis",
            crosswalkSha256=sha,
            masterVersion=crosswalk[1],
            indexRows=crosswalk[0].count(),
            requestedBatches=a.batches,
            acknowledgedMessages=publisher.sent - 1,
            staleProtected=True,
            checkpointResumed=True,
            mismatchRejected=True,
            sparkVersion=spark.version,
            batches=metrics,
            inMemoryPlan="InMemoryTableScan"
            in crosswalk[0]._jdf.queryExecution().executedPlan().toString(),
            productionValidated=False,
        )
        (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps(report), flush=True)
    finally:
        if query is not None:
            query.stop()
        crosswalk[0].unpersist()
        spark.stop()


if __name__ == "__main__":
    main()
