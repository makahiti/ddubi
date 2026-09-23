package com.ssafy.ddubi.domain.recommendation.evaluation;

import static com.ssafy.ddubi.domain.recommendation.evaluation.ConditionalDeparturePolicy.*;
import static com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.*;

import com.ssafy.ddubi.domain.route.model.RouteCandidate;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * P 평가 후 필요한 경우에만 D-10분 목표를 탐색한다. 모든 시각은 초 단위 Instant다. 고정 API 시간은 직접 역산하고, 시간에 따라 달라지는 유효 증거는 한
 * 세션의 제한 격자로 평가한다. 동적 탐색이 완료되지 않으면 원본 구간 시간으로 복귀한다. 시험 등급만으로 계산값을 폐기하지 않는다. 실제 정시 도착 보장은 아니다.
 */
@Service
public class ConditionalDepartureEvaluator {
    private final JourneyEvaluationService journeys;
    private final SubwayTimetable timetable;
    private final int maxEvaluations, gridSeconds, maxMillis;

    public ConditionalDepartureEvaluator(
            JourneyEvaluationService journeys,
            SubwayTimetable timetable,
            @Value("${ddubi.recommendation.search-max-evaluations:256}") int maxEvaluations,
            @Value("${ddubi.recommendation.search-grid-seconds:60}") int gridSeconds,
            @Value("${ddubi.recommendation.search-max-millis:5000}") int maxMillis) {
        if (maxEvaluations < 1
                || maxEvaluations > 10000
                || gridSeconds < 1
                || maxMillis < 1
                || maxMillis > 60000) throw new IllegalArgumentException("탐색 예산 범위 오류");
        this.journeys = journeys;
        this.timetable = timetable;
        this.maxEvaluations = maxEvaluations;
        this.gridSeconds = gridSeconds;
        this.maxMillis = maxMillis;
    }

    /** complete는 명시한 격자/경계 후보 탐색 완료이며 연속시간 최적성의 증명이 아니다. */
    public record Search(
            String domain,
            int evaluatedCount,
            boolean complete,
            int gridSeconds,
            int maxEvaluations,
            int maxComputeMillis,
            List<String> limitations) {}

    /** original은 P 비교의 불변 결과, proposedComparison은 실제 제안 시각의 근거다. */
    public record Evaluation(
            Decision decision,
            Decision bestKnownDecision,
            Instant targetArriveAt,
            int appliedBufferSeconds,
            boolean departureMissed,
            Result original,
            Result proposed,
            MlRoutePreview.Comparison originalComparison,
            MlRoutePreview.Comparison proposedComparison,
            Search search,
            com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService.Evidence walking) {
        public Evaluation withWalking(
                com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService.Evidence value) {
            return new Evaluation(
                    decision,
                    bestKnownDecision,
                    targetArriveAt,
                    appliedBufferSeconds,
                    departureMissed,
                    original,
                    proposed,
                    originalComparison,
                    proposedComparison,
                    search,
                    value);
        }

        public DepartureSuggestionService.Suggestion suggestion() {
            return new DepartureSuggestionService.Suggestion(
                    originalComparison.candidates().stream()
                            .map(MlRoutePreview.Candidate::result)
                            .toList(),
                    proposed,
                    proposed,
                    search.evaluatedCount(),
                    !search.complete());
        }
    }

    /** 원천은 평가 시작 시 고정하고 이후 출발 후보에서는 API 경로 재검색/증거 재수집을 하지 않는다. */
    public Evaluation evaluate(
            List<RouteCandidate> candidates,
            RouteEvidenceAssembler.Snapshot snapshot,
            Instant asOf,
            Instant planned,
            Instant deadline) {
        return evaluate(candidates, snapshot, asOf, planned, deadline, maxMillis);
    }

