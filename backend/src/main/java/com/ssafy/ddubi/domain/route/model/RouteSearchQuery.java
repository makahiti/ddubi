package com.ssafy.ddubi.domain.route.model;

/** 프론트 요청 DTO를 변환한 내부 검색 조건. 특정 외부 API의 필드명에 의존하지 않는다. */
public record RouteSearchQuery(Coordinate start, Coordinate end) {
    public RouteSearchQuery {
        // null 좌표가 외부 API 호출까지 흘러가지 않게 모델 생성 시점에 막는다.
        if (start == null || end == null) {
            throw new IllegalArgumentException("출발지와 도착지 좌표는 필수입니다.");
        }
    }
}
