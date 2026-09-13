package com.ssafy.ddubi.domain.recommendation.evaluation;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.ssafy.ddubi.domain.recommendation.evaluation.JourneyEvidence.*;
import static com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.*;

/** 손계산 가능한 배차/환승·자정·통제 fixture로 미래 탑승 확정, 이중 합산, 과거 출발 안내를 방어한다. */
class RouteTimelineEvaluatorTest {
    final Instant now = Instant.parse("2026-09-10T00:00:00Z");
    final RouteTimelineEvaluator evaluator = new RouteTimelineEvaluator();
    Window window() { return new Window("FIXTURE", now, now, now.plusSeconds(7200)); }
    Leg walk(long seconds) { return new Leg("walk", Kind.WALK, List.of(), seconds, seconds, window(), List.of()); }
    Leg ride() { return new Leg("ride", Kind.RIDE, List.of("BUS:1:2:3"), 600L, 600L, window(), List.of()); }
    Leg waitFor(List<Boarding> buses) { return new Leg("wait", Kind.WAIT, List.of(), null, null, window(), buses); }
    Boarding bus(String id, long seconds) { return new Boarding(id, now.plusSeconds(seconds), now.plusSeconds(seconds), true); }

    @Test void missedBusUsesNextVehicleAndKeepsTransferSequence() {
        var journey = new Journey("r1", List.of(walk(420), waitFor(List.of(bus("first", 360), bus("next", 900))), ride()));
        var result = evaluator.evaluate(journey, now, now, now.plusSeconds(1800), List.of());
        // 7분 걷고 6분 차량을 놓침 → 15분 차량까지 8분 대기 → 10분 이동 = 25분.
        assertThat(result.estimatedArrivalAt()).isEqualTo(now.plusSeconds(1500));
        assertThat(result.steps().get(1).vehicleId()).isEqualTo("next");
        assertThat(result.assessment()).isEqualTo(Assessment.ON_TIME_ESTIMATE);
    }

    @Test void overlappingArrivalRangeDoesNotGuaranteeBoarding() {
        var uncertain = new Boarding("uncertain", now.plusSeconds(360), now.plusSeconds(480), true);
        var journey = new Journey("r", List.of(walk(420), waitFor(List.of(uncertain, bus("next", 900))), ride()));
        var result = evaluator.evaluate(journey, now, now, now.plusSeconds(1800), List.of());
        assertThat(result.tentative()).isTrue();
        assertThat(result.steps().get(1).vehicleId()).isEqualTo("next");
        assertThat(result.reasons()).anyMatch(r -> r.contains("BOARDING_UNCERTAIN"));
    }

    @Test void noNextBusIsInsufficientRatherThanZeroWait() {
        var journey = new Journey("r", List.of(walk(420), waitFor(List.of(bus("gone", 360))), ride()));
        var result = evaluator.evaluate(journey, now, now, now.plusSeconds(1800), List.of());
        assertThat(result.assessment()).isEqualTo(Assessment.INSUFFICIENT_DATA);
        assertThat(result.estimatedArrivalAt()).isNull();
    }

    @Test void futureKnowledgeAndExpiredForecastFallBackExplicitly() {
        for (Window window : List.of(new Window("future", now.plusSeconds(1), now, now.plusSeconds(60)),
                new Window("expired", now.minusSeconds(100), now.minusSeconds(100), now))) {
            var journey = new Journey("r", List.of(new Leg("ride", Kind.RIDE, List.of(), 600L, 10L, window, List.of())));
            var result = evaluator.evaluate(journey, now, now, now.plusSeconds(500), List.of());
            assertThat(result.estimatedArrivalAt()).isEqualTo(now.plusSeconds(600));
            assertThat(result.tentative()).isTrue();
            assertThat(result.assessment()).isEqualTo(Assessment.LATE_ESTIMATE);
        }
    }

    @Test void closureNeedsMatchingSegmentAndOverlappingTraversal() {
        var journey = new Journey("r", List.of(ride()));
        var closure = new Closure("incident", List.of("BUS:1:2:3"), new Window("official", now, now.plusSeconds(300), now.plusSeconds(900)));
        assertThat(evaluator.evaluate(journey, now, now, now.plusSeconds(1000), List.of(closure)).assessment()).isEqualTo(Assessment.BLOCKED);
        assertThat(evaluator.evaluate(journey, now, now.plusSeconds(900), now.plusSeconds(2000), List.of(closure)).assessment())
                .isEqualTo(Assessment.ON_TIME_ESTIMATE);
        var other = new Closure("other", List.of("BUS:9:8:7"), closure.window());
        assertThat(evaluator.evaluate(journey, now, now, now.plusSeconds(1000), List.of(other)).assessment()).isEqualTo(Assessment.ON_TIME_ESTIMATE);
    }

    @Test void providerTotalCannotBeCombinedWithWaitingAgain() {
        var total = new Leg("total", Kind.PROVIDER_TOTAL, List.of(), 1500L, null, null, List.of());
        assertThatThrownBy(() -> new Journey("r", List.of(total, waitFor(List.of())))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void crossesMidnightAndReplaysExactly() {
        Instant late = Instant.parse("2026-09-10T14:59:00Z"); // 한국 23:59
        var journey = new Journey("r", List.of(new Leg("walk", Kind.WALK, List.of(), 120L, null, null, List.of())));
        var result = evaluator.evaluate(journey, late, late, late.plusSeconds(120), List.of());
        assertThat(result.estimatedArrivalAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate()).isEqualTo(LocalDate.of(2026,9,11));
        assertThat(evaluator.evaluate(journey, late, late, late.plusSeconds(120), List.of())).isEqualTo(result);
        assertThatThrownBy(() -> evaluator.evaluate(journey, late, late.minusSeconds(1), late, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void earlierDepartureReevaluatesBoardingInsteadOfSubtractingLateness() {
        var service = new DepartureSuggestionService(evaluator);
        var journey = new Journey("r", List.of(walk(120), waitFor(List.of(bus("early", 300), bus("late", 900))), ride()));
        var result = service.suggest(List.of(journey), now, now.plusSeconds(240), now.plusSeconds(1200), now,
                List.of(), new DepartureSuggestionService.SearchPolicy(60, 600, 100));
        // 09:04 출발 → 앞차 놓침 → 09:25 도착. 09:03 출발이면 앞차 → 09:15 도착.
        assertThat(result.originalCandidates().get(0).estimatedArrivalAt()).isEqualTo(now.plusSeconds(1500));
        assertThat(result.proposed().departureAt()).isEqualTo(now.plusSeconds(180));
        assertThat(result.proposed().estimatedArrivalAt()).isEqualTo(now.plusSeconds(900));
    }

    @Test void searchBudgetAndEarliestDepartureAreRespected() {
        var service = new DepartureSuggestionService(evaluator);
        var journey = new Journey("r", List.of(ride()));
        var result = service.suggest(List.of(journey), now, now.plusSeconds(300), now.plusSeconds(100), now,
                List.of(), new DepartureSuggestionService.SearchPolicy(60, 600, 2));
        assertThat(result.budgetExhausted()).isTrue();
        assertThat(result.evaluatedCount()).isEqualTo(2);
        assertThat(result.proposed()).isNull();
        assertThat(result.earliestArrival().departureAt()).isAfterOrEqualTo(now);
    }
}
