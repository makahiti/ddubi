package com.ssafy.ddubi.domain.recommendation.service;

import static com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.*;

import com.ssafy.ddubi.domain.recommendation.entity.Confidence;
import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import com.ssafy.ddubi.domain.recommendation.evaluation.*;
import com.ssafy.ddubi.domain.route.client.PublicTransitRouteClient;
import com.ssafy.ddubi.domain.route.model.*;
import com.ssafy.ddubi.global.exception.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 사용자 출발 입력→후보 확보→구간 매핑→버스 ML 검증→최소시간 후보·증거 저장의 실행 흐름이다. 외부 I/O를 DB 트랜잭션 밖에서 수행하며 실패는 저장소가 기록한다. 미래
 * 배차를 만들지 않으며 미승인 ML은 PREVIEW 비교 결과로만 사용한다. 최초 평가에서 저장한 대중교통 후보와 도보시간은 같은 일정 입력 revision 동안 고정하며,
 * 이후 평가는 실시간 교통·통제 근거만 다시 읽는다.
 */
@Service
public class ScheduleEvaluationWorkflow {
    private final ScheduleEvaluationStore store;
    private final PublicTransitRouteClient routes;
    private final RouteEvidenceAssembler evidence;
    private final ConditionalDepartureEvaluator conditional;
    private final EvaluationNotificationPlanner notifications;
    private final ScheduleMonitoringPolicy monitoring;
    private final com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService walking;
    private final com.ssafy.ddubi.domain.route.service.RouteMappingRefresher mappingRefresher;
    private final Budget budget;
    private final RoadClosureDemoSupport roadClosureDemo;

    /**
     * 트리거별 계산 예산(ms). 최초 등록은 화면이 "최대 30초" 안내와 함께 기다리고, 자동 갱신은 백그라운드, 사용자 수동 갱신은 "최대 10초" 안내다. 상한이며
     * 계산이 끝나면 즉시 응답한다. 수동 갱신은 REFRESH에 사용자 ID가 함께 오는 경우(컨트롤러)이고, 사용자 ID가 없는 REFRESH·EVENT는 서버 자동
     * 갱신이다.
     */
    public record Budget(long initialMillis, long autoRefreshMillis, long manualRefreshMillis) {
        public static final Budget DEFAULT = new Budget(30000, 15000, 10000);

        public Budget {
            for (long value : new long[] {initialMillis, autoRefreshMillis, manualRefreshMillis})
                if (value < 1 || value > 60000) throw new IllegalArgumentException("계산 예산 범위 오류");
        }

        public long forTrigger(TriggerType trigger, Long userId) {
            if (trigger == TriggerType.INITIAL) return initialMillis;
            if (trigger == TriggerType.REFRESH && userId != null) return manualRefreshMillis;
            return autoRefreshMillis;
        }
    }

    public ScheduleEvaluationWorkflow(
            ScheduleEvaluationStore store,
            PublicTransitRouteClient routes,
            RouteEvidenceAssembler evidence,
            ConditionalDepartureEvaluator conditional,
            EvaluationNotificationPlanner notifications,
            com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService walking) {
        this(store, routes, evidence, conditional, notifications, walking, null);
    }

    public ScheduleEvaluationWorkflow(
            ScheduleEvaluationStore store,
            PublicTransitRouteClient routes,
            RouteEvidenceAssembler evidence,
            ConditionalDepartureEvaluator conditional,
            EvaluationNotificationPlanner notifications,
            com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService walking,
            com.ssafy.ddubi.domain.route.service.RouteMappingRefresher mappingRefresher) {
        this(
                store,
                routes,
                evidence,
                conditional,
                notifications,
                walking,
                mappingRefresher,
                Budget.DEFAULT);
    }

