"""기존 단독 수집 API를 weather와 동일한 SourceSpec/Record 계약에 연결한다."""
from __future__ import annotations

import hashlib
import importlib
import json
import logging
from datetime import datetime
from pathlib import Path
from functools import lru_cache
from bus_crosswalk import BusCrosswalk, POLICY as BUS_ID_POLICY

from config import Settings
from request_budget import RequestBudget
from sources.base import KST, Record, SourceSpec, now_kst

log = logging.getLogger(__name__)
DEFAULT_BUS_ROUTE_LIMIT = 100
# module: (key, event type, interval, schema)
POLICIES = {
    "bus_arrival": ("BUS_ARRIVAL_API_KEY", "bus.arrival", 28800, "transport"),
    "bus_location": ("BUS_LOCATION_API_KEY", "bus.position", 28800, "transport"),
    "subway_arrival": ("SUBWAY_API_KEY", "subway.arrival", 360, "transport"),
    "subway_location": ("SUBWAY_API_KEY", "subway.position", 3600, "transport"),
    "subway_alert": ("SUBWAY_ALERT_API_KEY", "subway.alert", 900, "transport"),
    "city_realtime": ("CITY_REALTIME_API_KEY", "city.realtime", 21600, "city"),
    "topis_notice": ("TOPIS_NOTICE_API_KEY", "topis.notice", 900, "city"),
    "traffic_incident": ("TRAFFIC_INCIDENT_API_KEY", "traffic.incident", 900, "city"),
}


def _rows(value):
    if value is None:
        return []
    if isinstance(value, dict):
        return [value]
    if isinstance(value, list) and all(isinstance(row, dict) for row in value):
        return value
    raise ValueError("Invalid API item list")


def _limit_bus_targets(targets, raw_limit=None):
    """공유 인증키의 일일 예산을 위해 버스 수집 범위를 최대 100개로 제한한다."""
    try:
        limit = int(raw_limit or DEFAULT_BUS_ROUTE_LIMIT)
    except ValueError as exc:
        raise ValueError("BUS_ROUTE_LIMIT must be an integer") from exc
    if not 1 <= limit <= DEFAULT_BUS_ROUTE_LIMIT:
        raise ValueError(f"BUS_ROUTE_LIMIT must be between 1 and {DEFAULT_BUS_ROUTE_LIMIT}")
    return targets[:limit]


def _time(value, fallback):
    try:
        result = datetime.fromisoformat(str(value))
        return result.replace(tzinfo=KST) if result.tzinfo is None else result
    except (ValueError, TypeError):
        return fallback


@lru_cache(maxsize=2)
def _crosswalk(path, sha):
    """기동 설정의 고정 파일을 한 번 검증한다. 버전 교체는 재시작으로 적용한다."""
    return BusCrosswalk(path, sha) if path else None


def to_record(name, raw, context="", crosswalk=None):
    """공급자 ID와 원본을 보존. 원본 시각이 없으면 수신 시각 사용을 표시한다."""
    ids, source_time = {}, None
    if name.startswith("bus_"):
        # 이 adapter의 버스 API는 서울 제공자다. 지역 문자열이나 노선 번호로 출처를 추정하지 않는다.
        ids["providerNamespace"] = "SEOUL_BUS"
        ids["providerRouteId"] = str(raw.get("busRouteId") or context)
        if name == "bus_arrival":
            ids["providerStopId"] = str(raw.get("stId") or "")
            source_time = raw.get("mkTm")
        else:
            ids["providerVehicleId"] = str(raw.get("vehId") or "")
    elif name in {"subway_arrival", "subway_location"}:
        ids["providerLineId"] = str(raw.get("subwayId") or context)
        ids["providerTrainId"] = str(raw.get("btrainNo") or raw.get("trainNo") or "")
        ids["providerStationId"] = str(raw.get("statnId") or "")
        source_time = raw.get("recptnDt")
    elif name == "city_realtime":
        ids["providerAreaId"] = str(raw.get("AREA_CD") or context)
    elif name == "topis_notice":
        ids["providerNoticeId"] = str(raw.get("noticeId") or "")
        source_time = raw.get("updatedAt") or raw.get("createdAt")
    elif name == "traffic_incident":
        # AccInfo LINK_ID를 표준노드링크나 다른 TOPIS ID와 숫자만으로 조인하지 않는다.
        ids["providerLinkNamespace"] = "SEOUL_ACCINFO_LINK"
        ids["providerIncidentId"] = str(raw.get("incidentId") or "")
        ids["providerLinkId"] = str(raw.get("linkId") or "")
        try:
            source_time = datetime.strptime(raw["occurredDate"] + raw["occurredTime"], "%Y%m%d%H%M%S").isoformat()
        except (KeyError, ValueError):
            pass
    else:
        # API가 공식 고유 ID를 제공하지 않으므로 본문을 제외한 식별 후보를 해시한다.
        identity = [raw.get(k) for k in ("noftOcrnDt", "noftTtl", "lineNmLst", "stnSctnCdLst")]
        ids["providerAlertId"] = hashlib.sha256(json.dumps(identity, ensure_ascii=False).encode()).hexdigest()
        source_time = raw.get("noftOcrnDt")
    received = now_kst()
    occurred = _time(source_time, received)
    payload = {**ids, "raw": raw, "observedAt": received.isoformat(),
               "timestampBasis": "received" if occurred == received else "provider",
               "contentHash": hashlib.sha256(json.dumps(raw, sort_keys=True, ensure_ascii=False).encode()).hexdigest()}
    key = ":".join(v for k,v in ids.items() if v and k not in {"providerNamespace", "providerLinkNamespace"}) or payload["contentHash"]
    segment_ids=None
    if name.startswith("bus_"):
        mapping=crosswalk.resolve(POLICIES[name][1],payload) if crosswalk else dict(
            segmentIds=[],state="UNRESOLVED",scope="NONE",reason="CROSSWALK_NOT_CONFIGURED",
            masterVersion=None,idPolicy=BUS_ID_POLICY,crosswalkSha256=None)
        segment_ids=mapping["segmentIds"] or None
        payload["canonicalMapping"]={k:v for k,v in mapping.items() if k!="segmentIds"}
    return Record(key=key, event_type=POLICIES[name][1], occurred_at=occurred, payload=payload,segment_ids=segment_ids)


