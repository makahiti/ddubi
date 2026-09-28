package com.ssafy.ddubi.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import com.ssafy.ddubi.domain.recommendation.evaluation.DepartureSuggestionService;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteEvidenceAssembler;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.Assessment;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.Result;
import com.ssafy.ddubi.domain.route.model.Coordinate;
import com.ssafy.ddubi.domain.schedule.entity.Importance;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 지금 경로가 통제로 막혔을 때의 알림. 막힌 경로에는 도착 예정이 없어 예전에는 "도착 시각을 계산하지 못했어요" 가 나갔는데, 다른 경로는 이미 계산돼
 * 있었다(2026-09-26 제보). 막혔다고 말하고 다른 경로로 언제 닿는지를 알린다.
 */
class EvaluationNotificationPlannerBlockedTest {
    private final Instant now = Instant.parse("2026-09-26T05:30:00Z"); // 14:30 KST
    private final Instant arriveBy = Instant.parse("2026-09-26T07:30:00Z"); // 16:30 KST
    private final EvaluationNotificationPlanner planner =
            new EvaluationNotificationPlanner(true, Duration.ofMinutes(5), true);

    @Test
    void blockedRouteWithOnTimeAlternativeSaysBlockedAndWhenTheOtherRouteArrives() {
        var planned =
                planner.plan(
                                job(),
                                outcome(
                                        result(
                                                "other",
                                                "2026-09-26T06:55:00Z",
                                                Assessment.ON_TIME_ESTIMATE)))
                        .orElseThrow();

        assertThat(planned.command().title()).isEqualTo("지금 경로가 막혔어요");
        assertThat(planned.command().body()).isEqualTo("다른 경로로 가면 15:55 도착 예상 · 목표 16:30");
        assertThat(planned.command().body()).doesNotContain("계산하지 못했어요");
    }

    @Test
    void blockedRouteWhoseAlternativeIsAlsoLateStillWarnsAboutLateness() {
        var planned =
                planner.plan(
                                job(),
                                outcome(
                                        result(
                                                "other",
                                                "2026-09-26T07:40:00Z",
                                                Assessment.LATE_ESTIMATE)))
                        .orElseThrow();

        assertThat(planned.command().title()).isEqualTo("제시간 도착이 어려울 수 있어요");
        assertThat(planned.command().body()).startsWith("지금 경로가 막혔어요");
    }

    @Test
    void blockedRouteWithoutAnyAlternativeAsksToCheckTheApp() {
        var planned = planner.plan(job(), outcome(null)).orElseThrow();

        assertThat(planned.command().body()).isEqualTo("지금 경로가 막혔어요 · 앱에서 다른 경로를 확인해 주세요");
    }

    private ScheduleEvaluationStore.Job job() {
        return new ScheduleEvaluationStore.Job(
                7L,
                3L,
                LocalDateTime.of(2026, 9, 26, 14, 0),
                Instant.parse("2026-09-26T06:30:00Z"),
                arriveBy,
                new Coordinate(127.02, 37.52),
                new Coordinate(127.04, 37.50),
                "token",
                0,
                null,
                null,
                Importance.values()[0],
                1);
    }

    private ScheduleEvaluationWorkflow.Outcome outcome(Result proposed) {
        Result blocked = result("selected", null, Assessment.BLOCKED);
        return new ScheduleEvaluationWorkflow.Outcome(
                "evaluation-1",
                7L,
                null,
                Instant.parse("2026-09-26T06:30:00Z"),
                arriveBy,
                now,
                "selected",
                blocked,
                new DepartureSuggestionService.Suggestion(
                        proposed == null ? List.of(blocked) : List.of(blocked, proposed),
                        proposed,
                        proposed,
                        2,
                        false),
                List.of(),
                new RouteEvidenceAssembler.Snapshot(Map.of(), List.of()),
                TriggerType.EVENT,
                "test",
                0,
                null,
                null,
                null,
                null);
    }

    private Result result(String key, String arrival, Assessment assessment) {
        return new Result(
                key,
                Instant.parse("2026-09-26T06:30:00Z"),
                arrival == null ? null : Instant.parse(arrival),
                assessment,
                false,
                List.of(),
                List.of(),
                "test");
    }
}
