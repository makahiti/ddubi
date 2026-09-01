package com.ssafy.ddubi.domain.route.model;

/** 원 단위 요금. 단일 요금이면 최소와 최대에 같은 값이 들어간다. */
public record FareRange(Integer minimumWon, Integer maximumWon) {
}
