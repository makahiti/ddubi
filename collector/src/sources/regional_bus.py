"""경기·인천 공식 XML API를 기존 transport.raw 봉투로 연결하는 선택 실행 수집기.

운영 compose와 기존 수집 주기는 바꾸지 않는다. 명시한 정류소/노선만 조회하며 같은 키의
호출 예산을 공유한다. 인천 API ID는 CSV ID와 동일하다고 가정하지 않고 INCHEON_API로
보존한다. 위치 정류소 하나로 인접 구간이나 실제 통과시간을 추측하지 않는다.
"""

import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import unquote
from config import ConfigError
from request_budget import RequestBudget
from sources.base import Record, SourceSpec, now_kst
from sources.kafka_adapter import _crosswalk

SERVICES = {
    "gbis_arrival": (
        "GBIS",
        "GBIS_API_KEY",
        "https://apis.data.go.kr/6410000/busarrivalservice/v2/getBusArrivalListv2",
        "stationId",
        "bus.arrival",
    ),
    "gbis_location": (
        "GBIS",
        "GBIS_API_KEY",
        "https://apis.data.go.kr/6410000/buslocationservice/v2/getBusLocationListv2",
        "routeId",
        "bus.position",
    ),
    "incheon_arrival": (
        "INCHEON_API",
        "INCHEON_API_KEY",
        "https://apis.data.go.kr/6280000/busArrivalService/getAllRouteBusArrivalList",
        "bstopId",
        "bus.arrival",
    ),
    "incheon_location": (
        "INCHEON_API",
        "INCHEON_API_KEY",
        "https://apis.data.go.kr/6280000/busLocationService/getBusRouteLocation",
        "routeId",
        "bus.position",
    ),
}


def parse_page(content, namespace):
    """HTTP 200인 제공자 오류도 실패시킨다. 빈 정상 응답과 실패를 구분하고 원본 필드를 보존한다."""
    root = ET.fromstring(content)
    code = root.findtext(".//resultCode")
    if code not in ({"0", "4"} if namespace == "GBIS" else {"00", "0"}):
        raise ValueError("provider response unsuccessful")
    if code == "4":
        return [], 0
    rows = []
    for node in root.iter():
        values = {child.tag: child.text or "" for child in node if len(child) == 0}
        if ("routeId" if namespace == "GBIS" else "ROUTEID") in values:
            rows.append(values)
    total = root.findtext(".//totalCount")
    return rows, int(total) if total is not None else len(rows)


def to_record(name, raw, target, crosswalk=None):
    """원본 ID·순번·예측 단위를 보존한다. 제공자 관측시각이 없으면 수신시각임을 명시한다."""
    namespace, _, _, _, event_type = SERVICES[name]
    gbis = namespace == "GBIS"
    received = now_kst()
    payload = dict(
        providerNamespace=namespace,
        providerRouteId=raw.get("routeId" if gbis else "ROUTEID", ""),
        providerStopId=raw.get(
            "stationId" if gbis else "BSTOPID",
            target if event_type == "bus.arrival" else raw.get("LATEST_STOP_ID", ""),
        ),
        providerVehicleId=raw.get("vehId" if gbis else "BUSID", ""),
        raw=raw,
        observedAt=received.isoformat(),
        timestampBasis="received",
        contentHash=hashlib.sha256(
            json.dumps(raw, sort_keys=True, ensure_ascii=False).encode()
        ).hexdigest(),
    )
    if not payload["providerRouteId"]:
        raise ValueError("provider route ID missing")
    # XML에 없는 다음 정류소 ID를 순번으로 발명하지 않는다. 인천은 API↔CSV 검증 전 미매핑이다.
    mapping = (
        crosswalk.resolve(event_type, payload)
        if crosswalk
        else dict(
            segmentIds=[],
            state="UNRESOLVED",
            scope="NONE",
            reason="CROSSWALK_NOT_CONFIGURED",
            masterVersion=None,
            idPolicy="canonical-adjacent-v2",
            crosswalkSha256=None,
        )
    )
    payload["canonicalMapping"] = {
        k: v for k, v in mapping.items() if k != "segmentIds"
    }
    key = ":".join(
        (
            namespace,
            payload["providerRouteId"],
            payload["providerStopId"],
            payload["providerVehicleId"],
        )
    )
    return Record(
        key=key,
        event_type=event_type,
        occurred_at=received,
        payload=payload,
        segment_ids=mapping["segmentIds"] or None,
    )


def collect(name, settings, client):
    """명시한 대상만 페이지 끝까지 수집한다. 페이지 누락/전체 건수 변경은 부분 성공으로 숨기지 않는다."""
    namespace, key_name, url, target_param, _ = SERVICES[name]
    targets = list(
        dict.fromkeys(
            t.strip()
            for t in settings.extra.get("REGIONAL_BUS_TARGET_IDS", "").split(",")
            if t.strip()
        )
    )
    if not targets or any(not re.fullmatch(r"[0-9]{1,30}", t) for t in targets):
        raise ConfigError("REGIONAL_BUS_TARGET_IDS requires explicit provider IDs")
    key = unquote(settings.api_key(key_name))
    budget = RequestBudget(
        settings.extra.get(
            "API_BUDGET_PATH",
            str(Path(__file__).resolve().parents[2] / "data/request_budget.sqlite3"),
        ),
        key,
        int(settings.extra.get("API_DAILY_LIMIT", "900")),
    )
    if not budget.claim_cycle(name, settings.interval_seconds or 28800, len(targets)):
        return []
    crosswalk = _crosswalk(
        settings.extra.get("BUS_CROSSWALK_PATH"),
        settings.extra.get("BUS_CROSSWALK_SHA256"),
    )
    hook = budget.before_request
    client.event_hooks["request"].append(hook)
    try:
        records = []
        for target in targets:
            count = 0
            previous_total = None
            for page in range(1, 101):
                params = {
                    target_param: target,
                    "serviceKey" if namespace == "GBIS" else "ServiceKey": key,
                }
                if namespace == "GBIS":
                    params["format"] = "xml"
                else:
                    params.update(pageNo=page, numOfRows=100)
                response = client.get(url, params=params, timeout=30)
                response.raise_for_status()
                rows, total = parse_page(response.content, namespace)
                if total < 0 or (
                    previous_total is not None and total != previous_total
                ):
                    raise ValueError("provider pagination changed")
                previous_total = total
                count += len(rows)
                records.extend(to_record(name, r, target, crosswalk) for r in rows)
                if namespace == "GBIS" or count >= total:
                    break
                if not rows:
                    raise ValueError("incomplete provider page")
            else:
                raise ValueError("provider page bound exceeded")
        return records
    except Exception as exc:
        # URL query에는 인증키가 있으므로 원본 HTTP 예외나 본문을 로그로 전파하지 않는다.
        raise RuntimeError(
            "regional bus collection failed: " + type(exc).__name__
        ) from None
    finally:
        client.event_hooks["request"].remove(hook)


def make_source(name):
    """명시적 COLLECTOR_SOURCE로만 시작한다. 기존 서울 SOURCE와 compose는 그대로 유지한다."""
    return SourceSpec(
        name=name.replace("_", ".", 1),
        topic="transport.raw",
        schema="transport",
        default_interval_seconds=28800,
        fetch=lambda settings, client: collect(name, settings, client),
    )
