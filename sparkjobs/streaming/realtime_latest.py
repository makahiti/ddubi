"""비기상 실시간 이벤트를 제공자 식별자별 최신 상태로 정규화해 Redis에 저장한다.

파이프라인 위치는 ``Collector → Kafka(transport.raw/city.raw) → 이 Spark 잡 → Redis``다.
Kafka 원문은 별도 Archiver가 Parquet/HDFS에 보존하므로 이 잡은 서비스가 지금 필요한 작은
상태만 만든다. 지원 자료는 버스 위치·도착, 지하철 위치·도착·알림, TOPIS 소통·돌발,
서울 실시간 도시데이터, S-DoT 유동인구다. 날씨는 격자·관측 구조가 달라 기존
``weather_latest.py``가 계속 담당한다.

아직 정적↔실시간 crosswalk 배포 계약이 없으므로 제공자 노선·정류장·역·링크 ID를 DDUBI
``segment_id``로 변조하지 않는다. 입력에 검증된 ``segmentIds``가 있을 때만 함께 전달하고,
그 외에는 ``canonicalMappingAvailable=false``로 표시한다. 따라서 이 결과는 수집·최신성 확인과
후속 매핑의 입력이며, 그 자체를 구간 지연 계산 완료나 ``EXACT`` 매핑으로 해석하면 안 된다.

각 이벤트는 발생시각, 수집시각, Kafka offset 순으로 최신 한 건을 선택한다. 같은 배치가
checkpoint 복구로 재실행되어도 동일한 Redis 키를 덮어써 멱등적이다. 필수 provider ID가 없거나
``unknown``인 행은 키 충돌을 피하려고 Redis에서 제외하되 Kafka/HDFS 원문에는 남고, 제외 건수는
배치 로그에 기록한다. TTL은 자료 갱신주기의 3~10배로 계약화해 수집 중단 시 오래된 값이 현재값처럼
남지 않게 한다.
"""

from __future__ import annotations

import logging
import os
import sys

from pyspark.sql import Column, DataFrame, SparkSession, Window
from pyspark.sql import functions as F

from common.kafka_source import read_events_stream
from common.redis_sink import (
    COLLECTED_TIME_COL,
    EVENT_TIME_COL,
    KEY_COL,
    TTL_COL,
    VALUE_COL,
    write_latest_with_row_ttl,
)
from common.settings import Settings

JOB_NAME = os.environ.get("SPARK_JOB_NAME", "realtime_latest")
# Spark Kafka의 subscribe 옵션은 쉼표 구분 토픽을 지원한다. 하나의 checkpoint에 두 토픽의
# partition/offset이 함께 기록되므로 잡 재시작 후에도 각 토픽을 독립적으로 이어 읽는다.
TOPICS = os.environ.get("SPARK_JOB_TOPICS", "transport.raw,city.raw")
TRIGGER = "10 seconds"

# 키 접두사와 TTL은 docs/contracts/redis-keys.yaml의 spark 소유 계약과 일치해야 한다.
# TTL은 정상 수집 간격보다 충분히 길지만 장애 데이터가 무기한 남지는 않도록 정했다.
EVENT_POLICY = {
    "bus.position": ("transit:latest:bus-position", 120),
    "bus.arrival": ("transit:latest:bus-arrival", 120),
    "subway.position": ("transit:latest:subway-position", 120),
    "subway.arrival": ("transit:latest:subway-arrival", 120),
    "traffic.flow": ("traffic:latest:flow", 900),
    "traffic.incident": ("traffic:latest:incident", 600),
    "city.realtime": ("city:latest:area", 900),
    "sdot.population": ("city:latest:sdot-population", 1800),
    "subway.alert": ("transit:latest:subway-alert", 600),
}

log = logging.getLogger(JOB_NAME)


def _json(path: str) -> Column:
    """payload JSON의 경로를 문자열로 읽는다.

    외부 제공자는 같은 의미의 숫자도 문자열 또는 JSON number로 섞어 반환한다. 여기서는 먼저
    문자열로 꺼낸 뒤 각 필드의 계약 단위에 맞춰 명시적으로 cast하여 schema inference와 조용한
    타입 변화를 피한다. cast 실패는 null이 되어 Redis 값에서 생략되고 원문은 HDFS에서 추적한다.
    """

    return F.get_json_object(F.col("payload"), path)


