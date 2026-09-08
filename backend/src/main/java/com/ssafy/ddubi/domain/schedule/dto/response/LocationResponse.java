package com.ssafy.ddubi.domain.schedule.dto.response;

import com.ssafy.ddubi.domain.schedule.entity.LocationSnapshot;

import java.math.BigDecimal;

public record LocationResponse(String name, BigDecimal lat, BigDecimal lng) {

    public static LocationResponse from(LocationSnapshot snapshot) {
        return new LocationResponse(snapshot.getName(), snapshot.getLat(), snapshot.getLng());
    }
}
