package com.ssafy.ddubi.domain.route.mapping;

import java.util.List;

/**
 * 버스 매핑의 판정, 공식 ID, 점수 근거와 인접 구간 목록을 함께 반환한다.
 *
 * <p>EXACT일 때만 승·하차 ID와 {@code segments}가 채워진다. PARTIAL은 확정 가능한
 * 노선까지만 남기고, AMBIGUOUS와 UNMAPPED는 임의 후보를 노출하지 않는다. 호출자는
 * {@code failureReason}과 {@code candidateCount}를 이용해 실패율을 숨기지 않고 집계할 수 있다.</p>
 *
 * <p>{@code matchScore}와 {@code passingMatchRatio}는 자동 선택 결과를 설명하고 회귀 검증하기
 * 위한 값이다. 공용 segment_id 문자열은 아직 이 결과에서 만들지 않는다.</p>
 */
public record BusMappingResult(
        BusMappingStatus status,
        String officialRouteId,
        String officialRouteName,
        String fromStopId,
        String toStopId,
        Integer startOrder,
        Integer endOrder,
        String direction,
        double matchScore,
        double passingMatchRatio,
        int candidateCount,
        String failureReason,
        List<MappedBusSegment> segments
) {
    public BusMappingResult {
        // 결과 객체를 캐시해도 호출자가 인접 구간 목록을 바꿀 수 없도록 불변 목록으로 만든다.
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public boolean exact() {
        return status == BusMappingStatus.EXACT;
    }
}
