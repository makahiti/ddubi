"""서울시 주요 121개 장소의 실시간 도시데이터를 JSON 파일로 저장한다.

서울 열린데이터광장의 ``서울시 실시간 도시데이터`` API는 한 번에
장소 하나만 조회할 수 있다. 공식 장소 목록을 별도 JSON 파일에서 읽어
순차 호출하며, 한 장소라도 실패하면 불완전한 결과를 저장하지 않는다.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import quote

import httpx

API_URL_TEMPLATE = "http://openapi.seoul.go.kr:8088/{api_key}/json/citydata/1/5/{area_identifier}"
COLLECTOR_DIR = Path(__file__).resolve().parents[2]
DEFAULT_ENV_PATH = COLLECTOR_DIR / ".env"
DEFAULT_OUTPUT_PATH = COLLECTOR_DIR / "data" / "city_realtime.json"
DEFAULT_CITY_AREAS_PATH = COLLECTOR_DIR / "config" / "city_areas.json"
_ENV_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
MAX_REQUESTS_PER_SECOND = 5
EXPECTED_AREA_COUNT = 121


class CityRealtimeError(RuntimeError):
    """API 요청, 응답 검증 또는 저장에 실패했을 때 발생한다."""


def load_env_file(path: Path = DEFAULT_ENV_PATH) -> None:
    """간단한 KEY=VALUE 형식의 환경 파일을 읽는다."""
    if not path.exists():
        return

    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].lstrip()
        if "=" not in line:
            raise CityRealtimeError(f"{path}:{line_number}: KEY=VALUE 형식이 아닙니다")

        name, value = line.split("=", 1)
        name, value = name.strip(), value.strip()
        if not _ENV_NAME.fullmatch(name):
            raise CityRealtimeError(f"{path}:{line_number}: 환경변수 이름이 올바르지 않습니다")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        os.environ.setdefault(name, value)


def load_city_areas(path: Path = DEFAULT_CITY_AREAS_PATH) -> list[dict[str, str]]:
    """서울시 공식 목록에서 만든 121개 장소 설정을 읽고 검증한다."""
    try:
        raw_areas = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise CityRealtimeError(f"장소 목록 파일을 읽을 수 없습니다: {path}") from exc
    except json.JSONDecodeError as exc:
        raise CityRealtimeError(f"장소 목록 파일이 올바른 JSON이 아닙니다: {path}") from exc

    if not isinstance(raw_areas, list):
        raise CityRealtimeError("장소 목록 파일은 JSON 배열이어야 합니다")
    if len(raw_areas) != EXPECTED_AREA_COUNT:
        raise CityRealtimeError(f"공식 장소 수와 다릅니다: 예상 {EXPECTED_AREA_COUNT}개, 실제 {len(raw_areas)}개")

    areas: list[dict[str, str]] = []
    codes: set[str] = set()
    names: set[str] = set()
    for index, raw_area in enumerate(raw_areas, start=1):
        if not isinstance(raw_area, dict):
            raise CityRealtimeError(f"장소 목록의 {index}번째 값이 객체가 아닙니다")
        area = {key: str(raw_area.get(key, "")).strip() for key in ("category", "areaCode", "areaName", "englishName")}
        if not area["areaCode"] or not area["areaName"]:
            raise CityRealtimeError(f"장소 목록의 {index}번째 코드 또는 이름이 비어 있습니다")
        if area["areaCode"] in codes:
            raise CityRealtimeError(f"장소 코드가 중복되었습니다: {area['areaCode']}")
        if area["areaName"] in names:
            raise CityRealtimeError(f"장소명이 중복되었습니다: {area['areaName']}")
        codes.add(area["areaCode"])
        names.add(area["areaName"])
        areas.append(area)
    return areas


def select_city_areas(areas: list[dict[str, str]], identifiers: list[str] | None) -> list[dict[str, str]]:
    """CLI로 지정한 장소 코드/이름만 고른다. 미지면 전체를 반환한다."""
    if not identifiers:
        return areas

    lookup: dict[str, dict[str, str]] = {}
    for area in areas:
        lookup[area["areaCode"]] = area
        lookup[area["areaName"]] = area

    selected: list[dict[str, str]] = []
    selected_codes: set[str] = set()
    for raw_identifier in identifiers:
        identifier = raw_identifier.strip()
        area = lookup.get(identifier)
        if area is None:
            raise CityRealtimeError(f"공식 장소 목록에 없는 코드/이름입니다: {identifier}")
        if area["areaCode"] not in selected_codes:
            selected.append(area)
            selected_codes.add(area["areaCode"])
    return selected


def _response_metadata(data: dict[str, Any]) -> tuple[str, str]:
    """서울 열린데이터광장 공통 결과 코드와 메시지를 읽는다."""
    result = data.get("RESULT")
    if not isinstance(result, dict):
        raise CityRealtimeError("도시데이터 API 응답에 RESULT가 없습니다")
    code = str(result.get("RESULT.CODE", result.get("CODE", "")))
    message = str(result.get("RESULT.MESSAGE", result.get("MESSAGE", "")))
    return code, message


def fetch_city_area(
    api_key: str,
    area: dict[str, str],
    *,
    client: httpx.Client | None = None,
) -> dict[str, Any]:
    """공식 장소 하나의 통합 실시간 도시데이터를 JSON으로 조회한다."""
    key = api_key.strip()
    if not key:
        raise CityRealtimeError("CITY_REALTIME_API_KEY가 비어 있습니다")

    area_code = area["areaCode"].strip()
    area_name = area["areaName"].strip()
    api_url = API_URL_TEMPLATE.format(
        api_key=quote(key, safe=""),
        area_identifier=quote(area_code, safe=""),
    )
    owns_client = client is None
    http_client = client or httpx.Client(timeout=httpx.Timeout(60.0, connect=5.0))
    try:
        response = http_client.get(
            api_url,
            headers={"Accept": "application/json"},
            follow_redirects=True,
        )
        if response.status_code != 200:
            raise CityRealtimeError(f"서울 실시간 도시데이터 API가 HTTP {response.status_code}을 반환했습니다")
        try:
            data = response.json()
        except ValueError as exc:
            raise CityRealtimeError("도시데이터 API 응답이 올바른 JSON이 아닙니다") from exc
        if not isinstance(data, dict):
            raise CityRealtimeError("도시데이터 API JSON의 최상위 값이 객체가 아닙니다")

        code, message = _response_metadata(data)
        if code != "INFO-000":
            raise CityRealtimeError(f"서울 실시간 도시데이터 API 오류({code}): {message}")

        citydata = data.get("CITYDATA")
        if not isinstance(citydata, dict):
            raise CityRealtimeError("도시데이터 API 응답에 CITYDATA 객체가 없습니다")
        received_code = str(citydata.get("AREA_CD", ""))
        received_name = str(citydata.get("AREA_NM", ""))
        if received_code != area_code:
            raise CityRealtimeError(f"요청 장소와 응답 장소가 다릅니다: {area_code} -> {received_code or '(없음)'}")

        return {
            "areaCode": received_code,
            "areaName": received_name or area_name,
            "category": area.get("category", ""),
            "response": data,
        }
    except httpx.HTTPError as exc:
        raise CityRealtimeError(f"서울 실시간 도시데이터 API 연결에 실패했습니다: {type(exc).__name__}") from exc
    finally:
        if owns_client:
            http_client.close()


def fetch_city_areas(
    api_key: str,
    areas: list[dict[str, str]],
    *,
    client: httpx.Client | None = None,
    requests_per_second: int = MAX_REQUESTS_PER_SECOND,
) -> dict[str, Any]:
    """지정된 장소를 한 번씩 순차 호출하고 완전한 수집 결과를 반환한다."""
    if not areas:
        raise CityRealtimeError("조회할 장소가 없습니다")
    if requests_per_second <= 0 or requests_per_second > MAX_REQUESTS_PER_SECOND:
        raise CityRealtimeError(f"초당 요청 수는 1 이상 {MAX_REQUESTS_PER_SECOND} 이하여야 합니다")

    owns_client = client is None
    http_client = client or httpx.Client(timeout=httpx.Timeout(60.0, connect=5.0))
    interval = 1 / requests_per_second
    last_request_started: float | None = None
    area_results: dict[str, dict[str, Any]] = {}
    try:
        for area in areas:
            if last_request_started is not None:
                remaining = interval - (time.monotonic() - last_request_started)
                if remaining > 0:
                    time.sleep(remaining)
            last_request_started = time.monotonic()
            result = fetch_city_area(api_key, area, client=http_client)
            area_results[result["areaCode"]] = result
    finally:
        if owns_client:
            http_client.close()

    return {
        "collectedAt": datetime.now(timezone.utc).isoformat(),
        "requestedAreaCount": len(areas),
        "receivedAreaCount": len(area_results),
        "isComplete": len(area_results) == len(areas),
        "areaResults": area_results,
    }


def save_json(data: dict[str, Any], output_path: Path) -> None:
    """검증된 응답을 UTF-8 JSON 파일로 원자적으로 저장한다."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = output_path.with_suffix(output_path.suffix + ".tmp")
    temporary_path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary_path.replace(output_path)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="서울시 주요 장소의 실시간 도시데이터를 JSON 파일로 저장")
    parser.add_argument(
        "--area",
        action="append",
        help="조회할 장소 코드 또는 장소명(여러 번 지정 가능, 미지정 시 121개 전체)",
    )
    parser.add_argument(
        "--city-areas-file",
        type=Path,
        default=DEFAULT_CITY_AREAS_PATH,
        help="서울시 공식 장소 목록을 변환한 JSON 파일",
    )
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV_PATH, help="API 키 환경 파일")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_PATH, help="JSON 출력 경로")
    parser.add_argument(
        "--requests-per-second",
        type=int,
        default=MAX_REQUESTS_PER_SECOND,
        help=f"전체 조회 초당 요청 수(최대 {MAX_REQUESTS_PER_SECOND})",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    load_env_file(args.env_file)
    areas = select_city_areas(load_city_areas(args.city_areas_file), args.area)
    data = fetch_city_areas(
        os.environ.get("CITY_REALTIME_API_KEY", ""),
        areas,
        requests_per_second=args.requests_per_second,
    )
    save_json(data, args.output)
    print(
        f"실시간 도시데이터 JSON 저장 완료: {args.output.resolve()} "
        f"(장소 {data['receivedAreaCount']}/{data['requestedAreaCount']}개, "
        f"전체 수신={data['isComplete']})"
    )
    return 0


# 서버 main.py가 읽는 Kafka 수집 계약. 직접 실행 JSON 기능도 유지한다.
if __package__:
    from sources.kafka_adapter import make_source

    SOURCE = make_source("city_realtime")


if __name__ == "__main__":
    raise SystemExit(main())
