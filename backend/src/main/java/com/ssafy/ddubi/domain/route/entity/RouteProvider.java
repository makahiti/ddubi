package com.ssafy.ddubi.domain.route.entity;

/** 길찾기 제공자. {@code route_request.provider} 컬럼 값과 같다. */
public enum RouteProvider {

    /** 지금 쓰는 제공자. */
    KAKAO,

    /** DDL 에 함께 정의돼 있다. 아직 붙이지 않았다. */
    ODSAY
}
