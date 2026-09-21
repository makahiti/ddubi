"""계약(internal-api.yaml)대로 동작하는 가짜 기준선 업로드 서버.

backend 의 실제 엔드포인트(Phase 5) 전까지 업로드 클라이언트의 연습 상대다.
검증 게이트(400)·인증(401)·행 수 대조와 급감 감지(409)·버전 보호까지 흉내 낸다.
응답은 뚜비 공통 래퍼 {"success", "data"|"error", "timestamp", "requestId"}.

테스트에서: server = make_server(); threading.Thread(target=server.serve_forever, daemon=True).start()
단독 실행:  python tests/mock_baseline_server.py --port 8099 --api-key test-key
"""

from __future__ import annotations

import argparse
import json
import re
import threading
import uuid
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer

KST = timezone(timedelta(hours=9))
WEATHER_BUCKETS = {"DRY", "RAIN", "SNOW"}
ROWS_RE = re.compile(r"^/internal/baselines/([^/]+)/versions/([^/]+)/rows$")
ACTIVATE_RE = re.compile(r"^/internal/baselines/([^/]+)/versions/([^/]+)/activate$")
CHUNK_MAX = 5000
ROW_KEY_FIELDS = ("segmentId", "dow", "hourSlot", "weatherBucket")


class MockState:
    """버전별 저장소와 활성 포인터. 실제 backend 의 baseline_serving + pipeline_config 역할."""

    def __init__(self, min_row_ratio: float = 0.5):
        self.versions = {}  # version -> {row_key: row}
        self.active_version = None
        self.previous_version = None
        self.last_activated_at = None
        self.last_shrink_reason = None
        self.min_row_ratio = min_row_ratio
        self.request_count = 0  # 테스트에서 재시도 횟수 검증용
        self.fail_next = 0  # n>0 이면 다음 n개 요청을 500 으로 응답 (재시도 테스트용)
        self.lock = threading.Lock()


def validate_row(row):
    """계약의 행 검증 규칙. 위반 시 이유 문자열, 통과 시 None."""
    if not isinstance(row, dict):
        return "row 가 객체가 아님"
    for field in ROW_KEY_FIELDS + ("p50", "p80", "p95", "sampleCount"):
        if field not in row:
            return f"필수 필드 누락: {field}"
    if row["weatherBucket"] not in WEATHER_BUCKETS:
        return "weatherBucket 은 DRY|RAIN|SNOW: {}".format(row["weatherBucket"])
    if not (isinstance(row["dow"], int) and 1 <= row["dow"] <= 7):
        return "dow 는 1(일)~7(토): {}".format(row["dow"])
    if not (isinstance(row["hourSlot"], int) and 0 <= row["hourSlot"] <= 23):
        return "hourSlot 은 0~23: {}".format(row["hourSlot"])
    if not (row["p50"] <= row["p80"] <= row["p95"]):
        return "p50 <= p80 <= p95 위반: {}/{}/{}".format(row["p50"], row["p80"], row["p95"])
    if not (isinstance(row["sampleCount"], int) and row["sampleCount"] >= 1):
        return "sampleCount 는 1 이상: {}".format(row["sampleCount"])
    return None


