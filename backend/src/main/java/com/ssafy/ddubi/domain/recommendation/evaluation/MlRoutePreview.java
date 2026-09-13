package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.ssafy.ddubi.domain.route.mapping.MappedTransitSegment;
import com.ssafy.ddubi.domain.route.model.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.ssafy.ddubi.domain.recommendation.evaluation.JourneyEvidence.*;
import static com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.*;

/**
 * 같은 출발시각의 카카오 후보를 버스 ML과 지하철 시간표·실시간 도착정보로 비교하는 검증용 실행기다.
 * API step을 전체 인접 edge의 예측 합으로 교체하고 API 잔여시간은 한 번만 보존한다.
 * 실패한 BUS step은 전체 API 시간으로 복귀한다. 부분 edge 예측과 전체 step을 중복 합산하지 않는다.
 * 구간 상태 Redis는 통제/위험 근거다. 지하철 최신 도착/위치는 SubwayTimingService가 검증한다.
 * 버스 모델은 실시간 관측을 입력받지 않는 v1 계약이다.
 */
@Service
public class MlRoutePreview {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final BusPreviewClient client;
    private final RouteEvidenceAssembler evidence;
    private final RouteTimelineEvaluator evaluator;
    private final SubwayTimingService subway;
    private final BusWaitingService busWaiting;

    public MlRoutePreview(BusPreviewClient client, RouteEvidenceAssembler evidence, RouteTimelineEvaluator evaluator,
                          SubwayTimingService subway, BusWaitingService busWaiting) {
        this.client = client; this.evidence = evidence; this.evaluator = evaluator; this.subway = subway; this.busWaiting = busWaiting;
    }

