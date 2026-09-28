"""버스 fallback 수식의 충분통계를 Backend 적재용 불변 번들로 만든다.

이 도구는 원본 GTFS나 서울시 수억 행을 서비스 서버에 복사하지 않기 위한 경계다.
별도 분석 과정에서 프로젝트 마스터 ID까지 매핑한 JSONL을 입력받아, Backend의
``BusTravelTimeFormula``와 같은 식으로 ``meanSeconds``를 계산하고 1,000행 단위 gzip
청크와 검증 manifest를 만든다. 출력은 내부 API로 MySQL에 넣을 수 있지만 이 도구 자체는
네트워크나 DB를 변경하지 않는다.

입력 공통 필드:
``namespace, routeId, fromStopId, toStopId, fromOrder, toOrder, dow, hourSlot,
sampleKind, sampleCount, observedDates, observationStart, observationEnd``.

수식별 추가 필드:
- SEOUL_BUS: ``weightedSeconds, weightTotal``
- GBIS/INCHEON_PUBLIC_CSV: ``localMeanSeconds, localSampleCount,
  segmentMeanSeconds, hourFactor``
- BUSAN_GTFS: 수도권 필드와 ``alpha=0.25``

GTFS에서 특정 시간대 표본이 없으면 localSampleCount=0, localMeanSeconds=null로 두고
구간 기준값과 시간대 계수로 계산한다. 행의 sampleCount는 Backend 저장 계약상 1 이상이어야
하므로 이때에는 해당 구간 기준값을 만든 전체 GTFS 운행편 수를 넣는다.
"""

import argparse
import datetime
import gzip
import hashlib
import heapq
import json
from pathlib import Path
import re
import shutil


SEOUL_FORMULA = "SEOUL_HALF_LIFE_180D"
METROPOLITAN_FORMULA = "METROPOLITAN_GTFS_TIME_BUCKET"
BUSAN_FORMULA = "BUSAN_GTFS_SHRINKAGE"
BUSAN_ALPHA = 0.25
ID_PREFIX = {
    "GBIS": "GBIS.",
    "INCHEON_PUBLIC_CSV": "INCHEON_PUBLIC_CSV.",
    "BUSAN_GTFS": "BUSAN_GTFS.",
}
ALLOWED_SAMPLE_KINDS = {
    "HOURLY_SOURCE_MEAN",
    "ACTUAL_TRAVERSAL_MEAN",
    "GTFS_SCHEDULE_FORMULA",
}


