package com.ssafy.ddubi.domain.recommendation.evaluation;

import java.util.List;

/**
 * 사용자 경로 안내 가능 여부와 ML 모델의 운영 승인을 분리한다. 원본 API fallback과
 * 검증된 시간표의 예상값은 안내할 수 있지만 시험 적용 ML은 출발시각 수락에 쓰지 않는다.
 * 입력은 동일 평가의 두 비교 결과다. DB 입력 버전·만료·출발 상태는 Store가 추가 검증한다.
 */
public record EvaluationGuidance(boolean canDisplayEstimate, boolean canAccept, String source, List<String> reasons) {
    public static EvaluationGuidance from(ConditionalDepartureEvaluator.Evaluation evaluation) {
        if(evaluation==null || evaluation.proposed()==null || evaluation.proposed().estimatedArrivalAt()==null)
            return new EvaluationGuidance(false,false,"UNAVAILABLE",List.of("NO_USABLE_ROUTE_ESTIMATE"));
        var result=evaluation.proposed();
        boolean experimental=result.steps().stream().anyMatch(s->"ML_PREVIEW".equals(s.source()));
        // P의 도착 가능 판정을 바꾼 시험 예측도 수락 판단에서 빠뜨리지 않는다.
        if(evaluation.original()!=null) experimental |= evaluation.original().steps().stream()
                .anyMatch(s->"ML_PREVIEW".equals(s.source()));
        boolean complete=evaluation.search()!=null && evaluation.search().complete();
        var reasons=new java.util.ArrayList<String>();
        if(experimental) reasons.add("ML_TEST_RESULT_NOT_ACCEPTABLE");
        if(!complete) reasons.add("SEARCH_INCOMPLETE");
        String source=experimental?"EXPERIMENTAL_ESTIMATE":result.steps().stream()
                .allMatch(s->"FALLBACK".equals(s.source()))?"PROVIDER_ESTIMATE":"MIXED_ESTIMATE";
        return new EvaluationGuidance(true,!experimental && complete,source,List.copyOf(reasons));
    }
}
