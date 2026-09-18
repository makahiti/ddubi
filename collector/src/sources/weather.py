"""기상청 초단기실황 (VilageFcstInfoService_2.0 / getUltraSrtNcst) → city.raw

같은 API 를 두 곳에서 제공한다. 키가 설정된 쪽을 쓴다 (둘 다 있으면 API 허브 우선).
- 기상청 API 허브 (apihub.kma.go.kr): KMA_API_KEY, 파라미터 authKey. 허브에서 이 API 를 '활용신청' 해야 한다.
- 공공데이터포털 (data.go.kr):        DATA_GO_KR_API_KEY, 파라미터 serviceKey. '디코딩된' 키를 넣어야 한다.
  (인코딩된 키를 넣으면 httpx 가 다시 인코딩해 SERVICE_KEY_IS_NOT_REGISTERED_ERROR 가 난다)
응답 JSON 형식은 두 곳이 같다.

- 매 정시 관측값이 약 10분 뒤에 공개되므로 base_time 은 (현재 - 40분) 의 정시를 쓴다.
- 서울 25개 구가 격자 14개를 공유하므로 API 는 격자당 1회만 부르고, payload.districts 에 그 격자의 구 목록을 담는다.
  WEATHER_GRID_POINTS="이름:nx:ny,..." (구 단위) 는 그대로 두고 내부에서 격자로 묶는다.
- 기본 10분 주기 × 14격자 = 하루 2,016 회 (data.go.kr 개발계정 일 10,000 회 한도 안)
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta
from typing import Any

import httpx

from config import ConfigError, Settings
from sources.base import PollBatch, Record, SourceSpec, now_kst

log = logging.getLogger(__name__)

# (환경변수 이름, URL, 키 파라미터 이름) — 앞에 있는 것이 우선
ENDPOINTS = (
    ("KMA_API_KEY", "https://apihub.kma.go.kr/api/typ02/openApi/VilageFcstInfoService_2.0/getUltraSrtNcst", "authKey"),
    ("DATA_GO_KR_API_KEY", "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtNcst", "serviceKey"),
)

# 초단기실황 category → 의미 (payload 에는 category 코드 그대로 둔다)
CATEGORIES = {
    "T1H": "기온(℃)",
    "RN1": "1시간 강수량(mm)",
    "UUU": "동서바람성분(m/s)",
    "VVV": "남북바람성분(m/s)",
    "REH": "습도(%)",
    "PTY": "강수형태(0없음 1비 2비/눈 3눈 5빗방울 6빗방울눈날림 7눈날림)",
    "VEC": "풍향(deg)",
    "WSD": "풍속(m/s)",
}

# 서울 25개 구 (기상청 격자)
DEFAULT_GRID_POINTS = (
    "종로구:60:127,중구:60:127,용산구:60:126,성동구:61:127,광진구:62:126,"
    "동대문구:61:127,중랑구:62:128,성북구:61:127,강북구:61:128,도봉구:61:129,"
    "노원구:61:129,은평구:59:127,서대문구:59:127,마포구:59:127,양천구:58:126,"
    "강서구:58:126,구로구:58:125,금천구:59:124,영등포구:58:126,동작구:59:125,"
    "관악구:59:125,서초구:61:125,강남구:61:126,송파구:62:126,강동구:62:126"
)


def base_datetime(now: datetime) -> datetime:
    """관측 공개 지연을 감안해 40분 전의 정시를 base 로 삼는다."""
    return (now - timedelta(minutes=40)).replace(minute=0, second=0, microsecond=0)


def parse_grid_points(raw: str) -> list[tuple[str, int, int]]:
    points = []
    for item in raw.split(","):
        item = item.strip()
        if not item:
            continue
        try:
            name, nx, ny = item.split(":")
            points.append((name.strip(), int(nx), int(ny)))
        except ValueError as exc:
            raise ConfigError(f"WEATHER_GRID_POINTS 형식 오류: '{item}' (이름:nx:ny)") from exc
    if not points:
        raise ConfigError("WEATHER_GRID_POINTS 가 비어 있습니다")
    return points


def group_by_grid(points: list[tuple[str, int, int]]) -> list[tuple[int, int, list[str]]]:
    """구 단위 목록을 격자 단위 (nx, ny, 구 목록) 로 묶는다. 같은 격자는 API 를 한 번만 부른다."""
    grids: dict[tuple[int, int], list[str]] = {}
    for name, nx, ny in points:
        districts = grids.setdefault((nx, ny), [])
        if name not in districts:
            districts.append(name)
    return [(nx, ny, districts) for (nx, ny), districts in grids.items()]


def parse_response(body: dict[str, Any]) -> dict[str, float]:
    """응답 JSON → {category: 값}. 정상 코드가 아니면 ValueError."""
    header = body.get("response", {}).get("header", {})
    code = header.get("resultCode")
    if code != "00":
        raise ValueError(f"KMA resultCode={code} msg={header.get('resultMsg')}")
    items = body["response"]["body"]["items"]["item"]
    values: dict[str, float] = {}
    for item in items:
        try:
            values[item["category"]] = float(item["obsrValue"])
        except (KeyError, TypeError, ValueError):
            log.warning("obsrValue 파싱 실패: %s", item)
    return values


def select_endpoint(settings: Settings) -> tuple[str, str, str]:
    """설정된 키에 따라 (url, 키 파라미터명, 키) 를 고른다."""
    for env_name, url, param in ENDPOINTS:
        if env_name in settings.api_keys:
            return url, param, settings.api_keys[env_name]
    raise ConfigError("API 키가 없습니다: KMA_API_KEY(기상청 API 허브) 또는 DATA_GO_KR_API_KEY(공공데이터포털) 를 .env 에 설정하세요")


def fetch(settings: Settings, client: httpx.Client) -> list[Record]:
    api_url, key_param, service_key = select_endpoint(settings)
    grids = group_by_grid(parse_grid_points(settings.extra.get("WEATHER_GRID_POINTS", DEFAULT_GRID_POINTS)))
    base = base_datetime(now_kst())
    base_date, base_time = base.strftime("%Y%m%d"), base.strftime("%H%M")

    records: list[Record] = []
    failures = 0
    for nx, ny, districts in grids:
        params = {
            key_param: service_key,
            "pageNo": 1,
            "numOfRows": 20,
            "dataType": "JSON",
            "base_date": base_date,
            "base_time": base_time,
            "nx": nx,
            "ny": ny,
        }
        try:
            resp = client.get(api_url, params=params)
            if resp.status_code != 200:
                # HTTPStatusError 메시지에는 URL(키 포함)이 들어가므로 상태코드와 본문 일부만 남긴다
                raise ValueError(f"HTTP {resp.status_code}: {' '.join(resp.text.split())[:200]}")
            try:
                body = resp.json()
            except ValueError as exc:
                # 키 오류·한도 초과 등은 dataType=JSON 이어도 XML 로 온다
                raise ValueError(f"JSON 이 아닌 응답: {resp.text[:200]}") from exc
            observations = parse_response(body)
        except httpx.HTTPError as exc:
            failures += 1
            log.warning("%s (nx=%s ny=%s) 수집 실패: %s", ",".join(districts), nx, ny, type(exc).__name__)
            continue
        except ValueError as exc:
            failures += 1
            log.warning("%s (nx=%s ny=%s) 수집 실패: %s", ",".join(districts), nx, ny, exc)
            continue

        records.append(
            Record(
                key=f"{nx}:{ny}",
                event_type="weather.observation",
                occurred_at=base,
                payload={
                    "districts": districts,
                    "nx": nx,
                    "ny": ny,
                    "baseDate": base_date,
                    "baseTime": base_time,
                    "observations": observations,
                },
            )
        )

    if failures and not records:
        raise RuntimeError(f"기상청 수집 전부 실패 ({failures}/{len(grids)} 격자)")
    if failures:
        log.warning("기상청 일부 실패 %d/%d 격자", failures, len(grids))
    return PollBatch(
        records,
        status="PARTIAL" if failures else "SUCCESS",
        complete_snapshot=failures == 0,
        page_count=len(grids),
        scope_key="configured-weather-grids",
        error_code="GRID_SCOPE_PARTIAL" if failures else None,
    )


SOURCE = SourceSpec(
    name="kma.ultra-srt-ncst",
    topic="city.raw",
    schema="city",
    default_interval_seconds=600,
    fetch=fetch,
)
