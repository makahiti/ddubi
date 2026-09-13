package com.ssafy.ddubi.domain.recommendation.evaluation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import static com.ssafy.ddubi.domain.recommendation.evaluation.JourneyEvidence.*;
import static com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.*;

/**
 * 고정된 후보와 교통 증거를 여러 출발시각에서 다시 평가한다. 사용자 출발시각을 저장/변경하지 않는다.
 * 지각분을 한 번 빼는 역산이나 단조성을 가정한 이분 탐색은 배차·환승에 맞지 않아 사용하지 않는다.
 * 외부 API를 탐색마다 부르지 않으며 API 후보 생성과 결과 저장은 호출자가 맡는다.
 */
@Service
public class DepartureSuggestionService {
    private final RouteTimelineEvaluator evaluator;

    public DepartureSuggestionService(RouteTimelineEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    /** 탐색 간격·범위는 초 단위의 명시적 정책이다. 상한은 서버 과부하 방지이며 안전 여유가 아니다. */
    public record SearchPolicy(int stepSeconds, int maxAdvanceSeconds, int maxEvaluations) {
        public SearchPolicy {
            if (stepSeconds < 1 || maxAdvanceSeconds < 0 || maxEvaluations < 1 || maxEvaluations > 10000) {
                throw new IllegalArgumentException("출발 탐색 정책 범위를 확인하세요.");
            }
        }
    }

    /**
     * 원래 출발에서의 후보와 제안, 수행 범위를 반환한다. 제안은 확인한 격자 안에서의 후보일 뿐
     * 수학적으로 최적임을 보장하지 않는다. budgetExhausted면 탐색을 완료했다고 표시하지 않는다.
     */
    public record Suggestion(List<Result> originalCandidates, Result proposed, Result earliestArrival,
                             int evaluatedCount, boolean budgetExhausted) {
        public Suggestion { originalCandidates = List.copyOf(originalCandidates); }
    }

    /**
     * 원래 출발에서 가능한 다른 경로를 먼저 찾는다. 없을 때에만 사용자 허용시각과 현재시각 이후를
     * 뒤로 탐색한다. 과거에만 가능했던 출발시각을 반환하지 않으며 같은 입력은 같은 순위로 정렬한다.
     */
    public Suggestion suggest(List<Journey> journeys, Instant asOf, Instant plannedDepartAt, Instant arriveBy,
                              Instant earliestDepartAt, List<Closure> closures, SearchPolicy policy) {
        if (journeys.isEmpty() || journeys.size() > policy.maxEvaluations()) {
            throw new IllegalArgumentException("최초 후보를 모두 평가할 수 있는 예산이 필요합니다.");
        }
        if (journeys.stream().map(Journey::key).distinct().count() != journeys.size()) {
            throw new IllegalArgumentException("경로 후보 키가 중복됩니다.");
        }
        if (earliestDepartAt.isAfter(plannedDepartAt)) {
            throw new IllegalArgumentException("가장 이른 출발은 원래 출발 이후일 수 없습니다.");
        }
        // 늦게 재평가한 경우 현재 출발의 대안을 보되 입력 예정시각을 과거로 실행하지 않는다.
        Instant originalDeparture = plannedDepartAt.isBefore(asOf) ? asOf : plannedDepartAt;
        var original = new ArrayList<Result>();
        var all = new ArrayList<Result>();
        for (Journey journey : journeys) {
            original.add(evaluator.evaluate(journey, asOf, originalDeparture, arriveBy, closures));
        }
        all.addAll(original);
        Result proposed = bestOnTime(original);
        if (proposed != null || plannedDepartAt.isBefore(asOf)) {
            return new Suggestion(original, proposed, earliest(all), all.size(), false);
        }

        Instant lower = plannedDepartAt.minusSeconds(policy.maxAdvanceSeconds());
        if (earliestDepartAt.isAfter(lower)) lower = earliestDepartAt;
        if (asOf.isAfter(lower)) lower = asOf;
        Instant candidate = plannedDepartAt;
        boolean exhausted = false;
        while (candidate.isAfter(lower)) {
            Instant next = candidate.minusSeconds(policy.stepSeconds());
            candidate = next.isBefore(lower) ? lower : next;
            // 어느 출발시각에서 일부 경로만 평가하면 후보 순서가 추천을 편향시키므로 묶음 단위로 제한한다.
            if (all.size() + journeys.size() > policy.maxEvaluations()) {
                exhausted = true;
                break;
            }
            var batch = new ArrayList<Result>();
            for (Journey journey : journeys) {
                batch.add(evaluator.evaluate(journey, asOf, candidate, arriveBy, closures));
            }
            all.addAll(batch);
            proposed = bestOnTime(batch);
            if (proposed != null) break;
        }
        return new Suggestion(original, proposed, earliest(all), all.size(), exhausted);
    }

    private Result bestOnTime(List<Result> results) {
        return results.stream().filter(r -> r.assessment() == Assessment.ON_TIME_ESTIMATE)
                .min(Comparator.comparing(Result::tentative).thenComparing(Result::estimatedArrivalAt)
                        .thenComparing(Result::journeyKey)).orElse(null);
    }

    private Result earliest(List<Result> results) {
        return results.stream().filter(r -> r.estimatedArrivalAt() != null)
                .min(Comparator.comparing(Result::estimatedArrivalAt).thenComparing(Result::journeyKey)).orElse(null);
    }
}
