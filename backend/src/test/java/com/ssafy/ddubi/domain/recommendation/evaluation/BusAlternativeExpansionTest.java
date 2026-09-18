package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.*;
import com.ssafy.ddubi.domain.route.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.ssafy.ddubi.domain.route.mapping.MetropolitanBusFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 차량 대안의 edge가 비어 ML 평가에서 탈락하던 회귀와 조합 한도 오탐을 방어한다. */
class BusAlternativeExpansionTest {
    final Instant now=Instant.parse("2026-09-17T00:00:00Z");
    RouteSegment segment(int sequence) {
        var provider=mock(SeoulTransitMasterDataProvider.class); when(provider.activeVersion()).thenReturn(Optional.empty());
        var routes=List.of(regular(),new MetropolitanBusMaster.Route("SEOUL_BUS","R2","200","서울",regular().stops(),Map.of()));
        var resolver=new MasterDataTransitMappingResolver(provider,null,new ProviderSegmentSignatureFactory(),
                new CanonicalSegmentIdFactory(),new MetropolitanBusCatalog(master(routes)),null,Clock.fixed(now,ZoneOffset.UTC));
        var vehicles=List.of(new VehicleInfo("100","BUS"),new VehicleInfo("200","BUS"));
        var names=List.of("출발","중간","도착");
        var mapping=resolver.resolve(new SegmentResolveRequest(TransportMode.BUS,"100","출발","도착",names,vehicles,geometry()));
        return new RouteSegment(sequence,TransportMode.BUS,null,"100",null,"출발","도착",400,100,names,vehicles,geometry(),mapping);
    }
    RouteSearchResult search(List<RouteSegment> segments) {
        return new RouteSearchResult("KAKAO",now,null,List.of(new RouteCandidate(7,RouteType.BUS,800,200,1,null,0,0,segments)));
    }
    @Test void expandsWholeJourneysWithoutMixingEdgesAndIsIdempotent() {
        var original=search(List.of(segment(1),segment(2)));
        var expanded=BusAlternativeExpansion.expand(original);
        assertThat(expanded.candidates()).hasSize(4);
        assertThat(expanded.candidates().stream().map(RouteEvidenceAssembler::key).distinct()).hasSize(4);
        assertThat(expanded.candidates()).allSatisfy(c -> {
            assertThat(c.providerCandidateIndex()).isEqualTo(7);
            assertThat(c.totalDurationSeconds()).isEqualTo(200);
            assertThat(c.segments()).allSatisfy(s -> {
                assertThat(s.mapping().status()).isEqualTo(TransitMappingStatus.EXACT);
                assertThat(s.mapping().segments()).hasSize(2);
                assertThat(s.mapping().segments().stream().map(MappedTransitSegment::routeId).distinct()).hasSize(1);
            });
        });
        assertThat(BusAlternativeExpansion.expand(expanded)).isEqualTo(expanded);
        assertThat(original.candidates().get(0).segments().get(0).mapping().segments()).isEmpty();
    }
    @Test void incompleteCombinationSearchRemainsExplicitFallback() {
        var expanded=BusAlternativeExpansion.expand(search(java.util.stream.IntStream.rangeClosed(1,6).mapToObj(this::segment).toList()));
        assertThat(expanded.candidates()).hasSize(1);
        assertThat(expanded.candidates().get(0).segments()).allSatisfy(s -> {
            assertThat(s.mapping().failureReason()).isEqualTo(BusAlternativeExpansion.LIMITED);
            assertThat(s.mapping().status()).isNotEqualTo(TransitMappingStatus.EXACT);
        });
    }
    @Test void remappingExpandedCacheUsesCurrentEvidenceAndDoesNotExtendProviderTtl() {
        var original=segment(1);var resolver=mock(TransitMappingResolver.class);
        when(resolver.resolve(any())).thenReturn(original.mapping());
        var refreshed=new RouteMappingRefresher(resolver).refresh(BusAlternativeExpansion.expand(search(List.of(original))));
        assertThat(refreshed.fetchedAt()).isEqualTo(now);
        assertThat(refreshed.candidates()).hasSize(1);
        assertThat(refreshed.candidates().get(0).segments().get(0).mapping()).isEqualTo(original.mapping());
        assertThat(BusAlternativeExpansion.expand(refreshed).candidates()).hasSize(2);
        verify(resolver,times(2)).resolve(any());
    }
    @Test void walkingEvidenceAndStoredVehicleReferToTheSameExpandedChoice() {
        var expanded=BusAlternativeExpansion.expand(search(List.of(segment(1))));
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var walking=new com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService(
                mock(com.ssafy.ddubi.domain.route.client.WalkingRouteClient.class),json,false,0,0,"");
        var prepared=walking.prepare(expanded.candidates(),new Coordinate(127,37.5),new Coordinate(127.004,37.5),now);
        assertThat(prepared.evidence().legs()).extracting(com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService.Leg::candidateKey)
                .containsExactlyInAnyOrderElementsOf(expanded.candidates().stream().map(RouteEvidenceAssembler::key).toList());
        assertThat(expanded.candidates()).allSatisfy(c -> {
            var s=c.segments().get(0);
            assertThat(BusAlternativeExpansion.displayedVehicles(s)).hasSize(1);
            assertThat(BusAlternativeExpansion.displayedRouteKey(s)).isEqualTo(s.mapping().busAlternatives().get(0).providerName());
            assertThat(s.vehicles()).hasSize(2); // 재매핑에 필요한 원래 카카오 선택지는 보존한다.
        });
    }

