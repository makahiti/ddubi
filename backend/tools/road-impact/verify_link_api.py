"""TOPIS 링크 Vertex를 공식 API로 검증하고 중단 지점부터 이어 받는다.

키는 env 파일에서만 읽고 URL, 로그, 캐시에 기록하지 않는다. API의 일일 호출 한도를
고려해 성공한 LINK_ID마다 별도 JSON 파일을 원자적으로 저장한다. 다음 실행은 이미
검증한 LINK_ID를 건너뛴다.
"""
from __future__ import annotations

import argparse
import json
import os
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path

from pyproj import Transformer


SERVICE = "LinkVerInfo"
SOURCE_CRS = "EPSG:5181"
TARGET_CRS = "EPSG:4326"
API_ROOT = "http://openapi.seoul.go.kr:8088"


def read_env_value(path: str | Path, name: str) -> str:
    for raw_line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() == name:
            result = value.strip().strip('"').strip("'")
            if result:
                return result
    raise ValueError(f"{name} is missing or empty")


def candidate_link_ids(path: str | Path) -> list[str]:
    values: set[str] = set()
    with Path(path).open(encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            row = json.loads(line)
            links = row.get("topisServiceLinkIds")
            if not isinstance(links, list):
                raise ValueError(f"invalid topisServiceLinkIds at line {number}")
            for value in links:
                link_id = str(value).strip()
                if not link_id or not link_id.isdigit():
                    raise ValueError(f"invalid LINK_ID at line {number}")
                values.add(link_id)
    if not values:
        raise ValueError("candidate file has no LINK_ID")
    return sorted(values)


def parse_vertex_xml(raw: bytes, expected_link_id: str) -> dict:
    root = ET.fromstring(raw)
    result = root.find("RESULT")
    code = result.findtext("CODE") if result is not None else None
    message = result.findtext("MESSAGE") if result is not None else None
    if code != "INFO-000":
        raise ValueError(f"TOPIS response {code or 'UNKNOWN'}: {message or 'no message'}")
    rows = []
    for row in root.findall("row"):
        link_id = (row.findtext("link_id") or "").strip()
        if link_id != expected_link_id:
            raise ValueError("response LINK_ID does not match request")
        try:
            rows.append((int(row.findtext("ver_seq")), float(row.findtext("grs80tm_x")),
                         float(row.findtext("grs80tm_y"))))
        except (TypeError, ValueError):
            raise ValueError("invalid Vertex sequence or coordinate")
    if not rows:
        raise ValueError("TOPIS response has no Vertex")
    rows.sort()
    sequences = [row[0] for row in rows]
    if len(set(sequences)) != len(sequences) or sequences != list(range(1, len(rows) + 1)):
        raise ValueError("Vertex sequence is duplicate or discontinuous")
    transformer = Transformer.from_crs(SOURCE_CRS, TARGET_CRS, always_xy=True)
    geometry = []
    for _, x, y in rows:
        longitude, latitude = transformer.transform(x, y)
        if not 126.0 <= longitude <= 128.0 or not 37.0 <= latitude <= 38.0:
            raise ValueError("transformed Vertex is outside the Seoul validation range")
        geometry.append([longitude, latitude])
    return {
        "linkId": expected_link_id,
        "sourceDataset": "OA-13311",
        "sourceService": SERVICE,
        "sourceCrs": SOURCE_CRS,
        "targetCrs": TARGET_CRS,
        "vertexCount": len(rows),
        "geometry": geometry,
    }


def fetch_one(api_key: str, link_id: str, timeout: float, retries: int) -> dict:
    encoded = "/".join(urllib.parse.quote(value, safe="") for value in
                       (api_key, "xml", SERVICE, "1", "1000", link_id))
    url = f"{API_ROOT}/{encoded}/"
    last_error: Exception | None = None
    for attempt in range(retries + 1):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "ddubi-road-impact-audit/1"})
            with urllib.request.urlopen(request, timeout=timeout) as response:
                raw = response.read(2_000_001)
            if len(raw) > 2_000_000:
                raise ValueError("TOPIS response is unexpectedly large")
            result = parse_vertex_xml(raw, link_id)
            result["verifiedAt"] = datetime.now(timezone.utc).isoformat()
            return result
        except (OSError, urllib.error.URLError, ValueError, ET.ParseError) as error:
            last_error = error
            if attempt < retries:
                time.sleep(min(2 ** attempt, 4))
    raise RuntimeError(str(last_error) if last_error else "TOPIS request failed")


def _cache_path(cache_dir: Path, link_id: str) -> Path:
    return cache_dir / f"{link_id}.json"


def _valid_cached(path: Path, link_id: str) -> bool:
    try:
        row = json.loads(path.read_text(encoding="utf-8"))
        return row.get("linkId") == link_id and row.get("sourceDataset") == "OA-13311" \
            and isinstance(row.get("geometry"), list) and bool(row["geometry"])
    except (OSError, ValueError, TypeError):
        return False


def _atomic_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent,
                                     prefix=path.name + ".", suffix=".tmp", delete=False) as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        temporary = Path(stream.name)
    os.replace(temporary, path)


def verify(candidates: str | Path, env_file: str | Path, cache_dir: str | Path,
           report_path: str | Path, max_requests: int, workers: int, timeout: float,
           retries: int) -> dict:
    if max_requests < 0 or workers < 1 or timeout <= 0 or retries < 0:
        raise ValueError("invalid request controls")
    link_ids = candidate_link_ids(candidates)
    cache = Path(cache_dir)
    cache.mkdir(parents=True, exist_ok=True)
    cached = [link_id for link_id in link_ids if _valid_cached(_cache_path(cache, link_id), link_id)]
    remaining = [link_id for link_id in link_ids if link_id not in set(cached)]
    selected = remaining[:max_requests]
    if selected:
        try:
            api_key = read_env_value(env_file, "SEOUL_OPEN_DATA_API_KEY")
        except ValueError:
            api_key = read_env_value(env_file, "TRAFFIC_INCIDENT_API_KEY")
    else:
        api_key = ""
    failures = []
    completed = 0
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(fetch_one, api_key, link_id, timeout, retries): link_id
                   for link_id in selected}
        for future in as_completed(futures):
            link_id = futures[future]
            try:
                _atomic_json(_cache_path(cache, link_id), future.result())
                completed += 1
            except Exception as error:
                failures.append({"linkId": link_id, "error": str(error)[:300]})
    verified_total = len(cached) + completed
    report = {
        "sourceDataset": "OA-13311",
        "sourceService": SERVICE,
        "candidateLinkCount": len(link_ids),
        "cachedBeforeRun": len(cached),
        "requestedThisRun": len(selected),
        "verifiedThisRun": completed,
        "failedThisRun": len(failures),
        "verifiedTotal": verified_total,
        "remaining": len(link_ids) - verified_total,
        "complete": verified_total == len(link_ids),
        "failures": failures,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
    }
    _atomic_json(Path(report_path), report)
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidates", required=True)
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--cache-dir", required=True)
    parser.add_argument("--report", dest="report_path", required=True)
    # 같은 키를 실시간 수집기도 사용하므로 호출 예산을 실행자가 명시해야 한다.
    parser.add_argument("--max-requests", type=int, required=True)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("--retries", type=int, default=2)
    args = parser.parse_args()
    print(json.dumps(verify(**vars(args)), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
