"""서울시·공공데이터 API 수집기가 공유하는 안전한 파싱 도구.

외부 API는 같은 의미의 응답도 단건 객체/배열, JSON/XML, 여러 시각 형식으로 돌려준다.
이 모듈은 그 차이를 수집 경계에서 흡수한다. 인증키가 들어간 요청 URL이나 원문 전체를
예외에 넣지 않으며, 제공자 ID는 그대로 보존하고 DDUBI ``segment_id``를 만들지 않는다.
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Callable, Iterable, TypeVar
from urllib.parse import quote
from xml.etree import ElementTree

import httpx

from config import ConfigError, Settings
from sources.base import KST, now_kst

T = TypeVar("T")
log = logging.getLogger(__name__)


def collect_scopes(
    scopes: list[str],
    fetch_one: Callable[[str], list[T]],
    *,
    provider: str,
    scope_name: str,
) -> list[T]:
    """여러 조회 범위를 독립 호출하고 성공한 범위의 결과를 보존한다.

    노선·역·링크처럼 한 주기에 여러 API 요청이 필요한 소스에서 한 대상의 장애가 전체
    수집을 지우지 않도록 한다. 정상적인 빈 응답은 성공으로 보며, 예외가 난 대상만 실패로
    센다. 모든 대상이 실패했을 때만 사이클을 실패시켜 운영 모니터링이 장애를 놓치지 않게
    하고, 일부 실패는 대상 식별자와 건수만 기록해 API 키가 로그에 노출되지 않게 한다.
    """
    records: list[T] = []
    failures = 0
    for scope in scopes:
        try:
            records.extend(fetch_one(scope))
        except (httpx.HTTPError, ValueError) as exc:
            failures += 1
            log.warning("%s %s=%s 수집 실패: %s", provider, scope_name, scope, type(exc).__name__)

    if failures == len(scopes):
        raise RuntimeError(f"{provider} 수집 전부 실패 ({failures}/{len(scopes)} {scope_name})")
    if failures:
        log.warning("%s 일부 실패 %d/%d %s", provider, failures, len(scopes), scope_name)
    return records


def collect_sources(
    fetchers: list[tuple[str, Callable[[], list[T]]]], *, collector_name: str
) -> list[T]:
    """호환용 통합 Collector에서 독립 소스의 부분 장애를 격리한다.

    대상 목록의 형식·키 누락 같은 ``ConfigError``는 즉시 중단한다. 반면 외부 제공자 한 곳의
    통신·응답 오류는 다른 소스까지 막지 않는다. 최소 한 소스가 정상 호출되면 그 결과를
    반환하고, 설정된 모든 소스가 실패한 경우에만 통합 사이클을 실패 처리한다.
    """
    records: list[T] = []
    failures = 0
    for source_name, fetch_one in fetchers:
        try:
            records.extend(fetch_one())
        except ConfigError:
            raise
        except (httpx.HTTPError, ValueError, RuntimeError) as exc:
            failures += 1
            log.warning("%s 하위 소스=%s 실패: %s", collector_name, source_name, type(exc).__name__)

    if failures == len(fetchers):
        raise RuntimeError(f"{collector_name} 하위 소스 전부 실패 ({failures}/{len(fetchers)})")
    if failures:
        log.warning("%s 하위 소스 일부 실패 %d/%d", collector_name, failures, len(fetchers))
    return records


def csv_values(settings: Settings, name: str, *, required: bool = True) -> list[str]:
    """쉼표 구분 설정을 중복 없이 읽는다. 필수 설정 누락은 시작 단계에서 실패시킨다."""
    raw = settings.extra.get(name, "")
    values = list(dict.fromkeys(value.strip() for value in raw.split(",") if value.strip()))
    if required and not values:
        raise ConfigError(f"{name} 환경변수에 수집 대상을 쉼표로 지정하세요")
    return values


def total_count(body: dict[str, Any], service: str) -> int | None:
    """서울시 경로형 API 응답에서 전체 행 수를 읽는다.

    제공처나 자료 종류에 따라 서비스 키의 대소문자가 달라질 수 있어 일치하는 컨테이너를
    먼저 찾고, 값이 없거나 정수가 아니면 ``None``을 반환한다. 호출자는 전체 건수를 신뢰할
    수 없을 때 실제 반환 행 수를 기준으로 보수적으로 종료해야 한다.
    """
    container = body.get(service)
    if not isinstance(container, dict):
        container = next(
            (
                value
                for key, value in body.items()
                if str(key).lower() == service.lower() and isinstance(value, dict)
            ),
            None,
        )
    if not isinstance(container, dict):
        return None
    raw = container.get("list_total_count")
    try:
        return int(raw) if raw is not None else None
    except (TypeError, ValueError):
        return None


def page_size(settings: Settings) -> int:
    """서울시 API의 1회 최대 1,000건 제한 안에서 페이지 크기를 검증한다."""
    raw = settings.extra.get("SEOUL_API_PAGE_SIZE", "1000")
    try:
        value = int(raw)
    except ValueError as exc:
        raise ConfigError("SEOUL_API_PAGE_SIZE 는 정수여야 합니다") from exc
    if not 1 <= value <= 1000:
        raise ConfigError("SEOUL_API_PAGE_SIZE 는 1~1000 범위여야 합니다")
    return value


def as_list(value: Any) -> list[dict[str, Any]]:
    """API의 단건 객체와 배열 응답을 동일한 레코드 배열로 바꾼다."""
    if value is None:
        return []
    if isinstance(value, dict):
        return [value]
    if isinstance(value, list):
        return [item for item in value if isinstance(item, dict)]
    return []


def first_value(item: dict[str, Any], names: Iterable[str]) -> Any:
    """제공처 버전에 따라 대소문자나 이름이 다른 동일 의미 필드를 찾는다."""
    lowered = {str(key).lower(): value for key, value in item.items()}
    for name in names:
        value = lowered.get(name.lower())
        if value not in (None, ""):
            return value
    return None


def parse_kst(value: Any, *, fallback: datetime | None = None) -> datetime:
    """제공자 시각을 Asia/Seoul 기준 tz-aware 값으로 변환한다.

    응답에 발생시각이 없을 때만 호출 시각을 fallback으로 사용한다. 이는 추정값이므로 원본
    payload에는 제공자가 준 시각 필드를 그대로 남겨 후속 품질 계산에서 구분할 수 있게 한다.
    """
    if isinstance(value, datetime):
        return value.replace(tzinfo=KST) if value.tzinfo is None else value.astimezone(KST)
    if value not in (None, ""):
        text = str(value).strip()
        try:
            parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
            return parsed.replace(tzinfo=KST) if parsed.tzinfo is None else parsed.astimezone(KST)
        except ValueError:
            pass
        for fmt in ("%Y-%m-%d %H:%M:%S", "%Y%m%d%H%M%S", "%Y%m%d %H%M%S", "%Y-%m-%d %H:%M", "%Y%m%d%H%M"):
            try:
                return datetime.strptime(text, fmt).replace(tzinfo=KST)
            except ValueError:
                continue
    return fallback or now_kst()


def safe_json(response: httpx.Response, *, provider: str) -> dict[str, Any]:
    """키가 포함된 URL을 노출하지 않고 HTTP·JSON·제공자 오류를 검증한다."""
    if response.status_code != 200:
        # 오류 본문이 요청 URL이나 인증 정보를 되비추는 제공자도 있으므로 상태코드만 남긴다.
        raise ValueError(f"{provider} HTTP {response.status_code}")
    try:
        body = response.json()
    except ValueError as exc:
        raise ValueError(f"{provider} JSON이 아닌 응답") from exc
    if not isinstance(body, dict):
        raise ValueError(f"{provider} 최상위 응답이 객체가 아닙니다")

    header = body.get("msgHeader") or body.get("response", {}).get("header") or body.get("errorMessage") or {}
    code = first_value(header, ("headerCd", "resultCode", "code", "status")) if isinstance(header, dict) else None
    if code is not None and str(code).upper() not in {"0", "00", "INFO-000", "200"}:
        message = first_value(header, ("headerMsg", "resultMsg", "message")) if isinstance(header, dict) else ""
        raise ValueError(f"{provider} resultCode={code} message={message}")
    return body


def bus_rows(body: dict[str, Any]) -> list[dict[str, Any]]:
    """서울 버스 API의 msgBody.itemList를 배열로 정규화한다."""
    msg_body = body.get("msgBody") or body.get("response", {}).get("body", {})
    if not isinstance(msg_body, dict):
        return []
    items = msg_body.get("itemList") or msg_body.get("items") or []
    if isinstance(items, dict) and "item" in items:
        items = items["item"]
    return as_list(items)


def seoul_rows(body: dict[str, Any], service: str) -> list[dict[str, Any]]:
    """서울 열린데이터광장 JSON의 서비스별 ``row``를 추출하고 오류를 검증한다."""
    container = body.get(service)
    if container is None:
        container = next((value for value in body.values() if isinstance(value, dict) and "row" in value), None)
    if not isinstance(container, dict):
        result = body.get("RESULT", {})
        code = result.get("CODE") if isinstance(result, dict) else None
        if code == "INFO-200":
            return []
        raise ValueError(f"서울시 {service} 응답에 row가 없습니다")
    result = container.get("RESULT", {})
    code = result.get("CODE") if isinstance(result, dict) else None
    if code not in (None, "INFO-000", "INFO-200"):
        raise ValueError(f"서울시 {service} resultCode={code} message={result.get('MESSAGE')}")
    return as_list(container.get("row"))


def seoul_url(key: str, service: str, start: int, end: int, *filters: str, subway: bool = False) -> str:
    """경로형 서울시 API URL을 만든다. 각 경로 값은 반드시 URL 인코딩한다."""
    host = "https://swopenapi.seoul.go.kr/api/subway" if subway else "https://openapi.seoul.go.kr:8088"
    parts = [key, "json", service, str(start), str(end), *filters]
    return f"{host}/" + "/".join(quote(str(part), safe="") for part in parts)


def xml_rows(response: httpx.Response, *, provider: str) -> list[dict[str, Any]]:
    """JSON을 지원하지 않는 TOPIS API의 반복 ``row``를 사전 배열로 변환한다."""
    if response.status_code != 200:
        raise ValueError(f"{provider} HTTP {response.status_code}")
    try:
        root = ElementTree.fromstring(response.text)
    except ElementTree.ParseError as exc:
        raise ValueError(f"{provider} XML 파싱 실패") from exc
    code = root.findtext(".//CODE")
    if code not in (None, "INFO-000", "INFO-200"):
        raise ValueError(f"{provider} resultCode={code} message={root.findtext('.//MESSAGE')}")
    return [{child.tag: child.text for child in row} for row in root.findall(".//row")]
