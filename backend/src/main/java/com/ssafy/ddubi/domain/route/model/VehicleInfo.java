package com.ssafy.ddubi.domain.route.model;

/** 카카오가 알려준 차량 정보. 예: name=66, type=마을. */
public record VehicleInfo(String name, String type) {
}
