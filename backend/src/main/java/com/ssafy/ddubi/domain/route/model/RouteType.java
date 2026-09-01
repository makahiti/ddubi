package com.ssafy.ddubi.domain.route.model;

/** 후보 경로 전체가 사용하는 대중교통 조합. */
public enum RouteType {
    /** 버스만 이용 */
    BUS,
    /** 지하철만 이용 */
    SUBWAY,
    /** 버스와 지하철을 함께 이용 */
    BUS_AND_SUBWAY
}
