package com.ssafy.ddubi.domain.recommendation.evaluation;

import java.util.List;

/**
 * 사용자 경로 안내·수동 적용 가능 여부와 ML 모델의 운영 승인을 분리한다. {@code TEST_APPLY}가 명시적으로 계산에 반영한 결과는 PREVIEW 표식을
 * 유지하면서도 앱 QA에서 수동 적용할 수 있다. 단순 미리보기 결과를 잘못 적용하지 않도록 실제 평가 사유에 {@code ML_TEST_APPLIED}가 남은 경우만
 * 허용한다. 입력은 동일 평가의 두 비교 결과다. DB 입력 버전·만료·출발 상태는 Store가 추가 검증한다.
 */
public record EvaluationGuidance(
        boolean canDisplayEstimate, boolean canAccept, String source, List<String> reasons) {
    public static EvaluationGuidance from(ConditionalDepartureEvaluator.Evaluation evaluation) {
        if (evaluation == null
                || evaluation.proposed() == null
                || evaluation.proposed().estimatedArrivalAt() == null)
            return new EvaluationGuidance(
                    false, false, "UNAVAILABLE", List.of("NO_USABLE_ROUTE_ESTIMATE"));
        var result = evaluation.proposed();
        boolean experimental =
                result.steps().stream().anyMatch(s -> "ML_PREVIEW".equals(s.source()));
        // P의 도착 가능 판정을 바꾼 시험 예측도 수락 판단에서 빠뜨리지 않는다.
        if (evaluation.original() != null)
            experimental |=
                    evaluation.original().steps().stream()
                            .anyMatch(s -> "ML_PREVIEW".equals(s.source()));
        boolean testApplied =
                containsReason(result, "ML_TEST_APPLIED")
                        || containsReason(evaluation.original(), "ML_TEST_APPLIED");
        boolean complete = evaluation.search() != null && evaluation.search().complete();
        var reasons = new java.util.ArrayList<String>();
        if (experimental && !testApplied) reasons.add("ML_PREVIEW_NOT_APPLIED");
        if (testApplied) reasons.add("ML_TEST_APPLIED");
        if (!complete) reasons.add("SEARCH_INCOMPLETE");
        String source =
                experimental
                        ? "EXPERIMENTAL_ESTIMATE"
                        : result.steps().stream().allMatch(s -> "FALLBACK".equals(s.source()))
                                ? "PROVIDER_ESTIMATE"
                                : "MIXED_ESTIMATE";
        return new EvaluationGuidance(
                true, (!experimental || testApplied) && complete, source, List.copyOf(reasons));
    }

    private static boolean containsReason(RouteTimelineEvaluator.Result result, String marker) {
        return result != null
                && result.reasons().stream().anyMatch(reason -> reason.endsWith(":" + marker));
    }
}
