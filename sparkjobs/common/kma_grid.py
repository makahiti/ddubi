"""WGS84 위도·경도를 기상청 동네예보 격자(nx, ny)로 변환한다."""

import math

GRID_VERSION = "kma-vilage-dfs-5km-v1"

# Collector의 기본 서울 25개 구 설정과 같은 값이다. 운영에서 WEATHER_GRID_POINTS를
# 지정하면 배치에도 같은 값을 전달해 실제 수집 범위를 기준으로 판정한다.
DEFAULT_WEATHER_GRID_POINTS = (
    "종로구:60:127,중구:60:127,용산구:60:126,성동구:61:127,광진구:62:126,"
    "동대문구:61:127,중랑구:62:128,성북구:61:127,강북구:61:128,도봉구:61:129,"
    "노원구:61:129,은평구:59:127,서대문구:59:127,마포구:59:127,양천구:58:126,"
    "강서구:58:126,구로구:58:125,금천구:59:124,영등포구:58:126,동작구:59:125,"
    "관악구:59:125,서초구:61:125,강남구:61:126,송파구:62:126,강동구:62:126"
)


def parse_weather_grid_points(raw):
    """Collector 형식 ``지역명:nx:ny,...``를 중복 없는 격자 집합으로 바꾼다."""
    grids = set()
    for item in (raw or "").split(","):
        item = item.strip()
        if not item:
            continue
        parts = item.split(":")
        if len(parts) != 3 or not parts[0].strip():
            raise ValueError("WEATHER_GRID_POINTS must contain name:nx:ny items")
        try:
            nx, ny = int(parts[1]), int(parts[2])
        except ValueError as exc:
            raise ValueError("WEATHER_GRID_POINTS nx/ny must be integers") from exc
        if nx < 1 or ny < 1:
            raise ValueError("WEATHER_GRID_POINTS nx/ny must be positive")
        grids.add((nx, ny))
    if not grids:
        raise ValueError("WEATHER_GRID_POINTS must not be empty")
    return sorted(grids)


def latlon_to_kma_grid(latitude, longitude):
    """기상청 단기예보 5km Lambert 격자의 정수 ``(nx, ny)``를 반환한다."""
    if isinstance(latitude, bool) or isinstance(longitude, bool):
        raise ValueError("latitude/longitude must be numbers")
    try:
        latitude, longitude = float(latitude), float(longitude)
    except (TypeError, ValueError) as exc:
        raise ValueError("latitude/longitude must be numbers") from exc
    if not math.isfinite(latitude) or not math.isfinite(longitude):
        raise ValueError("latitude/longitude must be finite")
    if not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
        raise ValueError("latitude/longitude out of range")

    radius_km = 6371.00877
    grid_km = 5.0
    standard_latitude_1 = 30.0
    standard_latitude_2 = 60.0
    origin_longitude = 126.0
    origin_latitude = 38.0
    origin_x = 43.0
    origin_y = 136.0
    degree_to_radian = math.pi / 180.0

    re = radius_km / grid_km
    slat1 = standard_latitude_1 * degree_to_radian
    slat2 = standard_latitude_2 * degree_to_radian
    olon = origin_longitude * degree_to_radian
    olat = origin_latitude * degree_to_radian
    sn = math.tan(math.pi * 0.25 + slat2 * 0.5) / math.tan(math.pi * 0.25 + slat1 * 0.5)
    sn = math.log(math.cos(slat1) / math.cos(slat2)) / math.log(sn)
    sf = math.tan(math.pi * 0.25 + slat1 * 0.5)
    sf = math.pow(sf, sn) * math.cos(slat1) / sn
    ro = math.tan(math.pi * 0.25 + olat * 0.5)
    ro = re * sf / math.pow(ro, sn)

    ra = math.tan(math.pi * 0.25 + latitude * degree_to_radian * 0.5)
    ra = re * sf / math.pow(ra, sn)
    theta = longitude * degree_to_radian - olon
    if theta > math.pi:
        theta -= 2.0 * math.pi
    if theta < -math.pi:
        theta += 2.0 * math.pi
    theta *= sn
    nx = math.floor(ra * math.sin(theta) + origin_x + 0.5)
    ny = math.floor(ro - ra * math.cos(theta) + origin_y + 0.5)
    return int(nx), int(ny)
