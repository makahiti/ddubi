"""부산 GTFS 연구 산출물을 Backend 버스 마스터와 수식 입력으로 연결한다.

하나의 GTFS route_id에 왕복·단축 운행이 함께 있으므로 서로 다른 정류장열을 합치지
않고 정류장열 SHA 기반 패턴 ID를 발급한다. 같은 변환에서 마스터와 수식 JSONL을
함께 만들어 추천 경로의 canonical edge와 MySQL 조회 키가 어긋나지 않게 한다.

이 도구는 파일만 생성한다. DB 적재, 활성 버전 교체, 서버 업로드는 하지 않는다.
Parquet를 읽을 때만 pyarrow가 필요하며 원본 산출물은 읽기 전용이다.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path
import sys


NAMESPACE = "BUSAN_GTFS"
ALPHA = 0.25


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    """대용량 Parquet도 메모리에 전부 올리지 않고 출처 지문을 계산한다."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def stable_pattern_id(route_id: str, signature: tuple, pattern_count: int) -> str:
    """한 route_id에 정류장열이 여러 개일 때만 재현 가능한 접미사를 붙인다."""
    if pattern_count == 1:
        return route_id
    encoded = json.dumps(signature, ensure_ascii=False, separators=(",", ":")).encode()
    return route_id + ".P" + hashlib.sha256(encoded).hexdigest()[:8]


def collect_patterns(trip_rows):
    """운행편별 edge를 정렬해 중복 시간표를 하나의 정류장열로 축약한다.

    입력 순서는 신뢰하지 않는다. 순번 또는 정류장 연결이 끊기면 일부 edge를 조용히
    살리는 대신 전체 변환을 실패시킨다.
    """
    trips = defaultdict(list)
    for row in trip_rows:
        trips[str(row["trip_id"])].append(row)
    grouped = defaultdict(Counter)
    for rows in trips.values():
        rows.sort(key=lambda row: int(row["from_sequence"]))
        signature = tuple(
            (str(row["from_stop_id"]), str(row["to_stop_id"]),
             int(row["from_sequence"]), int(row["to_sequence"]))
            # 같은 정류장의 연속 중복 occurrence는 실제 이동 edge가 아니며 Backend의
            # from!=to 계약에도 맞지 않는다. 앞뒤 연결은 아래 검증으로 다시 확인한다.
            for row in rows
            if str(row["from_stop_id"]) != str(row["to_stop_id"])
        )
        if not signature:
            raise ValueError("EMPTY_TRIP_PATTERN")
        for edge in signature:
            if edge[3] <= edge[2]:
                raise ValueError("NON_CONTIGUOUS_TRIP_PATTERN")
        for previous, current in zip(signature, signature[1:]):
            if current[2] < previous[3] or current[0] != previous[1]:
                raise ValueError("NON_CONTIGUOUS_TRIP_PATTERN")
        grouped[str(rows[0]["route_id"])][signature] += 1
    return grouped


def build_busan_routes(patterns, route_rows, stop_rows):
    """패턴별 마스터 노선과 원본 edge→패턴 edge 대응을 함께 만든다.

    좌표·이름 결측은 공간 검증을 불가능하게 하므로 즉시 실패한다. 승하차 가능 여부와
    방향은 현재 GTFS 산출물에 근거가 없어 null로 유지한다.
    """
    route_names = {str(row["route_id"]): str(row["route_name"]) for row in route_rows}
    stops = {str(row["stop_id"]): row for row in stop_rows}
    routes, pattern_edges = [], []
    for raw_route_id, variants in sorted(patterns.items()):
        if raw_route_id not in route_names:
            raise ValueError("ROUTE_METADATA_MISSING:" + raw_route_id)
        for signature, trip_count in sorted(variants.items(), key=lambda item: item[0]):
            pattern_id = stable_pattern_id(raw_route_id, signature, len(variants))
            # GTFS stop_sequence는 정렬 키일 뿐 1씩 증가한다는 보장이 없다. 원본 순번은
            # 아래 수식 통계 키에 보존하고, 서비스 마스터의 인접 occurrence는 1부터
            # 재번호화해 Java의 canonical-adjacent 계약과 분리한다.
            ordered_stop_ids = [signature[0][0]] + [edge[1] for edge in signature]
            ordered = [(stop_id, index + 1) for index, stop_id in enumerate(ordered_stop_ids)]
            master_stops = []
            for stop_id, sequence in ordered:
                source = stops.get(stop_id)
                if source is None:
                    raise ValueError("STOP_METADATA_MISSING:" + stop_id)
                name = str(source.get("stop_name") or "").strip()
                latitude, longitude = source.get("stop_lat"), source.get("stop_lon")
                if not name or latitude is None or longitude is None:
                    raise ValueError("STOP_NAME_OR_COORDINATE_MISSING:" + stop_id)
                master_stops.append({
                    "namespace": NAMESPACE, "stopId": stop_id, "stopName": name,
                    "sequence": sequence, "direction": None, "boardingAllowed": None,
                    "longitude": float(longitude), "latitude": float(latitude),
                    "mobileCode": None, "coordinateStatus": "KTDB_GTFS_202503",
                })
            routes.append({
                "namespace": NAMESPACE, "routeId": pattern_id,
                "routeName": route_names[raw_route_id], "region": "부산광역시",
                "stops": master_stops, "shapes": {},
            })
            for index, edge in enumerate(signature):
                pattern_edges.append({
                    "rawRouteId": raw_route_id, "patternRouteId": pattern_id,
                    "fromStopId": edge[0], "toStopId": edge[1],
                    "fromOrder": index + 1, "toOrder": index + 2,
                    "rawFromOrder": edge[2], "rawToOrder": edge[3],
                    "patternTripCount": trip_count,
                })
    return routes, pattern_edges


