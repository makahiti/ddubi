"""서울시 실시간 돌발 정보(OA-13315) 수집기.

``AccInfo`` API가 제공하는 XML을 검증한 뒤 활용하기 쉬운 JSON으로
변환해 저장한다. 이 데이터셋은 현재 공개 중인 돌발 상황 목록이므로
기본 실행은 최대 1,000건씩 페이지를 넘기며 전체 목록을 수집한다.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import quote

import httpx

API_HOST = "http://openapi.seoul.go.kr:8088"
SERVICE_NAME = "AccInfo"
API_KEY_ENV_NAME = "TRAFFIC_INCIDENT_API_KEY"
PORTAL_API_KEY_ENV_NAME = "SEOUL_OPEN_DATA_API_KEY"
MAX_PAGE_SIZE = 1_000
DEFAULT_PAGE_SIZE = 1_000
MAX_FETCH_ALL_PAGES = 100

COLLECTOR_DIR = Path(__file__).resolve().parents[2]
DEFAULT_ENV_PATH = COLLECTOR_DIR / ".env"
DEFAULT_OUTPUT_PATH = COLLECTOR_DIR / "data" / "traffic_incidents.json"
_ENV_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


class TrafficIncidentError(RuntimeError):
    """실시간 돌발 정보 요청, 응답 검증 또는 저장 실패."""


def load_env_file(path: Path = DEFAULT_ENV_PATH) -> None:
    """단순 KEY=VALUE 형식의 환경 파일을 읽는다."""
    if not path.exists():
        return

    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        if "=" not in line:
            raise TrafficIncidentError(f"{path}:{line_number}: KEY=VALUE 형식이 아닙니다")

        name, value = line.split("=", 1)
        name, value = name.strip(), value.strip()
        if not _ENV_NAME.fullmatch(name):
            raise TrafficIncidentError(f"{path}:{line_number}: 환경변수 이름이 올바르지 않습니다")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        os.environ.setdefault(name, value)


def require_api_key(env_path: Path = DEFAULT_ENV_PATH) -> str:
    load_env_file(env_path)
    api_key = (os.getenv(API_KEY_ENV_NAME) or os.getenv(PORTAL_API_KEY_ENV_NAME, "")).strip()
    if not api_key:
        raise TrafficIncidentError(
            f"{env_path}에 서울 열린데이터광장 일반 인증키를 {API_KEY_ENV_NAME} 또는 {PORTAL_API_KEY_ENV_NAME}로 설정해야 합니다"
        )
    return api_key


def _validate_range(start_index: int, end_index: int) -> None:
    if start_index < 1:
        raise ValueError("START_INDEX는 1 이상이어야 합니다")
    if end_index < start_index:
        raise ValueError("END_INDEX는 START_INDEX 이상이어야 합니다")
    if end_index - start_index + 1 > MAX_PAGE_SIZE:
        raise ValueError(f"한 번에 최대 {MAX_PAGE_SIZE}건까지 요청할 수 있습니다")


def _element_text(element: ET.Element, tag: str) -> str:
    child = element.find(tag)
    return (child.text or "").strip() if child is not None else ""


def _normalise_row(row: ET.Element) -> dict:
    fields = {child.tag.lower(): (child.text or "").strip() for child in row}
    return {
        "incidentId": fields.get("acc_id", ""),
        "occurredDate": fields.get("occr_date", ""),
        "occurredTime": fields.get("occr_time", ""),
        "expectedClearDate": fields.get("exp_clr_date", ""),
        "expectedClearTime": fields.get("exp_clr_time", ""),
        "incidentTypeCode": fields.get("acc_type", ""),
        "incidentDetailTypeCode": fields.get("acc_dtype", ""),
        "linkId": fields.get("link_id", ""),
        "grs80tmX": fields.get("grs80tm_x", ""),
        "grs80tmY": fields.get("grs80tm_y", ""),
        "description": fields.get("acc_info", ""),
        # 신규 필드와 원본 필드명을 보존하여 향후 통제 범위·해제 정보도 재처리할 수 있게 한다.
        "providerFields": {child.tag: (child.text or "") for child in row},
    }


def parse_incident_xml(xml_text: str) -> tuple[int, list[dict]]:
    """AccInfo XML의 결과 코드를 검사하고 돌발 목록을 정규화한다."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise TrafficIncidentError("실시간 돌발 정보 응답이 올바른 XML이 아닙니다") from exc

    result = root.find("RESULT") if root.tag != "RESULT" else root
    code = _element_text(result, "CODE") if result is not None else ""
    message = _element_text(result, "MESSAGE") if result is not None else ""
    if code not in {"INFO-000", "INFO-200"}:
        raise TrafficIncidentError(f"실시간 돌발 정보 API 오류({code or 'UNKNOWN'}): {message}")

    total_text = _element_text(root, "list_total_count")
    try:
        total_count = int(total_text or 0)
    except ValueError as exc:
        raise TrafficIncidentError("API의 전체 돌발 건수가 정수가 아닙니다") from exc

    return total_count, [_normalise_row(row) for row in root.findall("row")]


