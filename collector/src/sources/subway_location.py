"""서울시 호선별 실시간 열차 위치정보를 JSON 파일로 저장한다.

서울 열린데이터광장의 ``서울시 지하철 실시간 열차 위치정보`` API를
사용한다. API가 호선 하나씩만 조회하므로 별도 JSON 파일의 공식 지원
호선명을 순차 호출한다.
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

if __package__:
    from .subway_response import validated_rows
else:
    from subway_response import validated_rows

API_URL_TEMPLATE = (
    "http://swopenapi.seoul.go.kr/api/subway/"
    "{api_key}/json/realtimePosition/0/1000/{subway_line}/"
)
COLLECTOR_DIR = Path(__file__).resolve().parents[2]
DEFAULT_ENV_PATH = COLLECTOR_DIR / ".env"
DEFAULT_OUTPUT_PATH = COLLECTOR_DIR / "data" / "subway_location.json"
DEFAULT_SUBWAY_LINES_PATH = COLLECTOR_DIR / "config" / "subway_lines.json"
_ENV_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
MAX_REQUESTS_PER_SECOND = 5


class SubwayLocationError(RuntimeError):
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
            raise SubwayLocationError(f"{path}:{line_number}: KEY=VALUE 형식이 아닙니다")

        name, value = line.split("=", 1)
        name, value = name.strip(), value.strip()
        if not _ENV_NAME.fullmatch(name):
            raise SubwayLocationError(f"{path}:{line_number}: 환경변수 이름이 올바르지 않습니다")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        os.environ.setdefault(name, value)


def load_subway_lines(path: Path = DEFAULT_SUBWAY_LINES_PATH) -> list[str]:
    """별도 JSON 파일에서 중복 없는 조회 대상 호선명을 읽는다."""
    try:
        raw_lines = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise SubwayLocationError(f"지하철 호선 파일을 읽을 수 없습니다: {path}") from exc
    except json.JSONDecodeError as exc:
        raise SubwayLocationError(f"지하철 호선 파일이 올바른 JSON이 아닙니다: {path}") from exc

    if not isinstance(raw_lines, list) or not raw_lines:
        raise SubwayLocationError("지하철 호선 파일은 비어 있지 않은 JSON 배열이어야 합니다")

    lines: list[str] = []
    seen: set[str] = set()
    for index, value in enumerate(raw_lines, start=1):
        if not isinstance(value, str) or not value.strip():
            raise SubwayLocationError(f"지하철 호선 파일의 {index}번째 값이 올바르지 않습니다")
        line = value.strip()
        if line in seen:
            raise SubwayLocationError(f"지하철 호선명이 중복되었습니다: {line}")
        seen.add(line)
        lines.append(line)
    return lines


def fetch_subway_location(
    api_key: str,
    subway_line: str,
    *,
    client: httpx.Client | None = None,
) -> dict[str, Any]:
    """호선 하나에서 운행 중인 모든 열차 위치를 JSON으로 조회한다."""
    key = api_key.strip()
    line = subway_line.strip()
    if not key:
        raise SubwayLocationError("SUBWAY_API_KEY가 비어 있습니다")
    if not line:
        raise SubwayLocationError("지하철 호선명이 비어 있습니다")

    api_url = API_URL_TEMPLATE.format(
        api_key=quote(key, safe=""),
        subway_line=quote(line, safe=""),
    )
    owns_client = client is None
    http_client = client or httpx.Client(timeout=httpx.Timeout(30.0, connect=5.0))
    try:
        response = http_client.get(
            api_url,
            headers={"Accept": "application/json"},
            follow_redirects=True,
        )
        if response.status_code != 200:
            # 인증키가 포함된 URL은 오류 메시지나 로그에 넣지 않는다.
            raise SubwayLocationError(f"서울 지하철 위치 API가 HTTP {response.status_code}을 반환했습니다")
        try:
            data = response.json()
        except ValueError as exc:
            raise SubwayLocationError("서울 지하철 위치 API 응답이 올바른 JSON이 아닙니다") from exc
        if not isinstance(data, dict):
            raise SubwayLocationError("서울 지하철 위치 API JSON의 최상위 값이 객체가 아닙니다")

        try:
            data, total_count, positions = validated_rows(data, "realtimePositionList", SubwayLocationError)
        except SubwayLocationError as exc:
            raise SubwayLocationError(f"호선={line}: {exc}") from None
        received_count = len(positions)

        return {
            "totalCount": total_count,
            "receivedCount": received_count,
            "isComplete": True,
            "response": data,
        }
    except httpx.HTTPError as exc:
        raise SubwayLocationError(
            f"서울 지하철 위치 API 연결에 실패했습니다: {type(exc).__name__}"
        ) from exc
    finally:
        if owns_client:
            http_client.close()


def fetch_all_subway_locations(
    api_key: str,
    subway_lines: list[str],
    *,
    client: httpx.Client | None = None,
    requests_per_second: int = MAX_REQUESTS_PER_SECOND,
) -> dict[str, Any]:
    """공식 지원 호선을 순차 호출해 전체 실시간 열차 위치를 반환한다."""
    if requests_per_second <= 0 or requests_per_second > MAX_REQUESTS_PER_SECOND:
        raise SubwayLocationError(
            f"초당 요청 수는 1 이상 {MAX_REQUESTS_PER_SECOND} 이하여야 합니다"
        )

    owns_client = client is None
    http_client = client or httpx.Client(timeout=httpx.Timeout(30.0, connect=5.0))
    interval = 1 / requests_per_second
    last_request_started: float | None = None
    line_results: dict[str, dict[str, Any]] = {}
    position_count = 0
    try:
        for line in subway_lines:
            if last_request_started is not None:
                remaining = interval - (time.monotonic() - last_request_started)
                if remaining > 0:
                    time.sleep(remaining)
            last_request_started = time.monotonic()
            result = fetch_subway_location(api_key, line, client=http_client)
            line_results[line] = result
            position_count += result["receivedCount"]
    finally:
        if owns_client:
            http_client.close()

    empty_lines = [
        line for line, result in line_results.items() if result["receivedCount"] == 0
    ]
    return {
        "collectedAt": datetime.now(timezone.utc).isoformat(),
        "lineCount": len(line_results),
        "positionCount": position_count,
        "emptyLines": empty_lines,
        "lineResults": line_results,
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
    parser = argparse.ArgumentParser(
        description="서울시 호선별 실시간 열차 위치정보를 JSON 파일로 저장"
    )
    parser.add_argument(
        "--subway-line",
        help="이 호선 하나만 조회 (미지정 시 별도 호선 파일의 전체 호선 조회)",
    )
    parser.add_argument(
        "--subway-lines-file",
        type=Path,
        default=DEFAULT_SUBWAY_LINES_PATH,
        help="전체 조회에 사용할 호선명 JSON 파일",
    )
    parser.add_argument("--env-file", type=Path, default=DEFAULT_ENV_PATH, help="API 키 환경 파일")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_PATH, help="JSON 출력 경로")
    parser.add_argument(
        "--requests-per-second",
        type=int,
        default=MAX_REQUESTS_PER_SECOND,
        help=f"전체 조회 초당 요청 수 (최대 {MAX_REQUESTS_PER_SECOND})",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    load_env_file(args.env_file)
    api_key = os.environ.get("SUBWAY_API_KEY", "")

    if args.subway_line:
        line = args.subway_line.strip()
        result = fetch_subway_location(api_key, line)
        data = {
            "collectedAt": datetime.now(timezone.utc).isoformat(),
            "lineCount": 1,
            "positionCount": result["receivedCount"],
            "emptyLines": [line] if result["receivedCount"] == 0 else [],
            "lineResults": {line: result},
        }
    else:
        lines = load_subway_lines(args.subway_lines_file)
        data = fetch_all_subway_locations(
            api_key,
            lines,
            requests_per_second=args.requests_per_second,
        )

    save_json(data, args.output)
    print(
        f"지하철 위치정보 JSON 저장 완료: {args.output.resolve()} "
        f"(호선 {data['lineCount']}개, 열차 {data['positionCount']}대, "
        f"빈 호선 {len(data['emptyLines'])}개)"
    )
    return 0


# 서버 main.py가 읽는 Kafka 수집 계약. 직접 실행 JSON 기능도 유지한다.
if __package__:
    from sources.kafka_adapter import make_source
    SOURCE = make_source("subway_location")


if __name__ == "__main__":
    raise SystemExit(main())
