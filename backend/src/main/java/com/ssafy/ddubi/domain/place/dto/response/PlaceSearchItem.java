package com.ssafy.ddubi.domain.place.dto.response;

import java.math.BigDecimal;

/**
 * PLACE-001 서울 장소 검색 결과 한 건.
 *
 * @param providerPlaceId 카카오 장소 ID. 우리 place.id 와 무관한 외부 식별자라 이름을 구분한다
 */
public record PlaceSearchItem(
        String providerPlaceId,
        String name,
        String address,
        BigDecimal lat,
        BigDecimal lng
) {
}
