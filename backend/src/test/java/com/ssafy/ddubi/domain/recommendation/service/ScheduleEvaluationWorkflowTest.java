package com.ssafy.ddubi.domain.recommendation.service;

import static com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import com.ssafy.ddubi.domain.recommendation.evaluation.*;
import com.ssafy.ddubi.domain.route.client.*;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.*;
import com.ssafy.ddubi.domain.route.service.RouteMappingRefresher;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 외부 API·DB를 mock 경계로 고정하고 실제 Workflow의 캐시 전환·재사용 분기를 검증한다. 마스터 보강이나 새 통제가 저장 후보를 버리고 외부 경로를 다시
 * 호출하는 운영 회귀를 방어한다. DB 트랜잭션 자체는 별도의 ScheduleEvaluationIntegrationTest가 담당한다.
 */
class ScheduleEvaluationWorkflowTest {
    final Instant now = Instant.parse("2026-09-17T00:00:00Z");
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final ScheduleEvaluationStore store = mock(ScheduleEvaluationStore.class);
    final PublicTransitRouteClient routes = mock(PublicTransitRouteClient.class);
    final RouteMappingRefresher remapper = mock(RouteMappingRefresher.class);
    final RouteEvidenceAssembler evidence = mock(RouteEvidenceAssembler.class);
    final ConditionalDepartureEvaluator conditional = mock(ConditionalDepartureEvaluator.class);
    final EvaluationNotificationPlanner notifications = mock(EvaluationNotificationPlanner.class);
    final RouteEvidenceAssembler.Snapshot snapshot =
            new RouteEvidenceAssembler.Snapshot(Map.of(), List.of());
    final ScheduleEvaluationWorkflow workflow =
            new ScheduleEvaluationWorkflow(
                    store,
                    routes,
                    evidence,
                    conditional,
                    notifications,
                    new WalkingEnrichmentService(
                            mock(WalkingRouteClient.class), json, false, 0, 0, ""),
                    remapper);

    /** 원본 차량과 좌표는 같고 공식 occurrence/매핑 버전만 바꿀 수 있는 단일 버스 후보다. */
    RouteCandidate candidate(int index, String master, String routeId, int order) {
        TransitMappingResult mapping = TransitMappingResult.masterDataNotConfigured();
        if (master != null) {
            var edge =
                    new MappedTransitSegment(
                            1,
                            "BUS:" + routeId + ":10:11",
                            TransportMode.BUS,
                            routeId,
                            routeId,
                            "100",
                            "SEQUENCE_FORWARD",
                            "10",
                            "11",
                            "10",
                            "11",
                            order,
                            order + 1);
            mapping =
                    new TransitMappingResult(
                            TransitMappingStatus.EXACT,
                            master,
                            "policy",
                            routeId,
                            "100",
                            "10",
                            "11",
                            order,
                            order + 1,
                            "SEQUENCE_FORWARD",
                            1,
                            1,
                            1,
                            null,
                            null,
                            List.of(edge));
        }
        var step =
                new RouteSegment(
                        1,
                        TransportMode.BUS,
                        mapping.segments().isEmpty() ? null : mapping.segments().get(0).segmentId(),
                        "100",
                        null,
                        "출발",
                        "도착",
                        400,
                        100,
                        List.of("출발", "도착"),
                        List.of(new VehicleInfo("100", "BUS")),
                        List.of(),
                        mapping);
        return new RouteCandidate(index, RouteType.BUS, 400, 100, 0, null, 0, 0, List.of(step));
    }

    RouteSearchResult search(Instant fetchedAt, RouteCandidate... candidates) {
        return new RouteSearchResult("KAKAO", fetchedAt, null, List.of(candidates));
    }

    Result result(RouteCandidate candidate, Assessment assessment) {
        return new Result(
                RouteEvidenceAssembler.key(candidate),
                now.plusSeconds(600),
                assessment == Assessment.BLOCKED
                        ? null
                        : now.plusSeconds(assessment == Assessment.LATE_ESTIMATE ? 1900 : 700),
                assessment,
                true,
                List.of(),
                assessment == Assessment.BLOCKED ? List.of("ROUTE_BLOCKED") : List.of(),
                "cdep-v1");
    }

