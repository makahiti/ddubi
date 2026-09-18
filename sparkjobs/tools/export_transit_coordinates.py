"""검증된 transit master를 BE/ML용 좌표 artifact로 결정적으로 변환한다.

이 도구는 원본 master를 수정하지 않는다. 역 좌표는 출입구가 아니라
STATION_CENTER로 명시하며, 버스의 같은 정류장 재등장은 occurrence로 보존한다.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "transit-coordinate-v1"


def _read_json(path: Path) -> Any:
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8") as stream:
        return json.load(stream)


def _occurrences(items: list[dict[str, Any]], identity) -> list[int]:
    counts: dict[tuple[Any, ...], int] = {}
    result: list[int] = []
    for item in items:
        key = identity(item)
        counts[key] = counts.get(key, 0) + 1
        result.append(counts[key])
    return result


def build_records(bus: dict[str, Any], subway: dict[str, Any], coordinates: dict[str, Any]) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    bus_version = str(bus["version"])
    for route in bus.get("routes", []):
        stops = route.get("stops", [])
        occurrences = _occurrences(stops, lambda stop: (stop.get("namespace"), stop.get("stopId")))
        for stop, occurrence in zip(stops, occurrences):
            namespace = str(stop.get("namespace") or route.get("namespace") or "UNKNOWN")
            stop_id = str(stop.get("stopId") or "")
            longitude, latitude = stop.get("longitude"), stop.get("latitude")
            coordinate_type = "STOP_POINT" if longitude is not None and latitude is not None else "UNKNOWN"
            records.append({
                "mode": "BUS", "providerNamespace": namespace,
                "providerRouteId": str(route.get("routeId") or ""), "providerLineId": None,
                "providerStopId": stop_id,
                "canonicalNodeId": f"BUS_STOP:{namespace}:{stop_id}",
                "occurrence": occurrence, "direction": stop.get("direction"),
                "longitude": longitude, "latitude": latitude, "coordinateType": coordinate_type,
                "source": str(stop.get("coordinateStatus") or "TRANSIT_MASTER"),
                "sourceRef": f"route={route.get('routeId')};sequence={stop.get('sequence')}",
                "masterVersion": bus_version, "validFrom": None, "validUntil": None,
                "mappingStatus": "EXACT" if coordinate_type == "STOP_POINT" else "UNMAPPED",
                "evidence": "route, stop, direction and sequence preserved from versioned bus master",
            })
    subway_version = str(subway["dataVersion"])
    for line in subway.get("lines", []):
        stations = line.get("stations", [])
        occurrences = _occurrences(stations, lambda station: (station.get("stationId"),))
        for station, occurrence in zip(stations, occurrences):
            station_id = str(station.get("stationId") or "")
            coordinate = coordinates.get(station_id) or {}
            longitude, latitude = coordinate.get("longitude"), coordinate.get("latitude")
            coordinate_type = "STATION_CENTER" if longitude is not None and latitude is not None else "UNKNOWN"
            records.append({
                "mode": "SUBWAY", "providerNamespace": "METROPOLITAN_SUBWAY",
                "providerRouteId": None,
                "providerLineId": str(line.get("realtimeSubwayId") or line.get("subwayId") or ""),
                "providerStopId": str(station.get("realtimeStationId") or station_id),
                "canonicalNodeId": f"SUBWAY_STATION:{station_id}",
                "occurrence": occurrence, "direction": line.get("direction"),
                "longitude": longitude, "latitude": latitude, "coordinateType": coordinate_type,
                "source": str(coordinate.get("source") or "UNAVAILABLE"),
                "sourceRef": f"station={station_id};sequence={station.get('sequence')}",
                "masterVersion": subway_version, "validFrom": None, "validUntil": None,
                "mappingStatus": "EXACT" if coordinate_type == "STATION_CENTER" else "UNMAPPED",
                "evidence": "line, station, direction and sequence preserved; coordinate is station center, never an entrance",
            })
    return sorted(records, key=lambda row: (
        row["mode"], row["providerNamespace"], row.get("providerRouteId") or row.get("providerLineId") or "",
        row["providerStopId"], row["direction"] or "", row["occurrence"],
    ))


def build_artifact(bus_path: Path, subway_path: Path, coordinate_path: Path, generated_at: datetime) -> dict[str, Any]:
    bus, subway, coordinates = _read_json(bus_path), _read_json(subway_path), _read_json(coordinate_path)
    records = build_records(bus, subway, coordinates)
    source_versions = f"bus={bus['version']};subway={subway['dataVersion']}"
    digest = hashlib.sha256(
        json.dumps(records, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()[:16]
    return {
        "schemaVersion": SCHEMA_VERSION,
        "artifactVersion": f"coordinates-{digest}",
        "masterVersion": source_versions,
        "coordinateSystem": "WGS84",
        "generatedAt": generated_at.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
        "records": records,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bus-master", type=Path, required=True)
    parser.add_argument("--subway-master", type=Path, required=True)
    parser.add_argument("--subway-coordinates", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--generated-at", required=True, help="재현 가능한 ISO 8601 시각")
    args = parser.parse_args()
    generated_at = datetime.fromisoformat(args.generated_at.replace("Z", "+00:00"))
    if generated_at.tzinfo is None:
        raise SystemExit("--generated-at must include an offset")
    artifact = build_artifact(args.bus_master, args.subway_master, args.subway_coordinates, generated_at)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
