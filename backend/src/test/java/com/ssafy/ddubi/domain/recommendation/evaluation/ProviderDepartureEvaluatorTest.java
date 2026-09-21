package com.ssafy.ddubi.domain.recommendation.evaluation;

import static com.ssafy.ddubi.domain.recommendation.evaluation.ConditionalDeparturePolicy.Decision.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.model.RouteCandidate;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 실제 서비스 진입점에서 다수 후보·엄격한 도착 경계·과거 출발·통제·시험 ML의 복구를 손계산과 대조한다. */
class ProviderDepartureEvaluatorTest {
    final Instant now = Instant.parse("2026-09-18T00:00:00Z"); // 서울 09:00
    final Instant planned = now.plusSeconds(5400); // 10:30
    final Instant deadline = now.plusSeconds(7200); // 11:00
    final RouteEvidenceAssembler.Snapshot empty =
            new RouteEvidenceAssembler.Snapshot(Map.of(), List.of());
    final MlApplyModeTest fixture = new MlApplyModeTest();

    ProviderDepartureEvaluatorTest() {
        when(fixture.client.predict(any()))
                .thenReturn(BusPreviewClient.Estimate.unavailable("ML_NOT_CONFIGURED"));
    }

    private List<RouteCandidate> routes(int count, long seconds) {
        return IntStream.range(0, count)
                .mapToObj(i -> MlRoutePreviewTest.route(i, "1", seconds, 0, 1))
                .toList();
    }

    private ConditionalDepartureEvaluator engine(int maxEvaluations) {
        return new ConditionalDepartureEvaluator(
                fixture.service(MlApplyMode.SHADOW),
                mock(SubwayTimetable.class),
                maxEvaluations,
                60,
                5000);
    }

