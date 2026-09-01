package com.ssafy.ddubi.domain.route.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * 프론트가 백엔드에 보내는 길찾기 요청 JSON이다.
 * 경도(longitude)는 -180~180, 위도(latitude)는 -90~90 범위만 허용한다.
 */
public record RouteSearchRequest(
        @NotNull
        @DecimalMin(value = "-180.0")
        @DecimalMax(value = "180.0")
        Double startLongitude,

        @NotNull
        @DecimalMin(value = "-90.0")
        @DecimalMax(value = "90.0")
        Double startLatitude,

        @NotNull
        @DecimalMin(value = "-180.0")
        @DecimalMax(value = "180.0")
        Double endLongitude,

        @NotNull
        @DecimalMin(value = "-90.0")
        @DecimalMax(value = "90.0")
        Double endLatitude
) {
}
