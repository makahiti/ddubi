package com.ssafy.ddubi.domain.route.prediction;

import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.*;
import com.ssafy.ddubi.domain.route.service.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.ssafy.ddubi.domain.route.mapping.MetropolitanBusFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 손계산 가능한 30초+40초 경로로 대안 간 합산·부분 모델·미래 특성·미승인 모델 오탐을 막는다. */
class BusJourneyEvaluatorTest {
    final Instant now=Instant.parse("2026-09-10T00:00:00Z");
    RouteSegment segment(boolean two) {
        var routes=new ArrayList<MetropolitanBusMaster.Route>();routes.add(regular());
        if(two)routes.add(new MetropolitanBusMaster.Route("SEOUL_BUS","R2","200","서울",regular().stops(),Map.of()));
        var provider=mock(SeoulTransitMasterDataProvider.class);when(provider.activeVersion()).thenReturn(Optional.empty());
        var resolver=new MasterDataTransitMappingResolver(provider,null,new ProviderSegmentSignatureFactory(),new CanonicalSegmentIdFactory(),
                new MetropolitanBusCatalog(master(routes)),null,Clock.fixed(now,ZoneId.of("Asia/Seoul")));
        var vehicles=two?List.of(new VehicleInfo("100","BUS"),new VehicleInfo("200","BUS")):List.of(new VehicleInfo("100","BUS"));
        var names=List.of("출발","중간","도착");
        var mapping=resolver.resolve(new SegmentResolveRequest(TransportMode.BUS,"100","출발","도착",names,vehicles,geometry()));
        return new RouteSegment(1,TransportMode.BUS,null,"100",null,"출발","도착",400,100,names,vehicles,geometry(),mapping);
    }
    BusPredictionPort.Batch batch(BusPredictionPort.Input input,String mutation) {
        var edges=new ArrayList<BusPredictionPort.EdgeMean>();
        for(var edge:input.path().edges())edges.add(new BusPredictionPort.EdgeMean(edge.segmentId(),edge.sequence(),edge.sequence()==1?30:40,12));
        if(mutation.equals("missing"))edges.remove(1);
        if(mutation.equals("duplicate"))edges.set(1,edges.get(0));
        if(mutation.equals("nan"))edges.set(0,new BusPredictionPort.EdgeMean(edges.get(0).segmentId(),1,Double.NaN,12));
        return new BusPredictionPort.Batch("model1","features1",mutation.equals("digest")?"wrong":input.digest(),
                mutation.equals("quantile")?"P95":"ADJACENT_BUS_MEAN_SECONDS",!mutation.equals("unapproved"),!mutation.equals("unknownId"),
                mutation.equals("future")?now.plusSeconds(1):now,now,mutation.equals("expired")?now:now.plusSeconds(60),edges);
    }
    @Test void sumsEveryEdgeMeanPerAlternativeWithoutCombiningVehiclesOrWaiting() {
        var captured=new AtomicReference<BusPredictionPort.Input>();
        var evaluator=new BusJourneyEvaluator(input->{captured.set(input);return Optional.of(batch(input,"valid"));});
        var results=evaluator.evaluate(segment(true),now);
        assertThat(results).hasSize(2).allSatisfy(r->{assertThat(r.predictionReady()).isTrue();assertThat(r.estimatedSeconds()).isEqualTo(70);});
        assertThat(results.get(0).segmentIds()).containsExactly("BUS:R1:A:B","BUS:R1:B:C");
        assertThat(results.get(1).segmentIds()).containsExactly("BUS:R2:A:B","BUS:R2:B:C");
        assertThat(captured.get().path().source().stops()).hasSize(3);
    }
    @Test void invalidBatchesAlwaysFallBackAsWholeStep() {
        for(String mutation:List.of("missing","duplicate","nan","digest","quantile","unapproved","unknownId","future","expired")) {
            var result=new BusJourneyEvaluator(input->Optional.of(batch(input,mutation))).evaluate(segment(false),now).get(0);
            assertThat(result.predictionReady()).as(mutation).isFalse();
            assertThat(result.estimatedSeconds()).isEqualTo(100);assertThat(result.source()).isEqualTo("KAKAO");
        }
    }
    @Test void absentOrFailingModelNeverClaimsPredictionReadiness() {
        assertThat(new BusJourneyEvaluator(input->Optional.empty()).evaluate(segment(false),now).get(0).reason()).contains("UNAVAILABLE");
        var result=new BusJourneyEvaluator(input->{throw new IllegalStateException("secret URL");}).evaluate(segment(false),now).get(0);
        assertThat(result.reason()).isEqualTo("MODEL_CALL_FAILED");assertThat(result.toString()).doesNotContain("secret");
    }
    @Test void evidenceDigestChangesWithPredictionTime() {
        var evaluator=new BusJourneyEvaluator(input->Optional.empty());var path=BusCanonicalPath.select(segment(false).mapping(),0,0);
        assertThat(evaluator.input(path,now).digest()).isNotEqualTo(evaluator.input(path,now.plusSeconds(1)).digest());
    }

    @Test void responseKeepsProviderTotalsAndReportsReadinessWithoutMutatingMappingCache() {
        var segment=segment(true);
        var candidate=new RouteCandidate(0,RouteType.BUS,450,120,0,null,50,20,List.of(segment));
        var result=new RouteSearchResult("KAKAO",now,null,List.of(candidate));
        var evaluator=new BusJourneyEvaluator(input->Optional.of(batch(input,"valid")));
        var response=com.ssafy.ddubi.domain.route.dto.RouteSearchResponse.from(result,evaluator).candidates().get(0);
        assertThat(response.totalDurationSeconds()).isEqualTo(120);
        assertThat(response.segments().get(0).durationSeconds()).isEqualTo(100);
        assertThat(response.mappingSummary().busAlternativeCount()).isEqualTo(2);
        assertThat(response.mappingSummary().busGeometryVerifiedCount()).isEqualTo(2);
        assertThat(response.mappingSummary().busPredictionReadyCount()).isEqualTo(2);
        assertThat(response.segments().get(0).mapping().busAlternatives()).allSatisfy(a->assertThat(a.predictionReady()).isTrue());
        assertThat(segment.mapping().busAlternatives()).allSatisfy(a->assertThat(a.predictionReady()).isFalse());
        var fallback=com.ssafy.ddubi.domain.route.dto.RouteSearchResponse.from(result).candidates().get(0);
        assertThat(fallback.mappingSummary().busPredictionReadyCount()).isZero();
    }
}