def sha256(path):
    """대용량 입력도 메모리에 올리지 않고 재현성 지문을 계산한다."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def positive(value, field):
    """수식의 분모·시간 값에 0, NaN, 무한대가 들어가는 것을 적재 전에 차단한다."""
    if type(value) not in (int, float) or not float(value) > 0 or not float(value) < float("inf"):
        raise ValueError("INVALID_POSITIVE:" + field)
    return float(value)


def non_negative_count(value, field):
    if type(value) is not int or value < 0:
        raise ValueError("INVALID_COUNT:" + field)
    return value


def token(value, maximum, field):
    if not isinstance(value, str) or re.fullmatch(r"[A-Za-z0-9._-]{1," + str(maximum) + r"}", value) is None:
        raise ValueError("INVALID_TOKEN:" + field)
    return value


def formula(namespace, row):
    """Java 런타임과 같은 식으로 최종 초와 저장할 formulaInput을 만든다."""
    if namespace == "SEOUL_BUS":
        weighted = positive(row.get("weightedSeconds"), "weightedSeconds")
        total = positive(row.get("weightTotal"), "weightTotal")
        result = weighted / total
        values = {
            "formula": SEOUL_FORMULA,
            "weightedSeconds": weighted,
            "weightTotal": total,
        }
    elif namespace in ("GBIS", "INCHEON_PUBLIC_CSV"):
        count = non_negative_count(row.get("localSampleCount"), "localSampleCount")
        local = row.get("localMeanSeconds")
        segment = positive(row.get("segmentMeanSeconds"), "segmentMeanSeconds")
        factor = positive(row.get("hourFactor"), "hourFactor")
        if count > 0:
            local = positive(local, "localMeanSeconds")
            result = local
        else:
            if local is not None:
                raise ValueError("LOCAL_MEAN_WITHOUT_SAMPLE")
            result = segment * factor
        values = {
            "formula": METROPOLITAN_FORMULA,
            "localMeanSeconds": local,
            "localSampleCount": count,
            "segmentMeanSeconds": segment,
            "hourFactor": factor,
        }
    elif namespace == "BUSAN_GTFS":
        count = non_negative_count(row.get("localSampleCount"), "localSampleCount")
        local = row.get("localMeanSeconds")
        segment = positive(row.get("segmentMeanSeconds"), "segmentMeanSeconds")
        factor = positive(row.get("hourFactor"), "hourFactor")
        alpha = positive(row.get("alpha"), "alpha")
        if abs(alpha - BUSAN_ALPHA) > 1e-12:
            raise ValueError("INVALID_BUSAN_ALPHA")
        if count > 0:
            local = positive(local, "localMeanSeconds")
        elif local is not None:
            raise ValueError("LOCAL_MEAN_WITHOUT_SAMPLE")
        local_term = 0.0 if count == 0 else count * local
        result = (local_term + alpha * segment * factor) / (count + alpha)
        values = {
            "formula": BUSAN_FORMULA,
            "localMeanSeconds": local,
            "localSampleCount": count,
            "segmentMeanSeconds": segment,
            "hourFactor": factor,
            "alpha": alpha,
        }
    else:
        raise ValueError("UNSUPPORTED_NAMESPACE")
    if not 0 < result <= 3600:
        raise ValueError("INVALID_FORMULA_RESULT")
    return result, values


def convert(row, master_version, source_sha):
    """한 매핑 완료 행을 MySQL payload 계약으로 변환하고 의미 불일치를 거절한다."""
    namespace = token(row.get("namespace"), 30, "namespace")
    route = token(row.get("routeId"), 80, "routeId")
    from_stop = token(row.get("fromStopId"), 80, "fromStopId")
    to_stop = token(row.get("toStopId"), 80, "toStopId")
    prefix = ID_PREFIX.get(namespace)
    if prefix is not None and not all(value.startswith(prefix) for value in (route, from_stop, to_stop)):
        raise ValueError("NON_CANONICAL_ID")
    if namespace == "SEOUL_BUS" and any(value.startswith(tuple(ID_PREFIX.values())) for value in (route, from_stop, to_stop)):
        raise ValueError("NON_CANONICAL_ID")

    for name in ("fromOrder", "toOrder", "dow", "hourSlot", "sampleCount", "observedDates"):
        if type(row.get(name)) is not int:
            raise ValueError("INVALID_INTEGER:" + name)
    if row["fromOrder"] < 1 or row["toOrder"] != row["fromOrder"] + 1 or from_stop == to_stop:
        raise ValueError("INVALID_OCCURRENCE")
    if not 1 <= row["dow"] <= 7 or not 0 <= row["hourSlot"] <= 23:
        raise ValueError("INVALID_TIME_BUCKET")
    if row["sampleCount"] < 1 or row["observedDates"] < 0:
        raise ValueError("INVALID_SAMPLES")

    kind = row.get("sampleKind")
    if kind not in ALLOWED_SAMPLE_KINDS:
        raise ValueError("INVALID_SAMPLE_KIND")
    if namespace == "SEOUL_BUS":
        if kind == "GTFS_SCHEDULE_FORMULA" or row["observedDates"] < 1 or row["sampleCount"] < row["observedDates"]:
            raise ValueError("INVALID_SEOUL_SAMPLE")
    # 런타임은 실제 요일 관측이 없는 GTFS 행을 dow=1 템플릿으로만 조회한다.
    # 다른 요일을 허용하면 적재는 성공해도 추천 계산에서 영원히 찾지 못하는 행이 된다.
    elif kind != "GTFS_SCHEDULE_FORMULA" or row["observedDates"] != 0 or row["dow"] != 1:
        raise ValueError("INVALID_GTFS_SAMPLE")

    start = datetime.date.fromisoformat(row.get("observationStart"))
    end = datetime.date.fromisoformat(row.get("observationEnd"))
    if end < start or row["observedDates"] > (end - start).days + 1:
        raise ValueError("INVALID_OBSERVATION_PERIOD")
    seconds, formula_input = formula(namespace, row)
    return {
        "masterVersion": master_version,
        "namespace": namespace,
        "routeId": route,
        "fromStopId": from_stop,
        "toStopId": to_stop,
        "fromOrder": row["fromOrder"],
        "toOrder": row["toOrder"],
        "dow": row["dow"],
        "hourSlot": row["hourSlot"],
        "sampleKind": kind,
        "meanSeconds": seconds,
        "sampleCount": row["sampleCount"],
        "observedDates": row["observedDates"],
        "observationStart": start.isoformat(),
        "observationEnd": end.isoformat(),
        "sourceSha256": source_sha,
        "formulaInput": formula_input,
    }


def build(source, output, master_version):
    """전체 입력을 검증한 후에만 새 출력 폴더를 생성해 부분 번들을 남기지 않는다."""
    if output.exists():
        raise ValueError("OUTPUT_ALREADY_EXISTS")
    token(master_version, 100, "masterVersion")
    source_sha = sha256(source)
    code_sha = sha256(Path(__file__))
    rows = []
    with source.open(encoding="utf-8-sig") as stream:
        for number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                rows.append(convert(json.loads(line), master_version, source_sha))
            except (ValueError, TypeError, KeyError, json.JSONDecodeError) as error:
                raise ValueError("INVALID_SOURCE_ROW:" + str(number) + ":" + str(error)) from error
    if not rows:
        raise ValueError("EMPTY_SOURCE")

    key = lambda row: (
        row["masterVersion"], row["namespace"], row["routeId"], row["fromStopId"],
        row["toStopId"], row["fromOrder"], row["toOrder"], row["dow"], row["hourSlot"],
        row["sampleKind"],
    )
    rows.sort(key=key)
    if any(key(rows[index - 1]) == key(rows[index]) for index in range(1, len(rows))):
        raise ValueError("DUPLICATE_REFERENCE_KEY")

    version_seed = source_sha + code_sha + master_version
    data_version = "bus-formula-" + hashlib.sha256(version_seed.encode()).hexdigest()[:24]
    output.mkdir(parents=True)
    outputs = {}
    kinds = {}
    namespaces = {}
    for row in rows:
        kinds[row["sampleKind"]] = kinds.get(row["sampleKind"], 0) + 1
        namespaces[row["namespace"]] = namespaces.get(row["namespace"], 0) + 1
    for chunk, start in enumerate(range(0, len(rows), 1000)):
        body = json.dumps({"rows": rows[start:start + 1000]}, ensure_ascii=False,
                          separators=(",", ":"), allow_nan=False).encode()
        path = output / (f"{chunk:06d}.json.gz")
        temporary = output / (path.name + ".partial")
        temporary.write_bytes(gzip.compress(body, compresslevel=1, mtime=0))
        temporary.replace(path)
        outputs[path.name] = sha256(path)

    manifest = {
        "schemaVersion": 3,
        "dataVersion": data_version,
        "masterVersion": master_version,
        "rows": len(rows),
        "chunks": len(outputs),
        "complete": True,
        "productionReady": False,
        "outputs": outputs,
        "scannedRows": len(rows),
        "rejectedRows": 0,
        "rejectionReasons": {},
        "sourceManifestSha256": source_sha,
        "codeSha256": code_sha,
        "sampleKinds": kinds,
        "namespaces": namespaces,
        "formulaInputRequired": True,
    }
    temporary = output / "export.json.tmp"
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    temporary.replace(output / "export.json")
    return {key: value for key, value in manifest.items() if key != "outputs"}


def build_streaming(sources, output, master_version):
    """여러 대용량 JSONL을 외부 병합 정렬해 하나의 불변 번들로 만든다.

    5만 행씩만 메모리에 올려 정렬 run을 만든 뒤 heap 병합한다. 입력 순서와 무관하게
    결정적 청크를 만들고, 중복·오류가 있으면 최종 폴더를 남기지 않는다.
    """
    if output.exists():
        raise ValueError("OUTPUT_ALREADY_EXISTS")
    token(master_version, 100, "masterVersion")
    sources = [Path(source) for source in sources]
    if not sources:
        raise ValueError("EMPTY_SOURCE_LIST")
    source_hashes = [sha256(source) for source in sources]
    source_sha = hashlib.sha256("".join(source_hashes).encode()).hexdigest()
    code_sha = sha256(Path(__file__))
    temporary = output.with_name(output.name + ".partial")
    if temporary.exists():
        raise ValueError("PARTIAL_OUTPUT_ALREADY_EXISTS")
    temporary.mkdir(parents=True)
    runs = temporary / "runs"
    runs.mkdir()
    rows, outputs, kinds, namespaces = [], {}, {}, {}
    scanned, chunk = 0, 0
    key = lambda row: (
        row["masterVersion"], row["namespace"], row["routeId"], row["fromStopId"],
        row["toStopId"], row["fromOrder"], row["toOrder"], row["dow"], row["hourSlot"],
        row["sampleKind"],
    )

    def flush_chunk():
        """검증된 최대 1,000행만 gzip으로 쓰고 파일 SHA를 즉시 고정한다."""
        nonlocal rows, chunk
        if not rows:
            return
        body = json.dumps({"rows": rows}, ensure_ascii=False,
                          separators=(",", ":"), allow_nan=False).encode()
        path = temporary / f"{chunk:06d}.json.gz"
        path.write_bytes(gzip.compress(body, compresslevel=1, mtime=0))
        outputs[path.name] = sha256(path)
        rows, chunk = [], chunk + 1

    def write_run(values, number):
        """한 메모리 묶음을 canonical key로 정렬해 임시 JSONL로 고정한다."""
        values.sort(key=key)
        path = runs / f"{number:06d}.jsonl"
        with path.open("w", encoding="utf-8", newline="\n") as stream:
            for value in values:
                stream.write(json.dumps(value, ensure_ascii=False,
                                        separators=(",", ":"), allow_nan=False) + "\n")
        return path

    def read_run(path):
        """각 run의 현재 최소 key만 메모리에 유지하도록 한 행씩 읽는다."""
        with path.open(encoding="utf-8") as stream:
            for line in stream:
                value = json.loads(line)
                yield key(value), value

    try:
        run_rows, run_files = [], []
        for source_number, source in enumerate(sources, 1):
            with source.open(encoding="utf-8-sig") as stream:
                for line_number, line in enumerate(stream, 1):
                    if not line.strip():
                        continue
                    try:
                        row = convert(json.loads(line), master_version, source_sha)
                    except (ValueError, TypeError, KeyError, json.JSONDecodeError) as error:
                        raise ValueError(
                            f"INVALID_SOURCE_ROW:{source_number}:{line_number}:{error}") from error
                    run_rows.append(row)
                    if len(run_rows) == 50000:
                        run_files.append(write_run(run_rows, len(run_files)))
                        run_rows = []
        if run_rows:
            run_files.append(write_run(run_rows, len(run_files)))
        if not run_files:
            raise ValueError("EMPTY_SOURCE")
        previous = None
        for current, row in heapq.merge(*(read_run(path) for path in run_files)):
            if current == previous:
                raise ValueError("DUPLICATE_REFERENCE_KEY")
            previous = current
            rows.append(row)
            scanned += 1
            kinds[row["sampleKind"]] = kinds.get(row["sampleKind"], 0) + 1
            namespaces[row["namespace"]] = namespaces.get(row["namespace"], 0) + 1
            if len(rows) == 1000:
                flush_chunk()
        flush_chunk()
        shutil.rmtree(runs)
        data_version = "bus-formula-" + hashlib.sha256(
            (source_sha + code_sha + master_version).encode()).hexdigest()[:24]
        manifest = {
            "schemaVersion": 3, "dataVersion": data_version,
            "masterVersion": master_version, "rows": scanned, "chunks": len(outputs),
            "complete": True, "productionReady": False, "outputs": outputs,
            "scannedRows": scanned, "rejectedRows": 0, "rejectionReasons": {},
            "sourceManifestSha256": source_sha, "sourceSha256s": source_hashes,
            "codeSha256": code_sha, "sampleKinds": kinds, "namespaces": namespaces,
            "formulaInputRequired": True,
        }
        (temporary / "export.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8", newline="\n")
        temporary.replace(output)
        return {name: value for name, value in manifest.items() if name != "outputs"}
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="프로젝트 canonical ID까지 매핑된 충분통계 JSONL")
    parser.add_argument("output", type=Path, help="새로 만들 출력 폴더")
    parser.add_argument("--master-version", required=True)
    parser.add_argument("--additional-source", type=Path, action="append", default=[],
                        help="같은 masterVersion으로 묶을 추가 canonical 충분통계 JSONL")
    arguments = parser.parse_args()
    sources = [arguments.source] + arguments.additional_source
    function = build_streaming if arguments.additional_source else build
    values = function(sources, arguments.output, arguments.master_version) if arguments.additional_source \
        else function(arguments.source, arguments.output, arguments.master_version)
    print(json.dumps(values, ensure_ascii=False))
