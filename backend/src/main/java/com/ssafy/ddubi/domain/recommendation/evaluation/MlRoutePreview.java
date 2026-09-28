package com.ssafy.ddubi.domain.recommendation.evaluation;

import static com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.*;

import com.ssafy.ddubi.domain.route.model.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** 기존 PREVIEW API/저장 JSON을 유지하는 어댑터. 실제 계산과 세션 소유권은 공통 여정 계산기로 위임한다. */
@Service
public class MlRoutePreview {
    private final JourneyEvaluationService journeys;

    @org.springframework.beans.factory.annotation.Autowired
    public MlRoutePreview(JourneyEvaluationService journeys) {
        this.journeys = journeys;
    }

    /** 기존 순수 fixture의 생성 경로도 같은 계산기를 사용한다. */
    public MlRoutePreview(
            BusPreviewClient client,
            RouteEvidenceAssembler evidence,
            RouteTimelineEvaluator evaluator,
            SubwayTimingService subway,
            BusWaitingService busWaiting) {
        this(new JourneyEvaluationService(client, evidence, evaluator, subway, busWaiting));
    }

    /** 실제 호출 입력과 반환값. applied=false는 응답은 받았지만 BUS step 전체 실패로 적용하지 않은 값이다. */
    public record Prediction(
            String candidateKey,
            String stepKey,
            String segmentId,
            Instant enteredAt,
            BusPreviewClient.Input input,
            BusPreviewClient.Estimate estimate,
            boolean applied) {}

    /** 후보별 결과·모델 적용량. API_ONLY를 ML 결과로 표시하지 않는다. */
    public record Candidate(
            Result result,
            String quality,
            int mlSteps,
            int apiSteps,
            int subwaySteps,
            int busWaitSteps) {}

    /** 지하철 별도 계산과 실제 총시간 반영 여부를 구분한다. 미분해 잔여시간이 있으면 applied=false다. */
    public record SubwayPrediction(
            String candidateKey,
            String stepKey,
            Instant platformAt,
            SubwayTimingService.Estimate estimate,
            boolean applied,
            String reason) {}

    /**
     * 버스·지하철 공통 대기 결과다. seconds=null은 부족, applied=false는 ETA 미반영이다. validUntil은 적용 근거의 만료이며 저장
     * JSON에서도 보존한다. 구형 JSON의 null은 유효함을 뜻하지 않는다.
     */
    public record WaitPrediction(
            String candidateKey,
            String stepKey,
            TransportMode mode,
            Instant stationAt,
            Long seconds,
            Instant boardingAt,
            String vehicleId,
            String source,
            String eventId,
            Instant observedAt,
            Instant validUntil,
            boolean applied,
            List<String> reasons) {
        public WaitPrediction {
            reasons = List.copyOf(reasons);
        }
    }

    /** fastest는 반환 후보 중 계산 가능하고 차단되지 않은 최소시간 한 건이다. 실제 최적 경로나 도착 보장이 아니다. */
    public record Comparison(
            String status,
            boolean productionReady,
            Result fastest,
            List<Candidate> candidates,
            List<Prediction> predictions,
            boolean budgetExhausted,
            List<SubwayPrediction> subwayPredictions,
            List<WaitPrediction> waitPredictions) {
        /**
         * 도로 증거가 모든 버스 구간에서 유효하고 기록된 영향이 없는 후보를 별도로 제시한다. fastest의 최소 ETA 의미는 유지한다. 사건 피드가 모든 사고를
         * 포괄하거나 안전함을 보장하지 않는다. 마스터/관측 부족 또는 해제 미확인도 제외하며 사용자가 실제 경로 선택을 결정한다.
         */
        @com.fasterxml.jackson.annotation.JsonProperty(
                value = "roadAlternative",
                access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public Result roadAlternative() {
            if (fastest == null
                    || fastest.reasons().stream()
                            .noneMatch(r -> r.startsWith("ROAD_POTENTIAL_IMPACT:"))) return null;
            return candidates.stream()
                    .map(Candidate::result)
                    .filter(
                            r ->
                                    !r.journeyKey().equals(fastest.journeyKey())
                                            && r.estimatedArrivalAt() != null)
                    .filter(
                            r ->
                                    r.assessment() == Assessment.ON_TIME_ESTIMATE
                                            || r.assessment() == Assessment.LATE_ESTIMATE)
                    .filter(
                            r ->
                                    r.reasons().stream()
                                            .noneMatch(reason -> reason.startsWith("ROAD_")))
                    .min(
                            Comparator.comparing(Result::estimatedArrivalAt)
                                    .thenComparing(Result::journeyKey))
                    .orElse(null);
        }

        /**
         * 실제로 제안할 경로. 도로 사고가 걸린 최속 후보 대신, 사고 근거가 깨끗하면서 목표 시각을 여전히 지키는 대안이 있으면 그것을 제안한다.
         *
         * <p>대안이 없거나 대안으로는 목표를 못 지키면 {@code fallback}(최속)을 그대로 둔다. 그 길로 갈 수밖에 없는 상황에서 더 늦은 길을 억지로
         * 권하지 않고, 걸린 원인은 그대로 남아 안내 문장과 알림이 경고를 맡는다. 사건 피드가 모든 사고를 포괄하지 않으므로 이 선택은 안전을 보장하지 않는다.
         */
        public Result proposal(Result fallback, java.time.Instant target) {
            Result alternative = roadAlternative();
            if (fallback == null
                    || alternative == null
                    || target == null
                    || alternative.estimatedArrivalAt() == null) return fallback;
            return alternative.estimatedArrivalAt().isAfter(target) ? fallback : alternative;
        }

        public Comparison {
            candidates = List.copyOf(candidates);
            predictions = List.copyOf(predictions);
            subwayPredictions =
                    subwayPredictions == null ? List.of() : List.copyOf(subwayPredictions);
            waitPredictions = waitPredictions == null ? List.of() : List.copyOf(waitPredictions);
        }
    }

    public Comparison compare(
            List<RouteCandidate> routes,
            RouteEvidenceAssembler.Snapshot snapshot,
            Instant asOf,
            Instant departure,
            Instant arriveBy) {
        return journeys.compare(routes, snapshot, asOf, departure, arriveBy);
    }
}
