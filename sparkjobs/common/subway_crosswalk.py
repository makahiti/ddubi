"""Backend가 발급한 지하철 정적 마스터를 Spark에서 같은 ID 체계로 읽는다.

API 번호를 정적 번호로 복사하지 않는다. 검증된 호선/출발역/도착역 crosswalk가
유일하고 실제 인접한 경우만 SUB ID를 반환한다. 파일 버전과 SHA를 시작 시 고정하고,
HTTP/DB를 각 이벤트 처리 중 호출하지 않는다. 미제공·모호한 대응은 명시적으로 실패한다.
"""

import gzip
import hashlib
import io
import json
import re


class SubwayCrosswalk:
    """Backend JSON.gz의 정적 edge 목록과 실시간 ID 쌍 색인을 소유하는 불변 스냅샷이다."""

    def __init__(self, path, expected_sha):
        with open(path, "rb") as source:
            raw = source.read(20_000_001)
        if len(raw) > 20_000_000 or not expected_sha or hashlib.sha256(raw).hexdigest() != expected_sha:
            raise ValueError("subway master SHA/size mismatch")
        with gzip.GzipFile(fileobj=io.BytesIO(raw)) as source:
            content = source.read(100_000_001)
        if len(content) > 100_000_000:
            raise ValueError("subway master expanded size exceeded")
        data = json.loads(content)
        if data.get("schemaVersion") != 1 or not data.get("dataVersion") or not data.get("lines"):
            raise ValueError("subway master schema/version missing")
        self.version, self.sha = data["dataVersion"], expected_sha
        self.edges, self._pairs = set(), {}
        for line in data["lines"]:
            stations = line["stations"]
            if len(stations) < 2 or [s["sequence"] for s in stations] != list(range(1, len(stations) + 1)):
                raise ValueError("subway master sequence invalid")
            for a, b in zip(stations, stations[1:]):
                parts = [line["subwayId"], a["stationId"], b["stationId"]]
                if (
                    any(not isinstance(v, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,50}", v) for v in parts)
                    or parts[1] == parts[2]
                ):
                    raise ValueError("subway canonical edge invalid")
                # CanonicalSegmentIdFactory 및 redis-keys.yaml의 기존 형식을 그대로 따른다.
                segment = "SUB:" + ":".join(parts)
                self.edges.add(segment)
                pair = (
                    line.get("realtimeSubwayId"),
                    a.get("realtimeStationId"),
                    b.get("realtimeStationId"),
                )
                if all(pair):
                    self._pairs.setdefault(pair, set()).add(segment)
        self.edges = frozenset(self.edges)

    def resolve(self, realtime_line, realtime_from, realtime_to):
        """실시간 인접 역 쌍을 정적 edge 하나로 변환한다. 역 한 개만으로 이동 방향을 추정하지 않는다."""
        candidates = self._pairs.get((realtime_line, realtime_from, realtime_to), set())
        return dict(
            segmentIds=sorted(candidates) if len(candidates) == 1 else [],
            state=("CONFIRMED" if len(candidates) == 1 else "AMBIGUOUS" if candidates else "UNRESOLVED"),
            masterVersion=self.version,
            crosswalkSha256=self.sha,
        )
