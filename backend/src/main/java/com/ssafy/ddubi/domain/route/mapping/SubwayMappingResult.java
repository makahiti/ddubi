package com.ssafy.ddubi.domain.route.mapping;

import java.util.List;

/**
 * 지하철 매핑 판정과 공식 호선·역 ID, 방향, 인접 역 구간을 함께 반환한다.
 *
 * <p>EXACT일 때만 승·하차역 ID, 방향과 {@code segments}가 채워진다. PARTIAL은
 * 확정 가능한 호선 정보까지만 남기며, AMBIGUOUS와 UNMAPPED는 임의 후보를 노출하지 않는다.</p>
 */
public record SubwayMappingResult(
        SubwayMappingStatus status,
        String officialSubwayId,
        String officialLineName,
        String fromStationId,
        String toStationId,
        Integer startOrder,
        Integer endOrder,
        String direction,
        double matchScore,
        double passingMatchRatio,
        int candidateCount,
        String failureReason,
        List<MappedSubwaySegment> segments
) {
    public SubwayMappingResult {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public boolean isExact() {
        return status == SubwayMappingStatus.EXACT;
    }
}