def _first(*paths: str) -> Column:
    """제공처 버전에 따라 이름이 달라진 동일 의미 필드 중 첫 유효값을 고른다."""

    return F.coalesce(*(_json(path) for path in paths))


def _for(event_type: str, value: Column) -> Column:
    """서로 다른 이벤트의 필드가 한 observation 객체에 섞이지 않게 범위를 제한한다."""

    return F.when(F.col("eventType") == event_type, value)


def _usable_id(value: Column) -> Column:
    """Redis 키 구성요소로 쓸 수 있는 provider ID인지 검사한다.

    Collector가 제공자 응답의 ID 누락을 ``unknown``으로 보존하는 경우가 있다. 이를 실제 ID로
    사용하면 서로 다른 차량·정류장 이벤트가 같은 키를 덮어쓰므로 최신 상태 서빙에서는 제외한다.
    """

    return value.isNotNull() & (F.trim(value) != "") & (F.lower(F.trim(value)) != "unknown")


def _provider_key(event_type: Column, route_id: Column, vehicle_id: Column, stop_id: Column,
                  line_id: Column, station_id: Column, train_id: Column, link_id: Column,
                  incident_id: Column, area_id: Column, sensor_id: Column, alert_id: Column) -> Column:
    """이벤트별 provider ID 조합으로 충돌 없는 최신값 키의 뒷부분을 만든다.

    이 값은 Redis 조회용 provider key일 뿐 canonical ``segment_id``가 아니다. 예를 들어 차량
    번호는 노선 변경 가능성이 있으므로 버스 위치는 ``노선ID:차량ID``를 함께 사용한다. 지하철
    도착도 동일 열차가 여러 역 응답에 포함될 수 있어 ``호선ID:역ID:열차ID``로 구분한다.
    """

    return (
        F.when((event_type == "bus.position") & _usable_id(route_id) & _usable_id(vehicle_id),
               F.concat_ws(":", route_id, vehicle_id))
        .when((event_type == "bus.arrival") & _usable_id(route_id) & _usable_id(stop_id),
              F.concat_ws(":", route_id, stop_id))
        .when((event_type == "subway.position") & _usable_id(line_id) & _usable_id(train_id),
              F.concat_ws(":", line_id, train_id))
        .when((event_type == "subway.arrival") & _usable_id(line_id) & _usable_id(station_id)
              & _usable_id(train_id), F.concat_ws(":", line_id, station_id, train_id))
        .when((event_type == "traffic.flow") & _usable_id(link_id), link_id)
        .when((event_type == "traffic.incident") & _usable_id(incident_id), incident_id)
        .when((event_type == "city.realtime") & _usable_id(area_id), area_id)
        .when((event_type == "sdot.population") & _usable_id(sensor_id), sensor_id)
        .when((event_type == "subway.alert") & _usable_id(alert_id), alert_id)
    )