    @Test
    void thirtyFiveCandidatesBacksolveTo1006WithoutMinuteGridExhaustion() throws Exception {
        var result = engine(256).evaluate(routes(35, 2640), empty, now, planned, deadline);
        assertThat(result.decision()).isEqualTo(ADVANCE_DEPARTURE);
        assertThat(result.proposed().departureAt()).isEqualTo(now.plusSeconds(3960));
        assertThat(result.proposed().estimatedArrivalAt()).isEqualTo(deadline.minusSeconds(600));
        assertThat(result.appliedBufferSeconds()).isEqualTo(600);
        assertThat(result.search().complete()).isTrue();
        assertThat(result.search().evaluatedCount()).isEqualTo(70);
        assertThat(result.proposedComparison().candidates()).hasSize(35);
        assertThat(EvaluationGuidance.from(result).canAccept()).isTrue();
        var dto =
                ScheduleEvaluationV2.from(
                        result,
                        "provider-backsolve",
                        9L,
                        1L,
                        0,
                        "MANUAL",
                        planned,
                        planned,
                        deadline,
                        now,
                        now.plusSeconds(600),
                        null,
                        7L,
                        7L,
                        List.of(),
                        null,
                        List.of(),
                        result.proposed(),
                        Map.of("candidate-0", 7L));
        assertThat(dto.guidance().canAccept()).isTrue();
        assertThat(dto.quality().productionEligible()).isFalse();
        assertThat(dto.latenessSeconds()).isZero();
        var json =
                new ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                        .disable(
                                com.fasterxml.jackson.databind.SerializationFeature
                                        .WRITE_DATES_AS_TIMESTAMPS);
        var file =
                java.nio.file.Path.of(
                        "target/conditional-departure-fixtures/provider_backsolve.json");
        java.nio.file.Files.createDirectories(file.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), dto);
    }

    @Test
    void arrivalStrictlyBeforeDeadlineKeepsPlannedDepartureWithoutBuffer() {
        var result = engine(1).evaluate(routes(35, 1799), empty, now, planned, deadline);
        assertThat(result.decision()).isEqualTo(KEEP_DEPARTURE);
        assertThat(result.proposed().departureAt()).isEqualTo(planned);
        assertThat(result.appliedBufferSeconds()).isZero();
        assertThat(result.targetArriveAt()).isNull();
        assertThat(result.search().complete()).isTrue();
    }

    @Test
    void exactDeadlineArrivalActivatesTenMinuteBuffer() {
        var result = engine(1).evaluate(routes(1, 1800), empty, now, planned, deadline);
        assertThat(result.decision()).isEqualTo(ADVANCE_DEPARTURE);
        assertThat(result.proposed().departureAt()).isEqualTo(planned.minusSeconds(600));
        assertThat(result.proposed().estimatedArrivalAt()).isEqualTo(deadline.minusSeconds(600));
    }

    @Test
    void latestDeparturePreservesSecondsAndDoesNotRoundToMinuteGrid() {
        var result = engine(1).evaluate(routes(1, 2641), empty, now, planned, deadline);
        assertThat(result.proposed().departureAt())
                .isEqualTo(Instant.parse("2026-09-18T01:05:59Z"));
    }

    @Test
    void missedBufferReturnsReachableArrivalInsteadOfPastDeparture() {
        var current = planned.minusSeconds(60);
        var result = engine(1).evaluate(routes(1, 1801), empty, current, planned, deadline);
        assertThat(result.decision()).isEqualTo(MARGIN_UNAVAILABLE);
        assertThat(result.proposed().departureAt()).isEqualTo(current);
        assertThat(result.proposed().estimatedArrivalAt()).isEqualTo(deadline.minusSeconds(59));
    }

    @Test
    void allLateCandidatesReturnLeastLateRouteFromNow() {
        var current = planned.minusSeconds(60);
        var result =
                engine(1)
                        .evaluate(
                                List.of(
                                        MlRoutePreviewTest.route(0, "1", 4000, 0, 1),
                                        MlRoutePreviewTest.route(1, "2", 2640, 0, 1)),
                                empty,
                                current,
                                planned,
                                deadline);
        assertThat(result.decision()).isEqualTo(LATE_EXPECTED);
        assertThat(result.proposed().journeyKey()).isEqualTo("candidate-1");
        assertThat(result.proposed().departureAt()).isEqualTo(current);
        assertThat(result.proposed().estimatedArrivalAt()).isEqualTo(current.plusSeconds(2640));
    }

    @Test
    void passedPlannedDepartureUsesNowRatherThanClaimingEarlierSuccess() {
        var current = planned.plusSeconds(60);
        var result = engine(1).evaluate(routes(1, 60), empty, current, planned, deadline);
        assertThat(result.decision()).isEqualTo(DEPART_NOW);
        assertThat(result.departureMissed()).isTrue();
        assertThat(result.proposed().departureAt()).isEqualTo(current);
    }

    @Test
    void confirmedClosureStillExcludesFastestProviderRoute() {
        var json = new ObjectMapper();
        var state =
                json.createObjectNode()
                        .put("status", "blocked")
                        .put("computedAt", now.toString())
                        .put("validUntil", deadline.plusSeconds(3600).toString())
                        .put("eventId", "official-closure");
        var snapshot = new RouteEvidenceAssembler.Snapshot(Map.of("BUS:1:1:2", state), List.of());
        var result =
                engine(1)
                        .evaluate(
                                List.of(
                                        MlRoutePreviewTest.route(0, "1", 60, 0, 1),
                                        MlRoutePreviewTest.route(1, "2", 120, 0, 1)),
                                snapshot,
                                now,
                                planned,
                                deadline);
        assertThat(result.proposed().journeyKey()).isEqualTo("candidate-1");
        assertThat(result.proposedComparison().candidates().get(0).result().assessment())
                .isEqualTo(RouteTimelineEvaluator.Assessment.BLOCKED);
        assertThat(result.proposedComparison().candidates().get(0).result().reasons())
                .contains("CLOSURE:official-closure");
    }

    @Test
    void unavailableTimetableRetainsProviderEstimateAndDiagnostic() {
        var route = routes(1, 60);
        var unavailable =
                new RouteTimelineEvaluator.Result(
                        "candidate-0",
                        planned,
                        null,
                        RouteTimelineEvaluator.Assessment.INSUFFICIENT_DATA,
                        true,
                        List.of(),
                        List.of("step-1:SUBWAY_NO_BOARDABLE_TRAIN"),
                        "fixture");
        var previous =
                new MlRoutePreview.Comparison(
                        "PREVIEW",
                        false,
                        null,
                        List.of(new MlRoutePreview.Candidate(unavailable, "API_ONLY", 0, 1, 0, 0)),
                        List.of(),
                        false,
                        List.of(),
                        List.of());
        var result =
                fixture.service(MlApplyMode.SHADOW)
                        .providerFallback(route, empty, now, planned, deadline, previous, "TEST");
        assertThat(result.proposed().estimatedArrivalAt()).isEqualTo(planned.plusSeconds(60));
        assertThat(result.proposed().reasons()).contains("step-1:SUBWAY_NO_BOARDABLE_TRAIN");
        assertThat(EvaluationGuidance.from(result).canDisplayEstimate()).isTrue();
    }

    @Test
    void trialMlTravelTimeIsRetainedWithoutPromotingModelApproval() {
        when(fixture.client.predict(any())).thenReturn(fixture.sufficient(4000));
        var service =
                new ConditionalDepartureEvaluator(
                        fixture.service(MlApplyMode.TEST_APPLY),
                        mock(SubwayTimetable.class),
                        256,
                        60,
                        5000);
        var result = service.evaluate(routes(1, 600), empty, now, planned, deadline);
        assertThat(result.decision()).isEqualTo(ADVANCE_DEPARTURE);
        assertThat(
                        Duration.between(
                                        result.proposed().departureAt(),
                                        result.proposed().estimatedArrivalAt())
                                .getSeconds())
                .isEqualTo(4000);
        assertThat(result.proposedComparison().predictions())
                .allMatch(MlRoutePreview.Prediction::applied);
        assertThat(result.proposedComparison().productionReady()).isFalse();
        assertThat(EvaluationGuidance.from(result).canDisplayEstimate()).isTrue();
    }

    @Test
    void incompleteDynamicSearchRecoversUsingOriginalProviderDuration() {
        // 승인된 ML 경로라도 동적 탐색 1회 한도를 소진하면 계산 실패 대신 API 기준으로 다시 역산한다.
        when(fixture.client.predictBatch(anyList(), eq(BusPreviewClient.Contract.PRODUCTION)))
                .thenAnswer(
                        call -> {
                            var out =
                                    new LinkedHashMap<
                                            BusPreviewClient.Input, BusPreviewClient.Estimate>();
                            List<BusPreviewClient.Input> inputs = call.getArgument(0);
                            inputs.forEach(i -> out.put(i, fixture.production(4000)));
                            return out;
                        });
        var service =
                new ConditionalDepartureEvaluator(
                        fixture.service(MlApplyMode.PRODUCTION),
                        mock(SubwayTimetable.class),
                        1,
                        60,
                        5000);
        var result = service.evaluate(routes(1, 2640), empty, now, planned, deadline);
        assertThat(result.decision()).isEqualTo(ADVANCE_DEPARTURE);
        assertThat(result.proposed().departureAt()).isEqualTo(now.plusSeconds(3960));
        assertThat(result.search().limitations()).contains("DYNAMIC_SEARCH_PROVIDER_FALLBACK");
        assertThat(EvaluationGuidance.from(result).canAccept()).isTrue();
    }

    @Test
    void noProviderCandidatesDoesNotInventARecommendation() {
        var result = engine(1).evaluate(List.of(), empty, now, planned, deadline);
        assertThat(result.decision()).isEqualTo(ROUTE_UNAVAILABLE);
        assertThat(result.proposed()).isNull();
    }
}