    /** 원본 후보의 정상→위험 전이를 고정한다. 시간 계산 정확도가 아니라 조회/저장 조건을 검증한다. */
    ScheduleEvaluationStore.Job prepare(
            RouteSearchResult cached,
            RouteSearchResult remapped,
            Assessment current,
            Assessment previous) {
        var selected = cached.candidates().get(0);
        var walking =
                new WalkingEnrichmentService.Evidence(List.of(), List.of(), 0, null, Map.of());
        var cache =
                new ScheduleEvaluationStore.Cache(
                        cached,
                        Map.of(RouteEvidenceAssembler.key(selected), 10L),
                        RouteEvidenceAssembler.key(selected),
                        selected,
                        walking);
        var job =
                new ScheduleEvaluationStore.Job(
                        1,
                        2,
                        LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                        now.plusSeconds(600),
                        now.plusSeconds(1800),
                        new Coordinate(127, 37.5),
                        new Coordinate(127.01, 37.5),
                        "lease",
                        0,
                        cache,
                        "previous",
                        null,
                        1);
        when(store.claim(1L, 2L, now)).thenReturn(job);
        when(store.write(any())).thenAnswer(call -> json.writeValueAsString(call.getArgument(0)));
        when(store.read("previous", ScheduleEvaluationWorkflow.Outcome.class))
                .thenReturn(
                        new ScheduleEvaluationWorkflow.Outcome(
                                "old",
                                1,
                                null,
                                job.departure(),
                                job.arriveBy(),
                                now.minusSeconds(30),
                                cache.selectedKey(),
                                result(selected, previous),
                                null,
                                List.of(),
                                snapshot,
                                TriggerType.INITIAL,
                                "cdep-v1",
                                0,
                                null,
                                null));
        when(remapper.refresh(cached)).thenReturn(remapped);
        when(evidence.read(anyList(), eq(now))).thenReturn(snapshot);
        when(conditional.evaluate(
                        anyList(),
                        eq(snapshot),
                        eq(now),
                        eq(job.departure()),
                        eq(job.arriveBy()),
                        anyLong()))
                .thenAnswer(
                        call -> {
                            List<RouteCandidate> candidates = call.getArgument(0);
                            var results =
                                    candidates.stream()
                                            .map(
                                                    c ->
                                                            new MlRoutePreview.Candidate(
                                                                    result(c, current),
                                                                    "API_ONLY",
                                                                    0,
                                                                    1,
                                                                    0,
                                                                    0))
                                            .toList();
                            var chosen = results.get(0).result();
                            var comparison =
                                    new MlRoutePreview.Comparison(
                                            "PREVIEW", false, chosen, results, List.of(), false,
                                            List.of(), List.of());
                            return new ConditionalDepartureEvaluator.Evaluation(
                                    ConditionalDeparturePolicy.Decision.KEEP_DEPARTURE,
                                    null,
                                    job.arriveBy(),
                                    0,
                                    false,
                                    chosen,
                                    chosen,
                                    comparison,
                                    comparison,
                                    new ConditionalDepartureEvaluator.Search(
                                            "FIXTURE",
                                            results.size(),
                                            true,
                                            60,
                                            256,
                                            5000,
                                            List.of()),
                                    null);
                        });
        when(notifications.plan(eq(job), any())).thenReturn(Optional.empty());
        when(store.complete(eq(job), any(), anyBoolean(), any(), any(), isNull(), isNull(), any()))
                .thenAnswer(call -> call.getArgument(3));
        return job;
    }

    @Test
    void mappingEnrichmentRetainsTheOnlySameProviderSelectionAndSavesNewEdges() {
        var old = search(now.minusSeconds(30), candidate(7, null, "100", 1));
        var mapped = search(old.fetchedAt(), candidate(7, "new", "100", 1));
        var job = prepare(old, mapped, Assessment.ON_TIME_ESTIMATE, Assessment.ON_TIME_ESTIMATE);
        var outcome = workflow.evaluate(1, 2L, TriggerType.REFRESH, now);
        assertThat(outcome.selectedKey()).isEqualTo("candidate-7");
        assertThat(outcome.selected().assessment()).isEqualTo(Assessment.ON_TIME_ESTIMATE);
        verify(routes, never()).search(any());
        verify(store)
                .complete(eq(job), eq(mapped), eq(true), any(), any(), isNull(), isNull(), any());
    }

    @ParameterizedTest
    @EnumSource(
            value = Assessment.class,
            names = {"BLOCKED", "LATE_ESTIMATE"})
    void newRiskUsesStoredCandidatesWithoutCallingProvider(Assessment risk) {
        var old = search(now.minusSeconds(30), candidate(7, "old", "100", 1));
        var mapped = search(old.fetchedAt(), candidate(7, "new", "100", 1));
        var job = prepare(old, mapped, risk, Assessment.ON_TIME_ESTIMATE);
        workflow.evaluate(1, 2L, TriggerType.REFRESH, now);
        verify(routes, never()).search(any());
        verify(conditional, times(1))
                .evaluate(
                        anyList(),
                        eq(snapshot),
                        eq(now),
                        eq(job.departure()),
                        eq(job.arriveBy()),
                        anyLong());
        verify(store)
                .complete(eq(job), eq(mapped), eq(true), any(), any(), isNull(), isNull(), any());
    }