def transform(events: DataFrame) -> DataFrame:
    """계약 봉투를 제공자별 최신 상태 후보로 정규화한다.

    입력은 ``common.kafka_source.parse_envelope``의 컬럼이다. ``payload.raw``에서 서비스에
    필요한 대표 지표만 typed column으로 승격하며, API 응답 전체는 Redis에 복제하지 않는다.
    숫자 단위는 필드명에 명시한다: 속도 km/h, 거리 m, 시간·도착예정 초, 인구 명이다.
    출력에는 유효하지 않은 provider ID 행도 남겨 ``process_batch``가 품질 건수를 기록할 수
    있게 하고, 실제 Redis 변환 직전에만 제외한다.
    """

    supported = events.filter(F.col("eventType").isin(list(EVENT_POLICY)))

    # 1. canonical ID와 혼동하지 않도록 payload의 provider ID를 종류별 독립 컬럼으로 꺼낸다.
    route_id = _json("$.providerRouteId")
    vehicle_id = _json("$.providerVehicleId")
    stop_id = _json("$.providerStopId")
    line_id = _json("$.providerLineId")
    station_id = _json("$.providerStationId")
    train_id = _json("$.providerTrainId")
    link_id = _json("$.providerLinkId")
    incident_id = _json("$.providerIncidentId")
    area_id = _json("$.providerAreaId")
    sensor_id = _json("$.providerSensorId")
    alert_id = _json("$.providerAlertId")

    # 2. API별 raw 명칭 차이를 흡수해 계산에서 바로 단위 확인 가능한 대표 관측값으로 만든다.
    #    필드가 없는 소스는 null이며 Spark JSON 직렬화 시 생략된다.
    observation = F.struct(
        _for("bus.position", _first("$.raw.sectOrd", "$.raw.SECT_ORD").cast("int")).alias("sectionOrder"),
        _for("bus.position", _first("$.raw.sectDist", "$.raw.SECT_DIST").cast("double")).alias("sectionDistanceMeters"),
        _for("bus.position", _first("$.raw.fullSectDist", "$.raw.FULL_SECT_DIST").cast("double")).alias("fullSectionDistanceMeters"),
        _for("bus.position", _first("$.raw.congetion", "$.raw.congestion", "$.raw.CONGESTION")).alias("congestionCode"),
        _for("bus.position", _first("$.raw.stopFlag", "$.raw.STOP_FLAG")).alias("stopFlag"),

        _for("bus.arrival", _first("$.raw.traTime1", "$.raw.TRA_TIME1").cast("int")).alias("firstArrivalSeconds"),
        _for("bus.arrival", _first("$.raw.traTime2", "$.raw.TRA_TIME2").cast("int")).alias("secondArrivalSeconds"),
        # sectOrd1/2는 '몇 정거장 전'이 아니라 해당 차량의 현재 노선상 구간 순번이다.
        # 정류장 수 차이는 마스터의 승차 정류장 순번과 결합한 뒤 계산해야 하므로 이름으로 과장하지 않는다.
        _for("bus.arrival", _first("$.raw.sectOrd1", "$.raw.SECT_ORD1").cast("int")).alias("firstVehicleSectionOrder"),
        _for("bus.arrival", _first("$.raw.sectOrd2", "$.raw.SECT_ORD2").cast("int")).alias("secondVehicleSectionOrder"),
        _for("bus.arrival", _first("$.raw.vehId1", "$.raw.VEH_ID1")).alias("firstVehicleId"),
        _for("bus.arrival", _first("$.raw.vehId2", "$.raw.VEH_ID2")).alias("secondVehicleId"),
        _for("bus.arrival", _first("$.raw.arrmsg1", "$.raw.ARRMSG1")).alias("firstArrivalMessage"),
        _for("bus.arrival", _first("$.raw.arrmsg2", "$.raw.ARRMSG2")).alias("secondArrivalMessage"),

        _for("subway.position", _first("$.raw.statnNm", "$.raw.STATN_NM")).alias("stationName"),
        _for("subway.position", _first("$.raw.updnLine", "$.raw.UPDN_LINE")).alias("direction"),
        _for("subway.position", _first("$.raw.statnTnm", "$.raw.STATN_TNM")).alias("destinationStation"),
        _for("subway.position", _first("$.raw.trainSttus", "$.raw.TRAIN_STTUS")).alias("trainStatusCode"),

        _for("subway.arrival", _first("$.raw.barvlDt", "$.raw.BARVL_DT").cast("int")).alias("arrivalSeconds"),
        _for("subway.arrival", _first("$.raw.arvlCd", "$.raw.ARVL_CD")).alias("arrivalStatusCode"),
        _for("subway.arrival", _first("$.raw.arvlMsg2", "$.raw.ARVL_MSG2")).alias("arrivalMessage"),
        _for("subway.arrival", _first("$.raw.arvlMsg3", "$.raw.ARVL_MSG3")).alias("previousStationMessage"),
        _for("subway.arrival", _first("$.raw.updnLine", "$.raw.UPDN_LINE")).alias("arrivalDirection"),
        _for("subway.arrival", _first("$.raw.bstatnNm", "$.raw.BSTATN_NM")).alias("arrivalDestination"),

        _for("traffic.flow", _first("$.raw.SPD", "$.raw.speed", "$.raw.SPEED").cast("double")).alias("speedKph"),
        _for("traffic.flow", _first("$.raw.TRV_TIME", "$.raw.travelTime").cast("int")).alias("travelTimeSeconds"),
        _for("traffic.flow", _first("$.raw.IDX", "$.raw.trafficStatus")).alias("trafficStatusCode"),

        _for("traffic.incident", _first("$.raw.ACC_TYPE", "$.raw.INCIDENT_TYPE", "$.raw.CODE")).alias("incidentType"),
        _for("traffic.incident", _first("$.raw.ACC_INFO", "$.raw.INCIDENT_MSG", "$.raw.MESSAGE")).alias("incidentMessage"),
        _for("traffic.incident", _first("$.raw.ROAD_NM", "$.raw.ROAD_NAME")).alias("roadName"),
        _for("traffic.incident", _first("$.raw.EXP_CLR_DATE", "$.raw.END_DATE")).alias("expectedClearAt"),

        # CITYDATA는 제공 시점에 따라 객체 또는 1건 배열이다. 두 구조와 하위 단건/배열을 모두
        # 허용하되, 값의 의미와 단위가 같은 공식 필드만 하나의 출력으로 합친다.
        _for("city.realtime", _first(
            "$.raw.LIVE_PPLTN_STTS.AREA_CONGEST_LVL",
            "$.raw.LIVE_PPLTN_STTS[0].AREA_CONGEST_LVL",
            "$.raw[0].LIVE_PPLTN_STTS.AREA_CONGEST_LVL",
            "$.raw[0].LIVE_PPLTN_STTS[0].AREA_CONGEST_LVL",
        )).alias("areaCongestionLevel"),
        _for("city.realtime", _first(
            "$.raw.LIVE_PPLTN_STTS.AREA_PPLTN_MIN",
            "$.raw.LIVE_PPLTN_STTS[0].AREA_PPLTN_MIN",
            "$.raw[0].LIVE_PPLTN_STTS.AREA_PPLTN_MIN",
            "$.raw[0].LIVE_PPLTN_STTS[0].AREA_PPLTN_MIN",
        ).cast("long")).alias("areaPopulationMin"),
        _for("city.realtime", _first(
            "$.raw.LIVE_PPLTN_STTS.AREA_PPLTN_MAX",
            "$.raw.LIVE_PPLTN_STTS[0].AREA_PPLTN_MAX",
            "$.raw[0].LIVE_PPLTN_STTS.AREA_PPLTN_MAX",
            "$.raw[0].LIVE_PPLTN_STTS[0].AREA_PPLTN_MAX",
        ).cast("long")).alias("areaPopulationMax"),
        _for("city.realtime", _first(
            "$.raw.ROAD_TRAFFIC_STTS.AVG_ROAD_DATA_SPEED",
            "$.raw.ROAD_TRAFFIC_STTS[0].AVG_ROAD_DATA_SPEED",
            "$.raw[0].ROAD_TRAFFIC_STTS.AVG_ROAD_DATA_SPEED",
            "$.raw[0].ROAD_TRAFFIC_STTS[0].AVG_ROAD_DATA_SPEED",
        ).cast("double")).alias("areaRoadSpeedKph"),
        _for("city.realtime", _first(
            "$.raw.WEATHER_STTS.TEMP", "$.raw.WEATHER_STTS[0].TEMP",
            "$.raw[0].WEATHER_STTS.TEMP", "$.raw[0].WEATHER_STTS[0].TEMP",
        ).cast("double")).alias("areaTemperatureCelsius"),
        _for("city.realtime", _first(
            "$.raw.WEATHER_STTS.PCP_MSG", "$.raw.WEATHER_STTS[0].PCP_MSG",
            "$.raw[0].WEATHER_STTS.PCP_MSG", "$.raw[0].WEATHER_STTS[0].PCP_MSG",
        )).alias("areaPrecipitationMessage"),

        _for("sdot.population", _first("$.raw.VISITOR_COUNT", "$.raw.VISITOR_CNT", "$.raw.PEOPLE").cast("long")).alias("visitorCount"),
        _for("sdot.population", _first("$.raw.ADMINISTRATIVE_DISTRICT", "$.raw.GU_NM", "$.raw.GU_NAME")).alias("sensorDistrict"),

        _for("subway.alert", _first("$.raw.title", "$.raw.ntcTtl", "$.raw.TITLE")).alias("alertTitle"),
        _for("subway.alert", _first("$.raw.content", "$.raw.ntcCn", "$.raw.MESSAGE")).alias("alertContent"),
        _for("subway.alert", _first("$.raw.lineNm", "$.raw.subwayNm", "$.raw.LINE_NM")).alias("alertLineName"),
        _for("subway.alert", _json("$.activeUntil")).alias("activeUntil"),
    )

    # 3. Redis의 provider key와 값에 사용할 공통 메타데이터를 결합한다. segmentIds는 upstream
    #    Resolver가 실제로 검증해 넣은 경우에만 전달하고, 비어 있으면 매핑 완료로 표현하지 않는다.
    provider_key = _provider_key(
        F.col("eventType"), route_id, vehicle_id, stop_id, line_id, station_id, train_id,
        link_id, incident_id, area_id, sensor_id, alert_id,
    )
    return supported.select(
        "eventId", "eventType", "source", "occurredAt", "collectedAt", "latitude", "longitude",
        "segmentIds", "kafkaPartition", "kafkaOffset",
        provider_key.alias("providerKey"),
        F.struct(
            route_id.alias("routeId"), vehicle_id.alias("vehicleId"), stop_id.alias("stopId"),
            line_id.alias("lineId"), station_id.alias("stationId"), train_id.alias("trainId"),
            link_id.alias("linkId"), incident_id.alias("incidentId"), area_id.alias("areaId"),
            sensor_id.alias("sensorId"), alert_id.alias("alertId"),
        ).alias("providerIds"),
        observation.alias("observation"),
    )


