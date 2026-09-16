"""Archive Parquet -> versioned Bus Silver; raw JSON is parsed without prefilter loss."""
import argparse
from datetime import date

from pyspark.sql import SparkSession, Window, functions as F, types as T
from common.bus_normalizer import ALL_FIELDS, COMMON, POSITION, ARRIVAL, normalize_json


def prepare(archive):
    types = {"string": T.StringType(), "long": T.LongType(), "double": T.DoubleType(), "array": T.ArrayType(T.StringType())}
    schema = T.StructType([T.StructField(k, types[v], True) for k, v in ALL_FIELDS.items()])
    parsed = archive.select(
        F.col("value").alias("rawEventJson"), "kafka_partition", "kafka_offset", "kafka_timestamp",
        F.from_json(F.udf(normalize_json, T.StringType())("value"), schema).alias("bus"),
    ).select("bus.*", "rawEventJson", "kafka_partition", "kafka_offset", "kafka_timestamp")
    # Exact content repeats only: conflicting events sharing an eventId are all quarantined.
    bus = parsed.filter(F.col("status") != "IGNORED")
    counts = bus.filter(F.col("eventId").isNotNull()).groupBy("eventId").agg(F.countDistinct("rawEventJson").alias("variants"))
    checked = bus.join(counts, "eventId", "left").withColumn(
        "rejectionReasons", F.when(F.col("variants") > 1, F.array_union("rejectionReasons", F.array(F.lit("EVENT_ID_CONTENT_CONFLICT")))).otherwise(F.col("rejectionReasons")),
    ).withColumn("status", F.when(F.size("rejectionReasons") > 0, "REJECTED").otherwise(F.col("status")))
    order = Window.partitionBy("eventId", "rawEventJson").orderBy("kafka_partition", "kafka_offset", "kafka_timestamp")
    checked = checked.withColumn("repeatIndex", F.row_number().over(order))
    return parsed, checked


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="Archive transport.raw Parquet directory")
    parser.add_argument("--output", required=True, help="NEW versioned directory; existing outputs are never overwritten")
    parser.add_argument("--start", type=date.fromisoformat)
    parser.add_argument("--end", type=date.fromisoformat)
    args = parser.parse_args()
    if args.start and args.end and args.start > args.end:
        parser.error("start must not be after end")
    spark = SparkSession.builder.appName("bus_silver").getOrCreate()
    spark.conf.set("spark.sql.session.timeZone", "Asia/Seoul")
    archive = spark.read.parquet(args.input)
    if args.start:
        archive = archive.filter(F.col("date") >= args.start.isoformat())
    if args.end:
        archive = archive.filter(F.col("date") <= args.end.isoformat())
    # Reserve the root before any writes: a reused version fails immediately.
    root = args.output.rstrip("/")
    spark.createDataFrame([(args.input, str(args.start), str(args.end), "bus-silver-v1")], ["input", "start", "end", "silverVersion"]).write.mode("errorifexists").json(root + "/_run")
    parsed, checked = prepare(archive)
    parsed = parsed.cache()
    checked = checked.cache()
    try:
        unique = checked.filter("repeatIndex = 1")
        provenance = ["rawEventJson", "kafka_partition", "kafka_offset", "kafka_timestamp"]
        for kind, fields, folder in (("bus.position", POSITION, "position"), ("bus.arrival", ARRIVAL, "arrival")):
            unique.filter((F.col("status") == "ACCEPTED") & (F.col("eventType") == kind)).select(*COMMON, *fields, *provenance).write.mode("errorifexists").partitionBy("providerNamespace", "archiveDate").parquet(root + "/" + folder)
        unique.filter("status = 'REJECTED'").write.mode("errorifexists").parquet(root + "/rejected")
        checked.filter("repeatIndex > 1").write.mode("errorifexists").parquet(root + "/duplicates")
        parsed.groupBy("status", "eventType", "providerNamespace", "archiveDate").count().write.mode("errorifexists").json(root + "/quality/input-counts")
        checked.groupBy("status", "eventType", "providerNamespace", "archiveDate", "repeatIndex").count().write.mode("errorifexists").json(root + "/quality/output-counts")
        for column in ("missingFields", "qualityFlags", "rejectionReasons"):
            unique.select("eventType", "providerNamespace", "archiveDate", F.explode(column).alias("reason")).groupBy("eventType", "providerNamespace", "archiveDate", "reason").count().write.mode("errorifexists").json(root + "/quality/" + column)
        # Sampling gaps use collectedAt explicitly; they are not traversal durations.
        accepted = unique.filter("status = 'ACCEPTED'").withColumn("collectionSeconds", F.to_timestamp("collectedAt").cast("long"))
        key = Window.partitionBy("eventType", "providerNamespace", "providerRouteId", "providerStopId", "providerVehicleId").orderBy("collectionSeconds", "eventId")
        gaps = accepted.withColumn("collectionGapSeconds", F.col("collectionSeconds") - F.lag("collectionSeconds").over(key))
        gaps.groupBy("eventType", "providerNamespace", "providerRouteId", "providerStopId", "providerVehicleId").agg(
            F.count("collectionGapSeconds").alias("gapCount"),
            F.min("collectionGapSeconds").alias("minGapSeconds"),
            F.max("collectionGapSeconds").alias("maxGapSeconds"),
            F.percentile_approx("collectionGapSeconds", 0.5).alias("medianGapSeconds"),
        ).write.mode("errorifexists").json(root + "/quality/collection-intervals")
        spark.createDataFrame([(parsed.count(), unique.count(), checked.filter("repeatIndex > 1").count(), parsed.filter("status = 'IGNORED'").count())], ["inputRows", "uniqueBusAndInvalidRows", "duplicateRows", "ignoredRows"]).write.mode("errorifexists").json(root + "/manifest")
        # This marker, rather than individual Spark _SUCCESS files, signifies a complete run.
        spark.createDataFrame([("COMPLETE",)], ["status"]).write.mode("errorifexists").json(root + "/complete")
    finally:
        parsed.unpersist()
        checked.unpersist()
        spark.stop()


if __name__ == "__main__":
    main()
