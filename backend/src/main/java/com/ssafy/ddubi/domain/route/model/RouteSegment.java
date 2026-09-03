package com.ssafy.ddubi.domain.route.model;

import com.ssafy.ddubi.domain.route.mapping.TransitMappingResult;

import java.util.List;

/**
 * 외부 경로의 이동 구간. API 단위(초, m)를 보존하고 DB 저장 시 분 단위로 변환한다.
 */
public record RouteSegment(
        // 후보 내부 순서. DB route_segment.seq 규칙에 맞춰 1부터 시작한다.
        int sequence,
        TransportMode mode,
        // 공통 파이프라인 ID. WALK는 항상 null이며 다른 교통수단도 매핑 전에는 null이다.
        String segmentId,
        // 카카오 차량 이름을 모은 매핑 근거. 공통 segment_id 자체는 아니다.
        String providerRouteKey,
        String guidance,
        // 첫 정류장/역과 마지막 정류장/역 이름
        String fromName,
        String toName,
        // 카카오 원본 단위를 유지한다: 거리=m, 시간=초
        long distanceMeters,
        long durationSeconds,
        List<String> stopNames,
        List<VehicleInfo> vehicles,
        List<Coordinate> geometry,
        // 카카오 step을 서울 공식 마스터의 인접 구간들로 전개한 결과와 실패 근거다.
        TransitMappingResult mapping
) {
    public RouteSegment {
        // 내부 모델이 만들어진 뒤 목록이 몰래 변경되지 않게 불변 복사한다.
        stopNames = List.copyOf(stopNames);
        vehicles = List.copyOf(vehicles);
        geometry = List.copyOf(geometry);
    }
}
