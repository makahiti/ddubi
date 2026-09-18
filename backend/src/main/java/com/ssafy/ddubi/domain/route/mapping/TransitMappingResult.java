package com.ssafy.ddubi.domain.route.mapping;

import java.util.List;

/**
 * 카카오의 BUS/SUBWAY step 하나를 수도권 버스·서울 지하철 공식 마스터에 대조한 통합 결과다.
 *
 * <p>호출자는 {@code status == EXACT}인 경우에만 {@code segments}를 기준선 계산에 사용하고,
 * 실시간값은 {@code realtimeCoverage}까지 확인한다. 판정에 사용한 마스터·정책 버전을 함께
 * 보존하므로 이후 route_segment_edge 저장과 Replay에서 같은 결과를 재현할 수 있다.</p>
 *
 * <p>{@code officialRouteId/fromNodeId/toNodeId}는 카카오가 준 ID가 아니라 활성 정적 마스터에서
 * 확정한 값이다. 하나의 provider step이 여러 인접 edge로 분해되므로 실제 시간 연산은 상위
 * 승·하차 ID보다 {@code segments}를 순서대로 사용한다. 실패 결과는 이 목록이 항상 비어 있다.</p>
 */
public record TransitMappingResult(
        TransitMappingStatus status,
        String masterDataVersion,
        String mappingPolicyVersion,
        String officialRouteId,
        String officialRouteName,
        String fromNodeId,
        String toNodeId,
        Integer startOrder,
        Integer endOrder,
        String direction,
        double matchScore,
        double passingMatchRatio,
        int candidateCount,
        String failureReason,
        RealtimeMappingCoverage realtimeCoverage,
        List<MappedTransitSegment> segments,
        List<BusAlternativeMapping> busAlternatives
) {
    public TransitMappingResult {
        segments = segments == null ? List.of() : List.copyOf(segments);
        busAlternatives = busAlternatives == null ? List.of() : List.copyOf(busAlternatives);
        if (realtimeCoverage == null) {
            realtimeCoverage = deriveRealtimeCoverage(status, segments);
        }
    }

    /** 응답 전용 복사본에 현재 모델 평가를 반영한다. 시각 의존 ML 승인을 정적 매핑 캐시에 저장하지 않는다.
     * 한 대안에 여러 출처 후보가 있으면 하나 이상 승인된 후보가 있다는 뜻이며, 실제 선택은 평가의 candidateIndex를 따른다. */
    public TransitMappingResult withBusPredictionReady(java.util.Set<Integer> readyAlternatives) {
        var updated=java.util.stream.IntStream.range(0,busAlternatives.size()).mapToObj(i->{
            var a=busAlternatives.get(i);
            return new BusAlternativeMapping(a.providerName(),a.identityStatus(),a.geometryStatus(),a.reason(),readyAlternatives.contains(i),a.candidates());
        }).toList();
        return new TransitMappingResult(status,masterDataVersion,mappingPolicyVersion,officialRouteId,officialRouteName,
                fromNodeId,toNodeId,startOrder,endOrder,direction,matchScore,passingMatchRatio,candidateCount,failureReason,
                realtimeCoverage,segments,updated);
    }

    /** 기존 지하철·서울 매핑 소비 코드와 캐시 JSON에 대한 하위 호환 생성자다. */
    public TransitMappingResult(TransitMappingStatus status,String masterDataVersion,String mappingPolicyVersion,
            String officialRouteId,String officialRouteName,String fromNodeId,String toNodeId,Integer startOrder,Integer endOrder,
            String direction,double matchScore,double passingMatchRatio,int candidateCount,String failureReason,
            RealtimeMappingCoverage realtimeCoverage,List<MappedTransitSegment> segments) {
        this(status,masterDataVersion,mappingPolicyVersion,officialRouteId,officialRouteName,fromNodeId,toNodeId,startOrder,endOrder,
                direction,matchScore,passingMatchRatio,candidateCount,failureReason,realtimeCoverage,segments,List.of());
    }

    /** WALK처럼 서울 대중교통 마스터 매핑 대상이 아닌 구간의 명시적 결과다. */
    public static TransitMappingResult notApplicable() {
        return new TransitMappingResult(
                TransitMappingStatus.NOT_APPLICABLE,
                null, null,
                null, null, null, null, null, null, null,
                0.0, 0.0, 0, "NOT_TRANSIT_SEGMENT",
                RealtimeMappingCoverage.NOT_APPLICABLE, List.of()
        );
    }

    /** 마스터 공급원이 아직 구성되지 않았을 때 성공으로 오인하지 않도록 만드는 결과다. */
    public static TransitMappingResult masterDataNotConfigured() {
        return new TransitMappingResult(
                TransitMappingStatus.UNMAPPED,
                null, null,
                null, null, null, null, null, null, null,
                0.0, 0.0, 0, "MASTER_DATA_NOT_CONFIGURED",
                RealtimeMappingCoverage.NONE, List.of()
        );
    }

    /**
     * 사람이 같은 마스터·정책·provider signature의 자동 판정을 거절한 경우다.
     * canonical ID와 edge를 노출하지 않아 후속 기준선/실시간 연산이 오판을 재사용하지 못하게 한다.
     */
    public static TransitMappingResult manuallyRejected(
            String masterDataVersion,
            String mappingPolicyVersion
    ) {
        return new TransitMappingResult(
                TransitMappingStatus.UNMAPPED,
                masterDataVersion, mappingPolicyVersion,
                null, null, null, null, null, null, null,
                0.0, 0.0, 0, "MANUALLY_REJECTED",
                RealtimeMappingCoverage.NONE, List.of()
        );
    }

    /** topology EXACT 여부와 실시간 provider ID coverage를 서로 다른 축으로 계산한다. */
    private static RealtimeMappingCoverage deriveRealtimeCoverage(
            TransitMappingStatus status,
            List<MappedTransitSegment> segments
    ) {
        if (status == TransitMappingStatus.NOT_APPLICABLE) {
            return RealtimeMappingCoverage.NOT_APPLICABLE;
        }
        if (status != TransitMappingStatus.EXACT || segments.isEmpty()) {
            return RealtimeMappingCoverage.NONE;
        }
        long ready = segments.stream().filter(MappedTransitSegment::realtimeLookupReady).count();
        if (ready == 0) {
            return RealtimeMappingCoverage.NONE;
        }
        return ready == segments.size()
                ? RealtimeMappingCoverage.FULL
                : RealtimeMappingCoverage.PARTIAL;
    }
}
