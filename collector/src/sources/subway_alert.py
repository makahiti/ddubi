"""서울시 지하철 알림정보 수집기.

기본 실행은 최근 알림 100건을 한 번 조회해 JSON으로 저장한다.
전체 이력이 필요할 때만 ``--all`` 옵션을 사용하며, 호출당 최대 조회
범위인 1,000건씩 순차적으로 페이지를 요청한다.
"""

from __future__ import annotations

import argparse
import json
import os
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import httpx


API_HOST = "http://openapi.seoul.go.kr:8088"
SERVICE_NAME = "getNtceList"
API_KEY_ENV_NAME = "SUBWAY_ALERT_API_KEY"
MAX_PAGE_SIZE = 1_000
DEFAULT_PAGE_SIZE = 100
REQUEST_TIMEOUT_SECONDS = 30

COLLECTOR_DIR = Path(__file__).resolve().parents[2]
DEFAULT_ENV_PATH = COLLECTOR_DIR / ".env"
DEFAULT_OUTPUT_PATH = COLLECTOR_DIR / "data" / "subway_alert.json"


class SubwayAlertError(RuntimeError):
    """지하철 알림 API 호출 또는 응답 검증 실패."""


def load_env_file(path: Path = DEFAULT_ENV_PATH) -> None:
    """python-dotenv 없이 단순 KEY=VALUE 형식의 .env를 읽는다."""
    if not path.exists():
        return

    for raw_line in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key:
            os.environ.setdefault(key, value)


def require_api_key() -> str:
    load_env_file()
    api_key = os.getenv(API_KEY_ENV_NAME, "").strip()
    if not api_key:
        raise SubwayAlertError(
            f"{DEFAULT_ENV_PATH}에 {API_KEY_ENV_NAME}를 설정해야 합니다."
        )
    return api_key


def _validate_range(start_index: int, end_index: int) -> None:
    if start_index < 1:
        raise ValueError("START_INDEX는 1 이상이어야 합니다.")
    if end_index < start_index:
        raise ValueError("END_INDEX는 START_INDEX 이상이어야 합니다.")
    if end_index - start_index + 1 > MAX_PAGE_SIZE:
        raise ValueError(
            f"한 번에 요청할 수 있는 범위는 최대 {MAX_PAGE_SIZE}건입니다."
        )


def _request_json(
    api_key: str, start_index: int, end_index: int, *, client: httpx.Client | None = None
) -> dict[str, Any]:
    _validate_range(start_index, end_index)
    url = (
        f"{API_HOST}/{api_key}/json/{SERVICE_NAME}/"
        f"{start_index}/{end_index}/"
    )
    request = Request(url, headers={"User-Agent": "seoul-data-collector/1.0"})

    if client is not None:
        response = client.get(url, headers={"Accept": "application/json"}, follow_redirects=True)
        if response.status_code != 200:
            raise SubwayAlertError(f"API HTTP 오류: {response.status_code}")
        payload = response.json()
        if not isinstance(payload, dict):
            raise SubwayAlertError("API 최상위 응답이 JSON 객체가 아닙니다.")
        return payload

    try:
        with urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            raw = response.read().decode("utf-8")
    except HTTPError as exc:
        raise SubwayAlertError(f"API HTTP 오류가 발생했습니다: {exc.code}") from exc
    except URLError as exc:
        raise SubwayAlertError("지하철 알림 API에 연결하지 못했습니다.") from exc

    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise SubwayAlertError("API 응답이 올바른 JSON이 아닙니다.") from exc
    if not isinstance(payload, dict):
        raise SubwayAlertError("API 최상위 응답이 JSON 객체가 아닙니다.")
    return payload


def _items_as_list(value: Any) -> list[dict[str, Any]]:
    if value is None:
        return []
    if isinstance(value, dict):
        return [value]
    if isinstance(value, list) and all(isinstance(item, dict) for item in value):
        return value
    raise SubwayAlertError("API의 알림 목록 형식을 해석할 수 없습니다.")


