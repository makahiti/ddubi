package com.ssafy.ddubi.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ssafy.ddubi.domain.recommendation.evaluation.DepartureEstimate;
import com.ssafy.ddubi.domain.recommendation.evaluation.JourneyEvaluationService;
import com.ssafy.ddubi.domain.recommendation.evaluation.MlRoutePreview;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteEvidenceAssembler;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator;
import com.ssafy.ddubi.domain.route.model.RouteCandidate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 출발 사실을 보존하면서 실제 출발 시각의 선택 경로만 재평가하는 경계를 검증한다. */
class DepartureRecalculationServiceTest {
    private final ScheduleEvaluationStore store = mock(ScheduleEvaluationStore.class);
    private final RouteEvidenceAssembler evidence = mock(RouteEvidenceAssembler.class);
    private final JourneyEvaluationService journeys = mock(JourneyEvaluationService.class);
    private final DepartureRecalculationService service =
            new DepartureRecalculationService(store, evidence, journeys);
    private final Instant departedAt = Instant.parse("2026-09-23T22:51:00Z");
    private final Instant arriveBy = departedAt.plusSeconds(3600);
    private final RouteCandidate route = mock(RouteCandidate.class);

    @Test
    void evaluatesOnlySelectedRouteAtActualDepartureAndStoresItsEta() {
        // 계획 출발시각을 받아 계산하지 않고 출발 확인으로 저장된 실제 시각을
        // asOf와 출발 입력 양쪽에 사용해야 버스·지하철 대기가 다시 선택된다.
        when(store.stop(7, 9))
                .thenReturn(
                        new ScheduleEvaluationStore.DepartureContext(
                                departedAt,
                                arriveBy,
                                route,
                                "selected",
                                "evaluation-1",
                                true,
                                false));
        var snapshot = new RouteEvidenceAssembler.Snapshot(java.util.Map.of(), List.of());
        when(evidence.read(List.of(route), departedAt)).thenReturn(snapshot);
        var session = mock(JourneyEvaluationService.Session.class);
        when(journeys.newSession(10_000)).thenReturn(session);
        var result =
                new RouteTimelineEvaluator.Result(
                        "selected",
                        departedAt,
                        departedAt.plusSeconds(2400),
                        RouteTimelineEvaluator.Assessment.ON_TIME_ESTIMATE,
                        false,
                        List.of(),
                        List.of(),
                        "v1");
        var comparison =
                new MlRoutePreview.Comparison(
                        "PREVIEW",
                        false,
                        result,
                        List.of(new MlRoutePreview.Candidate(result, "API_ONLY", 0, 1, 0, 0)),
                        List.of(),
                        false,
                        List.of(),
                        List.of());
        when(journeys.compare(
                        eq(List.of(route)),
                        eq(snapshot),
                        eq(departedAt),
                        eq(departedAt),
                        eq(arriveBy),
                        eq(session)))
                .thenReturn(comparison);

        var estimate = service.depart(7, 9);

        assertThat(estimate.estimatedArrivalAt()).isEqualTo(departedAt.plusSeconds(2400));
        assertThat(estimate.durationSeconds()).isEqualTo(2400);
        verify(journeys)
                .compare(
                        eq(List.of(route)),
                        eq(snapshot),
                        eq(departedAt),
                        eq(departedAt),
                        eq(arriveBy),
                        eq(session));
        verify(store).saveDepartureEstimate(7, 9, "evaluation-1", estimate);
    }

    @Test
    void dependencyFailureKeepsDepartureButDoesNotInventEta() {
        when(store.stop(7, 9))
                .thenReturn(
                        new ScheduleEvaluationStore.DepartureContext(
                                departedAt,
                                arriveBy,
                                route,
                                "selected",
                                "evaluation-1",
                                true,
                                false));
        when(evidence.read(List.of(route), departedAt))
                .thenThrow(new IllegalStateException("offline"));

        var estimate = service.depart(7, 9);

        assertThat(estimate.status()).isEqualTo("UNAVAILABLE");
        assertThat(estimate.estimatedArrivalAt()).isNull();
        ArgumentCaptor<DepartureEstimate> saved = ArgumentCaptor.forClass(DepartureEstimate.class);
        verify(store).saveDepartureEstimate(eq(7L), eq(9L), eq("evaluation-1"), saved.capture());
        assertThat(saved.getValue().estimatedArrivalAt()).isNull();
    }

    @Test
    void repeatedClickDoesNotRecalculateHistoricalDeparture() {
        when(store.stop(7, 9))
                .thenReturn(
                        new ScheduleEvaluationStore.DepartureContext(
                                departedAt,
                                arriveBy,
                                route,
                                "selected",
                                "evaluation-1",
                                false,
                                false));

        assertThat(service.depart(7, 9)).isNull();
        verifyNoInteractions(evidence, journeys);
    }
}
