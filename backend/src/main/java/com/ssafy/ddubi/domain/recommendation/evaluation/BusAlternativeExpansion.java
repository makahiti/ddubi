package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.*;
import java.util.*;

/**
 * 카카오 한 후보의 검증된 버스 대안을 각각 완전한 여정으로 전개한다. 노선별로 따로
 * 시간표·대기·ML·통제를 평가하므로 서로 다른 차량의 빠른 edge를 섞지 않는다.
 * providerCandidateIndex는 원본을 보존하고 선택된 인접 ID열로 내부 평가 key를 구분한다.
 * 조합이 한도를 넘으면 일부 대안을 임의 최선으로 확정하지 않고 원본 fallback을 보존한다.
 */
public final class BusAlternativeExpansion {
    private static final int MAX_VARIANTS_PER_ROUTE = 32;
    public static final String SELECTED = "BUS_ALTERNATIVE_SELECTED";
    public static final String LIMITED = "BUS_ALTERNATIVE_COMBINATIONS_EXCEEDED";
    private BusAlternativeExpansion() { }

    /** 원본 vehicles는 캐시 재매핑에 보존하고, 사용자 표시/저장에는 실제 계산한 차량만 사용한다. */
    public static List<String> displayedVehicles(RouteSegment segment) {
        var mapping=segment.mapping();
        if(mapping!=null && SELECTED.equals(mapping.failureReason()))
            return mapping.busAlternatives().stream().map(BusAlternativeMapping::providerName).toList();
        return segment.vehicles().stream().map(VehicleInfo::name).toList();
    }
    /** 선택 대안 표시 이름은 canonical ID가 아니다. 영향 구간은 별도의 mapping.segments로 저장한다. */
    public static String displayedRouteKey(RouteSegment segment) {
        return segment.mapping()!=null && SELECTED.equals(segment.mapping().failureReason())
                ? String.join(",",displayedVehicles(segment)):segment.providerRouteKey();
    }

    /** 외부 호출과 DB 쓰기 없는 순수 변환이다. 이미 선택된 버전 고정 후보는 그대로 반환한다. */
    public static RouteSearchResult expand(RouteSearchResult search) {
        var output = new ArrayList<RouteCandidate>();
        for (var route : search.candidates()) {
            var variants = new ArrayList<List<RouteSegment>>();
            variants.add(List.of());
            boolean limited = false;
            for (var segment : route.segments()) {
                var options = options(segment);
                if ((long) variants.size() * options.size() > MAX_VARIANTS_PER_ROUTE) { limited = true; break; }
                var next = new ArrayList<List<RouteSegment>>();
                for (var prefix : variants) for (var option : options) {
                    var value = new ArrayList<>(prefix); value.add(option); next.add(value);
                }
                variants = next;
            }
            if (limited) {
                output.add(copy(route, route.segments().stream().map(BusAlternativeExpansion::limited).toList()));
            } else for (var segments : variants) output.add(copy(route, segments));
        }
        return new RouteSearchResult(search.provider(),search.fetchedAt(),search.landingUrl(),output.stream().distinct().toList());
    }

    private static RouteCandidate copy(RouteCandidate route, List<RouteSegment> segments) {
        return new RouteCandidate(route.providerCandidateIndex(),route.type(),route.totalDistanceMeters(),
                route.totalDurationSeconds(),route.transfers(),route.fare(),route.unassignedDistanceMeters(),
                route.unassignedDurationSeconds(),segments);
    }

    private static List<RouteSegment> options(RouteSegment segment) {
        var mapping = segment.mapping();
        if (segment.mode()!=TransportMode.BUS || mapping==null || mapping.status()==TransitMappingStatus.EXACT)
            return List.of(segment);
        var result = new LinkedHashMap<List<MappedTransitSegment>, RouteSegment>();
        boolean unresolved = false;
        for (int a=0; a<mapping.busAlternatives().size(); a++) {
            var alt=mapping.busAlternatives().get(a);
            if (!"CONFIRMED".equals(alt.identityStatus()) || !"VERIFIED".equals(alt.geometryStatus())) {
                unresolved=true; continue;
            }
            for(int c=0;c<alt.candidates().size();c++) {
                try {
                    var path=BusCanonicalPath.select(mapping,a,c); var edges=path.edges();
                    var first=edges.get(0); var last=edges.get(edges.size()-1);
                    var selected=new TransitMappingResult(TransitMappingStatus.EXACT,mapping.masterDataVersion(),
                            mapping.mappingPolicyVersion(),first.routeId(),path.source().routeName(),first.fromNodeId(),
                            last.toNodeId(),first.fromOrder(),last.toOrder(),first.direction(),1,1,1,SELECTED,null,edges,
                            List.of(new BusAlternativeMapping(alt.providerName(),alt.identityStatus(),alt.geometryStatus(),
                                    alt.reason(),false,List.of(alt.candidates().get(c)))));
                    result.putIfAbsent(edges,withMapping(segment,selected));
                } catch(IllegalArgumentException invalid) { unresolved=true; }
            }
        }
        // 검증되지 않은 선택지도 사라졌다고 주장하지 않는다. 원본 API fallback 후보를 별도로 남긴다.
        var values=new ArrayList<>(result.values());
        if(unresolved || values.isEmpty()) values.add(segment);
        return values;
    }

    private static RouteSegment limited(RouteSegment segment) {
        var m=segment.mapping();
        if(segment.mode()!=TransportMode.BUS || m==null || m.status()==TransitMappingStatus.EXACT) return segment;
        return withMapping(segment,new TransitMappingResult(m.status(),m.masterDataVersion(),m.mappingPolicyVersion(),
                m.officialRouteId(),m.officialRouteName(),m.fromNodeId(),m.toNodeId(),m.startOrder(),m.endOrder(),m.direction(),
                m.matchScore(),m.passingMatchRatio(),m.candidateCount(),LIMITED,m.realtimeCoverage(),m.segments(),m.busAlternatives()));
    }

    private static RouteSegment withMapping(RouteSegment s, TransitMappingResult m) {
        return new RouteSegment(s.sequence(),s.mode(),m.segments().size()==1?m.segments().get(0).segmentId():null,
                s.providerRouteKey(),s.guidance(),s.fromName(),s.toName(),s.distanceMeters(),s.durationSeconds(),
                s.stopNames(),s.vehicles(),s.geometry(),m);
    }
}
