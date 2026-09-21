"""Bus Silver v1: loss-aware normalization; no crosswalk, interpolation or labels.

Pure Python core is shared by local review exports and the Spark archive batch.
Times are ISO strings in KST; source codes remain strings, not invented meanings.
"""

import json
import math
import re
from datetime import datetime, timedelta, timezone

VERSION = "bus-silver-v1"
KST = timezone(timedelta(hours=9))
COMMON = {
    "silverVersion": "string",
    "eventId": "string",
    "eventType": "string",
    "source": "string",
    "providerNamespace": "string",
    "providerRouteId": "string",
    "providerStopId": "string",
    "providerVehicleId": "string",
    "observedAt": "string",
    "collectedAt": "string",
    "archiveDate": "string",
    "timestampBasis": "string",
    "claimedSegmentIds": "array",
    "claimedMappingJson": "string",
    "rawPayloadJson": "string",
    "missingFields": "array",
    "qualityFlags": "array",
    "rejectionReasons": "array",
    "status": "string",
}
POSITION = {
    "longitude": "double",
    "latitude": "double",
    "providerStopOrder": "long",
    "sectionOrder": "long",
    "sectionDistanceMeters": "double",
    "fullSectionDistanceMeters": "double",
    "congestionCode": "string",
    "providerStateCode": "string",
    "directionCode": "string",
    "stopFlag": "string",
}
ARRIVAL = {"arrivalTimeBasis": "string", "sourceGeneratedAt": "string", "stopOrder": "long", "detourCode": "string"}
for _prefix in ("first", "second"):
    ARRIVAL.update(
        {
            _prefix + "VehicleId": "string",
            _prefix + "ArrivalSeconds": "long",
            _prefix + "TravelTimeMinutes": "double",
            _prefix + "RemainingStops": "long",
            _prefix + "VehicleSectionOrder": "long",
            _prefix + "FullCode": "string",
            _prefix + "TerminalArrivalCode": "string",
            _prefix + "ArrivalMessage": "string",
        }
    )
ALL_FIELDS = dict(COMMON, **POSITION, **ARRIVAL)
SOURCE_NAMESPACE = {
    "seoul.bus_location": "SEOUL_BUS",
    "seoul.bus_arrival": "SEOUL_BUS",
    "gbis.location": "GBIS",
    "gbis.arrival": "GBIS",
    "incheon.location": "INCHEON_API",
    "incheon.arrival": "INCHEON_API",
}


def text(value, identifier=False):
    if value is None or isinstance(value, (dict, list, bool)):
        return None
    value = str(value).strip()
    if value.lower() in ("", "unknown", "null", "none"):
        return None
    if identifier and value == "0":
        return None
    return value


# 초의 소수점 자리. 앞이 ":SS" 일 때만 잡아 시간대(+09:00)의 콜론과 섞이지 않게 한다.
_SECOND_FRACTION = re.compile(r"(?<=:\d\d)\.(\d{1,9})")


def _pad_fraction(value):
    """초의 소수점 자릿수를 6자리로 맞춘다. 값 자체는 바꾸지 않는다.

    파이썬 3.11 미만의 ``datetime.fromisoformat`` 은 소수점이 정확히 3자리나 6자리일 때만
    읽는다. 서울시 버스 도착 원문의 ``mkTm`` 은 ``2026-09-18 10:02:03.0`` 처럼 한 자리로 온다.
    Spark 컨테이너가 파이썬 3.8 이라 이 값이 통째로 버려졌고, 그 결과 Redis 의 버스 도착값에
    ``observedAt`` 이 비어 백엔드가 버스 실시간을 100% 버리고 있었다(2026-09-18 확인).

    개발 기기의 파이썬은 3.11 이상이라 로컬에서는 멀쩡히 읽혔다. 그래서 오래 드러나지 않았다.
    컨테이너 파이썬을 올리는 것이 근본 해법이지만, 그 쪽은 Spark 판올림이 얽혀 있어
    읽는 쪽에서 먼저 막는다.
    """
    return _SECOND_FRACTION.sub(lambda m: "." + (m.group(1) + "000000")[:6], value, count=1)


def timestamp(value, allow_naive=False):
    value = text(value)
    if value is None:
        return None
    try:
        parsed = datetime.fromisoformat(_pad_fraction(value.replace("Z", "+00:00")))
        if parsed.tzinfo is None:
            if not allow_naive:
                return None
            parsed = parsed.replace(tzinfo=KST)
        return parsed.astimezone(KST).isoformat()
    except ValueError:
        return None


