"""city.raw 사고를 링크별 최신 사건 hash와 도로 영향 알림으로 연결한다.

검증 도로 대응표는 기동 시 한 번 캐시한다. 원문/형식 오류는 Kafka와 별도 격리 출력에
보존한다. Redis는 사건별 수집시각 CAS를 사용하므로 늦은 배치가 새 응답을 덮지 않는다.
Kafka 알림은 Redis ACK 후 발행하고 실패 시 같은 eventId로 재시도한다. 사고를 blocked로
바꾸지 않으며 Backend가 실제 예상 통과시각과 사건 기간을 다시 비교한다.
"""

import os
from datetime import datetime, timedelta, timezone
from pyspark.sql import SparkSession, functions as F, types as T
from common.kafka_source import read_events_stream
from common.settings import Settings
from common.road_impact import RoadImpactIndex

# 하나의 hash가 무한히 커지지 않도록 만료 사건을 갱신 때 제거하고 1,000개 상한을 둔다.
UPSERT = """
local incoming=cjson.decode(ARGV[2])
local old = redis.call('GET', KEYS[2])
if old then
 local p=cjson.decode(old)
 if p.collectedAt > ARGV[3] then return 0 end
 if p.collectedAt == ARGV[3] and p.contentHash ~= incoming.contentHash then incoming.conflicted=true end
 if p.conflicted and p.collectedAt == ARGV[3] then incoming.conflicted=true end
 if p.key ~= KEYS[1] then redis.call('HDEL',p.key,ARGV[1]) end
end
local all=redis.call('HGETALL',KEYS[1])
for i=1,#all,2 do
 local ok,v=pcall(cjson.decode,all[i+1])
 if not ok or tonumber(v.expiresEpoch or 0)<=tonumber(ARGV[5]) then redis.call('HDEL',KEYS[1],all[i]) end
end
if redis.call('HLEN',KEYS[1])>=1000 and redis.call('HEXISTS',KEYS[1],ARGV[1])==0 then
 return redis.error_reply('road incident hash capacity exceeded')
end
redis.call('HSET',KEYS[1],ARGV[1],cjson.encode(incoming))
redis.call('EXPIRE',KEYS[1],math.max(redis.call('TTL',KEYS[1]),tonumber(ARGV[4])))
redis.call('SET',KEYS[2],cjson.encode({key=KEYS[1],collectedAt=ARGV[3],contentHash=incoming.contentHash,conflicted=incoming.conflicted}),'EX',tonumber(ARGV[4]))
return 1
"""


def normalize(events):
    """제공자 날짜/시각을 KST로 파싱하고 유효한 원천 ID만 링크 인덱스에 쓴다."""

    def field(path):
        return F.get_json_object("payload", path)

    rows = events.filter(F.col("eventType") == "traffic.incident").select(
        "eventId",
        "source",
        "collectedAt",
        field("$.providerLinkNamespace").alias("linkNamespace"),
        field("$.providerLinkId").alias("linkId"),
        field("$.providerIncidentId").alias("providerIncidentId"),
        F.to_timestamp(
            F.concat(field("$.raw.occurredDate"), field("$.raw.occurredTime")),
            "yyyyMMddHHmmss",
        ).alias("startsAt"),
        F.to_timestamp(
            F.concat(
                field("$.raw.expectedClearDate"), field("$.raw.expectedClearTime")
            ),
            "yyyyMMddHHmmss",
        ).alias("expectedEndsAt"),
        field("$.raw.incidentTypeCode").alias("incidentTypeCode"),
        F.concat(
            F.coalesce(field("$.raw.expectedClearDate"), F.lit("")),
            F.coalesce(field("$.raw.expectedClearTime"), F.lit("")),
        ).alias("expectedRaw"),
        "payload",
    )
    valid = (
        (F.col("linkNamespace") == "SEOUL_ACCINFO_LINK")
        & F.col("linkId").rlike(r"^[0-9A-Za-z._-]{1,50}$")
        & F.col("providerIncidentId").rlike(r"^[0-9A-Za-z._-]{1,100}$")
        & F.col("startsAt").isNotNull()
        & (F.col("startsAt") <= F.col("collectedAt"))
        & (
            F.col("expectedEndsAt").isNull()
            | (F.col("expectedEndsAt") > F.col("startsAt"))
        )
    )
    valid = (
        valid
        & (F.length("source") > 0)
        & (F.length("eventId") > 0)
        & ((F.length("expectedRaw") == 0) | F.col("expectedEndsAt").isNotNull())
    )
    return rows.filter(F.coalesce(valid, F.lit(False))), rows.filter(
        ~F.coalesce(valid, F.lit(False))
    )


