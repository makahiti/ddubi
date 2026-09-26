"""기존 단독 수집 API를 weather와 동일한 SourceSpec/Record 계약에 연결한다."""

from __future__ import annotations

import hashlib
import importlib
import json
import logging
from datetime import datetime
from functools import lru_cache
from pathlib import Path

from bus_collection_policy import BusCollectionPlan, plan_bus_collection
from bus_crosswalk import POLICY as BUS_ID_POLICY
from bus_crosswalk import BusCrosswalk
from bus_key_pool import bus_dense_policy_enabled, bus_key_candidates
from config import Settings
from request_budget import BudgetExceeded, RequestBudget, RetryScheduled, RetryWait
from topis_notice_policy import plan_topis_notice
from traffic_incident_poll_policy import plan_traffic_incident

from sources.base import KST, PollBatch, Record, SourceSpec, now_kst
from sources.subway_schedule import SkippedNonService, pause_until

log = logging.getLogger(__name__)
DEFAULT_BUS_ROUTE_LIMIT = 100
# module: (key, event type, interval, schema)
POLICIES = {
    "bus_arrival": ("BUS_ARRIVAL_API_KEY", "bus.arrival", 28800, "transport"),
    "bus_location": ("BUS_LOCATION_API_KEY", "bus.position", 28800, "transport"),
    "subway_arrival": ("SUBWAY_API_KEY", "subway.arrival", 360, "transport"),
    "subway_location": ("SUBWAY_API_KEY", "subway.position", 3600, "transport"),
    "subway_alert": ("SUBWAY_ALERT_API_KEY", "subway.alert", 900, "transport"),
    "city_realtime": ("CITY_REALTIME_API_KEY", "city.realtime", 300, "city"),
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


def _bus_budget_limit(name: str, settings: Settings) -> int:
    variable = "BUS_ARRIVAL_DAILY_BUDGET_PER_KEY" if name == "bus_arrival" else "BUS_LOCATION_DAILY_BUDGET_PER_KEY"
    return int(settings.extra.get(variable, settings.extra.get("API_DAILY_LIMIT", "900")))


def _claim_bus_budget(name, settings, candidates, interval, expected, allow_retry):
    path = settings.extra.get(
        "API_BUDGET_PATH", str(Path(__file__).resolve().parents[2] / "data" / "request_budget.sqlite3")
    )
    pool_variable = "BUS_ARRIVAL_QUOTA_POOL" if name == "bus_arrival" else "BUS_LOCATION_QUOTA_POOL"
    default_pool = "data-go-kr:15000314" if name == "bus_arrival" else "data-go-kr:15000332"
    pool = settings.extra.get(pool_variable, default_pool)
    limit = _bus_budget_limit(name, settings)
    budgets = [(slot, key, RequestBudget(path, key, limit, namespace=f"{pool}:{slot}")) for slot, key in candidates]
    if len(budgets) > 1:
        # Cadence belongs to the dataset, not to either credential. A shared
        # schedule prevents A/B calls from bunching when the time window changes.
        schedule = RequestBudget(path, "schedule", 1_000_000, namespace=f"{pool}:schedule")
        if not schedule.claim_cycle(name, interval, 0):
            return None, None
        for slot, key, budget in sorted(budgets, key=lambda item: (item[2].used(), item[0])):
            if budget.used() + expected <= limit:
                log.info("source=%s quotaPool=%s keySlot=%s plannedRequests=%d", name, pool, slot, expected)
                return key, budget
        raise BudgetExceeded(f"All credentials exhausted for {pool}")

    waits = []
    exceeded = []
    # Balance actual charged calls, not cycle count. Whole-route cycles stay on
    # one credential so diagnostics and partial failures remain attributable.
    for slot, key, budget in sorted(budgets, key=lambda item: (item[2].used(), item[0])):
        try:
            if budget.claim_cycle(
                name,
                interval,
                expected,
                allow_retry=allow_retry,
            ):
                log.info("source=%s quotaPool=%s keySlot=%s plannedRequests=%d", name, pool, slot, expected)
                return key, budget
        except RetryWait as exc:
            waits.append(exc)
        except BudgetExceeded as exc:
            exceeded.append(exc)
    if waits:
        raise min(waits, key=lambda exc: exc.wait_seconds)
    if len(exceeded) == len(budgets):
        raise BudgetExceeded(f"All credentials exhausted for {pool}")
    return None, None


def make_request_budget(name: str, settings: Settings, key: str) -> RequestBudget:
    """소스별 호출 한도와 quota namespace를 동일하게 생성한다."""
    path = settings.extra.get(
        "API_BUDGET_PATH", str(Path(__file__).resolve().parents[2] / "data" / "request_budget.sqlite3")
    )
    if name == "city_realtime":
        limit = int(settings.extra.get("CITY_REALTIME_DAILY_BUDGET", "40000"))
        namespace = settings.extra.get("CITY_REALTIME_QUOTA_POOL", "seoul-open-data:OA-21285")
    elif name == "topis_notice":
        limit = int(settings.extra.get("TOPIS_NOTICE_DAILY_BUDGET", "500"))
        namespace = settings.extra.get("TOPIS_NOTICE_QUOTA_POOL", "seoul-open-data:OA-22861")
    else:
        limit = int(settings.extra.get("API_DAILY_LIMIT", "900"))
        namespace = ""
    return RequestBudget(path, key, limit, namespace=namespace)


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
        ids["providerRouteId"] = str(raw.get("busRouteId") or raw.get("routeId") or context)
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
    payload = {
        **ids,
        "raw": raw,
        "observedAt": received.isoformat(),
        "timestampBasis": "received" if occurred == received else "provider",
        "contentHash": hashlib.sha256(json.dumps(raw, sort_keys=True, ensure_ascii=False).encode()).hexdigest(),
    }
    key = (
        ":".join(v for k, v in ids.items() if v and k not in {"providerNamespace", "providerLinkNamespace"})
        or payload["contentHash"]
    )
    segment_ids = None
    if name.startswith("bus_"):
        position_reason = None
        if name == "bus_location" and crosswalk:
            # Kafka partition key는 차량 단위로 유지한다. pair는 key 계산 뒤 payload에만
            # 넣어 구간 변경 때 차량 관측의 순서가 다른 partition으로 흩어지지 않게 한다.
            pair, position_reason = crosswalk.seoul_position_pair(ids["providerRouteId"], raw)
            if context and ids["providerRouteId"] != str(context):
                pair, position_reason = {}, "POSITION_ROUTE_CONTEXT_MISMATCH"
            payload.update(pair)
        mapping = (
            crosswalk.resolve(POLICIES[name][1], payload)
            if crosswalk
            else dict(
                segmentIds=[],
                state="UNRESOLVED",
                scope="NONE",
                reason="CROSSWALK_NOT_CONFIGURED",
                masterVersion=None,
                idPolicy=BUS_ID_POLICY,
                crosswalkSha256=None,
            )
        )
        if position_reason and position_reason != "NONE":
            mapping["reason"] = position_reason
        segment_ids = mapping["segmentIds"] or None
        payload["canonicalMapping"] = {k: v for k, v in mapping.items() if k != "segmentIds"}
    return Record(key=key, event_type=POLICIES[name][1], occurred_at=occurred, payload=payload, segment_ids=segment_ids)


def collect(name, settings: Settings, client):
    now = now_kst()
    resume_at = pause_until(name, settings.extra, now)
    if resume_at is not None:
        # Before key lookup, budget reservation or HTTP calls.
        raise SkippedNonService(resume_at, now)
    crosswalk = (
        _crosswalk(settings.extra.get("BUS_CROSSWALK_PATH"), settings.extra.get("BUS_CROSSWALK_SHA256"))
        if name.startswith("bus_")
        else None
    )
    module = importlib.import_module(f"sources.{name}")
    key_name, _, default_interval, _ = POLICIES[name]
    key = None
    budget = None
    bus_candidates = []
    bus_plan = None
    topis_plan = plan_topis_notice(now, settings.extra) if name == "topis_notice" else None
    incident_plan = plan_traffic_incident(now, settings.extra) if name == "traffic_incident" else None
    targets = []
    if name.startswith("bus_"):
        targets = (
            [item.strip() for item in settings.extra["BUS_ROUTE_IDS"].split(",") if item.strip()]
            if settings.extra.get("BUS_ROUTE_IDS")
            else module.load_bus_route_ids()
        )
        targets = _limit_bus_targets(targets, settings.extra.get("BUS_ROUTE_LIMIT"))
        bus_candidates = bus_key_candidates(settings, name)
        pooled = bus_dense_policy_enabled(settings, name)
        bus_plan = (
            plan_bus_collection(name, targets, now, settings.extra)
            if pooled
            else BusCollectionPlan(list(targets), default_interval, "configured-routes")
        )
        targets = bus_plan.targets
    elif name == "subway_location":
        targets = (
            [item.strip() for item in settings.extra["SUBWAY_LINES"].split(",") if item.strip()]
            if settings.extra.get("SUBWAY_LINES")
            else module.load_subway_lines()
        )
    elif name == "city_realtime":
        registry = settings.extra.get("CITY_AREA_REGISTRY_PATH")
        areas = module.load_city_areas(Path(registry)) if registry else module.load_city_areas()
        requested = settings.extra.get("CITY_AREA_IDENTIFIERS")
        targets = module.select_city_areas(areas, requested.split(",") if requested else None)
    expected = len(targets) if targets else (2 if name == "subway_arrival" else 1)
    planned_interval = (
        bus_plan.interval_seconds
        if bus_plan
        else topis_plan.interval_seconds
        if topis_plan
        else incident_plan.interval_seconds
        if incident_plan
        else default_interval
    )
    interval = max(planned_interval, settings.interval_seconds or 0)
    if bus_candidates:
        # With multiple credentials the normal dense wakeup is already the
        # retry path. Keep the legacy delayed retry only for one-key installs.
        key, budget = _claim_bus_budget(
            name,
            settings,
            bus_candidates,
            interval,
            expected,
            allow_retry=name == "bus_location" and len(bus_candidates) == 1,
        )
        if budget is None:
            log.info("source=%s cooldown active; no API request", name)
            return PollBatch(status="RATE_LIMITED", scope_key=name, error_code="POLL_COOLDOWN")
    else:
        key = settings.api_key(key_name)
        budget = make_request_budget(name, settings, key)
    if not bus_candidates and not budget.claim_cycle(name, interval, expected, allow_retry=name == "bus_location"):
        log.info("source=%s cooldown active; no API request", name)
        return PollBatch(status="RATE_LIMITED", scope_key=name, error_code="POLL_COOLDOWN")
    hook = budget.before_request
    client.event_hooks["request"].append(hook)
    try:
        if name == "bus_arrival":
            result = module.fetch_all_bus_arrivals(key, targets, client=client, requests_per_second=1)
            return PollBatch(
                [
                    to_record(name, row, route, crosswalk)
                    for route, response in result["routeResults"].items()
                    for row in _rows(response.get("msgBody", {}).get("itemList"))
                ],
                complete_snapshot=result["routeCount"] == len(targets),
                page_count=result["routeCount"],
                scope_key=bus_plan.scope if bus_plan else "configured-routes",
            )
        if name == "bus_location":
            result = module.fetch_all_bus_locations(key, targets, client=client, requests_per_second=1)
            if result.get("noResultRouteIds"):
                log.info("source=bus_location no_result_routes=%d", len(result["noResultRouteIds"]))
            return PollBatch(
                [
                    to_record(name, row, route, crosswalk)
                    for route, response in result["routeResults"].items()
                    for row in _rows(response.get("msgBody", {}).get("itemList"))
                ],
                complete_snapshot=result["routeCount"] == len(targets),
                page_count=result["routeCount"],
                scope_key=bus_plan.scope if bus_plan else "configured-routes",
            )
        if name == "subway_arrival":
            result = module.fetch_all_subway_arrivals(key, client=client)
            if result["receivedCount"] == 0:
                log.info("source=subway_arrival status=EMPTY reason=NO_DATA")
            return PollBatch(
                [to_record(name, row) for row in result["response"]["realtimeArrivalList"]],
                complete_snapshot=bool(result.get("isComplete")),
                page_count=1,
                scope_key="metropolitan-all-stations",
            )
        if name == "subway_location":
            result = module.fetch_all_subway_locations(key, targets, client=client, requests_per_second=1)
            if result["emptyLines"]:
                log.info(
                    "source=subway_location emptyLines=%s status=%s",
                    result["emptyLines"],
                    "EMPTY" if result["positionCount"] == 0 else "PARTIAL_EMPTY",
                )
            return PollBatch(
                [
                    to_record(name, row, line)
                    for line, response in result["lineResults"].items()
                    for row in response["response"].get("realtimePositionList", [])
                ],
                complete_snapshot=result["lineCount"] == len(targets),
                page_count=result["lineCount"],
                scope_key="configured-lines",
            )
        if name == "city_realtime":
            result = module.fetch_city_areas(key, targets, client=client, requests_per_second=1)
            return PollBatch(
                [to_record(name, area["response"]["CITYDATA"]) for area in result["areaResults"].values()],
                complete_snapshot=bool(result["isComplete"]),
                page_count=result["receivedAreaCount"],
                scope_key="configured-areas",
            )
        if name == "subway_alert":
            result = module.fetch_alerts(key, page_size=1000, client=client)
        elif name == "topis_notice":
            result = module.fetch_notices(
                key,
                page_size=int(settings.extra.get("TOPIS_NOTICE_PAGE_SIZE", "100")),
                fetch_all=False,
                client=client,
            )
        else:
            result = module.fetch_incidents(key, page_size=1000, fetch_all=True, client=client)
        if result.get("hasMore") and name != "topis_notice":
            log.warning(
                "source=%s response may have more rows; first %s only",
                name,
                result.get("pageSize") or len(result.get("items", [])),
            )
        reported_complete = result.get("isComplete")
        complete = name == "topis_notice" or (not bool(result.get("hasMore")) and reported_complete is not False)
        status = "SUCCESS" if complete else "PARTIAL"
        scope_key = (
            f"{topis_plan.scope}:recent-{result.get('pageSize', 100)}"
            if topis_plan
            else incident_plan.scope
            if incident_plan
            else "all-current"
        )
        batch = PollBatch(
            [to_record(name, row) for row in result["items"]],
            status=status,
            complete_snapshot=complete,
            page_count=int(result.get("requestCount") or 1),
            scope_key=scope_key,
            error_code=None if complete else "PAGE_SCOPE_PARTIAL",
        )
        # The complete set travels in one event so downstream can distinguish an
        # actual zero from a missing/late row. Oversized sets remain unavailable.
        if name == "traffic_incident" and complete:
            snapshot = {"items": result["items"], "rowCount": len(result["items"]), "completeSnapshot": True}
            if len(json.dumps(snapshot, ensure_ascii=False).encode("utf-8")) <= 800_000:
                batch.snapshot = snapshot
            else:
                log.warning("source=traffic_incident complete snapshot exceeds Kafka event limit")
        return batch
    except Exception as exc:
        if name == "bus_location":
            from sources.bus_location import BusLocationError

            if isinstance(exc, BusLocationError):
                if exc.retryable and not settings.run_once and len(bus_candidates) <= 1:
                    retry_at = budget.schedule_retry(name, 300, expected)
                    if retry_at is not None:
                        raise RetryScheduled(
                            retry_at, budget.clock(), f"source={name} collection failed: {exc}"
                        ) from None
                raise RuntimeError(
                    f"source={name} collection failed: {exc}; delayed retry unavailable or not eligible"
                ) from None
        if name in ("subway_location", "subway_arrival"):
            from sources.subway_arrival import SubwayArrivalError
            from sources.subway_location import SubwayLocationError

            if isinstance(exc, (SubwayLocationError, SubwayArrivalError)):
                raise RuntimeError(f"source={name} collection failed: {exc}") from None
        # chained HTTP exceptions contain secret URL paths. Never propagate their traceback.
        raise RuntimeError(f"source={name} collection failed ({type(exc).__name__})") from None
    finally:
        client.event_hooks["request"].remove(hook)


def make_source(name):
    _, _, interval, schema = POLICIES[name]
    return SourceSpec(
        name=f"seoul.{name}",
        topic=f"{schema}.raw",
        schema=schema,
        default_interval_seconds=interval,
        fetch=lambda settings, client: collect(name, settings, client),
    )
