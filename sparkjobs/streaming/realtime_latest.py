"""비기상 실시간 이벤트를 정규화한다. 버스 Silver는 HDFS와 Redis 양쪽에 저장한다.

파이프라인 위치는 ``Collector → Kafka(transport.raw/city.raw) → 이 Spark 잡 → Redis``다.
Kafka 원문은 별도 Archiver가 Parquet/HDFS에 보존하므로 이 잡은 서비스가 지금 필요한 작은
상태만 만든다. 지원 자료는 버스 위치·도착, 지하철 위치·도착·알림, TOPIS 소통·돌발,
서울 실시간 도시데이터, S-DoT 유동인구다. 날씨는 격자·관측 구조가 달라 기존
``weather_latest.py``가 계속 담당한다.

BUS는 버전 고정 대응표로 검증하고 Collector와 버전이 다르면 ID를 비운다. 그 외 제공자
노선·역·링크 ID를 ``segment_id``로 추측하지 않으며 검증된 입력만 함께 전달하고,
그 외에는 ``canonicalMappingAvailable=false``로 표시한다. 따라서 이 결과는 수집·최신성 확인과
후속 매핑의 입력이며, 그 자체를 구간 지연 계산 완료나 ``EXACT`` 매핑으로 해석하면 안 된다.

각 이벤트는 발생시각, 수집시각, Kafka offset 순으로 최신 한 건을 선택한다. 같은 배치가
checkpoint 복구로 재실행되어도 동일한 Redis 키를 덮어써 멱등적이다. 필수 provider ID가 없거나
``unknown``인 행은 키 충돌을 피하려고 Redis에서 제외하되 Kafka/HDFS 원문에는 남고, 제외 건수는
배치 로그에 기록한다. TTL은 보관 기간이고 validUntil은 관측의 신선도 한계다.
예산상 긴 수집 주기 때문에 보관 기간을 늘려도 오래된 관측이 현재값으로 바뀌지는 않는다.
"""

from __future__ import annotations

import logging
import os
import sys

from pyspark.sql import Column, DataFrame, SparkSession, Window
from pyspark.sql import functions as F

from common.kafka_source import read_events_stream
from common.bus_crosswalk import load_crosswalk, apply_crosswalk
from common.redis_sink import (
    COLLECTED_TIME_COL,
    EVENT_TIME_COL,
    KEY_COL,
    TTL_COL,
    VALUE_COL,
    write_latest_with_row_ttl,
)
from common.settings import Settings
from common.bus_stream_sink import output_uri, prepare_live, save_batch

JOB_NAME = os.environ.get("SPARK_JOB_NAME", "realtime_latest")
# Spark Kafka의 subscribe 옵션은 쉼표 구분 토픽을 지원한다. 하나의 checkpoint에 두 토픽의
# partition/offset이 함께 기록되므로 잡 재시작 후에도 각 토픽을 독립적으로 이어 읽는다.
TOPICS = os.environ.get("SPARK_JOB_TOPICS", "transport.raw,city.raw")
TRIGGER = "10 seconds"

# 키 접두사와 TTL은 docs/contracts/redis-keys.yaml의 spark 소유 계약과 일치해야 한다.
# 수집 주기의 세 배를 보관한다. 신선도는 아래 별도 정책을 사용한다.
EVENT_POLICY = {
    "bus.position": ("transit:latest:bus-position", 86400),
    "bus.arrival": ("transit:latest:bus-arrival", 86400),
    "subway.position": ("transit:latest:subway-position", 10800),
    "subway.arrival": ("transit:latest:subway-arrival", 1080),
    "traffic.flow": ("traffic:latest:flow", 900),
    "traffic.incident": ("traffic:latest:incident", 2700),
    "city.realtime": ("city:latest:area", 64800),
    "sdot.population": ("city:latest:sdot-population", 1800),
    "subway.alert": ("transit:latest:subway-alert", 2700),
    "topis.notice": ("traffic:latest:topis-notice", 2700),
}
# 관측을 "현재"로 사용할 수 있는 기존 상한을 보존한다. 긴 TTL과 혼동하지 않는다.
# 보관 중이어도 이 시간 이후 Backend/후속 연산은 unknown 또는 기준선 fallback을 사용한다.
FRESHNESS_SECONDS = {
    "bus.position": 120, "bus.arrival": 120, "subway.position": 120,
    "subway.arrival": 120, "traffic.flow": 900, "traffic.incident": 600,
    "city.realtime": 900, "sdot.population": 1800, "subway.alert": 600, "topis.notice": 1800,
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
                  incident_id: Column, area_id: Column, sensor_id: Column, alert_id: Column,
                  notice_id: Column) -> Column:
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
        .when((event_type == "topis.notice") & _usable_id(notice_id), notice_id)
    )


