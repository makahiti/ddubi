"""기상청 격자 공식과 Collector 기본 수집 범위를 Spark 없이 검사한다."""

import pytest
from common.kma_grid import (
    DEFAULT_WEATHER_GRID_POINTS,
    latlon_to_kma_grid,
    parse_weather_grid_points,
)


def test_known_seoul_points_match_collector_grids():
    assert latlon_to_kma_grid(37.5665, 126.9780) == (60, 127)
    assert latlon_to_kma_grid(37.5172, 127.0473) == (61, 126)
    assert latlon_to_kma_grid(37.5145, 127.1060) == (62, 126)


def test_default_collector_scope_has_14_unique_grids():
    grids = parse_weather_grid_points(DEFAULT_WEATHER_GRID_POINTS)
    assert len(grids) == 14
    assert (60, 127) in grids
    assert (61, 126) in grids


@pytest.mark.parametrize("latitude,longitude", [(None, 127.0), (37.5, None), (91, 127), (37.5, 181)])
def test_invalid_coordinate_is_rejected(latitude, longitude):
    with pytest.raises(ValueError):
        latlon_to_kma_grid(latitude, longitude)


def test_invalid_collector_grid_configuration_is_rejected():
    for value in ("", "종로구:60", "종로구:x:127", ":60:127"):
        with pytest.raises(ValueError):
            parse_weather_grid_points(value)
