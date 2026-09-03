package com.ssafy.ddubi.domain.place.dto.response;

import com.ssafy.ddubi.domain.place.entity.Place;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** PLACE-002~005 공통 응답. */
public record PlaceResponse(
        Long id,
        String name,
        String address,
        BigDecimal lat,
        BigDecimal lng,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static PlaceResponse from(Place place) {
        return new PlaceResponse(place.getId(), place.getName(), place.getAddress(),
                place.getLat(), place.getLng(), place.getCreatedAt(), place.getUpdatedAt());
    }
}
