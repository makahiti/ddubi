package com.ssafy.ddubi.domain.recommendation.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.client.*;
import com.ssafy.ddubi.domain.route.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 화면에 ETA가 사라졌던 결측·도보 누락·시험 모델·통제 우회 사례를 외부 서버 없이 재현한다. */
class EvaluationRecoveryTest {
    @Test
    void evidenceExpirySchedulesRefreshWithoutExtendingItsValidity() {
        Instant now = Instant.parse("2026-09-18T00:00:00Z");
        assertThat(ScheduleEvaluationV2.nextRefresh(now, now.plusSeconds(600), now.plusSeconds(20)))
                .isEqualTo(now.plusSeconds(20));
        assertThat(ScheduleEvaluationV2.nextRefresh(now, now.plusSeconds(600), now.minusSeconds(1)))
                .isEqualTo(now.plusSeconds(15));
        assertThat(ScheduleEvaluationV2.nextRefresh(now, now, now.plusSeconds(60))).isEqualTo(now);
    }

    @Test
    void missingEndpointWalksAreAddedWithoutChangingCandidateIdentity() {
        var f = new WalkingEnrichmentTest();
        var ride = f.route(0).segments().get(1);
        var route = new RouteCandidate(0, RouteType.BUS, 1000, 600, 0, null, 0, 0, List.of(ride));
        var requests = new ArrayList<WalkingRouteClient.Request>();
        var service =
                new WalkingEnrichmentService(
                        r -> {
                            requests.add(r);
                            return new WalkingRouteClient.Evidence(
                                    120L, f.now.plusSeconds(60), "fixture", null);
                        },
                        new ObjectMapper(),
                        true,
                        10,
                        0,
                        "",
                        true);
        var prepared = service.prepare(List.of(route), f.origin, f.destination, f.now);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).destination()).isEqualTo(f.board);
        assertThat(requests.get(1).origin()).isEqualTo(f.alight);
        assertThat(prepared.candidates().get(0).totalDurationSeconds()).isEqualTo(840);
        assertThat(RouteEvidenceAssembler.key(prepared.candidates().get(0)))
                .isEqualTo(RouteEvidenceAssembler.key(route));
        assertThat(prepared.evidence().legs())
                .extracting(WalkingEnrichmentService.Leg::legRole)
                .containsExactly("ACCESS_WALK", "RIDE", "EGRESS_WALK");
        assertThat(prepared.evidence().legs().get(0).endpointEvidence().coordinateType())
                .isEqualTo("PROVIDER_PATH_ENDPOINT");
    }

    @Test
    void missingEndpointFailureAndUnknownResidualPreserveProviderTotal() {
        var f = new WalkingEnrichmentTest();
        var ride = f.route(0).segments().get(1);
        var route = new RouteCandidate(0, RouteType.BUS, 1000, 600, 0, null, 0, 0, List.of(ride));
        var service =
                new WalkingEnrichmentService(
                        r -> new WalkingRouteClient.Evidence(null, null, null, "WALK_HTTP_403"),
                        new ObjectMapper(),
                        true,
                        10,
                        0,
                        "",
                        true);
        var failed = service.prepare(List.of(route), f.origin, f.destination, f.now);
        assertThat(failed.candidates().get(0).totalDurationSeconds()).isEqualTo(600);
        assertThat(failed.candidates().get(0).segments()).hasSize(3);
        assertThat(failed.evidence().fallbackReasons())
                .contains("WALK_ENDPOINT_DURATION_UNAVAILABLE");
        var residual =
                new RouteCandidate(0, RouteType.BUS, 1000, 900, 0, null, 0, 300, List.of(ride));
        var unknown = service.prepare(List.of(residual), f.origin, f.destination, f.now);
        assertThat(unknown.evidence().apiCalls()).isEqualTo(2);
        assertThat(unknown.candidates().get(0).totalDurationSeconds()).isEqualTo(900);
        assertThat(unknown.candidates().get(0).unassignedDurationSeconds()).isEqualTo(300);
        assertThat(unknown.evidence().fallbackReasons()).contains("WALK_HTTP_403");
    }

    @Test
    void providerTotalFallbackStillBlocksVerifiedClosedEdge() {
        var f = new RouteEvidenceAssemblerTest();
        var route = RouteEvidenceAssemblerTest.candidate(0, 600, -100);
        var assembler =
                new RouteEvidenceAssembler(mock(StringRedisTemplate.class), new ObjectMapper());
        var journey =
                assembler.assemble(
                        route, new RouteEvidenceAssembler.Snapshot(Map.of(), List.of()), f.now);
        var closure =
                new JourneyEvidence.Closure(
                        "official-closure",
                        List.of("BUS:1:2:3"),
                        new JourneyEvidence.Window(
                                "official", f.now, f.now, f.now.plusSeconds(1000)));
        var result =
                new RouteTimelineEvaluator()
                        .evaluate(journey, f.now, f.now, f.now.plusSeconds(2000), List.of(closure));
        assertThat(result.assessment()).isEqualTo(RouteTimelineEvaluator.Assessment.BLOCKED);
        assertThat(result.estimatedArrivalAt()).isNull();
    }

    @Test
    void providerEstimateCanBeAcceptedWithoutPromotingModel() {
        var f = new ConditionalDepartureEvaluatorTest();
        var e =
                f.engine(256)
                        .evaluate(
                                1,
                                f.now,
                                f.p,
                                f.d,
                                List.of(),
                                at -> f.comparison(at, at.plusSeconds(10)));
        var dto =
                ScheduleEvaluationV2.from(
                        e,
                        "test",
                        1L,
                        1L,
                        0,
                        "MANUAL",
                        f.p,
                        f.p,
                        f.d,
                        f.now,
                        f.now.plusSeconds(600),
                        null,
                        7L,
                        7L,
                        List.of(),
                        null,
                        List.of(),
                        e.proposed(),
                        Map.of(e.proposed().journeyKey(), 7L));
        assertThat(dto.guidance().canAccept()).isTrue();
        assertThat(dto.quality().productionEligible()).isFalse();
        assertThat(dto.candidateEstimates()).hasSize(1);
        assertThat(dto.candidateEstimates().get(0).routeId()).isEqualTo(7L);
    }

    @Test
    void explicitlyTestAppliedModelCanBeAcceptedForManualQa() {
        var f = new MlApplyModeTest();
        when(f.client.predict(any())).thenReturn(f.sufficient(30));
        var e =
                new ConditionalDepartureEvaluator(null, null, 256, 60, 5000)
                        .evaluate(
                                1,
                                f.now,
                                f.now,
                                f.now.plusSeconds(1000),
                                List.of(),
                                at ->
                                        f.service(MlApplyMode.TEST_APPLY)
                                                .compare(
                                                        List.of(
                                                                MlRoutePreviewTest.route(
                                                                        0, "1", 300, 0, 1)),
                                                        f.empty,
                                                        f.now,
                                                        at,
                                                        f.now.plusSeconds(1000)));
        assertThat(EvaluationGuidance.from(e).canDisplayEstimate()).isTrue();
        assertThat(EvaluationGuidance.from(e).canAccept()).isTrue();
        assertThat(EvaluationGuidance.from(e).source()).isEqualTo("EXPERIMENTAL_ESTIMATE");
        assertThat(EvaluationGuidance.from(e).reasons()).contains("ML_TEST_APPLIED");
    }

    @Test
    void mlPrefetchChecksEachChunkAndKeepsFallbackForRemainingCandidates() {
        var f = new MlApplyModeTest();
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        when(f.client.maxBatchSegments()).thenReturn(1);
        when(f.client.timeoutMillis()).thenAnswer(call -> called.get() ? 60000L : 1L);
        when(f.client.predictBatch(anyList(), any()))
                .thenAnswer(
                        call -> {
                            called.set(true);
                            List<BusPreviewClient.Input> inputs = call.getArgument(0);
                            return Map.of(inputs.get(0), f.sufficient(30));
                        });
        var result =
                f.service(MlApplyMode.SHADOW)
                        .compare(
                                List.of(
                                        MlRoutePreviewTest.route(0, "1", 300, 0, 1),
                                        MlRoutePreviewTest.route(1, "2", 400, 0, 1)),
                                f.empty,
                                f.now,
                                f.now,
                                f.now.plusSeconds(1000));
        verify(f.client, times(1)).predictBatch(anyList(), any());
        verify(f.client, never()).predict(any());
        assertThat(result.candidates())
                .hasSize(2)
                .allMatch(c -> c.result().estimatedArrivalAt() != null);
    }
}