    @Test
    void computeBudgetFollowsTriggerAndCaller() {
        var old = search(now.minusSeconds(30), candidate(7, "old", "100", 1));
        var job = prepare(old, old, Assessment.ON_TIME_ESTIMATE, Assessment.ON_TIME_ESTIMATE);
        when(store.claim(eq(1L), isNull(), eq(now))).thenReturn(job);
        workflow.evaluate(1, 2L, TriggerType.INITIAL, now); // 최초 등록: 넉넉한 예산
        verify(conditional)
                .evaluate(
                        anyList(),
                        eq(snapshot),
                        eq(now),
                        eq(job.departure()),
                        eq(job.arriveBy()),
                        eq(30000L));
        workflow.evaluate(1, 2L, TriggerType.REFRESH, now); // 사용자 수동 갱신: 짧은 예산
        verify(conditional)
                .evaluate(
                        anyList(),
                        eq(snapshot),
                        eq(now),
                        eq(job.departure()),
                        eq(job.arriveBy()),
                        eq(10000L));
        workflow.evaluate(1, null, TriggerType.REFRESH, now); // 서버 자동 갱신
        verify(conditional)
                .evaluate(
                        anyList(),
                        eq(snapshot),
                        eq(now),
                        eq(job.departure()),
                        eq(job.arriveBy()),
                        eq(15000L));
        assertThat(ScheduleEvaluationWorkflow.Budget.DEFAULT.forTrigger(TriggerType.EVENT, null))
                .isEqualTo(15000L);
    }

    @Test
    void unchangedCandidatesDoNotCreateAnotherStoredRouteSet() {
        var old = search(now.minusSeconds(30), candidate(7, "old", "100", 1));
        var job = prepare(old, old, Assessment.ON_TIME_ESTIMATE, Assessment.ON_TIME_ESTIMATE);
        workflow.evaluate(1, 2L, TriggerType.REFRESH, now);
        verify(routes, never()).search(any());
        verify(store)
                .complete(eq(job), eq(old), eq(false), any(), any(), isNull(), isNull(), any());
    }

    @Test
    void cachedWalkingEvidencePreventsAnotherWalkingApiCall() {
        var old = search(now.minus(Duration.ofDays(1)), candidate(7, "old", "100", 1));
        prepare(old, old, Assessment.ON_TIME_ESTIMATE, Assessment.ON_TIME_ESTIMATE);
        var walking = mock(WalkingEnrichmentService.class);
        var cachedWorkflow =
                new ScheduleEvaluationWorkflow(
                        store, routes, evidence, conditional, notifications, walking, remapper);

        cachedWorkflow.evaluate(1, 2L, TriggerType.REFRESH, now);

        verifyNoInteractions(walking);
        verify(routes, never()).search(any());
    }

    @Test
    void knownClosureDoesNotRepeatedlyCallProviderAfterMappingChange() {
        var old = search(now.minusSeconds(30), candidate(7, "old", "100", 1));
        var mapped = search(old.fetchedAt(), candidate(7, "new", "100", 1));
        prepare(old, mapped, Assessment.BLOCKED, Assessment.BLOCKED);
        workflow.evaluate(1, 2L, TriggerType.REFRESH, now);
        verify(routes, never()).search(any());
    }

    @Test
    void oldCandidateTimestampNeverForcesProviderRefresh() {
        var old = search(now.minusSeconds(901), candidate(7, "old", "100", 1));
        var remapped = search(old.fetchedAt(), candidate(7, "new", "100", 1));
        prepare(old, remapped, Assessment.BLOCKED, Assessment.ON_TIME_ESTIMATE);
        workflow.evaluate(1, 2L, TriggerType.REFRESH, now);
        verify(routes, never()).search(any());
        verify(remapper).refresh(old);
    }

    @Test
    void ambiguousEnrichmentDoesNotArbitrarilySelectOneBus() {
        var old = search(now.minusSeconds(30), candidate(7, null, "100", 1));
        var mapped =
                search(
                        old.fetchedAt(),
                        candidate(7, "new", "100", 1),
                        candidate(8, "new", "200", 1));
        prepare(old, mapped, Assessment.ON_TIME_ESTIMATE, Assessment.BLOCKED);
        assertThat(workflow.evaluate(1, 2L, TriggerType.REFRESH, now).selectedKey())
                .isEqualTo("previous-unavailable");
    }

    @Test
    void previouslyVerifiedOccurrenceCannotMoveToAnotherLoopPosition() {
        var old = search(now.minusSeconds(30), candidate(7, "old", "100", 1));
        var mapped = search(old.fetchedAt(), candidate(7, "new", "100", 11));
        prepare(old, mapped, Assessment.ON_TIME_ESTIMATE, Assessment.BLOCKED);
        assertThat(workflow.evaluate(1, 2L, TriggerType.REFRESH, now).selectedKey())
                .isEqualTo("previous-unavailable");
    }

    @Test
    void rejectedMappingCannotErasePreviouslyVerifiedPathIdentity() {
        var old = search(now.minusSeconds(30), candidate(7, "old", "100", 1));
        var mapped = search(old.fetchedAt(), candidate(7, null, "100", 1));
        prepare(old, mapped, Assessment.ON_TIME_ESTIMATE, Assessment.BLOCKED);
        assertThat(workflow.evaluate(1, 2L, TriggerType.REFRESH, now).selectedKey())
                .isEqualTo("previous-unavailable");
    }
}
