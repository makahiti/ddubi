package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ssafy.ddubi.domain.route.client.WalkingRouteClient;
import com.ssafy.ddubi.domain.route.model.RouteCandidate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 보강→정방향 계산→공개 DTO까지 통과한 비어 있지 않은 FE 도보/사건 예제를 생성한다. */
class WalkingContractTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    final WalkingEnrichmentTest fixture=new WalkingEnrichmentTest();

    ScheduleEvaluationV2 evaluate(RouteCandidate route,boolean success,RouteEvidenceAssembler.Snapshot snapshot) {
        var prepared=fixture.service(r->success?new WalkingRouteClient.Evidence(120L,fixture.now.plusSeconds(60),"fixture",null)
                :new WalkingRouteClient.Evidence(null,null,null,"WALK_HTTP_403"))
                .prepare(List.of(route),fixture.origin,fixture.destination,fixture.now,fixture.points("BUS_STOP"));
        var candidate=prepared.candidates().get(0);
        var assembler=new RouteEvidenceAssembler(null,json);
        var timeline=new RouteTimelineEvaluator();
        Instant p=fixture.now.plusSeconds(60),d=fixture.now.plusSeconds(3600);
        var evaluation=new ConditionalDepartureEvaluator(null,null,256,60,5000).evaluate(1,fixture.now,p,d,List.of(),at->{
            var result=timeline.evaluate(assembler.assemble(candidate,snapshot,fixture.now),fixture.now,at,d,assembler.closures(snapshot));
            return new MlRoutePreview.Comparison("PREVIEW",false,result,List.of(new MlRoutePreview.Candidate(result,"API_ONLY",0,3,0,0)),
                    List.of(),false,List.of(),List.of());
        }).withWalking(prepared.evidence());
        return ScheduleEvaluationV2.from(evaluation,"synthetic-walking",null,null,0,"READ_ONLY",p,p,d,fixture.now,
                fixture.now.plusSeconds(60),null,null,null,List.of("SYNTHETIC_FIXTURE"),snapshot,List.of(route));
    }

    void write(String name,ScheduleEvaluationV2 dto) throws Exception {
        Path directory=Path.of("target/conditional-departure-details");Files.createDirectories(directory);
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(name+".json").toFile(),dto);
        assertThat(json.readValue(json.writeValueAsString(dto),ScheduleEvaluationV2.class)).isEqualTo(dto);
    }

    @Test void appliedWalkingAndFallbackKeepTheirOwnSourcesAndObjectEvidence() throws Exception {
        var snapshot=new RouteEvidenceAssembler.Snapshot(Map.of(),List.of());
        var applied=evaluate(fixture.route(0),true,snapshot);
        assertThat(applied.durationSeconds()).isEqualTo(840);
        assertThat(applied.quality().walkingApiCoverage()).isEqualTo("BOTH");
        assertThat(applied.segments()).extracting(ScheduleEvaluationV2.Segment::durationSource)
                .containsExactly("KAKAO_WALKING_API","PUBLIC_TRANSIT_STEP","KAKAO_WALKING_API");
        assertThat(applied.segments().get(0).endpointEvidence().verified()).isTrue();
        write("walking-applied",applied);
        var fallback=evaluate(fixture.route(0),false,snapshot);
        assertThat(fallback.durationSeconds()).isEqualTo(720);
        assertThat(fallback.quality().walkingApiCoverage()).isEqualTo("NONE");
        assertThat(fallback.quality().fallbackReasons()).contains("WALK_HTTP_403");
        assertThat(fallback.segments()).allMatch(s->s.durationSource().equals("PUBLIC_TRANSIT_STEP"));
        write("walking-fallback",fallback);
    }

    @Test void providerTotalFallbackDoesNotInventPerLegTimes() throws Exception {
        var original=fixture.route(0);
        var invalid=new RouteCandidate(0,original.type(),original.totalDistanceMeters(),500,0,null,0,0,original.segments());
        var dto=evaluate(invalid,false,new RouteEvidenceAssembler.Snapshot(Map.of(),List.of()));
        assertThat(dto.durationSeconds()).isEqualTo(500);
        assertThat(dto.segments()).allMatch(s->s.durationSource().equals("PROVIDER_TOTAL_FALLBACK") && s.durationSeconds()==null);
        assertThat(dto.quality().walkingApiApplied()).isFalse();
        write("provider-total-fallback",dto);
    }

    @Test void confirmedClosurePreservesNonEmptyCauseContract() throws Exception {
        var state=json.readTree("""
                {"eventId":"synthetic-closure","source":"OFFICIAL_FIXTURE","status":"blocked",
                 "computedAt":"2026-09-16T00:00:00Z","validUntil":"2026-09-16T02:00:00Z"}
                """);
        var dto=evaluate(fixture.route(0),false,new RouteEvidenceAssembler.Snapshot(Map.of("BUS:1:2:3",state),List.of()));
        assertThat(dto.causes()).singleElement().satisfies(c->{
            assertThat(c.eventId()).isEqualTo("synthetic-closure");assertThat(c.segmentIds()).containsExactly("BUS:1:2:3");
            assertThat(c.effect()).isEqualTo("BLOCKED");assertThat(c.confirmed()).isTrue();
        });
        write("confirmed-cause",dto);
    }
    /** 대안 B가 정상이어도 사용자가 선택한 A의 차단 원인을 잃지 않고 무관한 C의 사건은 제외한다. */
    @Test void alternativeRouteRetainsSelectedClosureButExcludesUnrelatedCandidate() throws Exception {
        Instant now=fixture.now,p=now.plusSeconds(60),d=now.plusSeconds(3600);
        var selected=new RouteTimelineEvaluator.Result("candidate-0",p,null,RouteTimelineEvaluator.Assessment.BLOCKED,
                true,List.of(),List.of("CLOSURE:selected-event"),"fixture");
        var unrelated=new RouteTimelineEvaluator.Result("candidate-2",p,null,RouteTimelineEvaluator.Assessment.BLOCKED,
                true,List.of(),List.of("CLOSURE:other-event"),"fixture");
        var alternative=new RouteTimelineEvaluator.Result("candidate-1",p,p.plusSeconds(600),RouteTimelineEvaluator.Assessment.ON_TIME_ESTIMATE,
                true,List.of(),List.of(),"fixture");
        var comparison=new MlRoutePreview.Comparison("PREVIEW",false,alternative,List.of(selected,alternative,unrelated).stream()
                .map(r->new MlRoutePreview.Candidate(r,"API_ONLY",0,1,0,0)).toList(),List.of(),false,List.of(),List.of());
        var evaluation=new ConditionalDepartureEvaluator(null,null,256,60,5000).evaluate(3,now,p,d,List.of(),at->comparison);
        var snapshot=new RouteEvidenceAssembler.Snapshot(Map.of(
                "selected-segment",json.readTree("{\"eventId\":\"selected-event\",\"source\":\"OFFICIAL\"}"),
                "other-segment",json.readTree("{\"eventId\":\"other-event\",\"source\":\"OFFICIAL\"}")),List.of());
        var dto=ScheduleEvaluationV2.from(evaluation,"fixture",1L,1L,0,"REFRESH",p,p,d,now,now.plusSeconds(60),null,
                10L,11L,List.of(),snapshot,List.of(),selected);
        assertThat(dto.proposedRouteId()).isEqualTo(11L);
        assertThat(dto.causes()).singleElement().satisfies(c->{
            assertThat(c.eventId()).isEqualTo("selected-event");
            assertThat(c.confirmed()).isTrue();
            assertThat(c.segmentIds()).containsExactly("selected-segment");
        });
    }
}