    /** 운영에서는 저장 후보 재사용에도 현재 매핑 판정을 적용한다. 매핑 갱신은 외부 경로 재조회를 뜻하지 않는다. */
    @org.springframework.beans.factory.annotation.Autowired
    public ScheduleEvaluationWorkflow(
            ScheduleEvaluationStore store,
            PublicTransitRouteClient routes,
            RouteEvidenceAssembler evidence,
            ConditionalDepartureEvaluator conditional,
            EvaluationNotificationPlanner notifications,
            com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService walking,
            com.ssafy.ddubi.domain.route.service.RouteMappingRefresher mappingRefresher,
            @Value("${ddubi.recommendation.budget.initial-millis:30000}") long initialBudgetMillis,
            @Value("${ddubi.recommendation.budget.auto-refresh-millis:15000}")
                    long autoRefreshBudgetMillis,
            @Value("${ddubi.recommendation.budget.manual-refresh-millis:10000}")
                    long manualRefreshBudgetMillis,
            RoadClosureDemoSupport roadClosureDemo) {
        this(
                store,
                routes,
                evidence,
                conditional,
                notifications,
                walking,
                mappingRefresher,
                new Budget(initialBudgetMillis, autoRefreshBudgetMillis, manualRefreshBudgetMillis),
                roadClosureDemo);
    }

    public ScheduleEvaluationWorkflow(
            ScheduleEvaluationStore store,
            PublicTransitRouteClient routes,
            RouteEvidenceAssembler evidence,
            ConditionalDepartureEvaluator conditional,
            EvaluationNotificationPlanner notifications,
            com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService walking,
            com.ssafy.ddubi.domain.route.service.RouteMappingRefresher mappingRefresher,
            Budget budget) {
        this(
                store,
                routes,
                evidence,
                conditional,
                notifications,
                walking,
                mappingRefresher,
                budget,
                null);
    }

    public ScheduleEvaluationWorkflow(
            ScheduleEvaluationStore store,
            PublicTransitRouteClient routes,
            RouteEvidenceAssembler evidence,
            ConditionalDepartureEvaluator conditional,
            EvaluationNotificationPlanner notifications,
            com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService walking,
            com.ssafy.ddubi.domain.route.service.RouteMappingRefresher mappingRefresher,
            Budget budget,
            RoadClosureDemoSupport roadClosureDemo) {
        this.store = store;
        this.routes = routes;
        this.evidence = evidence;
        this.conditional = conditional;
        this.notifications = notifications;
        this.walking = walking;
        this.budget = Objects.requireNonNull(budget);
        this.roadClosureDemo = roadClosureDemo;
        this.mappingRefresher = mappingRefresher;
        // 초기 실험 주기이며 검증된 도착 보장 임계값이 아니다. 여유시간으로 가산하지 않는다.
        monitoring =
                new ScheduleMonitoringPolicy(
                        Duration.ofHours(1),
                        Duration.ofMinutes(30),
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(1));
    }

    /** 후보별 현재 위험은 수치화하지 않고 별도로 표시한다. */
    public record CandidateInfo(String key, List<String> risks, List<RouteStep> steps) {}

    /** 화면에서 변경 경로를 확인하는 최소 정보. 외부 raw 응답은 포함하지 않는다. */
    public record RouteStep(String mode, String fromName, String toName, List<String> vehicles) {}

