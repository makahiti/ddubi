"""Validate codes before totals; normalize only explicit no-data responses."""

import re


def validated_rows(data, list_name, error_type):
    metadata = data.get("errorMessage")
    if not isinstance(metadata, dict):
        metadata = data.get("RESULT")
    if not isinstance(metadata, dict):
        raise error_type("지하철 API 응답에 결과 메타데이터가 없습니다")
    code = metadata.get("code", metadata.get("CODE"))
    if not isinstance(code, str) or not re.fullmatch(r"[A-Z]+-[0-9]{3}", code):
        raise error_type("지하철 API 결과 코드가 올바르지 않습니다")
    if code not in ("INFO-000", "INFO-200"):
        raise error_type(f"지하철 API 오류({code})")
    other = data.get("RESULT")
    if (
        isinstance(data.get("errorMessage"), dict)
        and isinstance(other, dict)
        and other.get("CODE", other.get("code", code)) != code
    ):
        raise error_type("지하철 API 결과 코드가 서로 모순됩니다")
    total = metadata.get("total", data.get("list_total_count"))
    if code == "INFO-200" and total in (None, ""):
        total = 0
    if isinstance(total, bool) or not re.fullmatch(r"[0-9]+", str(total)):
        raise error_type("지하철 API 응답의 전체 건수가 올바르지 않습니다")
    total = int(total)
    rows = data.get(list_name)
    if code == "INFO-200" and rows is None:
        rows = []
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise error_type(f"지하철 API의 {list_name}가 객체 배열이 아닙니다")
    if code == "INFO-200" and (total != 0 or rows):
        raise error_type("지하철 API 데이터 없음 응답에 데이터가 있어 모순됩니다")
    if len(rows) != total:
        raise error_type(f"전체 {total:d}건 중 {len(rows):d}건 수신: 불완전 지하철 응답")
    return dict(data, **{list_name: rows}), total, rows