def transform(events: DataFrame, crosswalk=None) -> DataFrame:
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
    namespace = F.coalesce(_json("$.providerNamespace"),
        F.when(F.col("source").isin("seoul.bus_location", "seoul.bus_arrival"),F.lit("SEOUL_BUS")))
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
    notice_id = _json("$.providerNoticeId")

    # 2. API별 raw 명칭 차이를 흡수해 계산에서 바로 단위 확인 가능한 대표 관측값으로 만든다.
    #    필드가 없는 소스는 null이며 Spark JSON 직렬화 시 생략된다.
    # 지역별 공식 단위를 명시적으로 구분한다. provider 필드명이 같은 숫자라도 서울 basis를 붙이지 않는다.
    def arrival_seconds(index):
        return (F.when(namespace == "GBIS", F.coalesce(_json("$.raw.predictTimeSec"+str(index)).cast("int"),
                        _json("$.raw.predictTime"+str(index)).cast("int")*60))
                .when((namespace == "INCHEON_API") & F.lit(index == 1), _json("$.raw.ARRIVALESTIMATETIME").cast("int"))
                .when(namespace == "SEOUL_BUS", _first("$.raw.exps"+str(index),"$.raw.EXPS"+str(index)).cast("int")))
    basis=(F.when(namespace == "GBIS", "GBIS_PREDICT_SECONDS_V1")
           .when(namespace == "INCHEON_API", "INCHEON_ESTIMATE_SECONDS_V1")
           .when(namespace == "SEOUL_BUS", "SEOUL_EXPS_SECONDS_V1"))
    observation = F.struct(
        _for("bus.position", _first("$.raw.stationSeq", "$.raw.LATEST_STOPSEQ").cast("int")).alias("providerStopOrder"),
        _for("bus.position", _first("$.raw.stateCd", "$.raw.DIRCD")).alias("providerStateCode"),
        _for("bus.arrival", _first("$.raw.locationNo1", "$.raw.REST_STOP_COUNT").cast("int")).alias("firstRemainingStops"),
        _for("bus.position", _first("$.raw.sectOrd", "$.raw.SECT_ORD").cast("int")).alias("sectionOrder"),
        _for("bus.position", _first("$.raw.sectDist", "$.raw.SECT_DIST").cast("double")).alias("sectionDistanceMeters"),
        _for("bus.position", _first("$.raw.fullSectDist", "$.raw.FULL_SECT_DIST").cast("double")).alias("fullSectionDistanceMeters"),
        _for("bus.position", _first("$.raw.congetion", "$.raw.congestion", "$.raw.CONGESTION")).alias("congestionCode"),
        _for("bus.position", _first("$.raw.stopFlag", "$.raw.STOP_FLAG")).alias("stopFlag"),

        # 공식 traTime1/2는 여행시간(분)이다. 도착 대기는 지수평활 도착예측 exps1/2(초)를 사용한다.
        # basis를 함께 기록해 예전 버전의 같은 이름 필드를 Backend가 초로 오독하지 못하게 한다.
        _for("bus.arrival", arrival_seconds(1)).alias("firstArrivalSeconds"),
        _for("bus.arrival", arrival_seconds(2)).alias("secondArrivalSeconds"),
        _for("bus.arrival", basis).alias("arrivalTimeBasis"),
        _for("bus.arrival", _first("$.raw.traTime1", "$.raw.TRA_TIME1").cast("double")).alias("firstTravelTimeMinutes"),
        _for("bus.arrival", _first("$.raw.traTime2", "$.raw.TRA_TIME2").cast("double")).alias("secondTravelTimeMinutes"),
        _for("bus.arrival", _first("$.raw.staOrd", "$.raw.STA_ORD", "$.raw.staOrder").cast("int")).alias("stopOrder"),
        _for("bus.arrival", _first("$.raw.deTourAt", "$.raw.DE_TOUR_AT")).alias("detourCode"),
        _for("bus.arrival", _first("$.raw.isArrive1", "$.raw.IS_ARRIVE1")).alias("firstTerminalArrivalCode"),
        _for("bus.arrival", _first("$.raw.isArrive2", "$.raw.IS_ARRIVE2")).alias("secondTerminalArrivalCode"),
        _for("bus.arrival", _first("$.raw.full1", "$.raw.FULL1")).alias("firstFullCode"),
        _for("bus.arrival", _first("$.raw.full2", "$.raw.FULL2")).alias("secondFullCode"),
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
        # 열차번호만 같아도 방향/급행/운행일이 다를 수 있다. Backend 시간표 연결은 이 원문과
        # 생성시각을 함께 검증한다. 누락된 원본 시각을 수집시각으로 보완해 실시간으로 표시하지 않는다.
        _for("subway.arrival", _first("$.raw.btrainStt", "$.raw.BTRAIN_STT")).alias("arrivalTrainType"),
        F.when(F.col("eventType").isin("subway.arrival", "subway.position"),
               _first("$.raw.recptnDt", "$.raw.RECPTN_DT"))
         .when(F.col("eventType") == "bus.arrival", _first("$.raw.mkTm", "$.raw.MK_TM")).alias("sourceGeneratedAt"),

        _for("traffic.flow", _first("$.raw.SPD", "$.raw.speed", "$.raw.SPEED").cast("double")).alias("speedKph"),
        _for("traffic.flow", _first("$.raw.TRV_TIME", "$.raw.travelTime").cast("int")).alias("travelTimeSeconds"),
        _for("traffic.flow", _first("$.raw.IDX", "$.raw.trafficStatus")).alias("trafficStatusCode"),

        _for("traffic.incident", _first("$.raw.ACC_TYPE", "$.raw.incidentTypeCode", "$.raw.INCIDENT_TYPE", "$.raw.CODE")).alias("incidentType"),
        _for("traffic.incident", _first("$.raw.ACC_INFO", "$.raw.description", "$.raw.INCIDENT_MSG", "$.raw.MESSAGE")).alias("incidentMessage"),
        _for("traffic.incident", _first("$.raw.ROAD_NM", "$.raw.ROAD_NAME")).alias("roadName"),
        _for("traffic.incident", _first("$.raw.EXP_CLR_DATE", "$.raw.expectedClearDate", "$.raw.END_DATE")).alias("expectedClearAt"),

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

        _for("subway.alert", _first("$.raw.title", "$.raw.noftTtl", "$.raw.ntcTtl", "$.raw.TITLE")).alias("alertTitle"),
        _for("subway.alert", _first("$.raw.content", "$.raw.noftCn", "$.raw.ntcCn", "$.raw.MESSAGE")).alias("alertContent"),
        _for("subway.alert", _first("$.raw.lineNm", "$.raw.lineNmLst", "$.raw.subwayNm", "$.raw.LINE_NM")).alias("alertLineName"),
        _for("subway.alert", _json("$.activeUntil")).alias("activeUntil"),

        _for("topis.notice", _json("$.raw.title")).alias("noticeTitle"),
        _for("topis.notice", _json("$.raw.contentText")).alias("noticeContent"),
        _for("topis.notice", _json("$.raw.categoryName")).alias("noticeCategory"),
    )

    # 3. Redis provider key·출처를 보존하고 마지막에 BUS ID를 고정 대응표로 다시 검증한다.
    #    upstream의 segmentIds만으로 성공 처리하지 않으며 다른 모드의 기존 ID는 유지한다.
    provider_key = _provider_key(
        F.col("eventType"), route_id, vehicle_id, stop_id, line_id, station_id, train_id,
        link_id, incident_id, area_id, sensor_id, alert_id, notice_id,
    )
    # 서울의 기존 provider 키는 유지하고 다른 출처는 별도 공간으로 분리한다.
    is_bus=F.col("eventType").isin("bus.position","bus.arrival")
    provider_key=F.when(is_bus,
        F.when(namespace=="SEOUL_BUS",provider_key)
         .when(namespace.isin("GBIS","INCHEON_PUBLIC_CSV","INCHEON_API") & provider_key.isNotNull(),F.concat_ws(":",namespace,provider_key)))        .otherwise(provider_key)
    # 인천은 차량별 도착 행이다. 노선·정류소만 키로 쓰면 다른 차량의 예측이 사라진다.
    provider_key=F.when((namespace=="INCHEON_API") & (F.col("eventType")=="bus.arrival"),
        F.when(_usable_id(vehicle_id) & provider_key.isNotNull(),F.concat_ws(":",provider_key,vehicle_id))).otherwise(provider_key)
    normalized = supported.select(
        "eventId", "eventType", "source", "occurredAt", "collectedAt", "latitude", "longitude",
        "segmentIds", "kafkaPartition", "kafkaOffset",
        provider_key.alias("providerKey"),
        _json("$.providerFromStopId").alias("mappingFromStopId"),
        _json("$.providerToStopId").alias("mappingToStopId"),
        _json("$.canonicalMapping.masterVersion").alias("claimedMappingVersion"),
        _json("$.canonicalMapping.crosswalkSha256").alias("claimedCrosswalkSha"),
        F.struct(
            namespace.alias("namespace"),route_id.alias("routeId"), vehicle_id.alias("vehicleId"), stop_id.alias("stopId"),
            line_id.alias("lineId"), station_id.alias("stationId"), train_id.alias("trainId"),
            link_id.alias("linkId"), incident_id.alias("incidentId"), area_id.alias("areaId"),
            sensor_id.alias("sensorId"), alert_id.alias("alertId"), notice_id.alias("noticeId"),
        ).alias("providerIds"),
        observation.alias("observation"),
    )
    return apply_crosswalk(normalized,crosswalk)


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
    freshness_entries = []
    for event_type, seconds in FRESHNESS_SECONDS.items():
        freshness_entries.extend((F.lit(event_type), F.lit(seconds)))
    freshness = F.element_at(F.create_map(*freshness_entries), F.col("eventType"))
    # event time 기준 유효시각은 Replay 시각으로 다시 계산하지 않는다.
    valid_until = (F.col("occurredAt").cast("long") + freshness).cast("timestamp")

    return df.select(
        F.concat_ws(":", prefix, F.col("providerKey")).alias(KEY_COL),
        F.to_json(
            F.struct(
                "eventType", "providerKey", "providerIds", "observation",
                F.struct("latitude", "longitude").alias("location"),
                "segmentIds", "canonicalMapping",
                (F.size(F.coalesce(F.col("segmentIds"), F.array())) > 0).alias("canonicalMappingAvailable"),
                "occurredAt", "collectedAt", "eventId", "source",
                valid_until.alias("validUntil"), freshness.alias("freshnessSeconds"),
                ttl.alias("retentionSeconds"),
            )
        ).alias(VALUE_COL),
        ttl.cast("int").alias(TTL_COL),
        # Redis Lua 비교가 문자열 정렬만으로도 시간순과 일치하도록 KST·밀리초 형식을 고정한다.
        # 모든 Collector 시각 계약이 Asia/Seoul이므로 서로 다른 offset을 섞어 비교하지 않는다.
        F.date_format("occurredAt", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX").alias(EVENT_TIME_COL),
        F.date_format("collectedAt", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX").alias(COLLECTED_TIME_COL),
    )


def process_batch(batch_df: DataFrame, batch_id: int, settings: Settings, crosswalk=None, bus_output=None) -> None:
    """운영과 반복 부하 검증이 공유하는 한 배치 처리다.

    정적 crosswalk는 호출자가 전체 query 수명 동안 소유한다. 이 함수는 배치별 캐시만
    소유하고 Redis 쓰기 실패를 상위로 전파해 checkpoint가 실패 배치를 건너뛰지 않게 한다.
    """
    if bus_output is not None:
        bus_events = batch_df.filter(F.col("eventType").isin("bus.position", "bus.arrival"))
        if bus_events.limit(1).count():
            silver = prepare_live(bus_events, transform(bus_events, crosswalk)).cache()
            try:
                count = save_batch(silver, batch_id, bus_output, settings)
                log.info("batch=%d busSilverRows=%d busRedisKeys=%d busSilver=%s", batch_id, silver.count(), count, bus_output)
            finally:
                silver.unpersist()
        batch_df = batch_df.filter(~F.col("eventType").isin("bus.position", "bus.arrival"))
    normalized = transform(batch_df,crosswalk).cache()
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


def main() -> int:
    """두 원본 토픽을 지속 소비하고 배치별 최신 상태를 Redis에 멱등 저장한다."""

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        stream=sys.stdout,
    )
    settings = Settings.from_env()
    bus_output = output_uri(os.environ)
    spark = (
        SparkSession.builder.appName(f"ddubi-{JOB_NAME}")
        .config("spark.sql.session.timeZone", "Asia/Seoul")
        .getOrCreate()
    )
    spark.sparkContext.setLogLevel("WARN")

    crosswalk_path=os.environ.get("BUS_CROSSWALK_PATH")
    crosswalk=load_crosswalk(spark,crosswalk_path,os.environ.get("BUS_CROSSWALK_SHA256")) if crosswalk_path else None


    try:
        events = read_events_stream(spark, settings, TOPICS)
        query = (
            events.writeStream.foreachBatch(lambda frame, batch_id: process_batch(frame, batch_id, settings, crosswalk, bus_output))
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
    finally:
        # 정적 인덱스는 배치 종료마다 해제하지 않는다. 전체 query가 끝난 뒤에만 반환한다.
        if crosswalk is not None:
            crosswalk[0].unpersist()
    return 0


if __name__ == "__main__":
    sys.exit(main())