    /** 트리거별 계산 예산(ms)을 받는다. 최초 등록·자동 갱신·수동 갱신이 다른 예산을 쓴다. 범위는 1~60000ms. */
    public Evaluation evaluate(
            List<RouteCandidate> candidates,
            RouteEvidenceAssembler.Snapshot snapshot,
            Instant asOf,
            Instant planned,
            Instant deadline,
            long budgetMillis) {
        if (budgetMillis < 1 || budgetMillis > 60000)
            throw new IllegalArgumentException("계산 예산 범위 오류");
        var session = journeys.newSession(budgetMillis);
        Instant effective = planned.isBefore(asOf) ? asOf : planned;
        var initial = journeys.compare(candidates, snapshot, asOf, effective, deadline, session);
        // 고정 API 예상은 시간별 탐색 대상이 아니다. 후보 수×분 단위 예산 때문에
        // 계산 가능한 출발을 놓치지 않도록 바로 역산한다. 적용 모드가 허용한 ML 결과는 동적 평가에 유지한다.
        boolean providerOnly =
                initial.candidates().stream()
                        .allMatch(
                                c ->
                                        c.result().steps().stream()
                                                .allMatch(
                                                        step -> "FALLBACK".equals(step.source())));
        if (providerOnly)
            return journeys.providerFallback(
                    candidates,
                    snapshot,
                    asOf,
                    planned,
                    deadline,
                    initial,
                    "FIXED_PROVIDER_BACKSOLVE");
        var boundaries = new TreeSet<Instant>();
        // 운행표 경계 외에도 시간대·통제 적용 경계를 포함한다. 접근시간 때문에 경계만으로
        // 최적 출발을 증명할 수 없으므로 제한 격자라는 한계를 응답에 유지한다.
        if (timetable.data() != null)
            candidates.stream()
                    .flatMap(r -> r.segments().stream())
                    .filter(
                            s ->
                                    s.mode()
                                            == com.ssafy.ddubi.domain.route.model.TransportMode
                                                    .SUBWAY)
                    .map(RouteEvidenceAssembler::edges)
                    .filter(edges -> !edges.isEmpty())
                    .map(edges -> edges.get(0))
                    .flatMap(
                            edge ->
                                    timetable
                                            .tripsFor(
                                                    edge.routeId(),
                                                    edge.direction(),
                                                    edge.fromNodeId())
                                            .stream())
                    .distinct()
                    .forEach(
                            t ->
                                    t.calls()
                                            .forEach(
                                                    c -> {
                                                        if (c.departureAt() != null
                                                                && !c.departureAt().isBefore(asOf)
                                                                && !c.departureAt()
                                                                        .isAfter(planned))
                                                            boundaries.add(c.departureAt());
                                                    }));
        for (var row : snapshot.states().values())
            for (String field : List.of("computedAt", "validUntil")) {
                try {
                    boundaries.add(Instant.parse(row.path(field).asText()));
                } catch (RuntimeException ignored) {
                    /* 선택 필드 결측 */
                }
            }
        // 서울 시간대도 정각 경계가 UTC 정각과 일치한다. 시간대별 ML 입력 변경을 격자 사이에서 누락하지 않는다.
        Instant upper =
                planned.isBefore(deadline.minusSeconds(600)) ? planned : deadline.minusSeconds(600);
        Instant hour = upper.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        for (int i = 0;
                i <= maxEvaluations && !hour.isBefore(asOf);
                i++, hour = hour.minusSeconds(3600)) boundaries.add(hour);
        var evaluated =
                evaluate(
                        candidates.size(),
                        asOf,
                        planned,
                        deadline,
                        boundaries,
                        at ->
                                at.equals(effective)
                                        ? initial
                                        : journeys.compare(
                                                candidates, snapshot, asOf, at, deadline, session),
                        budgetMillis);
        // 탐색 도중 모델이 교체되면 이전 출발 후보의 숫자도 비교 근거를 잃는다.
        // 마지막 batch만 무효화하면 앞 batch의 ETA가 남으므로 원본 시간으로 평가 전체를 다시 만든다.
        if ((session.mixedModel && journeys.applyMode() != MlApplyMode.SHADOW)
                || !evaluated.search().complete()
                || evaluated.proposed() == null)
            return journeys.providerFallback(
                    candidates,
                    snapshot,
                    asOf,
                    planned,
                    deadline,
                    initial,
                    session.mixedModel
                            ? "ML_MODEL_VERSION_MISMATCH_PROVIDER_FALLBACK"
                            : "DYNAMIC_SEARCH_PROVIDER_FALLBACK");
        return evaluated;
    }

    /** 고정시각 fixture와 Replay를 위한 함수 경계. 전 시각의 duration을 빼는 역산은 사용하지 않는다. */
    public Evaluation evaluate(
            int candidateCount,
            Instant asOf,
            Instant planned,
            Instant deadline,
            Collection<Instant> boundaries,
            Function<Instant, MlRoutePreview.Comparison> forward) {
        return evaluate(candidateCount, asOf, planned, deadline, boundaries, forward, maxMillis);
    }