def collect(name, settings: Settings, client):
    crosswalk=_crosswalk(settings.extra.get("BUS_CROSSWALK_PATH"),settings.extra.get("BUS_CROSSWALK_SHA256")) if name.startswith("bus_") else None
    module = importlib.import_module(f"sources.{name}")
    key_name, _, default_interval, _ = POLICIES[name]
    key = settings.api_key(key_name)
    targets = []
    if name.startswith("bus_"):
        targets = ([item.strip() for item in settings.extra["BUS_ROUTE_IDS"].split(",") if item.strip()]
                   if settings.extra.get("BUS_ROUTE_IDS") else module.load_bus_route_ids())
        targets = _limit_bus_targets(targets, settings.extra.get("BUS_ROUTE_LIMIT"))
    elif name == "subway_location":
        targets = ([item.strip() for item in settings.extra["SUBWAY_LINES"].split(",") if item.strip()]
                   if settings.extra.get("SUBWAY_LINES") else module.load_subway_lines())
    elif name == "city_realtime":
        registry = settings.extra.get("CITY_AREA_REGISTRY_PATH")
        areas = module.load_city_areas(Path(registry)) if registry else module.load_city_areas()
        requested = settings.extra.get("CITY_AREA_IDENTIFIERS")
        targets = module.select_city_areas(areas, requested.split(",") if requested else None)
    expected = len(targets) if targets else (2 if name == "subway_arrival" else 1)
    budget = RequestBudget(settings.extra.get("API_BUDGET_PATH", str(Path(__file__).resolve().parents[2]/"data"/"request_budget.sqlite3")),
                           key, int(settings.extra.get("API_DAILY_LIMIT", "900")))
    interval = max(default_interval, settings.interval_seconds or 0)
    if not budget.claim_cycle(name, interval, expected):
        log.info("source=%s cooldown active; no API request", name)
        return []
    hook = budget.before_request
    client.event_hooks["request"].append(hook)
    try:
        if name == "bus_arrival":
            result = module.fetch_all_bus_arrivals(key, targets, client=client, requests_per_second=1)
            return [to_record(name, row, route, crosswalk) for route, response in result["routeResults"].items()
                    for row in _rows(response.get("msgBody", {}).get("itemList"))]
        if name == "bus_location":
            result = module.fetch_all_bus_locations(key, targets, client=client, requests_per_second=1)
            return [to_record(name, row, route, crosswalk) for route, response in result["routeResults"].items()
                    for row in _rows(response.get("msgBody", {}).get("itemList"))]
        if name == "subway_arrival":
            result = module.fetch_all_subway_arrivals(key, client=client)
            return [to_record(name, row) for row in result["response"]["realtimeArrivalList"]]
        if name == "subway_location":
            result = module.fetch_all_subway_locations(key, targets, client=client, requests_per_second=1)
            return [to_record(name, row, line) for line, response in result["lineResults"].items()
                    for row in response["response"].get("realtimePositionList", [])]
        if name == "city_realtime":
            result = module.fetch_city_areas(key, targets, client=client, requests_per_second=1)
            return [to_record(name, area["response"]["CITYDATA"]) for area in result["areaResults"].values()]
        if name == "subway_alert":
            result = module.fetch_alerts(key, page_size=1000, client=client)
        elif name == "topis_notice":
            result = module.fetch_notices(key, page_size=1000, fetch_all=False, client=client)
        else:
            result = module.fetch_incidents(key, page_size=1000, fetch_all=True, client=client)
        if result.get("hasMore"):
            log.warning("source=%s response may have more rows; first 1000 only", name)
        return [to_record(name, row) for row in result["items"]]
    except Exception as exc:
        # chained HTTP exceptions contain secret URL paths. Never propagate their traceback.
        raise RuntimeError(f"source={name} collection failed ({type(exc).__name__})") from None
    finally:
        client.event_hooks["request"].remove(hook)


def make_source(name):
    _, _, interval, schema = POLICIES[name]
    return SourceSpec(name=f"seoul.{name}", topic=f"{schema}.raw", schema=schema,
                      default_interval_seconds=interval,
                      fetch=lambda settings, client: collect(name, settings, client))
