package com.ssafy.ddubi.domain.route.service;

import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.domain.route.model.VehicleInfo;

import java.util.List;

/**
 * segment_id를 찾는 데 필요한 단서 모음이다.
 * Resolver가 카카오 DTO를 직접 알 필요가 없도록 우리 내부 값만 전달한다.
 */
public record SegmentResolveRequest(
        TransportMode mode,
        String providerRouteKey,
        String fromName,
        String toName,
        List<String> stopNames,
        List<VehicleInfo> vehicles
) {
}