def fetch_incident_page(
    api_key: str,
    start_index: int,
    end_index: int,
    *,
    client: httpx.Client | None = None,
) -> tuple[int, list[dict[str, Any]]]:
    """돌발 정보 한 페이지를 XML로 호출해 정규화된 항목을 반환한다."""
    _validate_range(start_index, end_index)
    key = api_key.strip()
    if not key:
        raise TrafficIncidentError(f"{API_KEY_ENV_NAME} 또는 {PORTAL_API_KEY_ENV_NAME}가 비어 있습니다")

    url = f"{API_HOST}/{quote(key, safe='')}/xml/{SERVICE_NAME}/{start_index}/{end_index}/"
    owns_client = client is None
    http_client = client or httpx.Client(timeout=httpx.Timeout(30.0, connect=5.0))
    try:
        response = http_client.get(
            url,
            headers={"Accept": "application/xml"},
            follow_redirects=True,
        )
        if response.status_code != 200:
            raise TrafficIncidentError(f"실시간 돌발 정보 API가 HTTP {response.status_code}을 반환했습니다")
        return parse_incident_xml(response.text)
    except httpx.HTTPError as exc:
        raise TrafficIncidentError(f"실시간 돌발 정보 API 연결에 실패했습니다: {type(exc).__name__}") from exc
    finally:
        if owns_client:
            http_client.close()


def fetch_incidents(
    api_key: str,
    *,
    start_index: int = 1,
    page_size: int = DEFAULT_PAGE_SIZE,
    fetch_all: bool = True,
    client: httpx.Client | None = None,
) -> dict[str, Any]:
    """현재 공개 중인 돌발 상황 한 페이지 또는 전체 목록을 수집한다."""
    if start_index < 1:
        raise ValueError("start_index는 1 이상이어야 합니다")
    if page_size < 1 or page_size > MAX_PAGE_SIZE:
        raise ValueError(f"page_size는 1~{MAX_PAGE_SIZE} 범위여야 합니다")

    owns_client = client is None
    http_client = client or httpx.Client(timeout=httpx.Timeout(30.0, connect=5.0))
    items: list[dict[str, Any]] = []
    current_start = start_index
    current_end = start_index + page_size - 1
    total_count = 0
    request_count = 0
    last_page_size = 0
    previous_page_ids: tuple[str, ...] | None = None
    try:
        while True:
            total_count, page_items = fetch_incident_page(
                api_key,
                current_start,
                current_end,
                client=http_client,
            )
            last_page_size = len(page_items)
            items.extend(page_items)
            request_count += 1
            if not fetch_all or last_page_size < page_size:
                break

            page_ids = tuple(item["incidentId"] for item in page_items)
            if page_ids == previous_page_ids:
                raise TrafficIncidentError("API가 같은 페이지를 반복해 전체 조회를 중단했습니다")
            previous_page_ids = page_ids
            if request_count >= MAX_FETCH_ALL_PAGES:
                raise TrafficIncidentError(f"안전을 위해 전체 조회를 {MAX_FETCH_ALL_PAGES}페이지에서 중단했습니다")
            current_start = current_end + 1
            current_end = current_start + page_size - 1
    finally:
        if owns_client:
            http_client.close()

    is_complete = fetch_all and last_page_size < page_size
    return {
        "collectedAt": datetime.now(timezone.utc).isoformat(),
        "datasetId": "OA-13315",
        "service": SERVICE_NAME,
        "sourceType": "traffic_incident",
        "fetchMode": "all_current" if fetch_all else "first_page",
        "startIndex": start_index,
        "endIndex": current_end,
        "pageSize": page_size,
        "requestCount": request_count,
        "apiReportedTotalCount": total_count,
        "receivedCount": len(items),
        "hasMore": not fetch_all and last_page_size == page_size,
        "isComplete": is_complete,
        "items": items,
    }


def save_json(data: dict[str, Any], output_path: Path) -> None:
    """검증된 결과를 UTF-8 JSON 파일로 원자적으로 저장한다."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = output_path.with_suffix(output_path.suffix + ".tmp")
    temporary_path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary_path.replace(output_path)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="서울시 실시간 돌발 정보를 JSON으로 저장")
    parser.add_argument("--start-index", type=int, default=1, help="조회 시작 행(기본값: 1)")
    parser.add_argument(
        "--page-size",
        type=int,
        default=DEFAULT_PAGE_SIZE,
        help=f"호출당 조회 건수 1~{MAX_PAGE_SIZE}(기본값: {DEFAULT_PAGE_SIZE})",
    )
    parser.add_argument(
        "--first-page-only",
        action="store_true",
        help="전체 페이지를 넘기지 않고 첫 페이지만 조회",
    )
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV_PATH, help="API 키 환경 파일")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_PATH, help="JSON 출력 경로")
    return parser.parse_args(argv)


def main() -> int:
    args = parse_args()
    try:
        data = fetch_incidents(
            require_api_key(args.env_file),
            start_index=args.start_index,
            page_size=args.page_size,
            fetch_all=not args.first_page_only,
        )
        save_json(data, args.output)
    except (TrafficIncidentError, ValueError) as exc:
        print(f"[실패] {exc}")
        return 1

    print(f"[완료] 현재 돌발 {data['receivedCount']}건 저장 (API {data['requestCount']}회): {args.output}")
    return 0


# 서버 main.py가 읽는 Kafka 수집 계약. 직접 실행 JSON 기능도 유지한다.
if __package__:
    from sources.kafka_adapter import make_source

    SOURCE = make_source("traffic_incident")


if __name__ == "__main__":
    raise SystemExit(main())