def _parse_response(payload: dict[str, Any]) -> tuple[int, list[dict[str, Any]]]:
    """서울 OpenAPI 기본 형식과 items/item 래퍼 형식을 모두 처리한다."""
    service = payload.get(SERVICE_NAME)
    if isinstance(service, dict):
        result = service.get("RESULT") or {}
        code = str(result.get("CODE", ""))
        message = str(result.get("MESSAGE", ""))
        if code not in {"INFO-000", "INFO-200"}:
            raise SubwayAlertError(f"API 오류 {code or 'UNKNOWN'}: {message}")
        items = _items_as_list(service.get("row"))
        total_count = int(service.get("list_total_count") or len(items))
        return total_count, items

    response = payload.get("response")
    if isinstance(response, dict):
        header = response.get("header") or {}
        code = str(header.get("resultCode", header.get("RESULT_CODE", "")))
        message = str(header.get("resultMsg", header.get("RESULT_MSG", "")))
        if code and code not in {"00", "0", "INFO-000", "INFO-200"}:
            raise SubwayAlertError(f"API 오류 {code}: {message}")
        body = response.get("body") or {}
        items_wrapper = body.get("items") or {}
        item_value = (
            items_wrapper.get("item")
            if isinstance(items_wrapper, dict)
            else items_wrapper
        )
        items = _items_as_list(item_value)
        total_count = int(body.get("totalCount") or len(items))
        return total_count, items

    items_wrapper = payload.get("items")
    if isinstance(items_wrapper, dict) and "item" in items_wrapper:
        items = _items_as_list(items_wrapper.get("item"))
        total_count = int(payload.get("totalCount") or len(items))
        return total_count, items

    raise SubwayAlertError(
        f"응답에서 {SERVICE_NAME} 또는 response.body.items를 찾지 못했습니다."
    )


def fetch_alerts(
    api_key: str,
    *,
    start_index: int = 1,
    page_size: int = DEFAULT_PAGE_SIZE,
    fetch_all: bool = False,
    client: httpx.Client | None = None,
) -> dict[str, Any]:
    """알림을 조회한다. fetch_all=False이면 지정 범위 한 페이지만 요청한다."""
    if page_size < 1 or page_size > MAX_PAGE_SIZE:
        raise ValueError(f"page_size는 1~{MAX_PAGE_SIZE} 범위여야 합니다.")
    if start_index < 1:
        raise ValueError("start_index는 1 이상이어야 합니다.")

    collected_items: list[dict[str, Any]] = []
    current_start = start_index
    total_count = 0
    request_count = 0

    while True:
        current_end = current_start + page_size - 1
        payload = (_request_json(api_key, current_start, current_end, client=client)
                   if client is not None else _request_json(api_key, current_start, current_end))
        total_count, page_items = _parse_response(payload)
        collected_items.extend(page_items)
        request_count += 1

        if not fetch_all or not page_items or current_end >= total_count:
            break
        current_start = current_end + 1

    last_requested_index = start_index + request_count * page_size - 1
    return {
        "collectedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
        "service": SERVICE_NAME,
        "fetchMode": "all" if fetch_all else "recent",
        "startIndex": start_index,
        "endIndex": last_requested_index,
        "pageSize": page_size,
        "requestCount": request_count,
        "totalCount": total_count,
        "receivedCount": len(collected_items),
        "hasMore": last_requested_index < total_count,
        "items": collected_items,
    }


def save_json(data: dict[str, Any], output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="서울시 지하철 알림정보 수집")
    parser.add_argument(
        "--start-index", type=int, default=1, help="조회 시작 행 (기본값: 1)"
    )
    parser.add_argument(
        "--page-size",
        type=int,
        default=DEFAULT_PAGE_SIZE,
        help=f"호출당 조회 건수 1~{MAX_PAGE_SIZE} (기본값: {DEFAULT_PAGE_SIZE})",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        dest="fetch_all",
        help="전체 이력을 최대 1,000건 단위로 페이지 조회",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT_PATH,
        help=f"저장할 JSON 경로 (기본값: {DEFAULT_OUTPUT_PATH})",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        data = fetch_alerts(
            require_api_key(),
            start_index=args.start_index,
            page_size=args.page_size,
            fetch_all=args.fetch_all,
        )
        save_json(data, args.output)
    except (SubwayAlertError, ValueError) as exc:
        print(f"[실패] {exc}")
        return 1

    print(
        f"[완료] {data['receivedCount']}건 저장 "
        f"(전체 {data['totalCount']}건, API {data['requestCount']}회): {args.output}"
    )
    return 0


# 서버 main.py가 읽는 Kafka 수집 계약. 직접 실행 JSON 기능도 유지한다.
if __package__:
    from sources.kafka_adapter import make_source
    SOURCE = make_source("subway_alert")


if __name__ == "__main__":
    raise SystemExit(main())
