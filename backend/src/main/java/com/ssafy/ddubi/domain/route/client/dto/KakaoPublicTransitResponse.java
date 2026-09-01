package com.ssafy.ddubi.domain.route.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 카카오맵 대중교통 API의 JSON 모양을 그대로 표현한 DTO다.
 *
 * <p>이 DTO는 카카오 응답을 읽기 위한 용도일 뿐 프론트에 직접 반환하지 않는다.
 * 카카오가 필드를 추가해도 깨지지 않도록 {@code ignoreUnknown=true}를 사용한다.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoPublicTransitResponse(
        // OK, NO_RESULTS, INVALID_REQUEST 등의 결과 상태
        String status,
        // 전체 후보 개수와 카카오맵 연결 URL
        Properties properties,
        // 실제 후보 경로 목록
        List<Route> routes
) {
    /** 응답 전체에 대한 요약 정보. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Properties(
            Integer total,
            Integer bus,
            Integer subway,
            Integer busAndSubway,
            String landingURL
    ) {
    }

    /** 후보 경로 하나와 그 후보를 구성하는 이동 단계 목록. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Route(RouteProperties properties, List<Step> steps) {
    }

    /** 후보 전체의 교통수단, 거리, 시간, 환승 횟수, 요금. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RouteProperties(
            String type,
            Long totalDistance,
            Long totalTime,
            Integer transfers,
            Fare fare
    ) {
    }

    /** 요금은 value 하나 또는 min/max 범위 중 하나의 모양으로 내려온다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fare(Integer value, Integer min, Integer max) {
    }

    /** 버스·지하철·도보 이동 단계 하나. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(StepProperties properties, Path path) {
    }

    /** 단계에 표시할 안내 문구, 이동수단, 정류장과 차량 정보. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepProperties(
            String guidance,
            String type,
            Long distance,
            Long time,
            List<Stop> stops,
            List<Vehicle> vehicles
    ) {
    }

    /** 정류장 또는 지하철역. 현재 공개 API에서는 안정적인 ID 없이 이름만 제공된다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stop(String name) {
    }

    /** 버스 번호나 지하철 노선명과 차량 종류. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vehicle(String name, String type) {
    }

    /** 지도에 선을 그릴 좌표 목록. 각 점은 [경도, 위도] 순서다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Path(List<List<Double>> points) {
    }
}