def latest_per_provider(df: DataFrame) -> DataFrame:
    """마이크로배치 안에서 이벤트 종류와 provider key별 최신 한 건을 선택한다.

    event time을 첫 기준으로 써 늦게 도착한 과거 관측이 최신값을 되돌리지 않게 한다. 같은
    event time이면 수집시각, Kafka offset 순으로 결정해 Replay에서도 선택 결과를 고정한다.
    ID가 없는 행은 이 단계에서 제외되며 호출자는 전후 건수 차이를 품질 지표로 기록한다.
    """

    order = Window.partitionBy("eventType", "providerKey").orderBy(
        F.col("occurredAt").desc_nulls_last(),
        F.col("collectedAt").desc_nulls_last(),
        F.col("kafkaPartition").desc(),
        F.col("kafkaOffset").desc(),
    )
    return (
        # 시각을 파싱할 수 없는 행은 최신 여부를 증명할 수 없다. Redis에는 쓰지 않되 원문은
        # HDFS에 남고 process_batch의 invalidTimestamp 건수로 운영자가 확인한다.
        df.filter(
            F.col("providerKey").isNotNull()
            & F.col("occurredAt").isNotNull()
            & F.col("collectedAt").isNotNull()
        )
        .withColumn("rowNumber", F.row_number().over(order))
        .filter(F.col("rowNumber") == 1)
        .drop("rowNumber")
    )


