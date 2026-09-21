"""서울 실시간 도시데이터 ``city.realtime``를 Silver 행으로 정규화한다.

외부 API 호출이나 AI 추론은 하지 않는다. Collector가 Kafka 봉투에 보존한 CITYDATA 중
인구·인구예측·도로소통만 typed row로 승격하고, 나머지는 rawPayloadJson에서 추적한다.
공식 샘플(서울 열린데이터광장 citydata, 2026-09-17 확인)의 XYLIST는
``경도_위도|경도_위도`` 형식의 WGS84(EPSG:4326) 좌표다.
"""

from __future__ import annotations

import hashlib
import json
import math
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Iterable

KST = timezone(timedelta(hours=9))
SILVER_VERSION = "city-realtime-v1"
LINK_NAMESPACE = "SEOUL_CITYDATA_LINK"
XY_CRS = "EPSG:4326"

CONGESTION = {
    "여유": ("RELAXED", 1),
    "보통": ("NORMAL", 2),
    "약간 붐빔": ("BUSY", 3),
    "붐빔": ("CROWDED", 4),
    "relaxed": ("RELAXED", 1),
    "normal": ("NORMAL", 2),
    "busy": ("BUSY", 3),
    "crowded": ("CROWDED", 4),
}
TRAFFIC = {
    "원활": "SMOOTH",
    "서행": "SLOW",
    "정체": "CONGESTED",
    "smooth": "SMOOTH",
    "slow": "SLOW",
    "congested": "CONGESTED",
}


@dataclass
class NormalizedCityEvent:
    area: list[dict[str, Any]] = field(default_factory=list)
    roads: list[dict[str, Any]] = field(default_factory=list)
    forecasts: list[dict[str, Any]] = field(default_factory=list)
    rejected: list[dict[str, Any]] = field(default_factory=list)


def _compact(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def _text(value: Any) -> str | None:
    if value is None:
        return None
    value = str(value).strip()
    return value or None


def _number(value: Any, integer: bool = False) -> int | float | None:
    try:
        result = float(str(value).replace(",", "").strip())
        if not math.isfinite(result):
            return None
        return int(result) if integer and result.is_integer() else (None if integer else result)
    except (TypeError, ValueError):
        return None


def _objects(value: Any) -> list[dict[str, Any]]:
    if isinstance(value, dict):
        return [value]
    if isinstance(value, list):
        return [item for item in value if isinstance(item, dict)]
    return []


def _parse_time(value: Any) -> datetime | None:
    text = _text(value)
    if not text:
        return None
    normalized = text.replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError:
        parsed = None
        for pattern in ("%Y-%m-%d %H:%M", "%Y-%m-%d %H:%M:%S", "%Y%m%d%H%M%S"):
            try:
                parsed = datetime.strptime(text, pattern)
                break
            except ValueError:
                pass
    if parsed is None:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=KST)
    return parsed.astimezone(KST)


def _iso(value: datetime | None) -> str | None:
    return value.isoformat(timespec="seconds") if value else None


def _citydata(payload: dict[str, Any]) -> list[dict[str, Any]]:
    raw = payload.get("raw", payload)
    if isinstance(raw, dict) and "CITYDATA" in raw:
        raw = raw.get("CITYDATA")
    return _objects(raw)


def _first_object(container: dict[str, Any], key: str) -> dict[str, Any] | None:
    rows = _objects(container.get(key))
    return rows[0] if rows else None


def _status(missing: Iterable[str], flags: Iterable[str]) -> str:
    return "PARTIAL" if any(missing) or any(flags) else "VALID"


