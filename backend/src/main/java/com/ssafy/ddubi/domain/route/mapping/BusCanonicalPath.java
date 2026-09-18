package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.model.TransportMode;
import java.util.*;

/** 검증된 차량 대안 중 명시적으로 선택한 한 공식 경로를 공용 edge로 전개한다.
 * 상태를 소유하지 않는 순수 경계이며 버전·순번을 보존한다. 지역 정적 ID를 서울 실시간 ID로 채우지 않는다. */
public record BusCanonicalPath(String masterVersion,String policyVersion,String providerName,
        BusAlternativeMapping.Candidate source,List<MappedTransitSegment> edges) {
    public BusCanonicalPath { edges=List.copyOf(edges); }

    /** 서버가 생성한 mapping과 대안/출처 순번을 받는다. 모호함·좌표 충돌·비인접·출처 혼합은 거절한다. */
    public static BusCanonicalPath select(TransitMappingResult mapping,int alternativeIndex,int candidateIndex) {
        var alt=mapping.busAlternatives().get(alternativeIndex);
        if(!"CONFIRMED".equals(alt.identityStatus()) || !"VERIFIED".equals(alt.geometryStatus()))
            throw new IllegalArgumentException("검증되지 않은 버스 대안");
        var candidate=alt.candidates().get(candidateIndex);
        return of(mapping.masterDataVersion(),mapping.mappingPolicyVersion(),alt.providerName(),candidate);
    }

    /** Matcher가 NONE(검증 성공)으로 판정한 원본 경로만 허용한다. 모델 준비 여부와는 독립이다. */
    public static BusCanonicalPath of(String version,String policy,String providerName,BusAlternativeMapping.Candidate c) {
        if(version==null || version.isBlank() || !"NONE".equals(c.reason()) || c.stops().size()<2)
            throw new IllegalArgumentException("버스 경로 증거 부족");
        var factory=new CanonicalSegmentIdFactory();var edges=new ArrayList<MappedTransitSegment>();
        boolean realtime="SEOUL_BUS".equals(c.namespace());
        for(int i=1;i<c.stops().size();i++) {
            var a=c.stops().get(i-1);var b=c.stops().get(i);
            if(!a.namespace().equals(c.namespace()) || !b.namespace().equals(c.namespace()) || b.sequence()!=a.sequence()+1)
                throw new IllegalArgumentException("출처 혼합 또는 인접 순번 누락");
            edges.add(new MappedTransitSegment(i,factory.createBus(c.namespace(),c.routeId(),a.stopId(),b.stopId()),
                    TransportMode.BUS,factory.busComponent(c.namespace(),c.routeId()),realtime?c.routeId():null,c.routeName(),
                    "SEQUENCE_FORWARD",factory.busComponent(c.namespace(),a.stopId()),factory.busComponent(c.namespace(),b.stopId()),
                    realtime?a.stopId():null,realtime?b.stopId():null,a.sequence(),b.sequence()));
        }
        return new BusCanonicalPath(version,policy,providerName,c,edges);
    }
}
