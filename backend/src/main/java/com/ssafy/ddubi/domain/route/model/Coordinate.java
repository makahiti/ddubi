package com.ssafy.ddubi.domain.route.model;

/** 지도 좌표. 카카오 규격에 맞춰 경도(x)를 먼저, 위도(y)를 나중에 둔다. */
public record Coordinate(double longitude, double latitude) {
}