def _time_fields(provider_time: Any, collected: datetime | None, freshness: int) -> tuple[dict[str, Any], list[str]]:
    flags: list[str] = []
    if collected is None:
        flags.append("COLLECTED_AT_MISSING_OR_INVALID")
    observed = _parse_time(provider_time)
    basis = "PROVIDER"
    if observed is None:
        observed = collected
        basis = "COLLECTED_AT_FALLBACK"
        flags.append("PROVIDER_TIME_MISSING_OR_INVALID")
    if observed and collected and observed > collected + timedelta(minutes=5):
        flags.append("PROVIDER_TIME_IN_FUTURE")
    age = max(0, int((collected - observed).total_seconds())) if observed and collected else None
    valid_until = observed + timedelta(seconds=freshness) if observed else None
    return {
        "observedAt": _iso(observed),
        "timestampBasis": basis,
        "validUntil": _iso(valid_until),
        "dataAgeSeconds": age,
        "isStale": None if age is None else age > freshness,
    }, flags


def _normalize_category(value: Any, mapping: dict[str, Any]) -> tuple[Any, list[str]]:
    text = _text(value)
    if text is None:
        return None, []
    normalized = mapping.get(text, mapping.get(text.lower()))
    if normalized is None:
        return "UNKNOWN", ["UNKNOWN_CATEGORY:" + text]
    return normalized, []


def _point(value: Any) -> tuple[float, float] | None:
    if not isinstance(value, str):
        return None
    parts = value.strip().replace(",", "_").split("_")
    if len(parts) != 2:
        return None
    lon, lat = _number(parts[0]), _number(parts[1])
    if lon is None or lat is None:
        return None
    return float(lon), float(lat)


def _geometry(value: Any) -> tuple[dict[str, Any] | None, dict[str, Any], list[str]]:
    """공식 ``lon_lat|...`` WGS84 문자열을 GeoJSON 좌표 순서로 보존한다."""
    meta = {
        "coordinateCrs": XY_CRS,
        "coordinateOrder": "LONGITUDE_LATITUDE",
        "coordinateStatus": "MISSING",
        "geometryStatus": "MISSING",
        "startLongitude": None,
        "startLatitude": None,
        "endLongitude": None,
        "endLatitude": None,
        "representativeLongitude": None,
        "representativeLatitude": None,
    }
    if value is None:
        return None, meta, ["XYLIST_ABSENT"]
    if value == "" or value == []:
        meta["coordinateStatus"] = "EMPTY"
        meta["geometryStatus"] = "EMPTY"
        return None, meta, ["XYLIST_EMPTY"]
    tokens = value.split("|") if isinstance(value, str) else []
    points = [_point(token) for token in tokens]
    if not tokens or any(point is None for point in points):
        meta["coordinateStatus"] = "PARSE_FAILED"
        meta["geometryStatus"] = "INVALID"
        return None, meta, ["XYLIST_PARSE_FAILED"]
    coords = [[point[0], point[1]] for point in points if point]
    # 서울 외곽까지 여유를 둔 방어 범위. 통과해도 링크 마스터 일치로 간주하지 않는다.
    if any(not (126.70 <= lon <= 127.30 and 37.40 <= lat <= 37.75) for lon, lat in coords):
        meta["coordinateStatus"] = "OUT_OF_SEOUL_BOUNDS"
        meta["geometryStatus"] = "INVALID"
        return None, meta, ["XYLIST_OUT_OF_SEOUL_BOUNDS"]
    meta.update(
        {
            "coordinateStatus": "VALID_WGS84",
            "startLongitude": coords[0][0],
            "startLatitude": coords[0][1],
            "endLongitude": coords[-1][0],
            "endLatitude": coords[-1][1],
            "representativeLongitude": sum(p[0] for p in coords) / len(coords),
            "representativeLatitude": sum(p[1] for p in coords) / len(coords),
        }
    )
    if len(coords) < 2:
        meta["geometryStatus"] = "INSUFFICIENT_POINTS"
        return None, meta, ["XYLIST_INSUFFICIENT_POINTS"]
    meta["geometryStatus"] = "VALID_LINESTRING"
    return {"type": "LineString", "coordinates": coords}, meta, []


