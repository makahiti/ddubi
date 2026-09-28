package com.ssafy.ddubi.domain.route.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 공식 /v2/routing/walk 응답의 전체 경로 비용. 거리와 시간은 각각 미터와 초다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoWalkingResponse(String status, Route route) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Route(Properties properties, java.util.List<Leg> legs) {}

    /** 공식 legs[].steps[].properties의 안내와 거리만 보정 입력으로 사용한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Leg(java.util.List<Step> steps) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(StepProperties properties, Path path) {}

    /**
     * 공식 steps[].path.points. 한 점은 {@code [경도, 위도]}(WGS84)이며 대중교통 응답과 같은 형식이다.
     *
     * <p>지도에 도보 구간을 선으로 그릴 때만 쓴다. 시간·거리 계산에는 쓰지 않는다 —
     * 좌표 사이 거리를 우리가 다시 재면 공식 응답의 거리와 어긋난다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Path(java.util.List<java.util.List<Double>> points) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepProperties(Long distance, String guidance) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Properties(Long totalDistance, Long totalTime) {
        /** 소수·문자열·범위 초과 값을 정수 시간으로 강제 변환하지 않는다. */
        @com.fasterxml.jackson.annotation.JsonCreator(
                mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
        public static Properties read(com.fasterxml.jackson.databind.JsonNode value) {
            return new Properties(
                    integer(value.path("totalDistance")), integer(value.path("totalTime")));
        }

        private static Long integer(com.fasterxml.jackson.databind.JsonNode value) {
            return value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : null;
        }
    }
}