def formula_index(serving_rows):
    """연구 충분통계를 정확한 원본 edge·시간 키로 색인한다."""
    cells, segment_support, segment_means = {}, defaultdict(int), {}
    for row in serving_rows:
        edge = (str(row["route_id"]), str(row["from_stop_id"]), str(row["to_stop_id"]),
                int(row["from_sequence"]), int(row["to_sequence"]))
        key = edge + (int(row["hour"]),)
        if key in cells:
            raise ValueError("DUPLICATE_FORMULA_CELL")
        cells[key] = row
        mean = float(row["segment_mean_seconds"])
        if edge in segment_means and abs(segment_means[edge] - mean) > 1e-9:
            raise ValueError("INCONSISTENT_SEGMENT_MEAN")
        segment_means[edge] = mean
        segment_support[edge] += int(row["sample_count"])
    return cells, segment_means, segment_support


def build_formula_rows(pattern_edges, serving_rows, hour_factors, valid_from, valid_until):
    """모든 canonical edge에 24시간 행을 만든다.

    해당 시간대 운행편이 없으면 localSampleCount=0/localMeanSeconds=null로 두어 Backend가
    구간 평균과 부산 시간대 계수로 fallback한다. observedDates=0은 시간표를 실제 관측으로
    위장하지 않는 계약이다.
    """
    cells, means, support = formula_index(serving_rows)
    output = []
    for mapped in pattern_edges:
        edge = (mapped["rawRouteId"], mapped["fromStopId"], mapped["toStopId"],
                mapped.get("rawFromOrder", mapped["fromOrder"]),
                mapped.get("rawToOrder", mapped["toOrder"]))
        if edge not in means:
            raise ValueError("FORMULA_SEGMENT_MISSING:" + repr(edge))
        for hour in range(24):
            cell = cells.get(edge + (hour,))
            local_count = 0 if cell is None else int(cell["sample_count"])
            local_mean = None if cell is None else float(cell["local_mean_seconds"])
            factor = float(hour_factors[str(hour)])
            prediction = (means[edge] * factor if local_count == 0 else
                          (local_count * local_mean + ALPHA * means[edge] * factor) /
                          (local_count + ALPHA))
            if not 0 < prediction <= 3600:
                raise ValueError("INVALID_FORMULA_RESULT")
            prefix = NAMESPACE + "."
            output.append({
                "namespace": NAMESPACE, "routeId": prefix + mapped["patternRouteId"],
                "fromStopId": prefix + mapped["fromStopId"],
                "toStopId": prefix + mapped["toStopId"],
                "fromOrder": mapped["fromOrder"], "toOrder": mapped["toOrder"],
                "dow": 1, "hourSlot": hour, "sampleKind": "GTFS_SCHEDULE_FORMULA",
                "sampleCount": max(1, support[edge]), "observedDates": 0,
                "observationStart": valid_from, "observationEnd": valid_until,
                "localMeanSeconds": local_mean, "localSampleCount": local_count,
                "segmentMeanSeconds": means[edge], "hourFactor": factor,
                "alpha": ALPHA, "expectedSeconds": prediction,
            })
    return output


