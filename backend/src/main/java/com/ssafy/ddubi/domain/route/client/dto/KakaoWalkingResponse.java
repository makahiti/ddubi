package com.ssafy.ddubi.domain.route.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 공식 /v2/routing/walk 응답의 전체 경로 비용. 거리와 시간은 각각 미터와 초다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoWalkingResponse(String status, Route route) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Route(Properties properties) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Properties(Long totalDistance, Long totalTime) {
        /** 소수·문자열·범위 초과 값을 정수 시간으로 강제 변환하지 않는다. */
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
        public static Properties read(com.fasterxml.jackson.databind.JsonNode value) {
            return new Properties(integer(value.path("totalDistance")),integer(value.path("totalTime")));
        }
        private static Long integer(com.fasterxml.jackson.databind.JsonNode value) {
            return value.isIntegralNumber() && value.canConvertToLong()?value.longValue():null;
        }
    }
}
