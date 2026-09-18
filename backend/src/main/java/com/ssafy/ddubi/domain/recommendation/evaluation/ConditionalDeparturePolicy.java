package com.ssafy.ddubi.domain.recommendation.evaluation;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import static com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.*;

/**
 * 사용자 P와 일정 D에 대한 순수 정책. 외부 I/O·현재시각 조회·DB 변경은 하지 않는다.
 * P의 ETA가 D보다 엄격히 빠를 때만 유지하며, 그 외에만 D-600초를 탐색 목표로 만든다.
 * 기존 타임라인의 ON_TIME_ESTIMATE(ETA<=D)와 정책의 엄격한 경계를 혼동하지 않는다.
 */
public final class ConditionalDeparturePolicy {
    public static final String VERSION = "cdep-v1";
    public enum Decision { KEEP_DEPARTURE, ADVANCE_DEPARTURE, DEPART_NOW, MARGIN_UNAVAILABLE,
        LATE_EXPECTED, DATA_INSUFFICIENT, ROUTE_UNAVAILABLE, SEARCH_INCOMPLETE }

    /** target=null은 여유시간 미적용이다. missed여도 과거 P를 제안하지 않는다. */
    public record Initial(Result best, boolean searchRequired, Instant target, int bufferSeconds, boolean departureMissed) { }

    public Initial inspect(List<Result> results, Instant planned, Instant deadline, Instant asOf) {
        Result best = best(results);
        boolean search = best == null || !best.estimatedArrivalAt().isBefore(deadline);
        return new Initial(best, search, search ? deadline.minusSeconds(600) : null,
                search ? 600 : 0, planned.isBefore(asOf));
    }

    /** 차단 또는 정보 부족 결과는 ETA가 남아 있어도 이용 가능 후보로 승격하지 않는다. */
    public static Result best(List<Result> results) {
        return results.stream().filter(ConditionalDeparturePolicy::usable)
                .min(Comparator.comparing(Result::estimatedArrivalAt).thenComparing(Result::journeyKey)).orElse(null);
    }
    public static boolean usable(Result r) {
        return r != null && r.estimatedArrivalAt() != null
                && (r.assessment() == Assessment.ON_TIME_ESTIMATE || r.assessment() == Assessment.LATE_ESTIMATE);
    }
}
