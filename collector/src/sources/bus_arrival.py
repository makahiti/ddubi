"""서울 버스 노선별 도착정보를 JSON으로 요청해 파일로 저장한다.

공공데이터포털 ``서울특별시_버스도착정보조회 서비스``의
``getArrInfoByRouteAllList`` 기능을 사용한다.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import time
from pathlib import Path
from typing import Any
from urllib.parse import unquote

import httpx

# 이 서울시 레거시 API는 현재 HTTPS 연결이 되지 않고 공식 활용 예제도 HTTP를 사용한다.
API_URL = "http://ws.bus.go.kr/api/rest/arrive/getArrInfoByRouteAll"
COLLECTOR_DIR = Path(__file__).resolve().parents[2]
DEFAULT_ENV_PATH = COLLECTOR_DIR / ".env"
DEFAULT_OUTPUT_PATH = COLLECTOR_DIR / "data" / "bus_arrival.json"
DEFAULT_ROUTE_IDS_PATH = COLLECTOR_DIR / "config" / "bus_route_ids.json"
_ENV_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_ROUTE_ID = re.compile(r"^\d{9}$")
MAX_REQUESTS_PER_SECOND = 25
DEFAULT_ROUTE_LIMIT = 100


class BusArrivalError(RuntimeError):
    """API 요청 또는 JSON 응답 처리에 실패했을 때 발생한다."""


def load_env_file(path: Path = DEFAULT_ENV_PATH) -> None:
    """간단한 KEY=VALUE 형식의 환경 파일을 읽는다.

    이미 프로세스 환경에 설정된 값은 덮어쓰지 않는다. 이 함수는 현재 필요한
    일반적인 API 키 형식만 다루며 셸 표현식은 해석하지 않는다.
    """
    if not path.exists():
        return

    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        if "=" not in line:
            raise BusArrivalError(f"{path}:{line_number}: KEY=VALUE 형식이 아닙니다")

        name, value = line.split("=", 1)
        name, value = name.strip(), value.strip()
        if not _ENV_NAME.fullmatch(name):
            raise BusArrivalError(f"{path}:{line_number}: 환경변수 이름이 올바르지 않습니다")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        os.environ.setdefault(name, value)


def fetch_bus_arrival(
    api_key: str,
    bus_route_id: str,
    *,
    client: httpx.Client | None = None,
) -> dict[str, Any]:
    """노선 전체 정류소 도착정보를 JSON으로 받아 반환한다."""
    if not api_key.strip():
        raise BusArrivalError("BUS_ARRIVAL_API_KEY 또는 DATA_GO_KR_API_KEY API 키가 비어 있습니다")
    if not bus_route_id.strip():
        raise BusArrivalError("버스 노선 ID가 비어 있습니다")

    # 포털의 Encoding 키를 복사한 경우 httpx가 '%'를 다시 인코딩하지 않도록 한 번 해제한다.
    service_key = unquote(api_key.strip())
    owns_client = client is None
    http_client = client or httpx.Client(timeout=httpx.Timeout(15.0, connect=5.0))
    try:
        response = http_client.get(
            API_URL,
            params={
                "serviceKey": service_key,
                "busRouteId": bus_route_id.strip(),
                "resultType": "json",
            },
            headers={"Accept": "application/json"},
        )
        if response.status_code != 200:
            # 인증키가 포함된 요청 URL은 예외 메시지에 넣지 않는다.
            raise BusArrivalError(f"서울 버스도착 API가 HTTP {response.status_code}을 반환했습니다")
        try:
            data = response.json()
        except ValueError as exc:
            raise BusArrivalError("서울 버스도착 API 응답이 올바른 JSON이 아닙니다") from exc
        if not isinstance(data, dict):
            raise BusArrivalError("서울 버스도착 API JSON 응답의 최상위 값이 객체가 아닙니다")
        header = data.get("msgHeader", {})
        if str(header.get("headerCd", "")) != "0":
            raise BusArrivalError("서울 버스도착 API 업무 오류: " + str(header.get("headerCd", "UNKNOWN")))
        return data
    except httpx.HTTPError as exc:
        raise BusArrivalError(f"서울 버스도착 API 연결에 실패했습니다: {type(exc).__name__}") from exc
    finally:
        if owns_client:
            http_client.close()


def load_bus_route_ids(path: Path = DEFAULT_ROUTE_IDS_PATH) -> list[str]:
    """별도 JSON 파일에서 중복 없는 9자리 서울 버스 노선 ID를 읽는다."""
    try:
        raw_ids = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise BusArrivalError(f"버스 노선 ID 파일을 읽을 수 없습니다: {path}") from exc
    except json.JSONDecodeError as exc:
        raise BusArrivalError(f"버스 노선 ID 파일이 올바른 JSON이 아닙니다: {path}") from exc

    if not isinstance(raw_ids, list) or not raw_ids:
        raise BusArrivalError("버스 노선 ID 파일은 비어 있지 않은 JSON 배열이어야 합니다")

    route_ids: list[str] = []
    seen: set[str] = set()
    for index, value in enumerate(raw_ids, start=1):
        if not isinstance(value, str) or not _ROUTE_ID.fullmatch(value.strip()):
            raise BusArrivalError(f"버스 노선 ID 파일의 {index}번째 값이 올바른 9자리 ID가 아닙니다")
        route_id = value.strip()
        if route_id in seen:
            raise BusArrivalError(f"버스 노선 ID가 중복되었습니다: {route_id}")
        seen.add(route_id)
        route_ids.append(route_id)
    return route_ids


def fetch_all_bus_arrivals(
    api_key: str,
    bus_route_ids: list[str],
    *,
    client: httpx.Client | None = None,
    requests_per_second: int = MAX_REQUESTS_PER_SECOND,
) -> dict[str, Any]:
    """노선 ID를 순차 호출해 전체 노선의 정류소별 도착정보를 반환한다."""
    if requests_per_second <= 0:
        raise BusArrivalError("초당 요청 수는 1 이상이어야 합니다")

    owns_client = client is None
    http_client = client or httpx.Client(timeout=httpx.Timeout(15.0, connect=5.0))
    interval = 1 / requests_per_second
    last_request_started: float | None = None
    route_results: dict[str, dict[str, Any]] = {}
    try:
        for route_id in bus_route_ids:
            if last_request_started is not None:
                remaining = interval - (time.monotonic() - last_request_started)
                if remaining > 0:
                    time.sleep(remaining)
            last_request_started = time.monotonic()
            route_results[route_id] = fetch_bus_arrival(api_key, route_id, client=http_client)
    finally:
        if owns_client:
            http_client.close()

    return {
        "routeCount": len(route_results),
        "routeResults": route_results,
    }


def save_json(data: dict[str, Any], output_path: Path) -> None:
    """API JSON 응답을 UTF-8 JSON 파일로 저장한다."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="서울 버스 도착정보를 JSON 파일로 저장")
    parser.add_argument(
        "--bus-route-id",
        help="이 노선 하나만 조회 (미지정 시 별도 노선 ID 파일의 전체 노선 조회)",
    )
    parser.add_argument(
        "--route-ids-file",
        type=Path,
        default=DEFAULT_ROUTE_IDS_PATH,
        help="전체 조회에 사용할 노선 ID JSON 파일",
    )
    parser.add_argument(
        "--route-limit",
        type=int,
        default=DEFAULT_ROUTE_LIMIT,
        help=f"전체 조회 시 앞에서부터 조회할 노선 수 (1~{DEFAULT_ROUTE_LIMIT})",
    )
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV_PATH, help="API 키 환경 파일")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_PATH, help="JSON 출력 경로")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    load_env_file(args.env_file)

    api_key = os.environ.get("BUS_ARRIVAL_API_KEY") or os.environ.get("DATA_GO_KR_API_KEY", "")
    bus_route_id = args.bus_route_id
    if bus_route_id:
        data = fetch_bus_arrival(api_key, bus_route_id)
    else:
        if not 1 <= args.route_limit <= DEFAULT_ROUTE_LIMIT:
            raise BusArrivalError(f"노선 수는 1 이상 {DEFAULT_ROUTE_LIMIT} 이하여야 합니다")
        route_ids = load_bus_route_ids(args.route_ids_file)
        data = fetch_all_bus_arrivals(api_key, route_ids[: args.route_limit])
    save_json(data, args.output)
    print(f"버스 도착정보 JSON 저장 완료: {args.output.resolve()}")
    return 0


# 서버 main.py가 읽는 Kafka 수집 계약. 직접 실행 JSON 기능도 유지한다.
if __package__:
    from sources.kafka_adapter import make_source

    SOURCE = make_source("bus_arrival")


if __name__ == "__main__":
    raise SystemExit(main())
