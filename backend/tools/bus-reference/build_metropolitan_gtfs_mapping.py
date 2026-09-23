"""수도권 GTFS 수식 통계를 현재 GBIS·인천 canonical edge에 보수적으로 연결한다.

표시 노선명과 정류장 ID 숫자 본문이 모두 일치하고 GTFS 후보 edge가 정확히 하나일
때만 매핑한다. 후보가 없거나 둘 이상이면 추측하지 않고 제외한다. 출력은 24시간
수식 JSONL과 품질 manifest이며 DB·서버·활성 마스터를 변경하지 않는다.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import gzip
import hashlib
import json
from pathlib import Path
import re
import sys
import unicodedata


REGIONS = {"GBIS": ("GYEONGGI", "SEOUL"), "INCHEON_PUBLIC_CSV": ("INCHEON",)}


def route_key(value):
    """Java MetropolitanBusMatcher의 후보 검색 정규화와 같은 의미를 사용한다."""
    text = unicodedata.normalize("NFKC", str(value or "")).upper()
    text = re.sub(r"\([^)]*\)|\[[^]]*\]", "", text)
    text = re.sub(r"[^\w\-]", "", text, flags=re.UNICODE).replace("_", "")
    text = re.sub(r"(버스|번)$", "", text)
    return re.sub(r"(?:평일)?(?:출근|퇴근)$", "", text)


def numeric_tail(value):
    """KTDB의 BS_* 접두사를 제거하되 숫자 본문이 없는 ID는 매핑하지 않는다."""
    match = re.search(r"(\d+)$", str(value))
    return None if match is None else match.group(1)


def build_candidate_index(route_rows, segment_rows):
    """지역·정규화 노선명·양 끝 정류장 ID가 모두 같은 GTFS edge를 색인한다."""
    routes = {str(row["route_id"]): row for row in route_rows}
    index = defaultdict(list)
    for row in segment_rows:
        route = routes.get(str(row["route_id"]))
        if route is None:
            raise ValueError("GTFS_ROUTE_METADATA_MISSING")
        from_id, to_id = numeric_tail(row["from_stop_id"]), numeric_tail(row["to_stop_id"])
        if from_id is None or to_id is None or from_id == to_id:
            continue
        key = (str(row["region"]), route_key(route["route_name"]), from_id, to_id)
        index[key].append((str(row["route_id"]), str(row["from_stop_id"]),
                           str(row["to_stop_id"]), int(row["from_sequence"]),
                           int(row["to_sequence"])))
    return index


def map_master_edges(master, candidate_index):
    """현재 마스터의 인접 edge마다 유일한 GTFS 원본 키만 확정한다."""
    mapped, reasons = [], Counter()
    for route in master["routes"]:
        namespace = route.get("namespace")
        if namespace not in REGIONS:
            continue
        for first, second in zip(route["stops"], route["stops"][1:]):
            if int(second["sequence"]) != int(first["sequence"]) + 1:
                reasons["MASTER_SEQUENCE_GAP"] += 1
                continue
            candidates = []
            for region in REGIONS[namespace]:
                candidates.extend(candidate_index.get(
                    (region, route_key(route["routeName"]), str(first["stopId"]), str(second["stopId"])), []))
            candidates = sorted(set(candidates))
            if len(candidates) != 1:
                reasons["NO_EXACT_GTFS_EDGE" if not candidates else "AMBIGUOUS_GTFS_EDGE"] += 1
                continue
            mapped.append({
                "namespace": namespace, "routeId": str(route["routeId"]),
                "fromStopId": str(first["stopId"]), "toStopId": str(second["stopId"]),
                "fromOrder": int(first["sequence"]), "toOrder": int(second["sequence"]),
                "gtfsEdge": candidates[0],
            })
    return mapped, reasons


def load_formula_cells(parquet_path, needed):
    """3백만 행 전체를 객체화하지 않고 필요한 GTFS edge만 배치 단위로 읽는다."""
    try:
        import pyarrow.parquet as parquet
    except ImportError as error:
        raise RuntimeError("pyarrow 고정 버전이 필요합니다.") from error
    columns = ["route_id", "from_stop_id", "to_stop_id", "from_sequence", "to_sequence",
               "hour", "sample_count", "local_mean_seconds", "segment_mean_seconds"]
    cells, means, support = {}, {}, defaultdict(int)
    for batch in parquet.ParquetFile(parquet_path).iter_batches(columns=columns, batch_size=65536):
        for row in batch.to_pylist():
            edge = (str(row["route_id"]), str(row["from_stop_id"]), str(row["to_stop_id"]),
                    int(row["from_sequence"]), int(row["to_sequence"]))
            if edge not in needed:
                continue
            key = edge + (int(row["hour"]),)
            if key in cells:
                raise ValueError("DUPLICATE_FORMULA_CELL")
            cells[key] = row
            mean = float(row["segment_mean_seconds"])
            if edge in means and abs(means[edge] - mean) > 1e-9:
                raise ValueError("INCONSISTENT_SEGMENT_MEAN")
            means[edge] = mean
            support[edge] += int(row["sample_count"])
    return cells, means, support


def formula_rows(mapped, cells, means, support, factors, valid_from, valid_until):
    """유일 매핑 edge를 24시간 템플릿으로 전개한다.

    수도권 검증식의 alpha=0이므로 표본이 있는 시간은 L, 없는 시간은 B×k를 사용한다.
    """
    for edge in mapped:
        source = edge["gtfsEdge"]
        if source not in means:
            raise ValueError("FORMULA_SEGMENT_MISSING")
        prefix = edge["namespace"] + "."
        for hour in range(24):
            cell = cells.get(source + (hour,))
            local_count = 0 if cell is None else int(cell["sample_count"])
            local_mean = None if cell is None else float(cell["local_mean_seconds"])
            factor = float(factors[str(hour)])
            prediction = local_mean if local_count > 0 else means[source] * factor
            if not 0 < prediction <= 3600:
                raise ValueError("INVALID_FORMULA_RESULT")
            yield {
                "namespace": edge["namespace"], "routeId": prefix + edge["routeId"],
                "fromStopId": prefix + edge["fromStopId"], "toStopId": prefix + edge["toStopId"],
                "fromOrder": edge["fromOrder"], "toOrder": edge["toOrder"],
                "dow": 1, "hourSlot": hour, "sampleKind": "GTFS_SCHEDULE_FORMULA",
                "sampleCount": max(1, support[source]), "observedDates": 0,
                "observationStart": valid_from, "observationEnd": valid_until,
                "localMeanSeconds": local_mean, "localSampleCount": local_count,
                "segmentMeanSeconds": means[source], "hourFactor": factor,
                "expectedSeconds": prediction,
            }


def valid_formula_edge(mapped, cells, means, factors):
    """24시간 중 하나라도 Backend 단일 edge 허용 범위를 벗어나면 edge 전체를 제외한다."""
    source = mapped["gtfsEdge"]
    if source not in means:
        return False
    for hour in range(24):
        cell = cells.get(source + (hour,))
        prediction = (float(cell["local_mean_seconds"]) if cell is not None and int(cell["sample_count"]) > 0
                      else means[source] * float(factors[str(hour)]))
        if not 0 < prediction <= 3600:
            return False
    return True


def read_parquet(path, columns=None):
    try:
        import pyarrow.parquet as parquet
    except ImportError as error:
        raise RuntimeError("pyarrow 고정 버전이 필요합니다.") from error
    return parquet.read_table(path, columns=columns).to_pylist()


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run(master_path, lab, output, valid_from, valid_until):
    """매핑·수식 검증이 모두 끝난 뒤 임시 JSONL을 최종 파일로 교체한다."""
    manifest_path = output.with_suffix(output.suffix + ".manifest.json")
    if output.exists() or manifest_path.exists():
        raise ValueError("OUTPUT_ALREADY_EXISTS")
    with gzip.open(master_path, "rt", encoding="utf-8") as stream:
        master = json.load(stream)
    route_rows = read_parquet(lab / "routes.parquet")
    segment_rows = read_parquet(lab / "segments.parquet",
        ["region", "route_id", "from_stop_id", "to_stop_id", "from_sequence", "to_sequence"])
    mapped, reasons = map_master_edges(master, build_candidate_index(route_rows, segment_rows))
    needed = {edge["gtfsEdge"] for edge in mapped}
    cells, means, support = load_formula_cells(lab / "serving_common_formula.parquet", needed)
    config = json.loads((lab / "common_formula_config.json").read_text(encoding="utf-8"))
    if float(config.get("alpha")) != 0.0:
        raise ValueError("UNSUPPORTED_METROPOLITAN_ALPHA")
    valid_mapped = []
    for edge in mapped:
        if valid_formula_edge(edge, cells, means, config["hour_factors"]):
            valid_mapped.append(edge)
        else:
            reasons["FORMULA_RESULT_OUT_OF_RANGE_OR_MISSING"] += 1
    mapped = valid_mapped
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".partial")
    row_count, namespaces = 0, Counter()
    with temporary.open("w", encoding="utf-8", newline="\n") as stream:
        for row in formula_rows(mapped, cells, means, support, config["hour_factors"],
                                valid_from, valid_until):
            stream.write(json.dumps(row, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n")
            row_count += 1
            namespaces[row["namespace"]] += 1
    temporary.replace(output)
    report = {
        "masterVersion": master["version"], "mappedEdges": len(mapped),
        "formulaRows": row_count, "allHoursPerEdge": row_count == len(mapped) * 24,
        "namespaces": dict(namespaces), "excludedEdges": dict(reasons),
        "mappingPolicy": "UNIQUE_ROUTE_NAME_AND_EXACT_STOP_ID_PAIR",
        "sourceSha256": sha256_file(lab / "serving_common_formula.parquet"),
        "outputSha256": sha256_file(output), "productionReady": False,
    }
    manifest_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--master", type=Path, required=True)
    parser.add_argument("--lab", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--valid-from", default="2017-01-01")
    parser.add_argument("--valid-until", default="2030-12-31")
    parser.add_argument("--pyarrow-path", type=Path)
    arguments = parser.parse_args()
    if arguments.pyarrow_path:
        sys.path.insert(0, str(arguments.pyarrow_path))
    print(json.dumps(run(arguments.master, arguments.lab, arguments.output,
                         arguments.valid_from, arguments.valid_until), ensure_ascii=False))