def write_incidents(rows, settings, max_age, now_epoch):
    """executor별 Redis 연결을 열고 링크 이동과 최신성 비교를 한 Lua에서 처리한다."""
    import json
    import hashlib

    host, port, password = (
        settings.redis_host,
        settings.redis_port,
        settings.redis_password,
    )

    def partition(items):
        import redis
        import time

        client = redis.Redis(
            host=host,
            port=port,
            password=password,
            socket_timeout=5,
            socket_connect_timeout=5,
        )
        try:
            for row in items:
                data = json.loads(row.value)
                write_epoch = int(time.time())
                ttl = int(data["expiresEpoch"] - write_epoch)
                if ttl <= 0:
                    continue
                identity = hashlib.sha256(
                    (data["source"] + ":" + data["providerIncidentId"]).encode()
                ).hexdigest()
                client.eval(
                    UPSERT,
                    2,
                    "road:incidents:" + data["linkNamespace"] + ":" + data["linkId"],
                    "road:incident-index:" + identity,
                    identity,
                    row.value,
                    data["collectedAt"],
                    ttl,
                    write_epoch,
                )
        finally:
            client.close()

    rows.foreachPartition(partition)


def main():
    """새 잡은 명시적으로 submit할 때만 실행한다. 기존 Collector나 realtime_latest 설정은 바꾸지 않는다."""
    settings = Settings.from_env()
    required = {
        name: os.environ[name]
        for name in (
            "BUS_CROSSWALK_PATH",
            "BUS_CROSSWALK_SHA256",
            "ROAD_CROSSWALK_PATH",
            "ROAD_CROSSWALK_SHA256",
            "ROAD_REJECTED_PATH",
        )
    }
    max_age = int(os.environ.get("ROAD_MAX_AGE_SECONDS", "600"))
    if not 1 <= max_age <= 3600:
        raise ValueError("road freshness out of range")
    spark = (
        SparkSession.builder.appName("ddubi-road-incidents")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .getOrCreate()
    )
    master = RoadImpactIndex(
        required["BUS_CROSSWALK_PATH"],
        required["BUS_CROSSWALK_SHA256"],
        required["ROAD_CROSSWALK_PATH"],
        required["ROAD_CROSSWALK_SHA256"],
    )
    records = [
        (
            link["namespace"],
            link["linkId"],
            path["segmentId"],
            path["validFrom"],
            path["validUntil"],
        )
        for paths in master.paths.values()
        for path in paths
        for link in path["roadLinks"]
    ]
    schema = "linkNamespace string, linkId string, segmentId string, pathFrom string, pathUntil string"
    index = spark.createDataFrame(records, schema).distinct().cache()
    index.count()

    def batch(events, batch_id):
        now = datetime.now(timezone(timedelta(hours=9)))
        epoch = int(now.timestamp())
        valid, rejected = normalize(events)
        # 원문 격리는 배치별 경로로 재시도 때 같은 내용을 덮는다. 체크포인트는 모든 sink 성공 후 진행한다.
        rejected.write.mode("overwrite").json(
            required["ROAD_REJECTED_PATH"].rstrip("/") + "/" + str(batch_id)
        )
        valid = valid.filter(
            (F.col("collectedAt").cast("long") <= epoch)
            & (F.col("collectedAt").cast("long") + max_age > epoch)
        ).cache()
        try:
            value = (
                valid.withColumn(
                    "contentHash",
                    F.sha2(
                        F.to_json(
                            F.struct(
                                "linkNamespace",
                                "linkId",
                                "providerIncidentId",
                                "startsAt",
                                "expectedEndsAt",
                                "incidentTypeCode",
                            )
                        ),
                        256,
                    ),
                )
                .drop("payload", "expectedRaw")
                .withColumn("expiresEpoch", F.col("collectedAt").cast("long") + max_age)
            )
            write_incidents(
                value.select(F.to_json(F.struct("*")).alias("value")),
                settings,
                max_age,
                epoch,
            )
            notices = (
                valid.join(index, ["linkNamespace", "linkId"])
                .filter(
                    (
                        F.col("pathFrom").cast("timestamp")
                        <= F.lit(now.isoformat()).cast("timestamp")
                    )
                    & (
                        F.col("pathUntil").cast("timestamp")
                        > F.lit(now.isoformat()).cast("timestamp")
                    )
                )
                .select(
                    "segmentId",
                    "eventId",
                    F.col("collectedAt").alias("computedAt"),
                    F.to_timestamp(
                        F.from_unixtime(F.col("collectedAt").cast("long") + max_age)
                    ).alias("validUntil"),
                    F.lit("POTENTIAL_IMPACT").alias("state"),
                    F.lit(master.master_version).alias("masterVersion"),
                )
                .distinct()
            )
            notices.select(
                F.col("segmentId").alias("key"), F.to_json(F.struct("*")).alias("value")
            ).write.format("kafka").option(
                "kafka.bootstrap.servers", settings.kafka_bootstrap_servers
            ).option(
                "topic", "road.impact"
            ).option(
                "kafka.acks", "all"
            ).save()
        finally:
            valid.unpersist()

    try:
        query = (
            read_events_stream(spark, settings, "city.raw")
            .writeStream.foreachBatch(batch)
            .option("checkpointLocation", settings.checkpoint_for("road-incidents"))
            .trigger(processingTime="30 seconds")
            .start()
        )
        query.awaitTermination()
    finally:
        index.unpersist()
        spark.stop()


if __name__ == "__main__":
    main()
