package com.ssafy.ddubi.domain.route.model;

/** RouteSegment 하나에서 사용하는 이동수단. */
public enum TransportMode {
    /** 카카오의 WALKING을 우리 DB enum 이름인 WALK로 바꾼 값 */
    WALK,
    BUS,
    SUBWAY
}