def to_redis_rows(df: DataFrame) -> DataFrame:
    """정규화 결과를 ``redisKey``, JSON ``redisValue``, 행별 TTL로 변환한다.

    Redis 값에는 원천 raw 객체를 넣지 않아 384MB 메모리 한도를 보호한다. 대신 API 변경이나
    누락 분석이 필요하면 ``eventId``로 HDFS 원문을 찾을 수 있다. ``canonicalMappingAvailable``은
    segmentIds의 실제 존재 여부만 나타내며 Backend 매핑 등급 ``EXACT``를 추측하지 않는다.
    """

    # Python UDF 대신 Spark map lookup을 사용해 Catalyst가 전체 변환을 최적화할 수 있게 한다.
    # transform에서 지원 eventType만 남기므로 lookup 실패는 계약/코드 불일치를 뜻하며 null TTL로
    # 쓰기를 실패시켜야 한다. 조용히 임의 기본 TTL을 사용하지 않는다.
    prefix_entries = []
    ttl_entries = []
    for event_type, (event_prefix, ttl_seconds) in EVENT_POLICY.items():
        prefix_entries.extend((F.lit(event_type), F.lit(event_prefix)))
        ttl_entries.extend((F.lit(event_type), F.lit(ttl_seconds)))
    prefix = F.element_at(F.create_map(*prefix_entries), F.col("eventType"))
    ttl = F.element_at(F.create_map(*ttl_entries), F.col("eventType"))

    return df.select(
        F.concat_ws(":", prefix, F.col("providerKey")).alias(KEY_COL),
        F.to_json(
            F.struct(
                "eventType", "providerKey", "providerIds", "observation",
                F.struct("latitude", "longitude").alias("location"),
                "segmentIds",
                (F.size(F.coalesce(F.col("segmentIds"), F.array())) > 0).alias("canonicalMappingAvailable"),
                "occurredAt", "collectedAt", "eventId", "source",
            )
        ).alias(VALUE_COL),
        ttl.cast("int").alias(TTL_COL),
        # Redis Lua 비교가 문자열 정렬만으로도 시간순과 일치하도록 KST·밀리초 형식을 고정한다.
        # 모든 Collector 시각 계약이 Asia/Seoul이므로 서로 다른 offset을 섞어 비교하지 않는다.
        F.date_format("occurredAt", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX").alias(EVENT_TIME_COL),
        F.date_format("collectedAt", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX").alias(COLLECTED_TIME_COL),
    )


def main() -> int:
    """두 원본 토픽을 지속 소비하고 배치별 최신 상태를 Redis에 멱등 저장한다."""

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        stream=sys.stdout,
    )
    settings = Settings.from_env()
    spark = (
        SparkSession.builder.appName(f"ddubi-{JOB_NAME}")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .getOrCreate()
    )
    spark.sparkContext.setLogLevel("WARN")

    def process_batch(batch_df: DataFrame, batch_id: int) -> None:
        normalized = transform(batch_df).cache()
        latest = None
        rows = None
        try:
            supported_count = normalized.count()
            valid_count = normalized.filter(F.col("providerKey").isNotNull()).count()
            valid_timestamp_count = normalized.filter(
                F.col("occurredAt").isNotNull() & F.col("collectedAt").isNotNull()
            ).count()
            latest = latest_per_provider(normalized).cache()
            rows = to_redis_rows(latest).cache()
            output_count = rows.count()
            if output_count:
                write_latest_with_row_ttl(
                    rows,
                    settings.redis_host,
                    settings.redis_port,
                    settings.redis_password,
                )
            log.info(
                "batch=%d supported=%d validIdentity=%d invalidIdentity=%d "
                "validTimestamp=%d invalidTimestamp=%d redisKeys=%d",
                batch_id,
                supported_count,
                valid_count,
                supported_count - valid_count,
                valid_timestamp_count,
                supported_count - valid_timestamp_count,
                output_count,
            )
        finally:
            if rows is not None:
                rows.unpersist()
            if latest is not None:
                latest.unpersist()
            normalized.unpersist()

    events = read_events_stream(spark, settings, TOPICS)
    query = (
        events.writeStream.foreachBatch(process_batch)
        .option("checkpointLocation", settings.checkpoint_for(JOB_NAME))
        .trigger(processingTime=TRIGGER)
        .queryName(JOB_NAME)
        .start()
    )
    log.info(
        "started topics=%s checkpoint=%s redis=%s:%s",
        TOPICS,
        settings.checkpoint_for(JOB_NAME),
        settings.redis_host,
        settings.redis_port,
    )
    query.awaitTermination()
    return 0


if __name__ == "__main__":
    sys.exit(main())