def _road_endpoint(value: Any, name: str) -> tuple[tuple[float, float] | None, list[str]]:
    if value is None:
        return None, [name + "_MISSING"]
    point = _point(value)
    if point is None:
        return None, [name + "_PARSE_FAILED"]
    if not (126.70 <= point[0] <= 127.30 and 37.40 <= point[1] <= 37.75):
        return None, [name + "_OUT_OF_SEOUL_BOUNDS"]
    return point, []


def _content_hash(payload: dict[str, Any], raw: Any) -> str:
    return _text(payload.get("contentHash")) or hashlib.sha256(_compact(raw).encode("utf-8")).hexdigest()


def _reject(
    event: dict[str, Any], raw: Any, reasons: list[str], record_type: str, area_id: str | None = None
) -> dict[str, Any]:
    return {
        "silverVersion": SILVER_VERSION,
        "eventId": _text(event.get("eventId")),
        "recordType": record_type,
        "providerAreaId": area_id,
        "collectedAt": _iso(_parse_time(event.get("collectedAt"))),
        "rejectionReasons": reasons,
        "processingStatus": "REJECTED",
        "rawPayloadJson": _compact(raw),
    }


def normalize_event(event: dict[str, Any], freshness_seconds: int = 900) -> NormalizedCityEvent:
    """한 Kafka 봉투를 area/road/forecast/rejected 행으로 펼친다."""
    result = NormalizedCityEvent()
    payload = event.get("payload") if isinstance(event.get("payload"), dict) else {}
    candidates = _citydata(payload)
    if not candidates:
        result.rejected.append(_reject(event, payload, ["CITYDATA_MISSING_OR_INVALID"], "AREA"))
        return result

    collected = _parse_time(event.get("collectedAt"))
    for raw in candidates:
        raw_json = _compact(raw)
        area_id = _text(raw.get("AREA_CD"))
        if not area_id:
            result.rejected.append(_reject(event, raw, ["AREA_CD_MISSING"], "AREA"))
            continue
        area_name = _text(raw.get("AREA_NM"))
        live_value = raw.get("LIVE_PPLTN_STTS")
        live = _first_object(raw, "LIVE_PPLTN_STTS")
        road_groups = _objects(raw.get("ROAD_TRAFFIC_STTS"))
        avg = next(
            (
                _first_object(group, "AVG_ROAD_DATA")
                for group in road_groups
                if _first_object(group, "AVG_ROAD_DATA") is not None
            ),
            None,
        )
        road_rows = [road for group in road_groups for road in _objects(group.get("ROAD_TRAFFIC_STTS"))]
        road_links_present = any("ROAD_TRAFFIC_STTS" in group for group in road_groups)
        missing: list[str] = []
        flags: list[str] = []
        if area_name is None:
            missing.append("AREA_NM")
        if live is None:
            missing.append("LIVE_PPLTN_STTS")
            flags.append("LIVE_PPLTN_EMPTY" if live_value == [] else "LIVE_PPLTN_ABSENT_OR_INVALID")
            live = {}
        if avg is None:
            missing.append("ROAD_TRAFFIC_STTS.AVG_ROAD_DATA")
            avg = {}
        if road_links_present and not road_rows:
            flags.append("ROAD_LINKS_EMPTY")
        elif not road_links_present:
            flags.append("ROAD_LINKS_ABSENT")

        population_min = _number(live.get("AREA_PPLTN_MIN"), integer=True)
        population_max = _number(live.get("AREA_PPLTN_MAX"), integer=True)
        if population_min is None:
            missing.append("AREA_PPLTN_MIN")
        if population_max is None:
            missing.append("AREA_PPLTN_MAX")
        population_mid = None
        if population_min is not None and population_max is not None:
            if population_min > population_max:
                flags.append("POPULATION_MIN_GREATER_THAN_MAX")
            elif population_min < 0:
                flags.append("NEGATIVE_POPULATION")
            else:
                population_mid = int(round((population_min + population_max) / 2))

        congestion, category_flags = _normalize_category(live.get("AREA_CONGEST_LVL"), CONGESTION)
        flags.extend(category_flags)
        if congestion is None:
            missing.append("AREA_CONGEST_LVL")
        score = congestion[1] if isinstance(congestion, tuple) else None
        level = congestion[0] if isinstance(congestion, tuple) else congestion
        speed = _number(avg.get("ROAD_TRAFFIC_SPD"))
        if speed is None:
            missing.append("ROAD_TRAFFIC_SPD")
        elif not (0 <= speed <= 200):
            speed = None
            flags.append("AVG_ROAD_SPEED_OUT_OF_RANGE")
        time_fields, time_flags = _time_fields(
            live.get("PPLTN_TIME") or avg.get("ROAD_TRAFFIC_TIME"), collected, freshness_seconds
        )
        flags.extend(time_flags)
        content_hash = _content_hash(payload, raw)
        area_row = {
            "silverVersion": SILVER_VERSION,
            "eventId": _text(event.get("eventId")),
            "contentHash": content_hash,
            "providerAreaId": area_id,
            "areaName": area_name,
            **time_fields,
            "collectedAt": _iso(collected),
            "populationObservedAt": _iso(_parse_time(live.get("PPLTN_TIME"))),
            "roadTrafficObservedAt": _iso(_parse_time(avg.get("ROAD_TRAFFIC_TIME"))),
            "congestionLevelRaw": _text(live.get("AREA_CONGEST_LVL")),
            "congestionLevel": level,
            "congestionScore": score,
            "congestionMessage": _text(live.get("AREA_CONGEST_MSG")),
            "populationMin": population_min,
            "populationMax": population_max,
            "populationMid": population_mid,
            "avgRoadSpeedKph": speed,
            "missingFields": sorted(set(missing)),
            "qualityFlags": sorted(set(flags)),
            "processingStatus": _status(missing, flags),
            "rawPayloadJson": raw_json,
        }
        result.area.append(area_row)

        forecasts_value = live.get("FCST_PPLTN")
        for forecast in _objects(forecasts_value):
            forecast_at = _parse_time(forecast.get("FCST_TIME"))
            if forecast_at is None:
                result.rejected.append(_reject(event, forecast, ["FCST_TIME_MISSING_OR_INVALID"], "FORECAST", area_id))
                continue
            forecast_missing: list[str] = []
            forecast_flags: list[str] = list(time_flags)
            fmin = _number(forecast.get("FCST_PPLTN_MIN"), integer=True)
            fmax = _number(forecast.get("FCST_PPLTN_MAX"), integer=True)
            if fmin is None:
                forecast_missing.append("FCST_PPLTN_MIN")
            if fmax is None:
                forecast_missing.append("FCST_PPLTN_MAX")
            fmid = None
            if fmin is not None and fmax is not None:
                if fmin > fmax:
                    forecast_flags.append("POPULATION_MIN_GREATER_THAN_MAX")
                elif fmin < 0:
                    forecast_flags.append("NEGATIVE_POPULATION")
                else:
                    fmid = int(round((fmin + fmax) / 2))
            fcongestion, fflags = _normalize_category(forecast.get("FCST_CONGEST_LVL"), CONGESTION)
            forecast_flags.extend(fflags)
            if fcongestion is None:
                forecast_missing.append("FCST_CONGEST_LVL")
            result.forecasts.append(
                {
                    "silverVersion": SILVER_VERSION,
                    "eventId": _text(event.get("eventId")),
                    "contentHash": content_hash,
                    "providerAreaId": area_id,
                    "areaName": area_name,
                    "forecastCreatedAt": time_fields["observedAt"],
                    "forecastAt": _iso(forecast_at),
                    "timestampBasis": time_fields["timestampBasis"],
                    "collectedAt": _iso(collected),
                    "congestionLevelRaw": _text(forecast.get("FCST_CONGEST_LVL")),
                    "congestionLevel": fcongestion[0] if isinstance(fcongestion, tuple) else fcongestion,
                    "congestionScore": fcongestion[1] if isinstance(fcongestion, tuple) else None,
                    "populationMin": fmin,
                    "populationMax": fmax,
                    "populationMid": fmid,
                    "missingFields": sorted(set(forecast_missing)),
                    "qualityFlags": sorted(set(forecast_flags)),
                    "processingStatus": _status(forecast_missing, forecast_flags),
                    "rawPayloadJson": _compact(forecast),
                }
            )

        road_time = avg.get("ROAD_TRAFFIC_TIME")
        for road in road_rows:
            link_id = _text(road.get("LINK_ID"))
            if not link_id:
                result.rejected.append(_reject(event, road, ["LINK_ID_MISSING"], "ROAD", area_id))
                continue
            road_missing: list[str] = []
            road_flags: list[str] = []
            if _text(road.get("ROAD_NM")) is None:
                road_missing.append("ROAD_NM")
            road_speed = _number(road.get("SPD"))
            if road_speed is None:
                road_missing.append("SPD")
            elif not (0 <= road_speed <= 200):
                road_speed = None
                road_flags.append("ROAD_SPEED_OUT_OF_RANGE")
            traffic_status, traffic_flags = _normalize_category(road.get("IDX"), TRAFFIC)
            road_flags.extend(traffic_flags)
            if traffic_status is None:
                road_missing.append("IDX")
            geometry, geometry_meta, geometry_flags = _geometry(road.get("XYLIST"))
            road_flags.extend(geometry_flags)
            if road.get("XYLIST") is None:
                road_missing.append("XYLIST")
            start_point, endpoint_flags = _road_endpoint(road.get("START_ND_XY"), "START_ND_XY")
            road_flags.extend(endpoint_flags)
            end_point, endpoint_flags = _road_endpoint(road.get("END_ND_XY"), "END_ND_XY")
            road_flags.extend(endpoint_flags)
            geometry_meta.update(
                {
                    "startLongitude": start_point[0] if start_point else None,
                    "startLatitude": start_point[1] if start_point else None,
                    "endLongitude": end_point[0] if end_point else None,
                    "endLatitude": end_point[1] if end_point else None,
                }
            )
            road_times, road_time_flags = _time_fields(
                road.get("ROAD_TRAFFIC_TIME") or road_time, collected, freshness_seconds
            )
            road_flags.extend(road_time_flags)
            result.roads.append(
                {
                    "silverVersion": SILVER_VERSION,
                    "eventId": _text(event.get("eventId")),
                    "contentHash": content_hash,
                    "providerAreaId": area_id,
                    "areaName": area_name,
                    "linkNamespace": LINK_NAMESPACE,
                    "linkId": link_id,
                    "roadName": _text(road.get("ROAD_NM")),
                    "startNodeId": _text(road.get("START_ND_CD")),
                    "startNodeName": _text(road.get("START_ND_NM")),
                    "endNodeId": _text(road.get("END_ND_CD")),
                    "endNodeName": _text(road.get("END_ND_NM")),
                    "distanceMeters": _number(road.get("DIST")),
                    **road_times,
                    "collectedAt": _iso(collected),
                    "speedKph": road_speed,
                    "trafficStatusRaw": _text(road.get("IDX")),
                    "trafficStatus": traffic_status,
                    "geometry": geometry,
                    **geometry_meta,
                    "missingFields": sorted(set(road_missing)),
                    "qualityFlags": sorted(set(road_flags)),
                    "processingStatus": _status(road_missing, road_flags),
                    "rawPayloadJson": _compact(road),
                }
            )
    return result


def deduplicate(rows: list[dict[str, Any]], keys: tuple[str, ...]) -> tuple[list[dict[str, Any]], int]:
    """동일 관측시각+내용만 제거한다. 같은 시각의 변경된 contentHash는 보존한다."""
    seen: set[tuple[Any, ...]] = set()
    output: list[dict[str, Any]] = []
    duplicates = 0
    for row in rows:
        key = tuple(row.get(name) for name in keys) + (row.get("contentHash"),)
        if key in seen:
            duplicates += 1
            continue
        seen.add(key)
        output.append(row)
    return output, duplicates
