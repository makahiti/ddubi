package com.ssafy.ddubi.domain.recommendation.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 손계산 가능한 후보로 API 시간 교체·다음 구간 진입시각·실패 fallback·최소시간 순위를 검증한다. */
class MlRoutePreviewTest {
    final Instant now = Instant.parse("2026-09-10T14:59:30Z");
    final BusPreviewClient client = mock(BusPreviewClient.class);
    final RouteEvidenceAssembler evidence =
            new RouteEvidenceAssembler(mock(StringRedisTemplate.class), new ObjectMapper());
    final MlRoutePreview service =
            new MlRoutePreview(
                    client,
                    evidence,
                    new RouteTimelineEvaluator(),
                    mock(SubwayTimingService.class),
                    new BusWaitingService(
                            mock(StringRedisTemplate.class), new ObjectMapper(), false));
    final RouteEvidenceAssembler.Snapshot empty =
            new RouteEvidenceAssembler.Snapshot(Map.of(), List.of());

    static RouteCandidate route(
            int index, String routeId, long apiSeconds, long residual, int edgeCount) {
        var edges = new ArrayList<MappedTransitSegment>();
        for (int i = 1; i <= edgeCount; i++)
            edges.add(
                    new MappedTransitSegment(
                            i,
                            "BUS:" + routeId + ":" + i + ":" + (i + 1),
                            TransportMode.BUS,
                            routeId,
                            routeId,
                            routeId,
                            "FORWARD",
                            "" + i,
                            "" + (i + 1),
                            "" + i,
                            "" + (i + 1),
                            i,
                            i + 1));
        var mapping =
                new TransitMappingResult(
                        TransitMappingStatus.EXACT,
                        "master",
                        "policy",
                        routeId,
                        routeId,
                        "1",
                        "" + (edgeCount + 1),
                        1,
                        edgeCount + 1,
                        "FORWARD",
                        edgeCount,
                        edgeCount,
                        1,
                        null,
                        RealtimeMappingCoverage.FULL,
                        edges);
        var segment =
                new RouteSegment(
                        1,
                        TransportMode.BUS,
                        null,
                        routeId,
                        null,
                        "A",
                        "B",
                        100,
                        apiSeconds,
                        List.of("A", "B"),
                        List.of(new VehicleInfo(routeId, "BUS")),
                        List.of(),
                        mapping);
        return new RouteCandidate(
                index,
                RouteType.BUS,
                100,
                apiSeconds + residual,
                0,
                new FareRange(1000, 1000),
                0,
                residual,
                List.of(segment));
    }

    BusPreviewClient.Estimate estimate(double seconds) {
        return new BusPreviewClient.Estimate(
                seconds, "MODEL_NOT_PRODUCTION_READY", "test-model", now, 0, 8, false);
    }

    @Test
    void experimentalSearchReturnsUsableProviderEstimateInsteadOfBlockingRecommendation() {
        var journeys =
                new JourneyEvaluationService(
                        client,
                        evidence,
                        new RouteTimelineEvaluator(),
                        mock(SubwayTimingService.class),
                        new BusWaitingService(
                                mock(StringRedisTemplate.class), new ObjectMapper(), false));
        var engine =
                new ConditionalDepartureEvaluator(
                        journeys, mock(SubwayTimetable.class), 256, 60, 5000);
        when(client.predict(any()))
                .thenAnswer(
                        call -> {
                            var input = (BusPreviewClient.Input) call.getArgument(0);
                            return new BusPreviewClient.Estimate(
                                    4000.0,
                                    "MODEL_NOT_PRODUCTION_READY",
                                    input.hour() == 0 ? "version-a" : "version-b",
                                    now,
                                    0,
                                    8,
                                    false);
                        });
        var result =
                engine.evaluate(
                        List.of(route(0, "1", 600, 0, 1)),
                        empty,
                        now,
                        now.plusSeconds(120),
                        now.plusSeconds(3600));
        assertThat(result.decision()).isEqualTo(ConditionalDeparturePolicy.Decision.KEEP_DEPARTURE);
        assertThat(result.proposed().estimatedArrivalAt()).isEqualTo(now.plusSeconds(720));
        assertThat(EvaluationGuidance.from(result).canAccept()).isTrue();
        assertThat(result.originalComparison().predictions()).allMatch(p -> !p.applied());
    }

