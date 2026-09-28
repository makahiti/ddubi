"""전체 AccInfo 목록을 보존·요약하고 선택한 설명 표본만 GMS로 전처리한다."""

import argparse
import importlib.util
import json
import os
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

SPARKJOBS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SPARKJOBS))

from common.traffic_incident_ai import IncidentAiExtractor, enrich_rows  # noqa: E402
from common.traffic_incident_enrichment import VerifiedLinkMaster, normalize_incident  # noqa: E402


def load_env(path):
    values = {}
    if path and path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            line = line.strip()
            if line.startswith("export "):
                line = line[7:]
            if not line or line.startswith("#") or "=" not in line:
                continue
            name, value = line.split("=", 1)
            values[name.strip()] = value.strip().strip("\"'")
    return {**values, **os.environ}


def source_module():
    path = SPARKJOBS.parent / "collector/src/sources/traffic_incident.py"
    spec = importlib.util.spec_from_file_location("accinfo_fetch", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def profile(snapshot):
    rows = snapshot["items"]
    descriptions = list(dict.fromkeys(str(row.get("description") or "") for row in rows))
    return {
        "collectedAt": snapshot.get("collectedAt"),
        "receivedCount": len(rows),
        "uniqueDescriptionCount": len(descriptions),
        "typeCounts": dict(Counter(str(row.get("incidentTypeCode") or "MISSING") for row in rows)),
        "descriptions": descriptions,
    }


def select_samples(rows, limit):
    groups = {}
    seen = set()
    for row in rows:
        description = row.get("description")
        if not description or description in seen:
            continue
        seen.add(description)
        # 유형별로 최소 한 건씩 먼저 보며 같은 문장의 반복을 제외한다.
        groups.setdefault(row.get("incidentTypeCode") or "UNKNOWN", []).append(row)
    selected = []
    while groups and len(selected) < limit:
        for name in list(groups):
            selected.append(groups[name].pop(0))
            if not groups[name]:
                del groups[name]
            if len(selected) >= limit:
                break
    return selected


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--fetch", action="store_true", help="서울 API에서 현재 전체 목록을 가져온다")
    source.add_argument("--input", type=Path, help="Collector JSON(collectedAt, items) 또는 fixture JSON")
    parser.add_argument("--env-file", type=Path, default=SPARKJOBS.parent / "infra/.env")
    parser.add_argument("--output", type=Path, default=SPARKJOBS / "data/incident-ai-review")
    parser.add_argument("--limit", type=int, default=5, help="GMS 표본 상한, 기본 5건 (1~100)")
    parser.add_argument("--profile-only", action="store_true", help="전체 목록 확인만 하고 GMS를 호출하지 않는다")
    args = parser.parse_args(argv)
    if not 1 <= args.limit <= 100:
        parser.error("limit must be 1..100")
    env = load_env(args.env_file)
    if not args.profile_only and not env.get("GMS_API_KEY"):
        parser.error("GMS_API_KEY is missing")
    if args.fetch:
        key = env.get("SEOUL_OPEN_DATA_API_KEY") or env.get("TRAFFIC_INCIDENT_API_KEY")
        if not key:
            parser.error("SEOUL_OPEN_DATA_API_KEY is missing")
        try:
            snapshot = source_module().fetch_incidents(key)
        except Exception as exc:
            # API URL에 키가 들어가므로 오류 원문/traceback은 출력하지 않는다.
            parser.exit(1, "AccInfo fetch failed (" + type(exc).__name__ + ")\n")
    else:
        snapshot = json.loads(args.input.read_text(encoding="utf-8-sig"))
    if not isinstance(snapshot.get("items"), list) or any(not isinstance(x, dict) for x in snapshot["items"]):
        parser.error("input must contain items array")
    args.output.mkdir(parents=True, exist_ok=True)
    for name, value in (("source.json", snapshot), ("profile.json", profile(snapshot))):
        (args.output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.profile_only:
        print(json.dumps({"rows": len(snapshot["items"]), "profilePath": str(args.output / "profile.json")}))
        return 0
    extractor = IncidentAiExtractor(
        env["GMS_API_KEY"],
        args.output / "cache.sqlite3",
        model=env.get("TRAFFIC_INCIDENT_AI_MODEL") or env.get("GMS_MODEL", "gpt-4o-mini"),
        base_url=env.get("GMS_BASE_URL", "https://gms.ssafy.io/gmsapi/"),
        timeout=float(env.get("TRAFFIC_INCIDENT_AI_TIMEOUT_SECONDS", "15")),
        max_calls_per_batch=args.limit,
    )
    collected = snapshot.get("collectedAt") or datetime.now(timezone.utc).isoformat()
    rows = [
        normalize_incident(
            {
                "eventId": "manual-incident-ai",
                "source": "seoul.traffic_incident",
                "collectedAt": collected,
                "payload": {"raw": raw, "providerLinkNamespace": "SEOUL_ACCINFO_LINK"},
            },
            VerifiedLinkMaster.unavailable(),
        )
        for raw in select_samples(snapshot["items"], args.limit)
    ]
    results = enrich_rows(rows, extractor)
    path = args.output / "enriched.json"
    path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(
        json.dumps(
            {
                "sourceRows": len(snapshot["items"]),
                "sampleRows": len(results),
                "statuses": dict(Counter(x["aiExtractionStatus"] for x in results)),
                "output": str(path),
            }
        )
    )
    return 0 if all(x["aiExtractionStatus"] == "VALID" for x in results) else 1


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