    /**
     * 추천 행을 남겼는지와 그 근거의 출처다. 화면이 Outcome 전체를 파싱하지 않고 <b>"검증용"</b> 표시와 <b>"왜 추천이 없는가"</b>를 읽을 수 있게
     * 한다.
     *
     * <h3>왜 preview 라는 이유로 저장을 막지 않나</h3>
     *
     * 예측 서비스가 미승인({@code production_ready=false})이라 지금은 <b>모든</b> 결과가 PREVIEW다. 그걸 이유로 저장을 막으면 추천
     * 행이 하나도 안 생기고, 최신 추천·추천 근거 API가 영영 빈손이다(2026-09-14 합의). 대신 검증용이라는 사실을 지우지 않고 이 값으로 함께 남긴다.
     * <b>일정 변경(accept)은 계속 막는다</b> — 저장은 되돌릴 수 있지만 사용자의 출발 시각을 실제로 바꾸는 것은 성격이 다르다. 자동 알림은 설정으로
     * 연다(기본 꺼짐, 2026-09-15 프론트 요청). {@link EvaluationNotificationPlanner} 참고.
     *
     * @param saved 추천 행을 남겼는가
     * @param reason 저장하지 않은 사유. saved=true면 null이다
     * @param preview 검증용 결과인가. 지금은 항상 true다
     * @param status 비교기가 매긴 상태 그대로. 현재는 {@code "PREVIEW"}
     * @param productionReady 모델 승인 여부. 지금은 항상 false다
     * @param confidence 저장했거나 저장했을 신뢰도. 미승인 모델이라 지금은 항상 LOW다. 추천 근거 API를 한 번 더 부르지 않고도 화면이 등급을 띄울 수
     *     있게 여기 둔다
     * @param source 선택 후보의 계산 출처 (API_ONLY / ML_PREVIEW / SUBWAY_PREVIEW / MIXED_PREVIEW)
     * @param fallbackUsed 경로 API 시간을 그대로 쓴 step이 있는가. 도보도 경로 API라 사실상 항상 true다 — 출처를 구분하려면 {@code
     *     source}와 step 개수를 봐야 한다
     * @param reasons 선택 후보의 사유 전체. 데이터 부족 사유가 여기 들어간다. 서버가 "부족"만 골라내지 않는 이유는, 분류 규칙이 코드마다 갈라지면 화면에
     *     보이는 근거와 저장된 근거가 어긋나기 때문이다
     */
    public record Persistence(
            boolean saved,
            String reason,
            boolean preview,
            String status,
            boolean productionReady,
            Confidence confidence,
            String source,
            boolean fallbackUsed,
            int mlSteps,
            int apiSteps,
            int subwaySteps,
            int busWaitSteps,
            List<String> reasons) {
        public Persistence {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    /**
     * 계산 시각의 입력·후보·증거를 보존한다. 내부 Instant는 절대시각이고 프론트는 KST로 표시한다. selected는 기존 선택 경로,
     * preview.fastest는 같은 출발의 최소시간 검증 후보다. suggestion.proposed는 조건부 탐색 제안이며 PREVIEW는 수락을 허용하지 않는다.
     * recommendationId·persistence는 저장 결과이며 store가 채운다.
     */
    public record Outcome(
            String evaluationId,
            long scheduleId,
            Long recommendationId,
            Instant plannedDepartAt,
            Instant arriveBy,
            Instant checkedAt,
            String selectedKey,
            Result selected,
            DepartureSuggestionService.Suggestion suggestion,
            List<CandidateInfo> candidates,
            RouteEvidenceAssembler.Snapshot evidence,
            TriggerType trigger,
            String policyVersion,
            long eventRevision,
            MlRoutePreview.Comparison preview,
            Persistence persistence,
            ConditionalDepartureEvaluator.Evaluation conditional,
            DepartureEstimate departureEstimate) {
        public Outcome {
            candidates = List.copyOf(candidates);
        }

        /** v1 저장 JSON/내부 호출을 읽는 호환 생성자. 신규 계산만 conditional을 가진다. */
        public Outcome(
                String evaluationId,
                long scheduleId,
                Long recommendationId,
                Instant plannedDepartAt,
                Instant arriveBy,
                Instant checkedAt,
                String selectedKey,
                Result selected,
                DepartureSuggestionService.Suggestion suggestion,
                List<CandidateInfo> candidates,
                RouteEvidenceAssembler.Snapshot evidence,
                TriggerType trigger,
                String policyVersion,
                long eventRevision,
                MlRoutePreview.Comparison preview,
                Persistence persistence) {
            this(
                    evaluationId,
                    scheduleId,
                    recommendationId,
                    plannedDepartAt,
                    arriveBy,
                    checkedAt,
                    selectedKey,
                    selected,
                    suggestion,
                    candidates,
                    evidence,
                    trigger,
                    policyVersion,
                    eventRevision,
                    preview,
                    persistence,
                    null,
                    null);
        }

        /** 기존 v2 저장 JSON과 호출자의 생성자 호환. 출발 후 결과는 별도 필드에만 채운다. */
        public Outcome(
                String evaluationId,
                long scheduleId,
                Long recommendationId,
                Instant plannedDepartAt,
                Instant arriveBy,
                Instant checkedAt,
                String selectedKey,
                Result selected,
                DepartureSuggestionService.Suggestion suggestion,
                List<CandidateInfo> candidates,
                RouteEvidenceAssembler.Snapshot evidence,
                TriggerType trigger,
                String policyVersion,
                long eventRevision,
                MlRoutePreview.Comparison preview,
                Persistence persistence,
                ConditionalDepartureEvaluator.Evaluation conditional) {
            this(
                    evaluationId,
                    scheduleId,
                    recommendationId,
                    plannedDepartAt,
                    arriveBy,
                    checkedAt,
                    selectedKey,
                    selected,
                    suggestion,
                    candidates,
                    evidence,
                    trigger,
                    policyVersion,
                    eventRevision,
                    preview,
                    persistence,
                    conditional,
                    null);
        }

        public Outcome withDepartureEstimate(DepartureEstimate value) {
            return new Outcome(
                    evaluationId,
                    scheduleId,
                    recommendationId,
                    plannedDepartAt,
                    arriveBy,
                    checkedAt,
                    selectedKey,
                    selected,
                    suggestion,
                    candidates,
                    evidence,
                    trigger,
                    policyVersion,
                    eventRevision,
                    preview,
                    persistence,
                    conditional,
                    value);
        }

        public Outcome withRecommendationId(Long id) {
            return new Outcome(
                    evaluationId,
                    scheduleId,
                    id,
                    plannedDepartAt,
                    arriveBy,
                    checkedAt,
                    selectedKey,
                    selected,
                    suggestion,
                    candidates,
                    evidence,
                    trigger,
                    policyVersion,
                    eventRevision,
                    preview,
                    persistence,
                    conditional,
                    departureEstimate);
        }

        public Outcome withPersistence(Persistence value) {
            return new Outcome(
                    evaluationId,
                    scheduleId,
                    recommendationId,
                    plannedDepartAt,
                    arriveBy,
                    checkedAt,
                    selectedKey,
                    selected,
                    suggestion,
                    candidates,
                    evidence,
                    trigger,
                    policyVersion,
                    eventRevision,
                    preview,
                    value,
                    conditional,
                    departureEstimate);
        }
    }

    /** 직접 요청과 scheduler가 같은 흐름을 사용한다. userId=null은 서버 scheduler 전용이며 외부 API는 사용자 ID를 전달한다. */
    public Outcome evaluate(long scheduleId, Long userId, TriggerType trigger, Instant now) {
        var job = store.claim(scheduleId, userId, now);
        try {
            var cache = job.cache();
            // 외부 경로 API는 일정 입력 revision에 저장 후보가 없을 때 한 번만 호출한다.
            // 통제·지연은 저장 후보 중 대안을 다시 선택할 근거이며 자동 경로 재검색 사유가 아니다.
            boolean candidatesChanged = cache == null;
            RouteSearchResult search =
                    cache == null
                            ? routes.search(new RouteSearchQuery(job.origin(), job.destination()))
                            : cache.search();
            if (cache != null && mappingRefresher != null) {
                var remapped = BusAlternativeExpansion.expand(mappingRefresher.refresh(search));
                // canonical 매핑만 현재 마스터로 보강한다. 경로 좌표·도보시간·provider fetchedAt은 보존한다.
                candidatesChanged = !remapped.equals(search);
                search = remapped;
            }
            var expanded = BusAlternativeExpansion.expand(search);
            candidatesChanged |= !expanded.equals(search);
            search = expanded;

            // 발표용 통제는 일정별 Redis 스냅샷으로만 결합한다. 공용 구간 상태를 바꾸지 않는다.
            var snapshot = evidence.read(search.candidates(), now);
            if (roadClosureDemo != null)
                snapshot =
                        roadClosureDemo.overlay(
                                scheduleId,
                                job.inputRevision(),
                                snapshot,
                                search.candidates(),
                                now);
            com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService.Prepared enriched;
            if (cache != null && cache.walkingEvidence() != null) {
                // 캐시에 저장된 후보에는 이미 도보시간이 반영돼 있다. 근거까지 함께 복원하여
                // 일정 자동 평가와 사용자 수동 새로고침 모두 도보 API를 다시 호출하지 않는다.
                enriched =
                        new com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService.Prepared(
                                search.candidates(), cache.walkingEvidence());
            } else {
                enriched =
                        walking.retainUntil(
                                walking.prepare(
                                        search.candidates(),
                                        job.origin(),
                                        job.destination(),
                                        now,
                                        selectedKey(job, search)),
                                job.arriveBy());
                search =
                        new RouteSearchResult(
                                search.provider(),
                                search.fetchedAt(),
                                search.landingUrl(),
                                enriched.candidates());
                candidatesChanged = true;
            }
            long budgetMillis = budget.forTrigger(trigger, userId);
            var calculation =
                    conditional
                            .evaluate(
                                    enriched.candidates(),
                                    snapshot,
                                    now,
                                    job.departure(),
                                    job.arriveBy(),
                                    budgetMillis)
                            .withWalking(enriched.evidence());
            var comparison = calculation.proposedComparison();
            var suggestion = calculation.suggestion();
            String selectedKey = selectedKey(job, search);
            Result selected = selected(suggestion, selectedKey);

            var info = new ArrayList<CandidateInfo>();
            // 최초 선택은 provider 배열 첫 항목이 아니라 P에서 평가한 최선이다.
            // 이미 사용자가 선택한 경로는 제안 수락 전까지 자동 변경하지 않는다.
            if (job.cache() == null && calculation.original() != null) {
                selectedKey = calculation.original().journeyKey();
                selected = calculation.original();
                if (roadClosureDemo != null && roadClosureDemo.eligible(job)) {
                    // 시연 제목·서울 좌표·서울 공식 버스 edge·우회 후보가 모두 있어야만 최초 선택을 바꾼다.
                    var bus =
                            RoadClosureDemoSupport.initialBusChoice(
                                    search.candidates(), suggestion.originalCandidates());
                    if (bus.isPresent()) {
                        selected = bus.get();
                        selectedKey = selected.journeyKey();
                    }
                }
            }
            // 몇 초 빠른 다른 경로를 "다른 경로 제안" 으로 띄우지 않는다. 지금 선택을 제안으로 둔다.
            var steady = keepSelectedWhenGainIsSmall(calculation, selected);
            if (steady != calculation) {
                calculation = steady;
                suggestion = steady.suggestion();
            }
            for (RouteCandidate c : search.candidates())
                info.add(
                        new CandidateInfo(
                                RouteEvidenceAssembler.key(c),
                                evidence.riskReasons(c, snapshot),
                                c.segments().stream()
                                        .map(
                                                s ->
                                                        new RouteStep(
                                                                s.mode().name(),
                                                                s.fromName(),
                                                                s.toName(),
                                                                BusAlternativeExpansion
                                                                        .displayedVehicles(s)))
                                        .toList()));
            var result =
                    new Outcome(
                            job.token(),
                            scheduleId,
                            null,
                            job.departure(),
                            job.arriveBy(),
                            now,
                            selectedKey,
                            selected,
                            suggestion,
                            info,
                            snapshot,
                            trigger,
                            "cdep-v1",
                            job.eventRevision(),
                            comparison,
                            null,
                            calculation);
            // 기본 예상과 시험 ML의 수락 가능 여부는 EvaluationGuidance에서 구분한다.
            // 알림은 설정으로 연다(기본 꺼짐). 보낼 것이 없으면 두 값이 null 이고,
            // 그때 dispatcher 는 아무것도 하지 않는다. EvaluationNotificationPlanner 참고.
            var planned = notifications.plan(job, result);
            Instant proposed =
                    suggestion.proposed() == null ? null : suggestion.proposed().departureAt();
            return store.complete(
                    job,
                    search,
                    candidatesChanged,
                    result,
                    monitoring.nextEvaluation(now, job.departure(), proposed),
                    planned.map(p -> store.write(p.command())).orElse(null),
                    planned.map(EvaluationNotificationPlanner.Planned::key).orElse(null),
                    Instant.now());
        } catch (RuntimeException failure) {
            String reason =
                    failure instanceof BusinessException be
                                    && be.errorCode() == ErrorCode.STALE_RECOMMENDATION
                            ? "STALE_RECOMMENDATION"
                            : failure instanceof BusinessException
                                    ? "EVALUATION_UNAVAILABLE"
                                    : "EVALUATION_FAILED";
            store.fail(job, reason, now);
            throw failure;
        }
    }

    /** 경로를 바꾸자고 할 만한 도착 이득. 2026-09-24 에 3초 빠른 경로로 "다른 경로 제안" 이 떴다. */
    static final java.time.Duration PROPOSE_ROUTE_GAIN = java.time.Duration.ofMinutes(2);

    /**
     * 제안 경로가 지금 선택보다 {@link #PROPOSE_ROUTE_GAIN} 넘게 빠르지 않으면 지금 선택을 제안으로 둔다.
     *
     * <p>바꾸지 않는 경우: 지금 선택이 늦을 것 같을 때(조금이라도 빠른 길이 중요하다), 지금 선택에 도로 사고가 걸렸을 때(사고 없는 대안을 권하는 것이 목적이다),
     * 출발 시각이 서로 다를 때(경로가 아니라 출발 시각을 권하는 경우다).
     */
    static ConditionalDepartureEvaluator.Evaluation keepSelectedWhenGainIsSmall(
            ConditionalDepartureEvaluator.Evaluation calculation, Result selected) {
        Result proposed = calculation.proposed();
        if (selected == null
                || proposed == null
                || selected.journeyKey().equals(proposed.journeyKey())
                || selected.assessment() != Assessment.ON_TIME_ESTIMATE
                || selected.estimatedArrivalAt() == null
                || proposed.estimatedArrivalAt() == null
                || !java.util.Objects.equals(selected.departureAt(), proposed.departureAt())
                || selected.reasons().stream()
                        .anyMatch(r -> r.startsWith("ROAD_POTENTIAL_IMPACT:"))) return calculation;
        var gain =
                java.time.Duration.between(
                        proposed.estimatedArrivalAt(), selected.estimatedArrivalAt());
        return gain.compareTo(PROPOSE_ROUTE_GAIN) < 0
                ? calculation.withProposed(selected)
                : calculation;
    }

    private Result selected(DepartureSuggestionService.Suggestion suggestion, String key) {
        if (suggestion.originalCandidates().isEmpty())
            return new Result(
                    "unavailable",
                    Instant.EPOCH,
                    null,
                    Assessment.BLOCKED,
                    true,
                    List.of(),
                    List.of("ROUTE_UNAVAILABLE"),
                    "cdep-v1");
        if ("previous-unavailable".equals(key)) {
            return new Result(
                    key,
                    suggestion.originalCandidates().get(0).departureAt(),
                    null,
                    Assessment.BLOCKED,
                    true,
                    List.of(),
                    List.of("SELECTED_ROUTE_UNAVAILABLE"),
                    "timeline-v1");
        }
        return suggestion.originalCandidates().stream()
                .filter(r -> r.journeyKey().equals(key))
                .findFirst()
                .orElse(suggestion.originalCandidates().get(0));
    }

    /**
     * 먼저 기존 canonical occurrence까지 일치하는 선택을 찾는다. 없으면 원본 차량·정류장·형상이 같고 이전에 비어 있던 매핑만 보강된 유일한 후보를
     * 이어받는다. 기존 확정 edge의 교체나 여러 차량으로 갈라지는 선택은 자동 승인하지 않고 선택 소실로 남긴다. 성공한 새 후보는 complete에서 캐시의
     * selectedCandidate와 DB 선택을 함께 교체한다.
     */
    private String selectedKey(ScheduleEvaluationStore.Job job, RouteSearchResult search) {
        if (job.cache() != null) {
            var previous = Optional.ofNullable(job.cache().selectedCandidate());
            if (previous.isPresent()) {
                String signature = signature(previous.get());
                var matches =
                        search.candidates().stream()
                                .filter(c -> signature(c).equals(signature))
                                .toList();
                if (matches.size() == 1) return RouteEvidenceAssembler.key(matches.get(0));
                if (matches.isEmpty()) {
                    var enriched =
                            search.candidates().stream()
                                    .filter(c -> canEnrichSelection(previous.get(), c))
                                    .toList();
                    if (enriched.size() == 1) return RouteEvidenceAssembler.key(enriched.get(0));
                }
            }
            return "previous-unavailable";
        }
        return search.candidates().isEmpty()
                ? "previous-unavailable"
                : RouteEvidenceAssembler.key(search.candidates().get(0));
    }

    private String signature(RouteCandidate candidate) {
        return store.write(
                candidate.segments().stream()
                        .map(
                                s ->
                                        Arrays.asList(
                                                s.mode(),
                                                s.providerRouteKey(),
                                                s.fromName(),
                                                s.toName(),
                                                s.stopNames(),
                                                s.vehicles(),
                                                s.geometry(),
                                                RouteEvidenceAssembler.pathIdentity(s)))
                        .toList());
    }

    /**
     * 미매핑 캐시를 현재 마스터로 보강하는 호환 검사다. 외부 I/O와 상태 변경은 없다. 1. 원본 이동 증거를 비교 → 2. 기존 확정 occurrence를 보존 →
     * 3. 새 매핑의 EXACT를 확인한다. 비어 있던 edge를 채우는 경우만 허용하며, 후보 유일성은 호출자가 전체 목록에서 확인한다.
     */
    private boolean canEnrichSelection(RouteCandidate previous, RouteCandidate current) {
        if (previous.segments().size() != current.segments().size()) return false;
        for (int i = 0; i < previous.segments().size(); i++) {
            var before = previous.segments().get(i);
            var after = current.segments().get(i);
            if (!providerIdentity(before).equals(providerIdentity(after))) return false;
            var oldPath = RouteEvidenceAssembler.pathIdentity(before);
            var newPath = RouteEvidenceAssembler.pathIdentity(after);
            // 같은 정류장 쌍도 순환 노선의 승차 순번이 다르면 다른 선택이다.
            if (!oldPath.isEmpty() && !oldPath.equals(newPath)) return false;
            if (oldPath.isEmpty()
                    && !newPath.isEmpty()
                    && after.mapping().status()
                            != com.ssafy.ddubi.domain.route.mapping.TransitMappingStatus.EXACT)
                return false;
        }
        return true;
    }

    private List<Object> providerIdentity(RouteSegment segment) {
        return Arrays.asList(
                segment.mode(),
                segment.providerRouteKey(),
                segment.fromName(),
                segment.toName(),
                segment.stopNames(),
                segment.vehicles(),
                segment.geometry());
    }
}