    @Test
    void mlReplacesApiMovementAndFastestWinsEvenWhenBothArriveOnTime() {
        when(client.predict(any()))
                .thenAnswer(
                        call ->
                                estimate(
                                        ((BusPreviewClient.Input) call.getArgument(0))
                                                        .routeId()
                                                        .equals("1")
                                                ? 200
                                                : 30));
        var result =
                service.compare(
                        List.of(route(0, "1", 10, 10, 1), route(1, "2", 900, 10, 1)),
                        empty,
                        now,
                        now,
                        now.plusSeconds(1000));
        assertThat(result.fastest().journeyKey()).isEqualTo("candidate-1");
        // 후보 1은 900+30+10이 아니라 30+10초다. 정시 여부와 무관하게 최소 소요시간을 선택한다.
        assertThat(result.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(30));
        assertThat(result.candidates().get(0).result().estimatedArrivalAt())
                .isEqualTo(now.plusSeconds(200));
        assertThat(result.candidates().get(1).quality()).isEqualTo("ML_PREVIEW");
        assertThat(result.productionReady()).isFalse();
    }

    @Test
    void nextEdgeUsesPredictedEntryHourAndNextKoreanDate() {
        when(client.predict(any())).thenReturn(estimate(45));
        var result =
                service.compare(
                        List.of(route(0, "1", 300, 0, 2)), empty, now, now, now.plusSeconds(1000));
        assertThat(result.predictions().get(0).input().date())
                .isEqualTo(LocalDate.parse("2026-09-10"));
        assertThat(result.predictions().get(0).input().hour()).isEqualTo(23);
        assertThat(result.predictions().get(1).input().date())
                .isEqualTo(LocalDate.parse("2026-09-11"));
        assertThat(result.predictions().get(1).input().hour()).isZero();
        assertThat(result.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(90));
    }

    @Test
    void partialMlFailureRevertsWholeStepAndDoesNotUseRedisMovement() throws Exception {
        when(client.predict(any()))
                .thenReturn(
                        estimate(45), BusPreviewClient.Estimate.unavailable("UNSEEN_PROVIDER_ID"));
        var state =
                new ObjectMapper().readTree("{\"status\":\"delayed\",\"observedSeconds\":9999}");
        var snapshot = new RouteEvidenceAssembler.Snapshot(Map.of("BUS:1:1:2", state), List.of());
        var result =
                service.compare(
                        List.of(route(0, "1", 300, 0, 2)),
                        snapshot,
                        now,
                        now,
                        now.plusSeconds(1000));
        assertThat(result.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(300));
        assertThat(result.candidates().get(0).quality()).isEqualTo("API_ONLY");
        assertThat(result.predictions()).allMatch(p -> !p.applied());
    }

    @Test
    void blockedShortestCandidateIsExcluded() throws Exception {
        when(client.predict(any())).thenReturn(estimate(30));
        var node =
                new ObjectMapper()
                        .readTree(
                                "{\"status\":\"blocked\",\"computedAt\":\"2026-09-10T14:59:00Z\",\"validUntil\":\"2026-09-10T15:05:00Z\"}");
        var snapshot = new RouteEvidenceAssembler.Snapshot(Map.of("BUS:1:1:2", node), List.of());
        var result =
                service.compare(
                        List.of(route(0, "1", 30, 0, 1), route(1, "2", 90, 0, 1)),
                        snapshot,
                        now,
                        now,
                        now.plusSeconds(1000));
        assertThat(result.candidates().get(0).result().assessment())
                .isEqualTo(RouteTimelineEvaluator.Assessment.BLOCKED);
        assertThat(result.fastest().journeyKey()).isEqualTo("candidate-1");
    }

    @Test
    void providerResidualDoesNotShiftMlEntryHour() {
        when(client.predict(any())).thenReturn(estimate(30));
        var result =
                service.compare(
                        List.of(route(0, "1", 300, 60, 1)), empty, now, now, now.plusSeconds(1000));
        verify(client).predict(any());
        assertThat(result.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(30));
        assertThat(result.fastest().reasons()).doesNotContain("step-1:ENTRY_HOUR_UNCERTAIN");
    }

    @Test
    void identicalInputUsesEvaluationLocalCacheAndAllBlockedReturnsNoWinner() throws Exception {
        when(client.predict(any())).thenReturn(estimate(30));
        var first =
                service.compare(
                        List.of(route(0, "1", 90, 0, 1), route(1, "1", 90, 0, 1)),
                        empty,
                        now,
                        now,
                        now.plusSeconds(1000));
        verify(client, times(1)).predict(any());
        assertThat(first.fastest().journeyKey()).isEqualTo("candidate-0");
        var node =
                new ObjectMapper()
                        .readTree(
                                "{\"status\":\"blocked\",\"computedAt\":\"2026-09-10T14:59:00Z\",\"validUntil\":\"2026-09-10T15:05:00Z\"}");
        var blocked =
                service.compare(
                        List.of(route(0, "1", 30, 0, 1)),
                        new RouteEvidenceAssembler.Snapshot(Map.of("BUS:1:1:2", node), List.of()),
                        now,
                        now,
                        now.plusSeconds(1000));
        assertThat(blocked.fastest()).isNull();
    }
}
