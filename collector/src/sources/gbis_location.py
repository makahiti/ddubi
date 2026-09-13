"""명시적으로 선택할 때만 실행하는 지역 버스 수집 진입점."""

from sources.regional_bus import make_source

SOURCE = make_source("gbis_location")