    @Test void repeatedStopPairOccurrencesCannotShareCandidateOrSelectionIdentity() {
        // 순환 노선에서 같은 canonical edge가 두 번 나타나도 서로 다른 승차 순번은 별도 선택이다.
        var candidate=BusAlternativeExpansion.expand(search(List.of(segment(1)))).candidates().get(0);
        var s=candidate.segments().get(0); var m=s.mapping();
        var shifted=m.segments().stream().map(e -> new MappedTransitSegment(e.sequence(),e.segmentId(),e.mode(),
                e.routeId(),e.realtimeRouteId(),e.routeName(),e.direction(),e.fromNodeId(),e.toNodeId(),
                e.fromRealtimeNodeId(),e.toRealtimeNodeId(),e.fromOrder()+10,e.toOrder()+10)).toList();
        var mapping=new TransitMappingResult(m.status(),m.masterDataVersion(),m.mappingPolicyVersion(),
                m.officialRouteId(),m.officialRouteName(),m.fromNodeId(),m.toNodeId(),m.startOrder()+10,m.endOrder()+10,
                m.direction(),m.matchScore(),m.passingMatchRatio(),m.candidateCount(),m.failureReason(),
                m.realtimeCoverage(),shifted,m.busAlternatives());
        var repeated=new RouteSegment(s.sequence(),s.mode(),s.segmentId(),s.providerRouteKey(),s.guidance(),
                s.fromName(),s.toName(),s.distanceMeters(),s.durationSeconds(),s.stopNames(),s.vehicles(),s.geometry(),mapping);
        var other=search(List.of(repeated)).candidates().get(0);
        assertThat(RouteEvidenceAssembler.pathIdentity(s)).isNotEqualTo(RouteEvidenceAssembler.pathIdentity(repeated));
        assertThat(RouteEvidenceAssembler.key(candidate)).isNotEqualTo(RouteEvidenceAssembler.key(other));
    }

    @Test void selectedPathPersistsTheSameEdgesAndVersionsUsedForEvaluation() {
        var assembler=new com.ssafy.ddubi.domain.recommendation.service.RouteCandidateAssembler(
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        var expanded=BusAlternativeExpansion.expand(search(List.of(segment(1))));
        for(var candidate:expanded.candidates()) {
            var step=candidate.segments().get(0);
            var assembled=assembler.assemble(null,null,candidate,1);
            var saved=assembled.route().getSegments().get(0);
            assertThat(saved.getProviderRouteKey()).isEqualTo(BusAlternativeExpansion.displayedRouteKey(step));
            assertThat(saved.getEdges()).extracting(com.ssafy.ddubi.domain.route.entity.SavedRouteSegmentEdge::getSegmentId)
                    .containsExactlyElementsOf(RouteEvidenceAssembler.edges(step).stream().map(MappedTransitSegment::segmentId).toList());
            assertThat(saved.getEdges()).allSatisfy(edge -> {
                assertThat(edge.getMappingVersion()).isEqualTo(step.mapping().masterDataVersion());
                assertThat(edge.getPolicyVersion()).isEqualTo(step.mapping().mappingPolicyVersion());
            });
        }
    }
}
