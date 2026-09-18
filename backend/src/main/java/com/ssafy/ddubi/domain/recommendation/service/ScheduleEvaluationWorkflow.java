package com.ssafy.ddubi.domain.recommendation.service;

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
import static com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.*;

/**
 * 사용자 출발 입력→후보 확보→구간 매핑→버스 ML 검증→최소시간 후보·증거 저장의 실행 흐름이다.
 * 외부 I/O를 DB 트랜잭션 밖에서 수행하며 실패는 저장소가 기록한다. 미래 배차를 만들지 않으며
 * 미승인 ML은 PREVIEW 비교 결과로만 사용한다. 카카오 요청 주기와 내부 평가 주기는 별도 정책이다.
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

    /**
     * 트리거별 계산 예산(ms). 최초 등록은 화면이 "최대 30초" 안내와 함께 기다리고, 자동 갱신은 백그라운드,
     * 사용자 수동 갱신은 "최대 10초" 안내다. 상한이며 계산이 끝나면 즉시 응답한다.
     * 수동 갱신은 REFRESH에 사용자 ID가 함께 오는 경우(컨트롤러)이고, 사용자 ID가 없는 REFRESH·EVENT는 서버 자동 갱신이다.
     */
    public record Budget(long initialMillis, long autoRefreshMillis, long manualRefreshMillis) {
        public static final Budget DEFAULT = new Budget(30000, 15000, 10000);
        public Budget {
            for (long value : new long[]{initialMillis, autoRefreshMillis, manualRefreshMillis})
                if (value < 1 || value > 60000) throw new IllegalArgumentException("계산 예산 범위 오류");
        }
        public long forTrigger(TriggerType trigger, Long userId) {
            if (trigger == TriggerType.INITIAL) return initialMillis;
            if (trigger == TriggerType.REFRESH && userId != null) return manualRefreshMillis;
            return autoRefreshMillis;
        }
    }

    public ScheduleEvaluationWorkflow(ScheduleEvaluationStore store, PublicTransitRouteClient routes,
            RouteEvidenceAssembler evidence, ConditionalDepartureEvaluator conditional, EvaluationNotificationPlanner notifications,
            com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService walking,
            @Value("${ddubi.recommendation.candidate-ttl-seconds:900}") int candidateTtl) {
        this(store, routes, evidence, conditional, notifications, walking, candidateTtl, null);
    }
    public ScheduleEvaluationWorkflow(ScheduleEvaluationStore store, PublicTransitRouteClient routes,
            RouteEvidenceAssembler evidence, ConditionalDepartureEvaluator conditional, EvaluationNotificationPlanner notifications,
            com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService walking, int candidateTtl,
            com.ssafy.ddubi.domain.route.service.RouteMappingRefresher mappingRefresher) {
        this(store, routes, evidence, conditional, notifications, walking, candidateTtl, mappingRefresher, Budget.DEFAULT);
    }

    /** 운영에서는 캐시 재사용에도 현재 매핑 판정을 적용한다. 외부 API TTL과 매핑 버전 수명은 별개다. */
    @org.springframework.beans.factory.annotation.Autowired
    public ScheduleEvaluationWorkflow(ScheduleEvaluationStore store, PublicTransitRouteClient routes,
            RouteEvidenceAssembler evidence, ConditionalDepartureEvaluator conditional, EvaluationNotificationPlanner notifications,
            com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService walking,
            @Value("${ddubi.recommendation.candidate-ttl-seconds:900}") int candidateTtl,
            com.ssafy.ddubi.domain.route.service.RouteMappingRefresher mappingRefresher,
            @Value("${ddubi.recommendation.budget.initial-millis:30000}") long initialBudgetMillis,
            @Value("${ddubi.recommendation.budget.auto-refresh-millis:15000}") long autoRefreshBudgetMillis,
            @Value("${ddubi.recommendation.budget.manual-refresh-millis:10000}") long manualRefreshBudgetMillis) {
        this(store, routes, evidence, conditional, notifications, walking, candidateTtl, mappingRefresher,
                new Budget(initialBudgetMillis, autoRefreshBudgetMillis, manualRefreshBudgetMillis));
    }
    public ScheduleEvaluationWorkflow(ScheduleEvaluationStore store, PublicTransitRouteClient routes,
            RouteEvidenceAssembler evidence, ConditionalDepartureEvaluator conditional, EvaluationNotificationPlanner notifications,
            com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService walking, int candidateTtl,
            com.ssafy.ddubi.domain.route.service.RouteMappingRefresher mappingRefresher, Budget budget) {
        this.store = store; this.routes = routes; this.evidence = evidence; this.conditional = conditional;
        this.notifications = notifications; this.walking=walking; this.budget = Objects.requireNonNull(budget);
        this.mappingRefresher = mappingRefresher;
        // 초기 실험 주기이며 검증된 도착 보장 임계값이 아니다. 여유시간으로 가산하지 않는다.
        monitoring = new ScheduleMonitoringPolicy(Duration.ofHours(1), Duration.ofMinutes(30), Duration.ofMinutes(10),
                Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofSeconds(candidateTtl));
    }

    /** 후보별 현재 위험은 수치화하지 않고 별도로 표시한다. */
    public record CandidateInfo(String key, List<String> risks, List<RouteStep> steps) { }
    /** 화면에서 변경 경로를 확인하는 최소 정보. 외부 raw 응답은 포함하지 않는다. */
    public record RouteStep(String mode, String fromName, String toName, List<String> vehicles) { }
    /**
     * 추천 행을 남겼는지와 그 근거의 출처다. 화면이 Outcome 전체를 파싱하지 않고
     * <b>"검증용"</b> 표시와 <b>"왜 추천이 없는가"</b>를 읽을 수 있게 한다.
     *
     * <h3>왜 preview 라는 이유로 저장을 막지 않나</h3>
     * 예측 서비스가 미승인({@code production_ready=false})이라 지금은 <b>모든</b> 결과가
     * PREVIEW다. 그걸 이유로 저장을 막으면 추천 행이 하나도 안 생기고, 최신 추천·추천 근거
     * API가 영영 빈손이다(2026-09-14 합의). 대신 검증용이라는 사실을 지우지 않고 이 값으로
     * 함께 남긴다. <b>일정 변경(accept)은 계속 막는다</b> — 저장은 되돌릴 수 있지만 사용자의
     * 출발 시각을 실제로 바꾸는 것은 성격이 다르다. 자동 알림은 설정으로 연다(기본 꺼짐,
     * 2026-09-15 프론트 요청). {@link EvaluationNotificationPlanner} 참고.
     *
     * @param saved     추천 행을 남겼는가
     * @param reason    저장하지 않은 사유. saved=true면 null이다
     * @param preview   검증용 결과인가. 지금은 항상 true다
     * @param status    비교기가 매긴 상태 그대로. 현재는 {@code "PREVIEW"}
     * @param productionReady 모델 승인 여부. 지금은 항상 false다
     * @param confidence 저장했거나 저장했을 신뢰도. 미승인 모델이라 지금은 항상 LOW다.
     *                  추천 근거 API를 한 번 더 부르지 않고도 화면이 등급을 띄울 수 있게 여기 둔다
     * @param source    선택 후보의 계산 출처 (API_ONLY / ML_PREVIEW / SUBWAY_PREVIEW / MIXED_PREVIEW)
     * @param fallbackUsed 경로 API 시간을 그대로 쓴 step이 있는가. 도보도 경로 API라 사실상 항상
     *                  true다 — 출처를 구분하려면 {@code source}와 step 개수를 봐야 한다
     * @param reasons   선택 후보의 사유 전체. 데이터 부족 사유가 여기 들어간다. 서버가 "부족"만
     *                  골라내지 않는 이유는, 분류 규칙이 코드마다 갈라지면 화면에 보이는 근거와
     *                  저장된 근거가 어긋나기 때문이다
     */
    public record Persistence(boolean saved, String reason, boolean preview, String status, boolean productionReady,
            Confidence confidence, String source, boolean fallbackUsed, int mlSteps, int apiSteps, int subwaySteps,
            int busWaitSteps, List<String> reasons) {
        public Persistence { reasons = reasons == null ? List.of() : List.copyOf(reasons); }
    }

    /**
     * 계산 시각의 입력·후보·증거를 보존한다. 내부 Instant는 절대시각이고 프론트는 KST로 표시한다.
     * selected는 기존 선택 경로, preview.fastest는 같은 출발의 최소시간 검증 후보다.
     * suggestion.proposed는 조건부 탐색 제안이며 PREVIEW는 수락을 허용하지 않는다.
     * recommendationId·persistence는 저장 결과이며 store가 채운다.
     */
    public record Outcome(String evaluationId, long scheduleId, Long recommendationId, Instant plannedDepartAt,
            Instant arriveBy, Instant checkedAt, String selectedKey, Result selected,
            DepartureSuggestionService.Suggestion suggestion, List<CandidateInfo> candidates,
            RouteEvidenceAssembler.Snapshot evidence, TriggerType trigger, String policyVersion, long eventRevision,
            MlRoutePreview.Comparison preview, Persistence persistence, ConditionalDepartureEvaluator.Evaluation conditional) {
        public Outcome { candidates = List.copyOf(candidates); }
        /** v1 저장 JSON/내부 호출을 읽는 호환 생성자. 신규 계산만 conditional을 가진다. */
        public Outcome(String evaluationId, long scheduleId, Long recommendationId, Instant plannedDepartAt,
                Instant arriveBy, Instant checkedAt, String selectedKey, Result selected,
                DepartureSuggestionService.Suggestion suggestion, List<CandidateInfo> candidates,
                RouteEvidenceAssembler.Snapshot evidence, TriggerType trigger, String policyVersion, long eventRevision,
                MlRoutePreview.Comparison preview, Persistence persistence) {
            this(evaluationId,scheduleId,recommendationId,plannedDepartAt,arriveBy,checkedAt,selectedKey,selected,
                    suggestion,candidates,evidence,trigger,policyVersion,eventRevision,preview,persistence,null);
        }
        public Outcome withRecommendationId(Long id) {
            return new Outcome(evaluationId, scheduleId, id, plannedDepartAt, arriveBy, checkedAt, selectedKey,
                    selected, suggestion, candidates, evidence, trigger, policyVersion, eventRevision, preview, persistence, conditional);
        }
        public Outcome withPersistence(Persistence value) {
            return new Outcome(evaluationId, scheduleId, recommendationId, plannedDepartAt, arriveBy, checkedAt,
                    selectedKey, selected, suggestion, candidates, evidence, trigger, policyVersion, eventRevision, preview, value, conditional);
        }
    }

    /** 직접 요청과 scheduler가 같은 흐름을 사용한다. userId=null은 서버 scheduler 전용이며 외부 API는 사용자 ID를 전달한다. */
    public Outcome evaluate(long scheduleId, Long userId, TriggerType trigger, Instant now) {
        var job = store.claim(scheduleId, userId, now);
        try {
            var cache = job.cache();
            boolean providerRefreshed = monitoring.refreshCandidates(now, cache == null ? null : cache.search().fetchedAt(), false);
            boolean candidatesChanged = providerRefreshed;
            RouteSearchResult search = providerRefreshed ? routes.search(new RouteSearchQuery(job.origin(), job.destination())) : cache.search();
            if (!providerRefreshed && mappingRefresher != null) {
                var remapped = BusAlternativeExpansion.expand(mappingRefresher.refresh(search));
                // 매핑 변경은 DB 후보 교체만 요구한다. 외부 후보를 새로 조회한 것은 아니므로
                // 아래 통제/지각 재조회 조건을 막지 않고 원본 fetchedAt도 유지한다.
                candidatesChanged = !remapped.equals(search);
                search = remapped;
            }
            var expanded = BusAlternativeExpansion.expand(search);
            candidatesChanged |= !expanded.equals(search);
            search = expanded;

            var snapshot = evidence.read(search.candidates(), now);
            Instant departure = job.departure().isBefore(now) ? now : job.departure();
            var walkingSession=new com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService.Session();
            var enriched=walking.prepare(search.candidates(),job.origin(),job.destination(),now,selectedKey(job,search),walkingSession);
            long budgetMillis = budget.forTrigger(trigger, userId);
            var calculation = conditional.evaluate(enriched.candidates(), snapshot, now, job.departure(), job.arriveBy(), budgetMillis).withWalking(enriched.evidence());
            var comparison = calculation.proposedComparison();
            var suggestion = calculation.suggestion();
            String selectedKey = selectedKey(job, search);
            Result selected = selected(suggestion, selectedKey);

            // 유효한 후보가 없거나 현재 선택이 지각/통제이면 TTL과 별도로 새 경로를 한 번만 요청한다.
            // 이전 결과도 같은 문제면 매분 재요청하지 않고 후보 TTL까지 기존 후보를 다시 평가한다.
            Outcome previous = store.read(job.previousJson(), Outcome.class);
            boolean newlyAtRisk = selected.assessment() == Assessment.BLOCKED || selected.assessment() == Assessment.LATE_ESTIMATE
                    || selected.reasons().stream().anyMatch(r -> r.startsWith("ROAD_POTENTIAL_IMPACT:"));
            boolean previousAtRisk = previous != null && (previous.selected().assessment() == Assessment.BLOCKED
                    || previous.selected().assessment() == Assessment.LATE_ESTIMATE
                    || previous.selected().reasons().stream().anyMatch(r -> r.startsWith("ROAD_POTENTIAL_IMPACT:")));
            if (!providerRefreshed && newlyAtRisk && !previousAtRisk) {
                search = BusAlternativeExpansion.expand(routes.search(new RouteSearchQuery(job.origin(), job.destination())));

                snapshot = evidence.read(search.candidates(), now);
                enriched=walking.prepare(search.candidates(),job.origin(),job.destination(),now,selectedKey(job,search),walkingSession);
                calculation = conditional.evaluate(enriched.candidates(), snapshot, now, job.departure(), job.arriveBy(), budgetMillis).withWalking(enriched.evidence());
                comparison = calculation.proposedComparison();
                suggestion = calculation.suggestion();
                selectedKey = selectedKey(job, search); selected = selected(suggestion, selectedKey); candidatesChanged = true;
            }
            var info = new ArrayList<CandidateInfo>();
            for (RouteCandidate c : search.candidates()) info.add(new CandidateInfo(RouteEvidenceAssembler.key(c), evidence.riskReasons(c, snapshot), c.segments().stream().map(s -> new RouteStep(s.mode().name(), s.fromName(), s.toName(), BusAlternativeExpansion.displayedVehicles(s))).toList()));
            var result = new Outcome(job.token(), scheduleId, null, job.departure(), job.arriveBy(), now,
                    selectedKey, selected, suggestion, info, snapshot, trigger, "cdep-v1", job.eventRevision(), comparison, null, calculation);
            // 출발 변경(accept)은 계속 막는다 — 미승인 모델 값으로 사용자의 일정을 바꾸지 않는다.
            // 알림은 설정으로 연다(기본 꺼짐). 보낼 것이 없으면 두 값이 null 이고,
            // 그때 dispatcher 는 아무것도 하지 않는다. EvaluationNotificationPlanner 참고.
            var planned = notifications.plan(job, result);
            Instant proposed = suggestion.proposed() == null ? null : suggestion.proposed().departureAt();
            return store.complete(job, search, candidatesChanged, result, monitoring.nextEvaluation(now, job.arriveBy(), proposed),
                    planned.map(p -> store.write(p.command())).orElse(null),
                    planned.map(EvaluationNotificationPlanner.Planned::key).orElse(null),
                    Instant.now());
        } catch (RuntimeException failure) {
            String reason = failure instanceof BusinessException be && be.errorCode()==ErrorCode.STALE_RECOMMENDATION ? "STALE_RECOMMENDATION" : failure instanceof BusinessException ? "EVALUATION_UNAVAILABLE" : "EVALUATION_FAILED";
            store.fail(job, reason, now);
            throw failure;
        }
    }

    private Result selected(DepartureSuggestionService.Suggestion suggestion, String key) {
        if (suggestion.originalCandidates().isEmpty()) return new Result("unavailable", Instant.EPOCH, null,
                Assessment.BLOCKED, true, List.of(), List.of("ROUTE_UNAVAILABLE"), "cdep-v1");
        if ("previous-unavailable".equals(key)) {
            return new Result(key, suggestion.originalCandidates().get(0).departureAt(), null, Assessment.BLOCKED,
                    true, List.of(), List.of("SELECTED_ROUTE_UNAVAILABLE"), "timeline-v1");
        }
        return suggestion.originalCandidates().stream().filter(r -> r.journeyKey().equals(key)).findFirst()
                .orElse(suggestion.originalCandidates().get(0));
    }

    /**
     * 먼저 기존 canonical occurrence까지 일치하는 선택을 찾는다. 없으면 원본 차량·정류장·형상이
     * 같고 이전에 비어 있던 매핑만 보강된 유일한 후보를 이어받는다. 기존 확정 edge의 교체나
     * 여러 차량으로 갈라지는 선택은 자동 승인하지 않고 선택 소실로 남긴다.
     * 성공한 새 후보는 complete에서 캐시의 selectedCandidate와 DB 선택을 함께 교체한다.
     */
    private String selectedKey(ScheduleEvaluationStore.Job job, RouteSearchResult search) {
        if (job.cache() != null) {
            var previous = Optional.ofNullable(job.cache().selectedCandidate());
            if (previous.isPresent()) {
                String signature = signature(previous.get());
                var matches = search.candidates().stream().filter(c -> signature(c).equals(signature)).toList();
                if (matches.size() == 1) return RouteEvidenceAssembler.key(matches.get(0));
                if (matches.isEmpty()) {
                    var enriched = search.candidates().stream().filter(c -> canEnrichSelection(previous.get(), c)).toList();
                    if (enriched.size() == 1) return RouteEvidenceAssembler.key(enriched.get(0));
                }
            }
            return "previous-unavailable";
        }
        return search.candidates().isEmpty() ? "previous-unavailable" : RouteEvidenceAssembler.key(search.candidates().get(0));
    }

    private String signature(RouteCandidate candidate) {
        return store.write(candidate.segments().stream().map(s -> Arrays.asList(s.mode(), s.providerRouteKey(),
                s.fromName(), s.toName(), s.stopNames(), s.vehicles(), s.geometry(),
                RouteEvidenceAssembler.pathIdentity(s))).toList());
    }

    /**
     * 미매핑 캐시를 현재 마스터로 보강하는 호환 검사다. 외부 I/O와 상태 변경은 없다.
     * 1. 원본 이동 증거를 비교 → 2. 기존 확정 occurrence를 보존 → 3. 새 매핑의 EXACT를 확인한다.
     * 비어 있던 edge를 채우는 경우만 허용하며, 후보 유일성은 호출자가 전체 목록에서 확인한다.
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
            if (oldPath.isEmpty() && !newPath.isEmpty()
                    && after.mapping().status() != com.ssafy.ddubi.domain.route.mapping.TransitMappingStatus.EXACT) return false;
        }
        return true;
    }

    private List<Object> providerIdentity(RouteSegment segment) {
        return Arrays.asList(segment.mode(), segment.providerRouteKey(), segment.fromName(), segment.toName(),
                segment.stopNames(), segment.vehicles(), segment.geometry());
    }

}
