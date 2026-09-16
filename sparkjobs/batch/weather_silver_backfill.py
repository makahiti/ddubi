"""Archived city.raw -> versioned Weather Silver backfill, without Redis/Kafka writes."""
import argparse
from datetime import date
from pyspark.sql import SparkSession, functions as F
from common.kafka_source import parse_envelope
from common.weather_normalizer import EVENT_TYPE, normalize_weather, to_weather_silver_batch


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="Archive city.raw Parquet root")
    parser.add_argument("--output", required=True, help="New versioned root outside streaming Silver directory")
    parser.add_argument("--start", type=date.fromisoformat, required=True)
    parser.add_argument("--end", type=date.fromisoformat, required=True)
    args = parser.parse_args()
    if args.start > args.end:
        parser.error("start must not be after end")
    # File sink metadata will not track files appended by a separate batch.
    if "weather-backfill/" not in args.output or "/weather/" in args.output:
        parser.error("output must use a weather-backfill/<new version> root, outside the live weather file sink")
    spark = SparkSession.builder.appName("weather_silver_backfill").config("spark.sql.session.timeZone", "Asia/Seoul").getOrCreate()
    archive = events = normalized = silver = None
    try:
        root = args.output.rstrip("/")
        spark.createDataFrame([(args.input, args.start.isoformat(), args.end.isoformat())], ["input", "start", "end"]).write.mode("errorifexists").json(root + "/_run")
        archive = spark.read.parquet(args.input).filter((F.col("date") >= args.start.isoformat()) & (F.col("date") <= args.end.isoformat())).cache()
        events = parse_envelope(archive.select(F.col("kafka_key").alias("key"), F.col("value"), F.col("kafka_partition").alias("partition"), F.col("kafka_offset").alias("offset"), F.col("kafka_timestamp").alias("timestamp"))).cache()
        weather = events.filter(F.col("eventType") == EVENT_TYPE)
        normalized = normalize_weather(weather).cache()
        silver = to_weather_silver_batch(normalized).cache()
        archive_count, parsed_count, weather_count, valid_count, output_count = archive.count(), events.count(), weather.count(), normalized.count(), silver.count()
        if output_count == 0:
            raise RuntimeError("No valid weather rows: archive=%d parsed=%d weather=%d valid=%d; verify date range, event type, payload and timestamps" % (archive_count, parsed_count, weather_count, valid_count))
        silver.write.mode("errorifexists").partitionBy("silverVersion", "eventDate", "eventHour").parquet(root + "/data")
        silver.select(F.explode("missingFields").alias("field")).groupBy("field").count().write.mode("errorifexists").json(root + "/missing-fields")
        spark.createDataFrame([(archive_count, archive_count - parsed_count, parsed_count - weather_count, weather_count - valid_count, valid_count - output_count, output_count)], ["archiveRows", "unparsedEnvelopeRows", "nonWeatherRows", "invalidWeatherRows", "duplicateWeatherRows", "outputRows"]).write.mode("errorifexists").json(root + "/manifest")
        spark.createDataFrame([("COMPLETE",)], ["status"]).write.mode("errorifexists").json(root + "/complete")
    finally:
        for frame in (silver, normalized, events, archive):
            if frame is not None:
                frame.unpersist()
        spark.stop()


if __name__ == "__main__":
    main()
