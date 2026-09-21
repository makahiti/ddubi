package com.ssafy.ddubi.domain.recommendation.evaluation;

import static com.ssafy.ddubi.domain.recommendation.evaluation.JourneyEvidence.*;
import static com.ssafy.ddubi.domain.recommendation.evaluation.MlRoutePreview.*;
import static com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.*;

import com.ssafy.ddubi.domain.route.mapping.MappedTransitSegment;
import com.ssafy.ddubi.domain.route.model.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 같은 출발시각의 카카오 후보를 버스 ML과 지하철 시간표·실시간 도착정보로 비교하는 검증용 실행기다. API step을 전체 인접 edge의 예측 합으로 교체한다. 총시간은
 * 선택된 구간과 계산된 대기만 합산한다. 실패한 BUS step은 해당 카카오 BUS step 시간으로 복귀한다. 부분 edge 예측과 전체 step을 중복 합산하지 않는다.
 * 구간 상태 Redis는 통제/위험 근거다. 지하철 최신 도착/위치는 SubwayTimingService가 검증한다. 버스 모델은 실시간 관측을 입력받지 않는 v1 계약이다.
 */
@Service
public class JourneyEvaluationService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final BusPreviewClient client;
    private final RouteEvidenceAssembler evidence;
    private final RouteTimelineEvaluator evaluator;
    private final SubwayTimingService subway;
    private final BusWaitingService busWaiting;
    private final BusHistoricalReferenceService historical;

    /** 한 세션 예산 중 ML HTTP 호출을 새로 시작할 수 있는 비율. 나머지는 대기·시간표·타임라인 계산에 남긴다. */
    public static final double DEFAULT_ML_BUDGET_SHARE = 0.6;

    /** 배치 선조회 뒤 앞 edge 예측으로 진입 시간대가 바뀐 edge에 허용하는 개별 ML 호출 수. 뒤 후보가 예산을 통째로 잃지 않게 한다. */
    public static final int INDIVIDUAL_CALL_LIMIT = 8;

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(JourneyEvaluationService.class);
    private final double mlBudgetShare;
    private final MlApplyMode applyMode;

    @org.springframework.beans.factory.annotation.Autowired
    public JourneyEvaluationService(
            BusPreviewClient client,
            RouteEvidenceAssembler evidence,
            RouteTimelineEvaluator evaluator,
            SubwayTimingService subway,
            BusWaitingService busWaiting,
            BusHistoricalReferenceService historical,
            @org.springframework.beans.factory.annotation.Value(
                            "${ddubi.recommendation.ml-budget-share:0.6}")
                    double mlBudgetShare,
            @org.springframework.beans.factory.annotation.Value(
                            "${ddubi.recommendation.ml-apply-mode:SHADOW}")
                    String applyMode,
            @org.springframework.beans.factory.annotation.Value(
                            "${ddubi.recommendation.ml-test-apply-allowed:false}")
                    boolean testApplyAllowed) {
        // TEST_APPLY는 application-test.yml만 허용 값을 함께 준다. 운영 환경변수 하나로는 켜지지 않는다.
        this(
                client,
                evidence,
                evaluator,
                subway,
                busWaiting,
                historical,
                mlBudgetShare,
                MlApplyMode.resolve(applyMode, testApplyAllowed));
        log.info("버스 ML 적용 모드={} ML 예산 비율={}", this.applyMode, mlBudgetShare);
    }

    public JourneyEvaluationService(
            BusPreviewClient client,
            RouteEvidenceAssembler evidence,
            RouteTimelineEvaluator evaluator,
            SubwayTimingService subway,
            BusWaitingService busWaiting,
            BusHistoricalReferenceService historical,
            double mlBudgetShare,
            MlApplyMode applyMode) {
        if (mlBudgetShare < 0.1 || mlBudgetShare > 1.0)
            throw new IllegalArgumentException("ML 예산 비율 범위 오류");
        this.client = client;
        this.evidence = evidence;
        this.evaluator = evaluator;
        this.subway = subway;
        this.busWaiting = busWaiting;
        this.historical = historical;
        this.mlBudgetShare = mlBudgetShare;
        this.applyMode = Objects.requireNonNull(applyMode);
    }

    /** 기존 fixture 호환: 시험 적용(TEST_APPLY)으로 동작한다. 운영 기본값은 설정의 SHADOW다. */
    public JourneyEvaluationService(
            BusPreviewClient client,
            RouteEvidenceAssembler evidence,
            RouteTimelineEvaluator evaluator,
            SubwayTimingService subway,
            BusWaitingService busWaiting,
            BusHistoricalReferenceService historical,
            double mlBudgetShare) {
        this(
                client,
                evidence,
                evaluator,
                subway,
                busWaiting,
                historical,
                mlBudgetShare,
                MlApplyMode.TEST_APPLY);
    }

    public JourneyEvaluationService(
            BusPreviewClient client,
            RouteEvidenceAssembler evidence,
            RouteTimelineEvaluator evaluator,
            SubwayTimingService subway,
            BusWaitingService busWaiting,
            BusHistoricalReferenceService historical) {
        this(
                client,
                evidence,
                evaluator,
                subway,
                busWaiting,
                historical,
                DEFAULT_ML_BUDGET_SHARE,
                MlApplyMode.TEST_APPLY);
    }

    public MlApplyMode applyMode() {
        return applyMode;
    }

    /** 보조 계산의 실패·미승인은 API 시간으로 재계산한다. 모델 승인 표시를 변경하지 않는다. */
    public ConditionalDepartureEvaluator.Evaluation providerFallback(
            List<RouteCandidate> routes,
            RouteEvidenceAssembler.Snapshot snapshot,
            Instant asOf,
            Instant planned,
            Instant deadline,
            Comparison previous,
            String reason) {
        return new ProviderDepartureEvaluator(evidence, evaluator)
                .evaluate(routes, snapshot, asOf, planned, deadline, previous, reason);
    }

    /** 기존 순수 fixture는 명시적 역사자료 설정 없이 이전 동작을 유지한다. */
    public JourneyEvaluationService(
            BusPreviewClient client,
            RouteEvidenceAssembler evidence,
            RouteTimelineEvaluator evaluator,
            SubwayTimingService subway,
            BusWaitingService busWaiting) {
        this(client, evidence, evaluator, subway, busWaiting, null);
    }

    /**
     * 외부 호출은 DB lease 안의 계산 구간에서만 수행한다. 한 평가에 64회/20초 한도와 입력별 캐시를 둔다. 다음 edge는 앞 edge의 예측 초를 반영한
     * KST 날짜/시간대로 조회한다. 시간대가 불명확하면 fallback한다.
     */
    public Comparison compare(
            List<RouteCandidate> routes,
            RouteEvidenceAssembler.Snapshot snapshot,
            Instant asOf,
            Instant departure,
            Instant arriveBy) {
        return compare(routes, snapshot, asOf, departure, arriveBy, newSession());
    }

    /** 하나의 조건부 평가 전체가 공유하는 관측·모델 캐시다. 다른 일정/평가에는 재사용하지 않는다. */
    public static final class Session {
        final Map<BusPreviewClient.Input, BusPreviewClient.Estimate> cache = new HashMap<>();
        final SubwayTimingService.Session subway = new SubwayTimingService.Session();
        final BusWaitingService.Session bus = new BusWaitingService.Session();
        final BusHistoricalReferenceService.Session historical =
                new BusHistoricalReferenceService.Session();
        final long deadline;

        /** ML HTTP 호출을 새로 시작할 수 있는 마지막 시점. 이후에는 ML_BUDGET_EXHAUSTED로 두고 남은 예산을 대기·시간표 계산 등에 쓴다. */
        final long mlDeadline;

        String modelVersion;
        boolean mixedModel;

        /** 배치 선조회 이후 개별로 보낸 ML 호출 수. INDIVIDUAL_CALL_LIMIT까지만 허용한다. */
        int mlIndividualCalls;

        boolean prefetched;

        Session(long maxMillis, double mlShare) {
            long start = System.nanoTime();
            long budget = Duration.ofMillis(maxMillis).toNanos();
            deadline = start + budget;
            mlDeadline = start + (long) (budget * mlShare);
        }

        private Session(long deadlineNanos, long mlDeadlineNanos, boolean absolute) {
            deadline = deadlineNanos;
            mlDeadline = mlDeadlineNanos;
        }

        /** 테스트·재현용: 절대 nanoTime 기준 마감을 직접 지정한다. */
        static Session withDeadlines(long deadlineNanos, long mlDeadlineNanos) {
            return new Session(deadlineNanos, mlDeadlineNanos, true);
        }

        /**
         * ML 호출 한 번을 끝까지 기다려도 전체 예산을 넘기지 않는지 본다. 수동 재계산은 예산이 10초인데 호출 한 건이 7초까지 걸릴 수 있어, 늦게 시작하면
         * 예산을 넘긴다. 남은 시간이 부족하면 호출을 시작하지 않고 해당 경로 API step fallback으로 간다.
         */
        boolean roomForMlCall(long timeoutMillis) {
            return System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos() <= deadline;
        }
    }

    public Session newSession() {
        return new Session(20000, mlBudgetShare);
    }

    /**
     * 조건부 탐색과 ML 호출이 같은 예산을 공유한다. 진행 중 HTTP 한 건은 클라이언트 timeout까지 걸릴 수 있다. ML은 예산의 mlBudgetShare
     * 비율까지만 새 호출을 시작하므로, 순차 ML 호출이 전체 예산을 소진해 시간표·타임라인 계산에 사용할 예산을 확보한다.
     */
    public Session newSession(long maxMillis) {
        return new Session(maxMillis, mlBudgetShare);
    }

    /** 출발시각마다 차량과 환승을 재선택하되 동일 입력 조회는 세션 안에서 고정한다. */
    public Comparison compare(
            List<RouteCandidate> routes,
            RouteEvidenceAssembler.Snapshot snapshot,
            Instant asOf,
            Instant departure,
            Instant arriveBy,
            Session session) {
        var cache = session.cache;
        var results = new ArrayList<Candidate>();
        var predictions = new ArrayList<Prediction>();
        var subwayPredictions = new ArrayList<SubwayPrediction>();
        var subwaySession = session.subway;
        var waitPredictions = new ArrayList<WaitPrediction>();
        var busSession = session.bus;
        long deadline = session.deadline;
        // 모든 후보의 BUS edge를 먼저 한 배치로 조회한다. 후보 순서 때문에 뒤 후보만 예산을 잃는 문제를 막는다.
        prefetch(routes, snapshot, asOf, departure, session);
        boolean exhausted = false;
        for (RouteCandidate route : routes) {
            Journey original = evidence.assemble(route, snapshot, asOf);
            var legs = new ArrayList<Leg>();
            Instant cursor = departure;
            int mlSteps = 0,
                    referenceSteps = 0,
                    apiSteps = 0,
                    subwaySteps = 0,
                    busWaitSteps = 0,
                    roadSteps = 0;
            var reasons = new ArrayList<String>();
            for (Leg leg : original.legs()) {
                RouteSegment segment =
                        route.segments().stream()
                                .filter(s -> ("step-" + s.sequence()).equals(leg.key()))
                                .findFirst()
                                .orElse(null);
                Leg chosen = leg;
                var edges =
                        segment == null
                                ? List.<MappedTransitSegment>of()
                                : RouteEvidenceAssembler.edges(segment);
                if (segment != null && segment.mode() == TransportMode.BUS) {
                    Instant station = cursor.plusSeconds(leg.entryUncertaintySeconds());
                    var wait = busWaiting.estimate(segment, asOf, station, busSession);
                    boolean canUseWait = wait.seconds() != null && wait.boardingAt() != null;
                    boolean waitApplied = false;
                    if (!topologySupported(edges)) {
                        reasons.add(leg.key() + ":UNVERIFIED_OR_UNSUPPORTED_BUS_MAPPING");
                        if (segment.mapping() != null
                                && BusAlternativeExpansion.LIMITED.equals(
                                        segment.mapping().failureReason())) {
                            reasons.add(leg.key() + ":" + BusAlternativeExpansion.LIMITED);
                            exhausted = true;
                        }
                    } else {
                        // 서울 ML은 실시간 대응 ID가 확인된 서울 edge에만 호출한다. 경기·인천·부산은
                        // 학습 범위 밖이므로 HTTP 호출 없이 아래 기준값 조회로 바로 내려간다.
                        if (!mlSupported(edges)) {
                            reasons.add(leg.key() + ":ML_NOT_APPLIED:UNSUPPORTED_PROVIDER");
                        } else {
                            // 대기 후 실제 탑승 예상시각을 버스 ML의 첫 edge 입력으로 사용한다.
                            Instant edgeCursor = canUseWait ? wait.boardingAt() : cursor;
                            long total = 0;
                            boolean complete = true;
                            var attempts = new ArrayList<Prediction>();
                            for (MappedTransitSegment edge : edges) {
                                var start = edgeCursor.atZone(KST);
                                var latest =
                                        edgeCursor
                                                .plusSeconds(leg.entryUncertaintySeconds())
                                                .atZone(KST);
                                if (!start.toLocalDate().equals(latest.toLocalDate())
                                        || start.getHour() != latest.getHour()) {
                                    reasons.add(leg.key() + ":ENTRY_HOUR_UNCERTAIN");
                                    complete = false;
                                    break;
                                }
                                var input =
                                        new BusPreviewClient.Input(
                                                edge.routeId(),
                                                edge.fromNodeId(),
                                                edge.toNodeId(),
                                                edge.fromOrder(),
                                                start.toLocalDate(),
                                                start.getHour());
                                BusPreviewClient.Estimate estimate = cache.get(input);
                                if (estimate == null) {
                                    // 선조회 뒤 앞 edge 예측으로 진입 시간대가 바뀐 edge만 개별 호출한다. 개수·시간 한도를 넘으면 예산
                                    // 소진.
                                    if (session.mlIndividualCalls >= INDIVIDUAL_CALL_LIMIT
                                            || System.nanoTime() >= session.mlDeadline
                                            || !session.roomForMlCall(client.timeoutMillis())) {
                                        estimate =
                                                BusPreviewClient.Estimate.unavailable(
                                                        "ML_BUDGET_EXHAUSTED");
                                    } else {
                                        session.mlIndividualCalls++;
                                        estimate =
                                                applyMode == MlApplyMode.PRODUCTION
                                                        ? client.predictBatch(
                                                                        List.of(input), contract())
                                                                .getOrDefault(
                                                                        input,
                                                                        BusPreviewClient.Estimate
                                                                                .unavailable(
                                                                                        "ML_INVALID_RESPONSE"))
                                                        : client.predict(input);
                                        trackModel(session, estimate);
                                        cache.put(input, estimate);
                                    }
                                }
                                attempts.add(
                                        new Prediction(
                                                original.key(),
                                                leg.key(),
                                                edge.segmentId(),
                                                edgeCursor,
                                                input,
                                                estimate,
                                                false));
                                String failure =
                                        session.mixedModel
                                                ? "ML_MODEL_VERSION_MISMATCH"
                                                : usable(estimate);
                                if (failure != null) {
                                    reasons.add(leg.key() + ":" + failure);
                                    reasons.add(leg.key() + ":ML_NOT_APPLIED:" + failure);
                                    complete = false;
                                    break;
                                }
                                long seconds = (long) Math.ceil(estimate.seconds());
                                total += seconds;
                                edgeCursor = edgeCursor.plusSeconds(seconds);
                            }
                            boolean applyMl = complete && applyMode != MlApplyMode.SHADOW;
                            for (Prediction attempt : attempts)
                                predictions.add(
                                        new Prediction(
                                                attempt.candidateKey(),
                                                attempt.stepKey(),
                                                attempt.segmentId(),
                                                attempt.enteredAt(),
                                                attempt.input(),
                                                attempt.estimate(),
                                                applyMl));
                            if (complete && !applyMl) {
                                // SHADOW: 수치·모델 버전·결측 정보만 저장하고 시간 계산에는 쓰지 않는다.
                                reasons.add(leg.key() + ":ML_SHADOW_RECORDED");
                                reasons.add(leg.key() + ":ML_NOT_APPLIED:SHADOW_MODE");
                            }
                            if (applyMl) {
                                // 확정된 각 입력의 날짜 범위는 HTTP 응답에서 확인했다. 이 Window는 관측 TTL이 아니라
                                // 이번 고정 입력의 모델 결과를 타임라인 계산기에 전달하는 범위다. 다른 출발 탐색에는 재사용하지 않는다.
                                String source =
                                        applyMode == MlApplyMode.PRODUCTION
                                                ? "ML_PRODUCTION"
                                                : "ML_PREVIEW";
                                chosen =
                                        new Leg(
                                                leg.key(),
                                                leg.kind(),
                                                leg.segmentIds(),
                                                total,
                                                total,
                                                new Window(source, asOf, asOf, Instant.MAX),
                                                List.of(),
                                                leg.entryUncertaintySeconds());
                                mlSteps++;
                                reasons.add(
                                        leg.key()
                                                + (applyMode == MlApplyMode.PRODUCTION
                                                        ? ":ML_APPLIED"
                                                        : ":ML_TEST_APPLIED"));
                                reasons.add(
                                        leg.key() + ":ML_MODEL_VERSION:" + session.modelVersion);
                                reasons.add(
                                        leg.key()
                                                + ":ML_MIN_HISTORY_SAMPLES:"
                                                + attempts.stream()
                                                        .mapToInt(
                                                                a ->
                                                                        a.estimate()
                                                                                                .historySampleCount()
                                                                                        == null
                                                                                ? 0
                                                                                : a.estimate()
                                                                                        .historySampleCount())
                                                        .min()
                                                        .orElse(0));
                                reasons.add(
                                        leg.key()
                                                + ":ML_MAX_MISSING_FEATURES:"
                                                + attempts.stream()
                                                        .mapToInt(
                                                                a ->
                                                                        a.estimate()
                                                                                                .missingFeatureCount()
                                                                                        == null
                                                                                ? 0
                                                                                : a.estimate()
                                                                                        .missingFeatureCount())
                                                        .max()
                                                        .orElse(0));
                                if (canUseWait) {
                                    legs.add(
                                            new Leg(
                                                    leg.key() + "-wait",
                                                    Kind.WAIT,
                                                    List.of(),
                                                    wait.seconds(),
                                                    null,
                                                    new Window(
                                                            wait.source(), asOf, asOf, Instant.MAX),
                                                    List.of(
                                                            new Boarding(
                                                                    wait.vehicleId(),
                                                                    wait.boardingAt(),
                                                                    wait.boardingAt(),
                                                                    false))));
                                    cursor = wait.boardingAt();
                                    waitApplied = true;
                                    busWaitSteps++;
                                }
                            }
                        }
                        // 서울 ML 실패·SHADOW·미적용과 비서울 노선은 같은 정확키 기준값 경계로
                        // 내려간다. 서비스는 미리 계산된 초만 합산하며 원본 수억 행이나 GTFS를
                        // 요청 중에 다시 읽지 않는다. 한 edge라도 실패하면 chosen을 바꾸지 않아
                        // 아래 API_FALLBACK이 BUS step 전체를 카카오 원본시간으로 유지한다.
                        if (chosen == leg && historical != null && !session.mixedModel) {
                            Instant referenceEntry = canUseWait ? wait.boardingAt() : cursor;
                            var reference =
                                    historical.estimate(
                                            edges,
                                            segment.mapping().masterDataVersion(),
                                            referenceEntry,
                                            leg.entryUncertaintySeconds(),
                                            asOf,
                                            session.historical,
                                            deadline);
                            reasons.add(leg.key() + ":" + reference.reason());
                            if (reference.seconds() != null) {
                                String source =
                                        "GTFS_SCHEDULE_FORMULA".equals(reference.sampleKind())
                                                ? "BUS_GTFS_FORMULA"
                                                : "BUS_HISTORICAL_REFERENCE";
                                chosen =
                                        new Leg(
                                                leg.key(),
                                                leg.kind(),
                                                leg.segmentIds(),
                                                reference.seconds(),
                                                reference.seconds(),
                                                new Window(source, asOf, asOf, Instant.MAX),
                                                List.of(),
                                                leg.entryUncertaintySeconds());
                                referenceSteps++;
                                reasons.add(
                                        leg.key()
                                                + ":BUS_REFERENCE_VERSION:"
                                                + reference.dataVersion());
                                reasons.add(
                                        leg.key()
                                                + ":BUS_REFERENCE_KIND:"
                                                + reference.sampleKind());
                                if (canUseWait) {
                                    legs.add(
                                            new Leg(
                                                    leg.key() + "-wait",
                                                    Kind.WAIT,
                                                    List.of(),
                                                    wait.seconds(),
                                                    null,
                                                    new Window(
                                                            wait.source(), asOf, asOf, Instant.MAX),
                                                    List.of(
                                                            new Boarding(
                                                                    wait.vehicleId(),
                                                                    wait.boardingAt(),
                                                                    wait.boardingAt(),
                                                                    false))));
                                    cursor = wait.boardingAt();
                                    waitApplied = true;
                                    busWaitSteps++;
                                }
                            }
                        }
                    }
                    if (chosen == leg) reasons.add(leg.key() + ":API_FALLBACK");
                    var waitReasons = new ArrayList<>(wait.reasons());
                    // 대기 결측은 이동시간(ML 또는 원본 step)을 취소하지 않는다.
                    // 유효한 대기는 원본 탑승시간 fallback에도 더하고, 결측은 합산에서 제외한다.
                    if (canUseWait && !waitApplied) {
                        legs.add(
                                new Leg(
                                        leg.key() + "-wait",
                                        Kind.WAIT,
                                        List.of(),
                                        wait.seconds(),
                                        null,
                                        new Window(wait.source(), asOf, asOf, Instant.MAX),
                                        List.of(
                                                new Boarding(
                                                        wait.vehicleId(),
                                                        wait.boardingAt(),
                                                        wait.boardingAt(),
                                                        false))));
                        cursor = wait.boardingAt();
                        waitApplied = true;
                        busWaitSteps++;
                    }
                    if (!waitApplied) {
                        reasons.add(leg.key() + ":WAIT_TIME_EXCLUDED");
                        waitReasons.add("WAIT_TIME_EXCLUDED");
                    }
                    waitPredictions.add(
                            new WaitPrediction(
                                    original.key(),
                                    leg.key(),
                                    TransportMode.BUS,
                                    station,
                                    wait.seconds(),
                                    wait.boardingAt(),
                                    wait.vehicleId(),
                                    wait.source(),
                                    wait.eventId(),
                                    wait.observedAt(),
                                    wait.validUntil(),
                                    waitApplied,
                                    waitReasons));
                    reasons.addAll(waitReasons.stream().map(r -> leg.key() + ":" + r).toList());
                }
                if (segment != null && segment.mode() == TransportMode.SUBWAY) {
                    // 앞선 도보·탑승·환승의 합으로 승강장 도착을 정하고 이용 가능한 운행을 찾는다.
                    // 성공한 대기와 탑승을 별도 합산하며 provider 총시간은 계산에 사용하지 않는다.
                    Instant platform = cursor.plusSeconds(leg.entryUncertaintySeconds());
                    var timing = subway.estimate(segment, asOf, platform, subwaySession);
                    String reason = timing.arrivalAt() == null ? "SUBWAY_TIMING_UNAVAILABLE" : null;
                    boolean applied = reason == null;
                    var waitReasons = new ArrayList<>(timing.reasons());
                    if (reason != null) waitReasons.add(reason);
                    waitPredictions.add(
                            new WaitPrediction(
                                    original.key(),
                                    leg.key(),
                                    TransportMode.SUBWAY,
                                    platform,
                                    timing.waitSeconds(),
                                    timing.boardingAt(),
                                    timing.tripKey(),
                                    timing.source(),
                                    timing.arrivalEventId(),
                                    timing.observedAt(),
                                    timing.validUntil(),
                                    applied,
                                    waitReasons));
                    subwayPredictions.add(
                            new SubwayPrediction(
                                    original.key(), leg.key(), platform, timing, applied, reason));
                    reasons.addAll(
                            timing.reasons().stream().map(r -> leg.key() + ":" + r).toList());
                    if (applied) {
                        // 이미 asOf와 platform에 대해 검증한 고정 결과다. 실시간 TTL을 연장하는 캐시가 아니며,
                        // 출발시각을 바꾸면 별도 estimate를 수행한다. 환승 후 다음 step도 갱신된 cursor를 쓴다.
                        // 대기는 선로 통과가 아니다. WAIT를 분리하여 탑승 전에 끝난 선로 통제가 경로를 막지 않게 한다.
                        legs.add(
                                new Leg(
                                        leg.key() + "-wait",
                                        Kind.WAIT,
                                        List.of(),
                                        timing.waitSeconds(),
                                        null,
                                        new Window(timing.source(), asOf, asOf, Instant.MAX),
                                        List.of(
                                                new Boarding(
                                                        timing.tripKey(),
                                                        timing.boardingAt(),
                                                        timing.boardingAt(),
                                                        false))));
                        cursor = cursor.plusSeconds(timing.waitSeconds());
                        chosen =
                                new Leg(
                                        leg.key(),
                                        leg.kind(),
                                        leg.segmentIds(),
                                        timing.rideSeconds(),
                                        timing.rideSeconds(),
                                        new Window(timing.source(), asOf, asOf, Instant.MAX),
                                        List.of());
                        subwaySteps++;
                    } else {
                        reasons.add(leg.key() + ":" + reason);
                        // 시간표 계산 실패는 해당 지하철 step만 원본으로 대체한다. 대기는 제외한다.
                        reasons.add(leg.key() + ":WAIT_TIME_EXCLUDED");
                        reasons.add(leg.key() + ":API_FALLBACK");
                    }
                }
                if (segment != null
                        && segment.mode() == TransportMode.BUS
                        && !snapshot.roads().isEmpty()) {
                    roadSteps++;
                    reasons.addAll(
                            RoadImpactEvidence.reasons(
                                    segment,
                                    snapshot.roads(),
                                    cursor,
                                    cursor.plusSeconds(
                                            chosen.fallbackSeconds()
                                                    + leg.entryUncertaintySeconds())));
                }
                if (chosen == leg) apiSteps++;
                legs.add(chosen);
                cursor = cursor.plusSeconds(chosen.fallbackSeconds());
            }
            if (!snapshot.roads().isEmpty()
                    && roadSteps
                            < route.segments().stream()
                                    .filter(s -> s.mode() == TransportMode.BUS)
                                    .count()) reasons.add("ROAD_IMPACT_UNKNOWN");
            Result raw =
                    evaluator.evaluate(
                            new Journey(original.key(), legs),
                            asOf,
                            departure,
                            arriveBy,
                            evidence.closures(snapshot));
            reasons.addAll(raw.reasons());
            reasons.add("VALIDATION_ONLY");
            Result result =
                    new Result(
                            raw.journeyKey(),
                            raw.departureAt(),
                            raw.estimatedArrivalAt(),
                            raw.assessment(),
                            true,
                            raw.steps(),
                            reasons,
                            "transit-prev-v3");
            String quality =
                    mlSteps + referenceSteps + subwaySteps == 0
                            ? "API_ONLY"
                            : busWaitSteps > 0
                                            || apiSteps > 0
                                            || (mlSteps + referenceSteps > 0 && subwaySteps > 0)
                                    ? "MIXED_PREVIEW"
                                    : subwaySteps > 0
                                            ? "SUBWAY_PREVIEW"
                                            : referenceSteps > 0 ? "BUS_REFERENCE" : "ML_PREVIEW";
            results.add(
                    new Candidate(result, quality, mlSteps, apiSteps, subwaySteps, busWaitSteps));
        }
        if (session.mixedModel && applyMode != MlApplyMode.SHADOW) {
            results.replaceAll(
                    c ->
                            new Candidate(
                                    new Result(
                                            c.result().journeyKey(),
                                            departure,
                                            null,
                                            Assessment.INSUFFICIENT_DATA,
                                            true,
                                            List.of(),
                                            List.of("ML_MODEL_VERSION_MISMATCH"),
                                            "cdep-v1"),
                                    "UNAVAILABLE",
                                    0,
                                    0,
                                    0,
                                    0));
            predictions.replaceAll(
                    p ->
                            new Prediction(
                                    p.candidateKey(),
                                    p.stepKey(),
                                    p.segmentId(),
                                    p.enteredAt(),
                                    p.input(),
                                    p.estimate(),
                                    false));
        }
        Result fastest =
                results.stream()
                        .map(Candidate::result)
                        .filter(
                                r ->
                                        r.estimatedArrivalAt() != null
                                                && (r.assessment() == Assessment.ON_TIME_ESTIMATE
                                                        || r.assessment()
                                                                == Assessment.LATE_ESTIMATE))
                        .min(
                                Comparator.comparing(Result::estimatedArrivalAt)
                                        .thenComparing(Result::journeyKey))
                        .orElse(null);
        return new Comparison(
                "PREVIEW",
                false,
                fastest,
                results,
                predictions,
                exhausted || System.nanoTime() >= deadline,
                subwayPredictions,
                waitPredictions);
    }

    /** 서로 다른 모델의 예측을 합친 평가에서는 ETA와 실제 적용 표시를 모두 제거한다. */
    public static Comparison rejectMixedModel(Comparison value) {
        return new Comparison(
                "PREVIEW",
                false,
                null,
                value.candidates().stream()
                        .map(
                                c ->
                                        new Candidate(
                                                new Result(
                                                        c.result().journeyKey(),
                                                        c.result().departureAt(),
                                                        null,
                                                        Assessment.INSUFFICIENT_DATA,
                                                        true,
                                                        List.of(),
                                                        List.of("ML_MODEL_VERSION_MISMATCH"),
                                                        "cdep-v1"),
                                                "UNAVAILABLE",
                                                0,
                                                0,
                                                0,
                                                0))
                        .toList(),
                value.predictions().stream()
                        .map(
                                p ->
                                        new Prediction(
                                                p.candidateKey(),
                                                p.stepKey(),
                                                p.segmentId(),
                                                p.enteredAt(),
                                                p.input(),
                                                p.estimate(),
                                                false))
                        .toList(),
                value.budgetExhausted(),
                List.of(),
                List.of());
    }

    private BusPreviewClient.Contract contract() {
        return applyMode == MlApplyMode.PRODUCTION
                ? BusPreviewClient.Contract.PRODUCTION
                : BusPreviewClient.Contract.PREVIEW;
    }

    private static void trackModel(Session session, BusPreviewClient.Estimate estimate) {
        if (estimate.seconds() == null) return;
        if (estimate.modelVersion() == null || estimate.modelVersion().isBlank())
            session.mixedModel = true;
        else if (session.modelVersion == null) session.modelVersion = estimate.modelVersion();
        else if (!Objects.equals(session.modelVersion, estimate.modelVersion()))
            session.mixedModel = true;
    }

    /**
     * 모드별 적용 가능 여부. null이면 이 edge 수치를 시간 계산에 쓸 수 있다. 실패 사유는 그대로 진단에 남긴다. 세 모드 모두 공통 게이트(숫자 유효·이력 표본
     * 존재)를 통과해야 한다. 시험 적용도 이력 0건·표본 부족 구간에는 쓰지 않는다 — 학습 중앙값으로 나온 숫자를 실제 관측처럼 다루지 않기 위해서다.
     * PRODUCTION만 여기에 운영 계약(승인 표시·학습 ID 검증) 확인을 더한다.
     */
    private String usable(BusPreviewClient.Estimate estimate) {
        String failure = estimate.applicationFailure();
        // 이유 없이 숫자가 없는 응답도 적용 대상이 아니다.
        if (failure == null && !estimate.numeric()) failure = "ML_UNAVAILABLE";
        return switch (applyMode) {
            case SHADOW, TEST_APPLY -> failure;
            case PRODUCTION ->
                    failure != null
                            ? failure
                            : estimate.productionEligible()
                                    ? null
                                    : "ML_PRODUCTION_CONTRACT_NOT_MET";
        };
    }

    /**
     * 모든 후보의 지원되는 BUS edge를 모아 제한된 수의 배치 요청으로 먼저 조회한다. 진입 시간대는 API 소요시간 기준 근사값이며, 앞 edge 예측으로 시간대가
     * 바뀐 edge만 이후 개별 호출한다. 같은 입력은 한 번만 보낸다. 반환값은 ML 예산이 이미 소진되어 선조회를 건너뛰었는지다.
     */
    private boolean prefetch(
            List<RouteCandidate> routes,
            RouteEvidenceAssembler.Snapshot snapshot,
            Instant asOf,
            Instant departure,
            Session session) {
        if (session.prefetched) return false;
        session.prefetched = true;
        var inputs = new LinkedHashSet<BusPreviewClient.Input>();
        for (RouteCandidate route : routes) {
            Journey original = evidence.assemble(route, snapshot, asOf);
            Instant cursor = departure;
            for (Leg leg : original.legs()) {
                RouteSegment segment =
                        route.segments().stream()
                                .filter(s -> ("step-" + s.sequence()).equals(leg.key()))
                                .findFirst()
                                .orElse(null);
                if (segment != null && segment.mode() == TransportMode.BUS) {
                    var edges = RouteEvidenceAssembler.edges(segment);
                    if (mlSupported(edges)) {
                        var start = cursor.atZone(KST);
                        var latest = cursor.plusSeconds(leg.entryUncertaintySeconds()).atZone(KST);
                        if (start.toLocalDate().equals(latest.toLocalDate())
                                && start.getHour() == latest.getHour())
                            for (MappedTransitSegment edge : edges)
                                inputs.add(
                                        new BusPreviewClient.Input(
                                                edge.routeId(),
                                                edge.fromNodeId(),
                                                edge.toNodeId(),
                                                edge.fromOrder(),
                                                start.toLocalDate(),
                                                start.getHour()));
                    }
                }
                Long step = leg.fallbackSeconds() != null ? leg.fallbackSeconds() : leg.seconds();
                cursor = cursor.plusSeconds(step == null ? 0 : step);
            }
        }
        inputs.removeIf(session.cache::containsKey);
        if (inputs.isEmpty()) return false;
        if (System.nanoTime() >= session.mlDeadline
                || !session.roomForMlCall(client.timeoutMillis())) return true;
        var results = new LinkedHashMap<BusPreviewClient.Input, BusPreviewClient.Estimate>();
        var ordered = new ArrayList<>(inputs);
        // 실제 HTTP 청크마다 남은 예산을 검사한다. 미호출 입력도 캐시에 결측으로 넣어
        // 후보별 반복 처리에서 동일 입력을 다시 호출하지 않고 API fallback을 유지한다.
        int batchSize = client.maxBatchSegments() > 0 ? client.maxBatchSegments() : 200;
        for (int from = 0; from < ordered.size(); from += batchSize) {
            var chunk = ordered.subList(from, Math.min(ordered.size(), from + batchSize));
            if (System.nanoTime() >= session.mlDeadline
                    || !session.roomForMlCall(client.timeoutMillis())) {
                for (var input : chunk)
                    results.put(
                            input, BusPreviewClient.Estimate.unavailable("ML_BUDGET_EXHAUSTED"));
            } else results.putAll(client.predictBatch(chunk, contract()));
        }
        for (var entry : results.entrySet()) {
            trackModel(session, entry.getValue());
            session.cache.put(entry.getKey(), entry.getValue());
        }
        return false;
    }

    /** 정적 마스터에서 확정된 한 출처의 인접 edge인지 검사한다. 실시간 crosswalk는 요구하지 않는다. */
    private boolean topologySupported(List<MappedTransitSegment> edges) {
        if (edges.isEmpty()) return false;
        for (int i = 0; i < edges.size(); i++) {
            var e = edges.get(i);
            if (e.mode() != TransportMode.BUS
                    || !component(e.routeId())
                    || !component(e.fromNodeId())
                    || !component(e.toNodeId())
                    || !e.segmentId()
                            .equals(
                                    "BUS:"
                                            + e.routeId()
                                            + ":"
                                            + e.fromNodeId()
                                            + ":"
                                            + e.toNodeId())
                    || e.fromOrder() < 1
                    || e.fromOrder() > 10000
                    || e.toOrder() != e.fromOrder() + 1
                    || e.sequence() != i + 1) return false;
            if (i > 0
                    && (!edges.get(i - 1).routeId().equals(e.routeId())
                            || !edges.get(i - 1).toNodeId().equals(e.fromNodeId())
                            || edges.get(i - 1).toOrder() != e.fromOrder())) return false;
        }
        return true;
    }

    /** 현재 ML bundle의 학습·HTTP 계약은 서울 숫자 ID만 지원한다. */
    private boolean mlSupported(List<MappedTransitSegment> edges) {
        if (!topologySupported(edges)) return false;
        return edges.stream()
                .allMatch(
                        e ->
                                numeric(e.routeId())
                                        && numeric(e.fromNodeId())
                                        && numeric(e.toNodeId())
                                        && Objects.equals(e.routeId(), e.realtimeRouteId())
                                        && Objects.equals(e.fromNodeId(), e.fromRealtimeNodeId())
                                        && Objects.equals(e.toNodeId(), e.toRealtimeNodeId()));
    }

    private boolean component(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,80}");
    }

    private boolean numeric(String value) {
        return value != null && value.matches("[0-9]{1,30}");
    }
}