class MockBaselineHandler(BaseHTTPRequestHandler):
    # -- 응답 헬퍼 -------------------------------------------------------------

    def _send(self, status, success, data=None, code=None, message=None):
        body = {
            "success": success,
            "timestamp": datetime.now(KST).isoformat(),
            "requestId": str(uuid.uuid4()),
        }
        if success:
            body["data"] = data
        else:
            body["error"] = {"code": code, "message": message, "fieldErrors": []}
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, fmt, *args):  # 테스트 출력 오염 방지
        pass

    # -- 라우팅 ---------------------------------------------------------------

    def do_POST(self):
        state = self.server.state  # type: ignore[attr-defined]
        with state.lock:
            state.request_count += 1
            if state.fail_next > 0:
                state.fail_next -= 1
                return self._send(500, False, code="INTERNAL_ERROR", message="주입된 일시 장애")

        if self.headers.get("X-DDUBI-API-KEY") != self.server.api_key:  # type: ignore[attr-defined]
            return self._send(401, False, code="UNAUTHORIZED", message="API 키 없음 또는 불일치")

        rows_match = ROWS_RE.match(self.path)
        activate_match = ACTIVATE_RE.match(self.path)
        if not rows_match and not activate_match:
            return self._send(404, False, code="NOT_FOUND", message=f"알 수 없는 경로: {self.path}")

        baseline_type = (rows_match or activate_match).group(1)
        if baseline_type != "segment":
            return self._send(
                400, False, code="INVALID_REQUEST", message=f"지원하지 않는 baselineType: {baseline_type}"
            )

        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return self._send(400, False, code="INVALID_REQUEST", message="본문이 JSON 이 아님")

        if rows_match:
            return self._handle_rows(state, rows_match.group(2), payload)
        return self._handle_activate(state, activate_match.group(2), payload)

    # -- 계약 동작 --------------------------------------------------------------

    def _handle_rows(self, state, version, payload):
        rows = payload.get("rows")
        if not isinstance(rows, list) or not rows:
            return self._send(400, False, code="INVALID_REQUEST", message="rows 배열이 필요함")
        if len(rows) > CHUNK_MAX:
            return self._send(400, False, code="INVALID_REQUEST", message=f"rows 는 요청당 최대 {CHUNK_MAX}개")
        for i, row in enumerate(rows):
            reason = validate_row(row)
            if reason:
                # 계약: 검증 실패 시 요청 전체 거부 (부분 저장 없음)
                return self._send(400, False, code="INVALID_REQUEST", message=f"rows[{i}]: {reason}")
        with state.lock:
            if version == state.active_version:
                return self._send(409, False, code="ALREADY_ACTIVATED", message=f"이미 활성화된 dataVersion: {version}")
            bucket = state.versions.setdefault(version, {})
            for row in rows:  # upsert: 같은 키 재전송은 덮어쓴다 → 청크 재시도 안전
                key = tuple(row[f] for f in ROW_KEY_FIELDS)
                bucket[key] = row
        return self._send(200, True, data={"dataVersion": version, "received": len(rows), "versionTotal": len(bucket)})

    def _handle_activate(self, state, version, payload):
        total = payload.get("totalRows")
        allow_shrink = payload.get("allowShrink") is True
        shrink_reason = (payload.get("shrinkReason") or "").strip()
        if allow_shrink and not shrink_reason:
            return self._send(
                400, False, code="INVALID_REQUEST", message="allowShrink=true 이면 shrinkReason 이 필요함"
            )
        if allow_shrink and len(shrink_reason) > 100:  # 계약 maxLength 100 (VARCHAR(100))
            return self._send(400, False, code="INVALID_REQUEST", message="shrinkReason 은 100자 이내")
        with state.lock:
            stored = len(state.versions.get(version, {}))
            if stored == 0:
                return self._send(409, False, code="ROW_COUNT_MISMATCH", message=f"업로드된 행이 없음: {version}")
            if total != stored:
                return self._send(
                    409, False, code="ROW_COUNT_MISMATCH", message=f"totalRows={total} 이지만 저장된 행은 {stored}"
                )
            if state.active_version and not allow_shrink:
                active_rows = len(state.versions.get(state.active_version, {}))
                if active_rows and stored < active_rows * state.min_row_ratio:
                    return self._send(
                        409,
                        False,
                        code="ROW_COUNT_DROPPED",
                        message=f"직전 활성 {active_rows}행 대비 {stored}행으로 급감 (기준 {state.min_row_ratio})",
                    )
            # 활성 전환 + 보호 규칙: 활성·직전 버전만 남기고 삭제
            state.previous_version = state.active_version
            state.active_version = version
            state.last_activated_at = datetime.now(KST).isoformat()
            state.last_shrink_reason = shrink_reason if allow_shrink else None
            keep = {state.active_version, state.previous_version}
            deleted = [v for v in list(state.versions) if v not in keep]
            for v in deleted:
                del state.versions[v]
        # 응답 필드는 backend 구현(2026-09-08)과 같게: activeVersion / previousVersion / rowCount / activatedAt / purgedVersions
        return self._send(
            200,
            True,
            data={
                "activeVersion": version,
                "previousVersion": state.previous_version,
                "rowCount": stored,
                "activatedAt": state.last_activated_at,
                "purgedVersions": deleted,
            },
        )


def make_server(port=0, api_key="test-key", min_row_ratio=0.5):
    server = HTTPServer(("127.0.0.1", port), MockBaselineHandler)
    server.api_key = api_key  # type: ignore[attr-defined]
    server.state = MockState(min_row_ratio)  # type: ignore[attr-defined]
    return server


def main():
    parser = argparse.ArgumentParser(description="가짜 기준선 업로드 서버")
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--api-key", default="test-key")
    parser.add_argument("--min-row-ratio", type=float, default=0.5)
    args = parser.parse_args()
    server = make_server(args.port, args.api_key, args.min_row_ratio)
    print(f"mock baseline server on http://127.0.0.1:{server.server_address[1]} (api-key={args.api_key})")
    server.serve_forever()


if __name__ == "__main__":
    main()
