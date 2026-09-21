"""TOPIS 공지의 AI 구조화와 카카오 지오코딩을 실시간·백필에서 함께 쓴다."""

from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timedelta, timezone

import requests

KST = timezone(timedelta(hours=9))
VERSION = "topis-enrichment-v1"
PROMPT_VERSION = "topis-extraction-v1"
ALLOWED_EVENTS = {"ASSEMBLY", "MARCH", "EVENT", "ROAD_CONTROL", "OTHER"}
ALLOWED_IMPACTS = {"VEHICLE_CONTROL", "DETOUR", "NON_STOP", "LANE_CONTROL", "NONE", "UNKNOWN"}
ALLOWED_STATUSES = {"PLANNED", "ACTIVE", "ENDED", "UNKNOWN"}
ROLES = {"START", "WAYPOINT", "END", "AFFECTED_AREA"}


class EnrichmentError(RuntimeError):
    pass


class ExternalServiceError(EnrichmentError):
    """재시도하면 복구될 수 있는 GMS·Kakao 호출 실패."""


class InvalidAIResponse(EnrichmentError):
    """호출은 성공했지만 계약 JSON으로 해석할 수 없는 응답."""


def content_hash(raw):
    return hashlib.sha256(
        json.dumps(raw, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


class GmsClient:
    def __init__(self, api_key, base_url="https://gms.ssafy.io/gmsapi/", model="gpt-4o-mini", timeout=30):
        if not api_key:
            raise ValueError("GMS_API_KEY is required")
        self.url = base_url.rstrip("/") + "/api.openai.com/v1/chat/completions"
        self.key, self.model, self.timeout, self.cache = api_key, model, timeout, {}

    def _chat(self, task, prompt):
        cache_key = (task, hashlib.sha256(prompt.encode("utf-8")).hexdigest())
        if cache_key in self.cache:
            return self.cache[cache_key]
        try:
            response = requests.post(
                self.url,
                timeout=self.timeout,
                headers={"Authorization": "Bearer " + self.key, "Content-Type": "application/json"},
                json={
                    "model": self.model,
                    "temperature": 0,
                    "response_format": {"type": "json_object"},
                    "messages": [
                        {
                            "role": "system",
                            "content": "너는 서울 교통 공지 정보 추출기다. 추측하지 말고 JSON만 반환한다.",
                        },
                        {"role": "user", "content": prompt},
                    ],
                },
            )
        except requests.RequestException as exc:
            raise ExternalServiceError("GMS request failed: " + type(exc).__name__) from exc
        if response.status_code != 200:
            raise ExternalServiceError(f"GMS request failed: HTTP {response.status_code}")
        try:
            result = json.loads(response.json()["choices"][0]["message"]["content"])
        except (KeyError, IndexError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise InvalidAIResponse("GMS response is not valid structured JSON") from exc
        self.cache[cache_key] = result
        return result

    def extract(self, notice):
        schema = {
            "events": [
                {
                    "eventType": "ASSEMBLY|MARCH|EVENT|ROAD_CONTROL|OTHER",
                    "impactType": "VEHICLE_CONTROL|DETOUR|NON_STOP|LANE_CONTROL|NONE|UNKNOWN",
                    "eventStatus": "PLANNED|ACTIVE|ENDED|UNKNOWN",
                    "startDateText": None,
                    "startTimeText": None,
                    "endDateText": None,
                    "endTimeText": None,
                    "roadNames": [],
                    "routePoints": [{"order": 1, "placeText": "", "role": "START|WAYPOINT|END|AFFECTED_AREA"}],
                    "affectedBusRoutes": [],
                    "affectedBusStops": [],
                    "evidenceText": "",
                }
            ]
        }
        prompt = (
            "다음 서울 TOPIS 공지에서 교통 영향 행사 정보를 추출하라. 원문에 없는 값은 null/빈 배열로 두고, "
            "장소는 이동 순서대로 유지하며 근거 문장은 원문 그대로 써라. JSON만 반환하라. 스키마 예: "
            + json.dumps(schema, ensure_ascii=False)
            + "\n입력: "
            + json.dumps(notice, ensure_ascii=False)
        )
        return self._chat("extract", prompt)

    def rewrite_place_queries(self, place_text, notice_text):
        prompt = (
            "카카오 장소 키워드 검색에 실패한 서울의 장소 표현을 검색 가능한 후보로 바꿔라. "
            '원문에 없는 장소를 만들지 말고 최대 3개만 반환하라. JSON 형식은 {"queries":["..."]}이다.\n'
            + json.dumps({"placeText": place_text, "noticeText": notice_text}, ensure_ascii=False)
        )
        result = self._chat("rewrite-place", prompt)
        values = result.get("queries") if isinstance(result, dict) else None
        if not isinstance(values, list):
            return []
        return [value.strip() for value in values[:3] if isinstance(value, str) and value.strip()]


class KakaoGeocoder:
    URL = "https://dapi.kakao.com/v2/local/search/keyword.json"

    def __init__(self, api_key, timeout=10):
        if not api_key:
            raise ValueError("KAKAO_REST_API_KEY is required")
        self.key, self.timeout, self.cache = api_key, timeout, {}

    def search(self, place_text, extra_queries=None):
        queries = ["서울특별시 " + place_text, place_text] + list(extra_queries or [])
        queries = list(dict.fromkeys(query.strip() for query in queries if query and query.strip()))
        saw_non_seoul = False
        for query in queries:
            if query in self.cache:
                result = self.cache[query]
            else:
                try:
                    response = requests.get(
                        self.URL,
                        timeout=self.timeout,
                        headers={"Authorization": "KakaoAK " + self.key},
                        params={"query": query, "size": 5},
                    )
                except requests.RequestException as exc:
                    raise ExternalServiceError("Kakao geocoder failed: " + type(exc).__name__) from exc
                if response.status_code != 200:
                    raise ExternalServiceError(f"Kakao geocoder failed: HTTP {response.status_code}")
                try:
                    result = response.json().get("documents", [])
                except (AttributeError, ValueError) as exc:
                    raise ExternalServiceError("Kakao geocoder returned invalid JSON") from exc
                self.cache[query] = result
            # 이 잡은 서울 버스 경로에만 사용한다. "서울" 접두어 검색이 타 지역의
            # 동명 장소만 돌려줘도 전체 결과로 fallback하지 않는다.
            candidates = [x for x in result if "서울" in (x.get("address_name", "") + x.get("road_address_name", ""))]
            saw_non_seoul = saw_non_seoul or bool(result and not candidates)
            if candidates:

                def canonical(value):
                    value = re.sub(r"\s+", "", value or "")
                    return value.replace("서울특별시청", "서울시청")

                exact = [item for item in candidates if canonical(item.get("place_name")) == canonical(place_text)]
                item = exact[0] if len(exact) == 1 else candidates[0]
                matched = len(exact) == 1 or len(candidates) == 1
                return {
                    "geocodeQuery": query,
                    "matchedPlaceName": item.get("place_name"),
                    "kakaoPlaceId": item.get("id"),
                    "latitude": float(item["y"]),
                    "longitude": float(item["x"]),
                    "geocodeStatus": "MATCHED" if matched else "AMBIGUOUS",
                    "geocodeCandidateCount": len(candidates),
                    "geocodeMatchBasis": "EXACT_PLACE_NAME"
                    if len(exact) == 1
                    else ("SINGLE_CANDIDATE" if len(candidates) == 1 else "FIRST_OF_MULTIPLE"),
                    "usableForBusMapping": matched,
                }
        return {
            "geocodeQuery": queries[-1],
            "matchedPlaceName": None,
            "kakaoPlaceId": None,
            "latitude": None,
            "longitude": None,
            "geocodeStatus": "NOT_FOUND",
            "geocodeCandidateCount": 0,
            "geocodeMatchBasis": "NO_SEOUL_CANDIDATE" if saw_non_seoul else "NO_CANDIDATE",
            "usableForBusMapping": False,
        }


def _clean_text(value):
    return str(value).strip() if value is not None and str(value).strip() else None


def _parse_datetime(date_text, time_text, collected_at, source_text=None):
    if not date_text or not time_text:
        return None, False
    date_match = re.search(r"(?:(\d{4})\s*(?:년|[./-])\s*)?(\d{1,2})\s*(?:월|[./-])\s*(\d{1,2})\s*일?", str(date_text))
    time_match = re.search(r"(?:(\d{1,2})\s*시(?:\s*(\d{1,2})\s*분)?|(\d{1,2})\s*:\s*(\d{2}))", str(time_text))
    if not date_match or not time_match:
        return None, False
    base = datetime.fromisoformat(str(collected_at).replace("Z", "+00:00")).astimezone(KST)
    source_has_year = bool(re.search(r"\b(?:19|20)\d{2}\b|(?:19|20)\d{2}\s*년", source_text or ""))
    inferred = date_match.group(1) is None or not source_has_year
    year = int(date_match.group(1)) if date_match.group(1) and source_has_year else base.year
    hour = int(time_match.group(1) or time_match.group(3))
    minute = int(time_match.group(2) or time_match.group(4) or 0)
    try:
        result = datetime(year, int(date_match.group(2)), int(date_match.group(3)), hour, minute, tzinfo=KST)
    except ValueError:
        return None, inferred
    if inferred and result < base - timedelta(days=45):
        result = result.replace(year=year + 1)
    return result.isoformat(), inferred


def _empty_result(base, status, reason, event_id=None):
    return {
        **base,
        "eventId": event_id,
        "eventType": None,
        "impactType": None,
        "eventStatus": "UNKNOWN",
        "startAt": None,
        "endAt": None,
        "validUntil": None,
        "durationMinutes": None,
        "yearInferred": False,
        "yearBasis": None,
        "evidenceText": None,
        "processingStatus": status,
        "rejectionReasons": [reason] if reason else [],
        "qualityFlags": [],
        "missingFields": [],
        "roadNames": [],
        "routePoints": [],
        "routeLegs": [],
        "routePointCount": 0,
        "mappedRoutePointCount": 0,
        "routeMappingReady": False,
        "affectedBusRoutes": [],
        "affectedBusStops": [],
    }


def _route_legs(points):
    legs = []
    for index, (start, end) in enumerate(zip(points, points[1:]), 1):
        mapped = start["usableForBusMapping"] and end["usableForBusMapping"]
        legs.append(
            {
                "legOrder": index,
                "fromOrder": start["order"],
                "toOrder": end["order"],
                "fromPlaceText": start["placeText"],
                "toPlaceText": end["placeText"],
                "fromLatitude": start["latitude"],
                "fromLongitude": start["longitude"],
                "toLatitude": end["latitude"],
                "toLongitude": end["longitude"],
                "mappingStatus": "MAPPED" if mapped else "PARTIAL",
            }
        )
    return legs


def normalize_notice(envelope, gms, geocoder):
    """Return one fixed-shape row per extracted event; errors are data, not job failures."""
    payload, rows = envelope.get("payload") or {}, []
    raw = payload.get("raw") if isinstance(payload.get("raw"), dict) else {}
    notice_id = _clean_text(raw.get("noticeId") or payload.get("providerNoticeId"))
    base = {
        "silverVersion": VERSION,
        "noticeId": notice_id,
        "sourceEventId": envelope.get("eventId"),
        "source": envelope.get("source"),
        "title": _clean_text(raw.get("title")),
        "contentText": _clean_text(raw.get("contentText")),
        "categoryName": _clean_text(raw.get("categoryName")),
        "createdAtText": _clean_text(raw.get("createdAt")),
        "updatedAtText": _clean_text(raw.get("updatedAt")),
        "collectedAt": envelope.get("collectedAt"),
        "noticeContentHash": content_hash(raw),
        "extractionModel": getattr(gms, "model", None),
        "promptVersion": PROMPT_VERSION,
    }
    if not notice_id or not base["contentText"] or not base["collectedAt"]:
        return [_empty_result(base, "REJECTED", "MISSING_NOTICE_ID_OR_TEXT_OR_COLLECTION_TIME")]
    try:
        extracted = gms.extract({k: base[k] for k in ("noticeId", "title", "contentText", "collectedAt")})
        events = extracted.get("events")
        if not isinstance(events, list) or len(events) > 20:
            raise InvalidAIResponse("GMS events must be an array")
        if not events:
            return [_empty_result(base, "NOT_RELEVANT", "NO_TRAFFIC_EVENT", notice_id + "-0")]
    except ExternalServiceError:
        raise
    except Exception as exc:
        return [_empty_result(base, "INVALID_AI_RESPONSE", type(exc).__name__, notice_id + "-error")]
    for index, event in enumerate(events, 1):
        if not isinstance(event, dict):
            rows.append(_empty_result(base, "INVALID_AI_RESPONSE", "EVENT_IS_NOT_OBJECT", notice_id + "-" + str(index)))
            continue
        reasons, flags = [], []
        event_type, impact = event.get("eventType"), event.get("impactType")
        if event_type not in ALLOWED_EVENTS:
            reasons.append("INVALID_EVENT_TYPE")
        if impact not in ALLOWED_IMPACTS:
            reasons.append("INVALID_IMPACT_TYPE")
        event_status = event.get("eventStatus") or "UNKNOWN"
        if event_status not in ALLOWED_STATUSES:
            reasons.append("INVALID_EVENT_STATUS")
        start, inferred1 = _parse_datetime(
            event.get("startDateText"), event.get("startTimeText"), base["collectedAt"], base["contentText"]
        )
        end, inferred2 = _parse_datetime(
            event.get("endDateText") or event.get("startDateText"),
            event.get("endTimeText"),
            base["collectedAt"],
            base["contentText"],
        )
        if start is None:
            flags.append("START_TIME_UNAVAILABLE")
        if end is None:
            flags.append("END_TIME_UNAVAILABLE")
        if start and end and end <= start:
            reasons.append("END_NOT_AFTER_START")
        road_names = (
            [_clean_text(value) for value in event.get("roadNames", [])]
            if isinstance(event.get("roadNames"), list)
            else []
        )
        road_names = [value for value in road_names if value]
        points = []
        raw_points = event.get("routePoints") if isinstance(event.get("routePoints"), list) else []
        if not raw_points:
            raw_points = [{"placeText": value, "role": "AFFECTED_AREA"} for value in road_names]
        for point_index, point in enumerate(raw_points, 1):
            place = _clean_text(point.get("placeText")) if isinstance(point, dict) else None
            if not place:
                continue
            role = point.get("role") if point.get("role") in ROLES else ("START" if point_index == 1 else "WAYPOINT")
            geo = geocoder.search(place)
            if geo["geocodeStatus"] == "NOT_FOUND":
                try:
                    geo = geocoder.search(place, gms.rewrite_place_queries(place, base["contentText"]))
                except InvalidAIResponse:
                    flags.append("GEOCODE_QUERY_REWRITE_INVALID")
            radius = 1000 if role == "AFFECTED_AREA" or place in road_names else 500
            points.append(
                {
                    "order": point_index,
                    "placeText": place,
                    "role": role,
                    "radiusMeters": radius,
                    "radiusBasis": "ROAD_REPRESENTATIVE" if radius == 1000 else "PLACE_DEFAULT",
                    **geo,
                }
            )
        if not points:
            flags.append("ROUTE_POINTS_UNAVAILABLE")
        if any(not p["usableForBusMapping"] for p in points):
            flags.append("ROUTE_POINT_NOT_EXACT")
        evidence = _clean_text(event.get("evidenceText"))
        if evidence and "".join(evidence.split()) not in "".join(base["contentText"].split()):
            flags.append("EVIDENCE_NOT_IN_SOURCE")
        status = "REJECTED" if reasons else ("PARTIAL" if flags else "VALID")
        duration = None
        if start and end:
            duration = int((datetime.fromisoformat(end) - datetime.fromisoformat(start)).total_seconds() // 60)
        valid_until = (
            (datetime.fromisoformat(end) + timedelta(hours=6)).isoformat()
            if end
            else (datetime.fromisoformat(base["collectedAt"].replace("Z", "+00:00")) + timedelta(hours=24)).isoformat()
        )
        legs = _route_legs(points)
        mapped_count = sum(1 for point in points if point["usableForBusMapping"])
        row = {
            **base,
            "eventId": notice_id + "-" + str(index),
            "eventType": event_type,
            "impactType": impact,
            "eventStatus": event_status,
            "startAt": start,
            "endAt": end,
            "validUntil": valid_until,
            "durationMinutes": duration,
            "yearInferred": inferred1 or inferred2,
            "yearBasis": "COLLECTED_AT_YEAR" if inferred1 or inferred2 else "SOURCE_TEXT",
            "roadNames": road_names,
            "routePoints": points,
            "routeLegs": legs,
            "routePointCount": len(points),
            "mappedRoutePointCount": mapped_count,
            "routeMappingReady": bool(points) and mapped_count == len(points),
            "affectedBusRoutes": event.get("affectedBusRoutes")
            if isinstance(event.get("affectedBusRoutes"), list)
            else [],
            "affectedBusStops": event.get("affectedBusStops")
            if isinstance(event.get("affectedBusStops"), list)
            else [],
            "evidenceText": evidence,
            "processingStatus": status,
            "rejectionReasons": reasons,
            "qualityFlags": flags,
        }
        row["missingFields"] = [k for k in ("startAt", "endAt", "evidenceText") if not row.get(k)]
        rows.append(row)
    return rows
