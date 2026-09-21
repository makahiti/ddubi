"""실제 GMS와 Kakao를 한 번 호출해 TOPIS 전처리 결과를 눈으로 확인한다."""

import argparse
import json
import sys
from pathlib import Path

SPARKJOBS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SPARKJOBS))

from common.topis_enrichment import GmsClient, KakaoGeocoder, normalize_notice

SAMPLE = "9월 19일 14시부터 17시까지 광화문광장에서 서울시청을 거쳐 서울역까지 행진이 예정되어 차량 통제가 실시됩니다."


def load_env(path):
    values = {}
    if not path.exists():
        return values
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip().strip("'\"")
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=SPARKJOBS.parent / "infra" / ".env")
    parser.add_argument("--text", default=SAMPLE)
    parser.add_argument("--collected-at", default="2026-09-17T10:00:00+09:00")
    args = parser.parse_args()
    env = load_env(args.env_file)
    gms = GmsClient(
        env.get("GMS_API_KEY"),
        env.get("GMS_BASE_URL", "https://gms.ssafy.io/gmsapi/"),
        env.get("GMS_MODEL", "gpt-4o-mini"),
        60,
    )
    geocoder = KakaoGeocoder(env.get("KAKAO_REST_API_KEY"), 20)
    envelope = {
        "eventId": "manual-verification",
        "eventType": "topis.notice",
        "source": "topis_notice",
        "collectedAt": args.collected_at,
        "payload": {
            "providerNoticeId": "MANUAL-VERIFY-1",
            "raw": {"noticeId": "MANUAL-VERIFY-1", "title": "수동 검증", "contentText": args.text},
        },
    }
    rows = normalize_notice(envelope, gms, geocoder)
    fields = (
        "eventId",
        "eventType",
        "impactType",
        "startAt",
        "endAt",
        "processingStatus",
        "qualityFlags",
        "routeMappingReady",
        "routePoints",
        "routeLegs",
        "extractionModel",
    )
    print(json.dumps([{field: row.get(field) for field in fields} for row in rows], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    main()
