package com.ssafy.ddubi.domain.route.service;

import com.ssafy.ddubi.domain.route.model.*;
import com.ssafy.ddubi.domain.route.mapping.TransitMappingStatus;
import org.springframework.stereotype.Component;

/**
 * 카카오 후보 캐시의 이름·순서·좌표 증거를 현재 마스터로 다시 해석한다.
 * 외부 길찾기를 재호출하거나 fetchedAt을 연장하지 않는다. Resolver의 버전별 캐시와
 * 수동 거절 판정을 그대로 사용하므로 마스터 활성화와 검토 변경을 다음 평가에 반영한다.
 * DB 저장은 호출자가 결과 변경 여부를 확인한 뒤 기존 평가 트랜잭션에서 처리한다.
 */
@Component
public class RouteMappingRefresher {
    private final TransitMappingResolver resolver;
    public RouteMappingRefresher(TransitMappingResolver resolver) { this.resolver = resolver; }

    /** 전체 후보의 원본 순번과 초/m 단위를 보존한다. WALK는 마스터 매핑 대상이 아니다. */
    public RouteSearchResult refresh(RouteSearchResult search) {
        var candidates = search.candidates().stream().map(route -> new RouteCandidate(
                route.providerCandidateIndex(), route.type(), route.totalDistanceMeters(), route.totalDurationSeconds(),
                route.transfers(), route.fare(), route.unassignedDistanceMeters(), route.unassignedDurationSeconds(),
                route.segments().stream().map(this::refreshSegment).toList())).distinct().toList();
        return new RouteSearchResult(search.provider(), search.fetchedAt(), search.landingUrl(), candidates);
    }

    private RouteSegment refreshSegment(RouteSegment segment) {
        if (segment.mode() == TransportMode.WALK) return segment;
        var mapping = resolver.resolve(new SegmentResolveRequest(segment.mode(), segment.providerRouteKey(),
                segment.fromName(), segment.toName(), segment.stopNames(), segment.vehicles(), segment.geometry()));
        String id = mapping.status() == TransitMappingStatus.EXACT && mapping.segments().size() == 1
                ? mapping.segments().get(0).segmentId() : null;
        return new RouteSegment(segment.sequence(), segment.mode(), id, segment.providerRouteKey(), segment.guidance(),
                segment.fromName(), segment.toName(), segment.distanceMeters(), segment.durationSeconds(),
                segment.stopNames(), segment.vehicles(), segment.geometry(), mapping);
    }
}