def read_parquet(path: Path, columns=None):
    """pyarrow를 지연 import해 순수 변환 단위 테스트는 추가 의존성 없이 실행한다."""
    try:
        import pyarrow.dataset as dataset
        import pyarrow.parquet as parquet
    except ImportError as error:
        raise RuntimeError("pyarrow 고정 버전이 필요합니다.") from error
    if path.is_dir():
        return dataset.dataset(path, format="parquet").to_table(columns=columns).to_pylist()
    return parquet.read_table(path, columns=columns).to_pylist()


def run(base_master_path: Path, lab: Path, master_output: Path, formula_output: Path,
        version: str, valid_from: str, valid_until: str):
    """전체 입력 검증 후 두 임시 파일을 원자적으로 최종 경로로 바꾼다."""
    if master_output.exists() or formula_output.exists():
        raise ValueError("OUTPUT_ALREADY_EXISTS")
    with gzip.open(base_master_path, "rt", encoding="utf-8") as stream:
        base = json.load(stream)
    if any(route.get("namespace") == NAMESPACE for route in base.get("routes", [])):
        raise ValueError("BUSAN_ALREADY_PRESENT")
    route_rows = read_parquet(lab / "routes.parquet")
    stop_rows = read_parquet(lab / "stops.parquet")
    trip_rows = read_parquet(lab / "trip_segments",
        ["trip_id", "route_id", "from_stop_id", "to_stop_id", "from_sequence", "to_sequence"])
    serving_rows = read_parquet(lab / "serving_busan_formula.parquet")
    config = json.loads((lab / "busan_formula_config.json").read_text(encoding="utf-8"))
    if float(config.get("alpha")) != ALPHA or config.get("region") != "BUSAN":
        raise ValueError("UNSUPPORTED_FORMULA_CONFIG")

    patterns = collect_patterns(trip_rows)
    routes, pattern_edges = build_busan_routes(patterns, route_rows, stop_rows)
    formulas = build_formula_rows(pattern_edges, serving_rows, config["hour_factors"],
                                  valid_from, valid_until)
    source_files = [base_master_path, lab / "manifest.json", lab / "routes.parquet",
                    lab / "stops.parquet", lab / "serving_busan_formula.parquet",
                    lab / "busan_formula_config.json"] + sorted((lab / "trip_segments").glob("*.parquet"))
    source_map = {str(path): sha256_file(path) for path in source_files}
    master = {
        "schemaVersion": 1, "version": version,
        "sourceCsvSha256": sha256_bytes(json.dumps(source_map, sort_keys=True).encode()),
        "sourceMetadataSha256": sha256_bytes(json.dumps(
            {"baseVersion": base["version"], "sources": source_map},
            ensure_ascii=False, sort_keys=True).encode()),
        "routes": list(base["routes"]) + routes, "reviews": list(base.get("reviews", [])),
    }
    raw = json.dumps(master, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()
    master_output.parent.mkdir(parents=True, exist_ok=True)
    formula_output.parent.mkdir(parents=True, exist_ok=True)
    master_tmp = master_output.with_suffix(master_output.suffix + ".partial")
    formula_tmp = formula_output.with_suffix(formula_output.suffix + ".partial")
    master_tmp.write_bytes(gzip.compress(raw, mtime=0))
    with formula_tmp.open("w", encoding="utf-8", newline="\n") as stream:
        for row in formulas:
            stream.write(json.dumps(row, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n")
    master_tmp.replace(master_output)
    formula_tmp.replace(formula_output)
    return {
        "version": version, "baseVersion": base["version"],
        "busanSourceRoutes": len(patterns), "busanPatternRoutes": len(routes),
        "busanEdges": len(pattern_edges), "formulaRows": len(formulas),
        "masterSha256": sha256_file(master_output),
        "formulaSha256": sha256_file(formula_output), "productionReady": False,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-master", type=Path, required=True)
    parser.add_argument("--lab", type=Path, required=True)
    parser.add_argument("--master-output", type=Path, required=True)
    parser.add_argument("--formula-output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--valid-from", default="2017-01-01")
    parser.add_argument("--valid-until", default="2030-12-31")
    parser.add_argument("--pyarrow-path", type=Path)
    arguments = parser.parse_args()
    if arguments.pyarrow_path:
        sys.path.insert(0, str(arguments.pyarrow_path))
    print(json.dumps(run(arguments.base_master, arguments.lab, arguments.master_output,
        arguments.formula_output, arguments.version, arguments.valid_from,
        arguments.valid_until), ensure_ascii=False))
