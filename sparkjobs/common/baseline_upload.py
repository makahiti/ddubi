"""기준선 업로드 클라이언트: 배치 결과를 Spring 내부 API 로 전달한다.

계약: docs/contracts/internal-api.yaml (2026-09-01 backend 회신 반영 확정)
- rows 를 청크(최대 5000행)로 나눠 업로드하고, 전부 성공하면 activate 를 호출한다
- 같은 (dataVersion, segmentId, dow, hourSlot, weatherBucket) 재전송은 서버가 upsert 로
  처리하므로 청크 단위 재시도가 안전하다
- 응답은 뚜비 공통 래퍼 {"success", "data"|"error", "timestamp", "requestId"} 로 온다
- 활성화 전까지는 서빙에 반영되지 않는다. 중간 실패 시 이전 버전이 계속 서빙된다

backend 의 실제 엔드포인트는 Phase 5 예정. 그 전까지는 tests/mock_baseline_server.py 를
상대로 개발·검증한다 (주소만 바꾸면 실물에 붙는다).
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict, Iterable, Iterator, List, Optional

import requests

log = logging.getLogger("baseline_upload")

CHUNK_MAX = 5000          # 계약: 한 요청당 rows 최대 5000개
RETRIABLE_STATUS = {500, 502, 503, 504}


class UploadError(RuntimeError):
    """업로드/활성화 실패. 서버가 준 코드·requestId 를 보존해 로그 추적에 쓴다."""

    def __init__(self, message: str, status: Optional[int] = None,
                 code: Optional[str] = None, request_id: Optional[str] = None):
        detail = message
        if status is not None:
            detail = "HTTP {}: {}".format(status, detail)
        if request_id:
            detail = "{} (requestId={})".format(detail, request_id)
        super().__init__(detail)
        self.status = status
        self.code = code
        self.request_id = request_id


class BaselineUploadClient:
    def __init__(self, base_url: str, api_key: str, baseline_type: str = "segment",
                 chunk_size: int = CHUNK_MAX, timeout_seconds: float = 30.0,
                 max_attempts: int = 3, backoff_seconds: float = 2.0,
                 session: Optional[requests.Session] = None):
        if not base_url:
            raise ValueError("base_url 이 비어 있습니다 (INTERNAL_API_BASE_URL)")
        if not api_key:
            raise ValueError("api_key 가 비어 있습니다 (INTERNAL_API_KEY)")
        if not (1 <= chunk_size <= CHUNK_MAX):
            raise ValueError("chunk_size 는 1~{} 사이여야 합니다".format(CHUNK_MAX))
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.baseline_type = baseline_type
        self.chunk_size = chunk_size
        self.timeout_seconds = timeout_seconds
        self.max_attempts = max_attempts
        self.backoff_seconds = backoff_seconds
        self.session = session or requests.Session()

    # -- 공개 API ------------------------------------------------------------

    def upload_and_activate(self, data_version: str, rows: Iterable[Dict[str, Any]]) -> Dict[str, Any]:
        """rows 전체를 업로드하고 활성화까지 마친다. 성공 시 activate 응답 data 를 돌려준다."""
        total = self.upload_rows(data_version, rows)
        result = self.activate(data_version, total)
        log.info("baseline activated version=%s rows=%d previous=%s",
                 data_version, total, (result or {}).get("previous"))
        return result

    def upload_rows(self, data_version: str, rows: Iterable[Dict[str, Any]]) -> int:
        """rows 를 청크로 나눠 업로드한다. 성공적으로 보낸 총 행 수를 돌려준다."""
        total = 0
        for i, chunk in enumerate(_chunks(rows, self.chunk_size)):
            data = self._post("/internal/baselines/{}/versions/{}/rows".format(
                self.baseline_type, data_version), {"rows": chunk})
            accepted = (data or {}).get("accepted", len(chunk))
            total += len(chunk)
            log.info("chunk %d uploaded rows=%d accepted=%s total=%d",
                     i + 1, len(chunk), accepted, total)
        if total == 0:
            raise UploadError("업로드할 행이 없습니다 (rows 가 비어 있음)")
        return total

    def activate(self, data_version: str, total_rows: int) -> Dict[str, Any]:
        """업로드 완료를 선언한다. 서버가 행 수 대조·급감 검사 후 활성 버전을 전환한다."""
        return self._post("/internal/baselines/{}/versions/{}/activate".format(
            self.baseline_type, data_version), {"totalRows": total_rows}) or {}

    # -- 내부 ------------------------------------------------------------------

    def _post(self, path: str, payload: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        """공통 래퍼를 해석해 data 를 돌려준다. 5xx·네트워크 오류만 재시도한다.

        4xx 는 재시도하지 않는다: 검증 실패(400)는 다시 보내도 똑같이 실패하고,
        409(이미 활성화됨/행 수 불일치)는 상태 문제라 재전송이 답이 아니다.
        """
        url = self.base_url + path
        last_error = None  # type: Optional[Exception]
        for attempt in range(1, self.max_attempts + 1):
            try:
                resp = self.session.post(
                    url, json=payload, timeout=self.timeout_seconds,
                    headers={"X-DDUBI-API-KEY": self.api_key})
            except requests.RequestException as exc:  # 연결 실패·타임아웃
                last_error = UploadError("요청 실패: {}".format(exc.__class__.__name__))
                log.warning("attempt %d/%d failed (%s), retrying",
                            attempt, self.max_attempts, exc.__class__.__name__)
                time.sleep(self.backoff_seconds * attempt)
                continue

            body = _parse_wrapper(resp)
            if resp.status_code < 400 and body.get("success") is True:
                return body.get("data")

            error = body.get("error") or {}
            upload_error = UploadError(
                error.get("message", resp.text[:200]), status=resp.status_code,
                code=error.get("code"), request_id=body.get("requestId"))
            if resp.status_code in RETRIABLE_STATUS:
                last_error = upload_error
                log.warning("attempt %d/%d got HTTP %d, retrying",
                            attempt, self.max_attempts, resp.status_code)
                time.sleep(self.backoff_seconds * attempt)
                continue
            raise upload_error

        raise last_error if last_error else UploadError("알 수 없는 업로드 실패")


def _parse_wrapper(resp: "requests.Response") -> Dict[str, Any]:
    try:
        body = resp.json()
        if isinstance(body, dict):
            return body
    except ValueError:
        pass
    return {"success": False, "error": {"message": resp.text[:200]}}


def _chunks(rows: Iterable[Dict[str, Any]], size: int) -> Iterator[List[Dict[str, Any]]]:
    chunk = []  # type: List[Dict[str, Any]]
    for row in rows:
        chunk.append(row)
        if len(chunk) >= size:
            yield chunk
            chunk = []
    if chunk:
        yield chunk