def normalize(value):
    """Return a fixed-schema row, including REJECTED/IGNORED instead of losing input."""
    row = dict.fromkeys(ALL_FIELDS)
    row.update(
        silverVersion=VERSION,
        missingFields=[],
        qualityFlags=[],
        rejectionReasons=[],
        claimedSegmentIds=[],
        status="ACCEPTED",
    )
    flags = row["qualityFlags"]
    try:
        event = json.loads(value) if isinstance(value, str) else value
        if not isinstance(event, dict):
            raise ValueError("object required")
    except (ValueError, TypeError):
        row.update(status="REJECTED", rejectionReasons=["INVALID_EVENT_JSON"], rawPayloadJson=str(value))
        return row
    kind = event.get("eventType")
    row.update(eventType=text(kind), eventId=text(event.get("eventId"), True), source=text(event.get("source")))
    if kind not in ("bus.position", "bus.arrival"):
        row["status"] = "IGNORED"
        return row
    payload = event.get("payload")
    row["rawPayloadJson"] = json.dumps(payload, ensure_ascii=False, sort_keys=True)
    if not isinstance(payload, dict) or not isinstance(payload.get("raw"), dict):
        row.update(status="REJECTED", rejectionReasons=["INVALID_PAYLOAD_OBJECT"])
        return row
    raw = payload["raw"]
    expected = SOURCE_NAMESPACE.get(row["source"])
    namespace = text(payload.get("providerNamespace")) or expected
    row["providerNamespace"] = namespace
    if namespace not in ("SEOUL_BUS", "GBIS", "INCHEON_API"):
        row["rejectionReasons"].append("UNSUPPORTED_PROVIDER_NAMESPACE")
    if expected and expected != namespace:
        row["rejectionReasons"].append("SOURCE_NAMESPACE_MISMATCH")
    for field in ("providerRouteId", "providerStopId", "providerVehicleId"):
        row[field] = text(payload.get(field), True)
    row["timestampBasis"] = text(payload.get("timestampBasis"))
    row["collectedAt"] = timestamp(event.get("collectedAt"))
    # Partition by collection day: source time may be missing; this is not serviceDate.
    row["archiveDate"] = row["collectedAt"][:10] if row["collectedAt"] else None
    segments = event.get("segmentIds")
    row["claimedSegmentIds"] = (
        segments if isinstance(segments, list) and all(isinstance(s, str) for s in segments) else []
    )
    row["claimedMappingJson"] = json.dumps(payload.get("canonicalMapping"), ensure_ascii=False, sort_keys=True)

    def get(*names):
        for name in names:
            if isinstance(raw.get(name), (bool, dict, list)) or text(raw.get(name)) is not None:
                return raw[name]
        return None

    def number(field, value, integer=False, minimum=0, maximum=None):
        if isinstance(value, (bool, dict, list)):
            flags.append(field + ":INVALID_NUMBER_OR_RANGE")
            row[field] = None
            return
        if text(value) is None:
            row[field] = None
            return
        try:
            if isinstance(value, bool):
                raise ValueError()
            result = float(value)
            if (
                not math.isfinite(result)
                or result < minimum
                or (maximum is not None and result > maximum)
                or (integer and not result.is_integer())
            ):
                raise ValueError()
            if integer and int(result) > 9223372036854775807:
                raise ValueError()
            row[field] = int(result) if integer else result
        except (ValueError, TypeError, OverflowError):
            row[field] = None
            flags.append(field + ":INVALID_NUMBER_OR_RANGE")

    # Seoul mkTm is provider-local time. Regional adapters explicitly use received time.
    generated = get("mkTm", "MK_TM") if namespace == "SEOUL_BUS" and kind == "bus.arrival" else None
    if generated is not None:
        row["sourceGeneratedAt"] = timestamp(generated, allow_naive=True)
        row["observedAt"] = row["sourceGeneratedAt"]
        if row["observedAt"] is None:
            flags.append("SOURCE_TIME_INVALID")
    elif row["timestampBasis"] == "provider":
        row["observedAt"] = timestamp(event.get("occurredAt"))
    if row["observedAt"] and row["collectedAt"] and row["observedAt"] > row["collectedAt"]:
        flags.append("SOURCE_TIME_AFTER_COLLECTION")
        row["observedAt"] = None
        row["sourceGeneratedAt"] = None
    if row["observedAt"] is None:
        flags.append("SOURCE_TIME_UNAVAILABLE")

    if kind == "bus.position":
        # Only Seoul gpsX/gpsY are used here. tmX/tmY may be projected coordinates.
        location = event.get("location")
        location = location if isinstance(location, dict) else {}
        lon = location.get("longitude", event.get("longitude"))
        lat = location.get("latitude", event.get("latitude"))
        if namespace == "SEOUL_BUS":
            lon = lon if lon is not None else get("gpsX", "GPS_X")
            lat = lat if lat is not None else get("gpsY", "GPS_Y")
        number("longitude", lon, minimum=-180, maximum=180)
        number("latitude", lat, minimum=-90, maximum=90)
        if (
            row["longitude"] is not None
            and row["latitude"] is not None
            and (row["longitude"] == 0 or row["latitude"] == 0)
        ):
            row["longitude"] = row["latitude"] = None
            flags.append("COORDINATE_ZERO_OUTSIDE_PROVIDER_REGION")
        if row["longitude"] is None or row["latitude"] is None:
            row["longitude"] = row["latitude"] = None
            flags.append("COORDINATE_PAIR_UNAVAILABLE")
        number("providerStopOrder", get("stationSeq", "LATEST_STOPSEQ"), True, 1)
        number("sectionOrder", get("sectOrd", "SECT_ORD"), True, 1)
        number("sectionDistanceMeters", get("sectDist", "SECT_DIST"))
        number("fullSectionDistanceMeters", get("fullSectDist", "FULL_SECT_DIST"))
        row["congestionCode"] = text(get("congetion", "congestion", "CONGESTION"))
        row["providerStateCode"] = text(get("stateCd"))
        row["directionCode"] = text(get("DIRCD"))
        row["stopFlag"] = text(get("stopFlag", "STOP_FLAG"))
    else:
        row["arrivalTimeBasis"] = {
            "SEOUL_BUS": "SEOUL_EXPS_SECONDS_V1",
            "GBIS": "GBIS_PREDICT_SECONDS_V1",
            "INCHEON_API": "INCHEON_ESTIMATE_SECONDS_V1",
        }.get(namespace)
        number("stopOrder", get("staOrd", "STA_ORD", "staOrder"), True, 1)
        row["detourCode"] = text(get("deTourAt", "DE_TOUR_AT"))
        for index, prefix in ((1, "first"), (2, "second")):
            s = str(index)
            if namespace == "SEOUL_BUS":
                seconds = get("exps" + s, "EXPS" + s)
            elif namespace == "GBIS":
                seconds = get("predictTimeSec" + s)
                if text(seconds) is None:
                    number(prefix + "ArrivalSeconds", get("predictTime" + s), True)
                    seconds = row[prefix + "ArrivalSeconds"]
                    seconds = seconds * 60 if seconds is not None else None
            else:
                seconds = get("ARRIVALESTIMATETIME") if index == 1 else None
            number(prefix + "ArrivalSeconds", seconds, True)
            row[prefix + "VehicleId"] = text(get("vehId" + s, "VEH_ID" + s), True)
            if namespace == "INCHEON_API" and index == 1:
                row[prefix + "VehicleId"] = row["providerVehicleId"]
            number(prefix + "RemainingStops", get("locationNo" + s, *(["REST_STOP_COUNT"] if index == 1 else [])), True)
            number(prefix + "VehicleSectionOrder", get("sectOrd" + s, "SECT_ORD" + s), True, 1)
            # traTime is Seoul-specific minutes, never a traversal label.
            if namespace == "SEOUL_BUS":
                number(prefix + "TravelTimeMinutes", get("traTime" + s, "TRA_TIME" + s))
            row[prefix + "FullCode"] = text(get("full" + s, "FULL" + s))
            row[prefix + "TerminalArrivalCode"] = text(get("isArrive" + s, "IS_ARRIVE" + s))
            row[prefix + "ArrivalMessage"] = text(get("arrmsg" + s, "ARRMSG" + s))

    required = ["eventId", "source", "providerNamespace", "providerRouteId", "collectedAt"]
    required += ["providerVehicleId"] if kind == "bus.position" else ["providerStopId"]
    if kind == "bus.arrival" and namespace == "INCHEON_API":
        required.append("providerVehicleId")
    row["rejectionReasons"] += ["MISSING_OR_INVALID:" + f for f in required if row[f] is None]
    relevant = list(COMMON) + list(POSITION if kind == "bus.position" else ARRIVAL)
    row["missingFields"] = [f for f in relevant if row[f] is None and f not in ("rawPayloadJson",)]
    if row["rejectionReasons"]:
        row["status"] = "REJECTED"
    return row


def normalize_json(value):
    return json.dumps(normalize(value), ensure_ascii=False, allow_nan=False)