    /** 실제 호출 입력과 반환값. applied=false는 응답은 받았지만 BUS step 전체 실패로 적용하지 않은 값이다. */
    public record Prediction(String candidateKey, String stepKey, String segmentId, Instant enteredAt,
                             BusPreviewClient.Input input, BusPreviewClient.Estimate estimate, boolean applied) { }
    /** 후보별 결과·모델 적용량. API_ONLY를 ML 결과로 표시하지 않는다. */
    public record Candidate(Result result, String quality, int mlSteps, int apiSteps, int subwaySteps, int busWaitSteps) { }
    /** 지하철 별도 계산과 실제 총시간 반영 여부를 구분한다. 미분해 잔여시간이 있으면 applied=false다. */
    public record SubwayPrediction(String candidateKey, String stepKey, Instant platformAt,
                                   SubwayTimingService.Estimate estimate, boolean applied, String reason) { }
    /** 버스·지하철 공통 대기 결과다. seconds=null은 부족, applied=false는 전체 ETA 미반영이며 0초와 다르다. */
    public record WaitPrediction(String candidateKey, String stepKey, TransportMode mode, Instant stationAt,
                                Long seconds, Instant boardingAt, String vehicleId, String source, String eventId,
                                Instant observedAt, boolean applied, List<String> reasons) {
        public WaitPrediction { reasons = List.copyOf(reasons); }
    }
    /** fastest는 반환 후보 중 계산 가능하고 차단되지 않은 최소시간 한 건이다. 실제 최적 경로나 도착 보장이 아니다. */
    public record Comparison(String status, boolean productionReady, Result fastest, List<Candidate> candidates,
                             List<Prediction> predictions, boolean budgetExhausted, List<SubwayPrediction> subwayPredictions,
                             List<WaitPrediction> waitPredictions) {
        /** 도로 증거가 모든 버스 구간에서 유효하고 기록된 영향이 없는 후보를 별도로 제시한다.
         * fastest의 최소 ETA 의미는 유지한다. 사건 피드가 모든 사고를 포괄하거나 안전함을 보장하지 않는다.
         * 마스터/관측 부족 또는 해제 미확인도 제외하며 사용자가 실제 경로 선택을 결정한다. */
        @com.fasterxml.jackson.annotation.JsonProperty(value="roadAlternative", access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public Result roadAlternative() {
            if (fastest == null || fastest.reasons().stream().noneMatch(r -> r.startsWith("ROAD_POTENTIAL_IMPACT:"))) return null;
            return candidates.stream().map(Candidate::result)
                    .filter(r -> !r.journeyKey().equals(fastest.journeyKey()) && r.estimatedArrivalAt() != null)
                    .filter(r -> r.assessment() == Assessment.ON_TIME_ESTIMATE || r.assessment() == Assessment.LATE_ESTIMATE)
                    .filter(r -> r.reasons().stream().noneMatch(reason -> reason.startsWith("ROAD_")))
                    .min(Comparator.comparing(Result::estimatedArrivalAt).thenComparing(Result::journeyKey)).orElse(null);
        }
        public Comparison { candidates = List.copyOf(candidates); predictions = List.copyOf(predictions);
            subwayPredictions = subwayPredictions == null ? List.of() : List.copyOf(subwayPredictions);
            waitPredictions = waitPredictions == null ? List.of() : List.copyOf(waitPredictions); }
    }

    /**
     * 외부 호출은 DB lease 안의 계산 구간에서만 수행한다. 한 평가에 64회/20초 한도와 입력별 캐시를 둔다.
     * 다음 edge는 앞 edge의 예측 초를 반영한 KST 날짜/시간대로 조회한다. 시간대가 불명확하면 fallback한다.
     */
    public Comparison compare(List<RouteCandidate> routes, RouteEvidenceAssembler.Snapshot snapshot,
                              Instant asOf, Instant departure, Instant arriveBy) {
        var cache = new HashMap<BusPreviewClient.Input, BusPreviewClient.Estimate>();
        var results = new ArrayList<Candidate>();
        var predictions = new ArrayList<Prediction>();
        var subwayPredictions = new ArrayList<SubwayPrediction>();
        var subwaySession = subway.newSession();
        var waitPredictions = new ArrayList<WaitPrediction>();
        var busSession = busWaiting.newSession();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        boolean exhausted = false;
        for (RouteCandidate route : routes) {
            Journey original = evidence.assemble(route, snapshot, asOf);
            var legs = new ArrayList<Leg>();
            Instant cursor = departure;
            int mlSteps = 0, apiSteps = 0, subwaySteps = 0, busWaitSteps = 0, roadSteps = 0;
            var reasons = new ArrayList<String>();
            for (Leg leg : original.legs()) {
                RouteSegment segment = route.segments().stream().filter(s -> ("step-" + s.sequence()).equals(leg.key())).findFirst().orElse(null);
                Leg chosen = leg;
                var edges = segment == null ? List.<MappedTransitSegment>of() : RouteEvidenceAssembler.edges(segment);
                if (segment != null && segment.mode() == TransportMode.BUS) {
                    Instant station = cursor.plusSeconds(leg.entryUncertaintySeconds());
                    var wait = busWaiting.estimate(segment, asOf, station, busSession);
                    boolean canUseWait = wait.seconds() != null && busWaiting.decompositionVerified() && leg.entryUncertaintySeconds() == 0;
                    boolean waitApplied = false;
                    if (!supported(edges)) {
                        reasons.add(leg.key() + ":UNVERIFIED_OR_UNSUPPORTED_BUS_MAPPING");
                    } else {
                        // 대기 후 실제 탑승 예상시각을 버스 ML의 첫 edge 입력으로 사용한다.
                        Instant edgeCursor = canUseWait ? wait.boardingAt() : cursor;
                        long total = 0;
                        boolean complete = true;
                        var attempts = new ArrayList<Prediction>();
                        for (MappedTransitSegment edge : edges) {
                            var start = edgeCursor.atZone(KST);
                            var latest = edgeCursor.plusSeconds(leg.entryUncertaintySeconds()).atZone(KST);
                            if (!start.toLocalDate().equals(latest.toLocalDate()) || start.getHour() != latest.getHour()) {
                                reasons.add(leg.key() + ":ENTRY_HOUR_UNCERTAIN"); complete = false; break;
                            }
                            var input = new BusPreviewClient.Input(edge.routeId(), edge.fromNodeId(), edge.toNodeId(),
                                    edge.fromOrder(), start.toLocalDate(), start.getHour());
                            BusPreviewClient.Estimate estimate = cache.get(input);
                            if (estimate == null) {
                                if (cache.size() >= 64 || System.nanoTime() >= deadline) {
                                    exhausted = true; estimate = BusPreviewClient.Estimate.unavailable("ML_BUDGET_EXHAUSTED");
                                } else {
                                    estimate = client.predict(input); cache.put(input, estimate);
                                }
                            }
                            attempts.add(new Prediction(original.key(), leg.key(), edge.segmentId(), edgeCursor, input, estimate, false));
                            if (estimate.seconds() == null) {
                                reasons.add(leg.key() + ":" + estimate.reason()); complete = false; break;
                            }
                            long seconds = (long) Math.ceil(estimate.seconds());
                            total += seconds; edgeCursor = edgeCursor.plusSeconds(seconds);
                        }
                        for (Prediction attempt : attempts) predictions.add(new Prediction(attempt.candidateKey(), attempt.stepKey(),
                                attempt.segmentId(), attempt.enteredAt(), attempt.input(), attempt.estimate(), complete));
                        if (complete) {
                            // 확정된 각 입력의 날짜 범위는 HTTP 응답에서 확인했다. 이 Window는 관측 TTL이 아니라
                            // 이번 고정 입력의 모델 결과를 타임라인 계산기에 전달하는 범위다. 다른 출발 탐색에는 재사용하지 않는다.
                            chosen = new Leg(leg.key(), leg.kind(), leg.segmentIds(), total, total,
                                    new Window("ML_PREVIEW", asOf, asOf, Instant.MAX), List.of(), leg.entryUncertaintySeconds());
                            mlSteps++;
                            if (canUseWait) {
                                legs.add(new Leg(leg.key()+"-wait", Kind.WAIT, List.of(), wait.seconds(), null,
                                        new Window(wait.source(), asOf, asOf, Instant.MAX),
                                        List.of(new Boarding(wait.vehicleId(), wait.boardingAt(), wait.boardingAt(), false))));
                                cursor = wait.boardingAt(); waitApplied = true; busWaitSteps++;
                            }
                        }
                    }
                    var waitReasons = new ArrayList<>(wait.reasons());
                    if (wait.seconds() != null && !waitApplied) waitReasons.add(canUseWait ? "BUS_WAIT_MOVEMENT_UNAVAILABLE" : "BUS_WAIT_TIME_COMPONENTS_UNRESOLVED");
                    if (busWaiting.decompositionVerified() && leg.entryUncertaintySeconds() == 0 && !waitApplied) {
                        // 대기 제외가 확인된 경로에서 결측 WAIT를 없애면 가짜 최단 ETA가 된다. 평가기를 정보 부족으로 종료시킨다.
                        legs.add(new Leg(leg.key()+"-wait",Kind.WAIT,List.of(),null,null,null,List.of()));
                        waitReasons.add("BUS_WAIT_REQUIRED_NOT_APPLIED");
                    }
                    waitPredictions.add(new WaitPrediction(original.key(),leg.key(),TransportMode.BUS,station,wait.seconds(),
                            wait.boardingAt(),wait.vehicleId(),wait.source(),wait.eventId(),wait.observedAt(),waitApplied,waitReasons));
                    reasons.addAll(waitReasons.stream().map(r -> leg.key()+":"+r).toList());
                }
                if (segment != null && segment.mode() == TransportMode.SUBWAY) {
                    // 잔여시간이 어디에 들어가는지 모르면 승강장 도착의 상한으로 별도 계산만 한다.
                    // 대기/승강장 접근 분해 검증 없이 WAIT를 API 총시간 위에 덧붙이지 않는다.
                    Instant platform = cursor.plusSeconds(leg.entryUncertaintySeconds());
                    var timing = subway.estimate(segment, asOf, platform, subwaySession);
                    String reason = timing.arrivalAt() == null ? "SUBWAY_TIMING_UNAVAILABLE"
                            : !subway.decompositionVerified() || leg.entryUncertaintySeconds() != 0
                            ? "SUBWAY_TIME_COMPONENTS_UNRESOLVED" : null;
                    boolean applied = reason == null;
                    var waitReasons = new ArrayList<>(timing.reasons());
                    if (reason != null) waitReasons.add(reason);
                    waitPredictions.add(new WaitPrediction(original.key(),leg.key(),TransportMode.SUBWAY,platform,timing.waitSeconds(),
                            timing.boardingAt(),timing.tripKey(),timing.source(),timing.arrivalEventId(),timing.observedAt(),applied,waitReasons));
                    subwayPredictions.add(new SubwayPrediction(original.key(), leg.key(), platform, timing, applied, reason));
                    reasons.addAll(timing.reasons().stream().map(r -> leg.key() + ":" + r).toList());
                    if (applied) {
                        // 이미 asOf와 platform에 대해 검증한 고정 결과다. 실시간 TTL을 연장하는 캐시가 아니며,
                        // 출발시각을 바꾸면 별도 estimate를 수행한다. 환승 후 다음 step도 갱신된 cursor를 쓴다.
                        // 대기는 선로 통과가 아니다. WAIT를 분리하여 탑승 전에 끝난 선로 통제가 경로를 막지 않게 한다.
                        legs.add(new Leg(leg.key() + "-wait", Kind.WAIT, List.of(), timing.waitSeconds(), null,
                                new Window(timing.source(), asOf, asOf, Instant.MAX),
                                List.of(new Boarding(timing.tripKey(), timing.boardingAt(), timing.boardingAt(), false))));
                        cursor = cursor.plusSeconds(timing.waitSeconds());
                        chosen = new Leg(leg.key(), leg.kind(), leg.segmentIds(), timing.rideSeconds(), timing.rideSeconds(),
                                new Window(timing.source(), asOf, asOf, Instant.MAX), List.of());
                        subwaySteps++;
                    } else {
                        reasons.add(leg.key() + ":" + reason);
                        if (subway.decompositionVerified() && leg.entryUncertaintySeconds() == 0) {
                            legs.add(new Leg(leg.key()+"-wait",Kind.WAIT,List.of(),null,null,null,List.of()));
                        }
                    }
                }
                if (segment!=null && segment.mode()==TransportMode.BUS && !snapshot.roads().isEmpty()) {
                    roadSteps++;
                    reasons.addAll(RoadImpactEvidence.reasons(segment,snapshot.roads(),cursor,
                            cursor.plusSeconds(chosen.fallbackSeconds()+leg.entryUncertaintySeconds())));
                }
                if (chosen == leg) apiSteps++;
                legs.add(chosen); cursor = cursor.plusSeconds(chosen.fallbackSeconds());
            }
            if (!snapshot.roads().isEmpty() && roadSteps < route.segments().stream().filter(s -> s.mode()==TransportMode.BUS).count()) reasons.add("ROAD_IMPACT_UNKNOWN");
            Result raw = evaluator.evaluate(new Journey(original.key(), legs), asOf, departure, arriveBy, evidence.closures(snapshot));
            reasons.addAll(raw.reasons()); reasons.add("VALIDATION_ONLY");
            Result result = new Result(raw.journeyKey(), raw.departureAt(), raw.estimatedArrivalAt(), raw.assessment(), true,
                    raw.steps(), reasons, "transit-prev-v3");
            String quality = mlSteps + subwaySteps == 0 ? "API_ONLY" : busWaitSteps > 0 || apiSteps > 0 || mlSteps > 0 && subwaySteps > 0
                    ? "MIXED_PREVIEW" : subwaySteps > 0 ? "SUBWAY_PREVIEW" : "ML_PREVIEW";
            results.add(new Candidate(result, quality, mlSteps, apiSteps, subwaySteps, busWaitSteps));
        }
        Result fastest = results.stream().map(Candidate::result)
                .filter(r -> r.estimatedArrivalAt() != null && (r.assessment() == Assessment.ON_TIME_ESTIMATE || r.assessment() == Assessment.LATE_ESTIMATE))
                .min(Comparator.comparing(Result::estimatedArrivalAt).thenComparing(Result::journeyKey)).orElse(null);
        return new Comparison("PREVIEW", false, fastest, results, predictions, exhausted, subwayPredictions, waitPredictions);
    }

    private boolean supported(List<MappedTransitSegment> edges) {
        if (edges.isEmpty()) return false;
        for (int i = 0; i < edges.size(); i++) {
            var e = edges.get(i);
            if (e.mode() != TransportMode.BUS || !numeric(e.routeId()) || !numeric(e.fromNodeId()) || !numeric(e.toNodeId())
                    || !Objects.equals(e.routeId(), e.realtimeRouteId()) || !Objects.equals(e.fromNodeId(), e.fromRealtimeNodeId())
                    || !Objects.equals(e.toNodeId(), e.toRealtimeNodeId()) || e.fromOrder() < 0 || e.fromOrder() > 10000
                    || e.toOrder() != e.fromOrder() + 1 || e.sequence() != i + 1) return false;
            if (i > 0 && (!edges.get(i-1).routeId().equals(e.routeId()) || !edges.get(i-1).toNodeId().equals(e.fromNodeId())
                    || edges.get(i-1).toOrder() != e.fromOrder())) return false;
        }
        return true;
    }
    private boolean numeric(String value) { return value != null && value.matches("[0-9]{1,30}"); }
}
