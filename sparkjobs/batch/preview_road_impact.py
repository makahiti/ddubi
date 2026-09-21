"""도로 영향 전처리 검토 CLI. Kafka·Redis·DB 쓰기 없이 JSON 입력을 JSON 출력으로 변환한다."""

import argparse
import json
from pathlib import Path

from common.road_impact import RoadImpactIndex


def main():
    """실제 경로 edge와 수집 시점 cutoff를 명시해야 한다. 운영 v12 HTTP 요청은 변경하지 않는다."""
    parser = argparse.ArgumentParser()
    for field in ("bus-crosswalk", "bus-sha", "road-crosswalk", "road-sha", "request", "events", "output"):
        parser.add_argument("--" + field, required=True)
    args = parser.parse_args()
    index = RoadImpactIndex(args.bus_crosswalk, args.bus_sha, args.road_crosswalk, args.road_sha)
    request = json.loads(Path(args.request).read_text(encoding="utf-8-sig"))
    # 검토용의 제한된 배치 입력이다. 대량 아카이브는 Spark partition별 공통 함수를 호출한다.
    events = [
        json.loads(line) for line in Path(args.events).read_text(encoding="utf-8-sig").splitlines() if line.strip()
    ]
    results = [
        index.assess(
            item["edge"],
            events,
            request["asOf"],
            item["passageStart"],
            item["passageEnd"],
            request["maxObservationAgeSeconds"],
        )
        for item in request["segments"]
    ]
    Path(args.output).write_text(
        json.dumps(
            dict(requestId=request["requestId"], productionReady=False, segments=results), ensure_ascii=False, indent=2
        )
        + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