    public Evaluation evaluate(
            int candidateCount,
            Instant asOf,
            Instant planned,
            Instant deadline,
            Collection<Instant> boundaries,
            Function<Instant, MlRoutePreview.Comparison> forward,
            long budgetMillis) {
        long budgetEnd = System.nanoTime() + Duration.ofMillis(budgetMillis).toNanos();
        Instant effective = planned.isBefore(asOf) ? asOf : planned;
        var initial = forward.apply(effective);
        var policy =
                new ConditionalDeparturePolicy()
                        .inspect(
                                initial.candidates().stream()
                                        .map(MlRoutePreview.Candidate::result)
                                        .toList(),
                                planned,
                                deadline,
                                asOf);
        int count = candidateCount;
        if (!policy.searchRequired())
            return result(
                    policy.departureMissed() ? Decision.DEPART_NOW : Decision.KEEP_DEPARTURE,
                    policy,
                    initial,
                    initial,
                    policy.best(),
                    count,
                    !initial.budgetExhausted()
                            && count <= maxEvaluations
                            && System.nanoTime() < budgetEnd,
                    budgetMillis);

        if (candidateCount == 0)
            return result(
                    Decision.ROUTE_UNAVAILABLE,
                    policy,
                    initial,
                    initial,
                    null,
                    0,
                    true,
                    budgetMillis);
        Instant upper = planned.isBefore(policy.target()) ? planned : policy.target();
        // 메모리도 예산으로 제한한다. 거대한 시간표/수개월 일정은 전체 시각열을 만들지 않는다.
        var times = new TreeSet<Instant>(Comparator.reverseOrder());
        boolean truncated = false;
        if (!upper.isBefore(asOf)) {
            times.add(upper);
            times.add(asOf);
            for (Instant t : boundaries)
                if (!t.isBefore(asOf) && !t.isAfter(upper)) {
                    times.add(t);
                    if (times.size() > maxEvaluations + 2) {
                        times.pollLast();
                        truncated = true;
                    }
                }
            // 경계가 먼저 cap을 채워도 그 사이의 더 늦은 격자 후보를 빠뜨리지 않는다.
            // 전체 합집합의 최신 K개만 보존하므로 평가 순서가 항상 내림차순이다.
            Instant t = upper;
            for (int i = 0;
                    i <= maxEvaluations + 2 && !t.isBefore(asOf);
                    i++, t = t.minusSeconds(gridSeconds)) {
                times.add(t);
                if (times.size() > maxEvaluations + 2) {
                    times.pollLast();
                    truncated = true;
                }
            }
            if (!t.isBefore(asOf)) truncated = true;
        }
        Result best = policy.best();
        MlRoutePreview.Comparison bestComparison = initial;
        boolean complete =
                !initial.budgetExhausted()
                        && count <= maxEvaluations
                        && System.nanoTime() < budgetEnd;
        for (Instant at : times) {
            if (at.equals(effective)) continue;
            if (count + candidateCount > maxEvaluations || System.nanoTime() >= budgetEnd) {
                complete = false;
                break;
            }
            var batch = forward.apply(at);
            count += candidateCount;
            if (batch.budgetExhausted() || System.nanoTime() >= budgetEnd) complete = false;
            Result current =
                    ConditionalDeparturePolicy.best(
                            batch.candidates().stream()
                                    .map(MlRoutePreview.Candidate::result)
                                    .toList());
            if (current != null
                    && (best == null
                            || current.estimatedArrivalAt().isBefore(best.estimatedArrivalAt()))) {
                best = current;
                bestComparison = batch;
            }
            if (current != null && !current.estimatedArrivalAt().isAfter(policy.target()))
                // 최신 후보부터 검사했으므로 여기까지 모두 평가했다면 제한 격자의 최신 해다.
                // 메모리 제한으로 버린 더 이른 후보는 이 해를 개선하지 못한다. 시간/호출 예산
                // 초과는 complete로 계속 차단하고, truncated는 해 없이 탐색을 마칠 때만 적용한다.
                return result(
                        at.equals(asOf) ? Decision.DEPART_NOW : Decision.ADVANCE_DEPARTURE,
                        policy,
                        initial,
                        batch,
                        current,
                        count,
                        complete,
                        budgetMillis);
            if (!complete) break;
        }
        // 잘린 도메인의 끝에 도달해도 완료라고 표시하지 않는다.
        if (truncated || !upper.isBefore(asOf) && !times.contains(asOf)) complete = false;
        Decision decision =
                best == null
                        ? (candidateCount == 0
                                        || initial.candidates().stream()
                                                .allMatch(
                                                        c ->
                                                                c.result().assessment()
                                                                        == Assessment.BLOCKED)
                                ? Decision.ROUTE_UNAVAILABLE
                                : Decision.DATA_INSUFFICIENT)
                        : best.estimatedArrivalAt().isBefore(deadline)
                                ? Decision.MARGIN_UNAVAILABLE
                                : Decision.LATE_EXPECTED;
        return result(
                decision, policy, initial, bestComparison, best, count, complete, budgetMillis);
    }

    private Evaluation result(
            Decision decision,
            ConditionalDeparturePolicy.Initial p,
            MlRoutePreview.Comparison original,
            MlRoutePreview.Comparison chosen,
            Result proposed,
            int count,
            boolean complete,
            long budgetMillis) {
        return new Evaluation(
                complete ? decision : Decision.SEARCH_INCOMPLETE,
                complete ? null : decision,
                p.target(),
                p.bufferSeconds(),
                p.departureMissed(),
                p.best(),
                chosen.proposal(proposed, p.target()),
                original,
                chosen,
                new Search(
                        p.searchRequired()
                                ? "BOUNDED_GRID_AND_SOURCE_BOUNDARIES"
                                : "PLANNED_DEPARTURE_ONLY",
                        count,
                        complete,
                        gridSeconds,
                        maxEvaluations,
                        (int) budgetMillis,
                        p.searchRequired()
                                ? List.of(
                                        "CONTINUOUS_OPTIMUM_NOT_PROVEN",
                                        "FUTURE_TRANSIT_COVERAGE_LIMITED")
                                : List.of()),
                null);
    }
}
