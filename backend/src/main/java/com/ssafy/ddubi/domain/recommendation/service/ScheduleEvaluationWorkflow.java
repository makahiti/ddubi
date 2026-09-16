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
    private final MlRoutePreview previews;
    private final EvaluationNotificationPlanner notifications;
    private final ScheduleMonitoringPolicy monitoring;

    public ScheduleEvaluationWorkflow(ScheduleEvaluationStore store, PublicTransitRouteClient routes,
            RouteEvidenceAssembler evidence, MlRoutePreview previews, EvaluationNotificationPlanner notifications,
            @Value("${ddubi.recommendation.candidate-ttl-seconds:900}") int candidateTtl) {
        this.store = store; this.routes = routes; this.evidence = evidence; this.previews = previews;
        this.notifications = notifications;
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
     * suggestion.proposed는 응답 호환용 동일 후보이며 PREVIEW는 수락을 허용하지 않는다.
     * recommendationId·persistence는 저장 결과이며 store가 채운다.
     */
    public record Outcome(String evaluationId, long scheduleId, Long recommendationId, Instant plannedDepartAt,
            Instant arriveBy, Instant checkedAt, String selectedKey, Result selected,
            DepartureSuggestionService.Suggestion suggestion, List<CandidateInfo> candidates,
            RouteEvidenceAssembler.Snapshot evidence, TriggerType trigger, String policyVersion, long eventRevision,
            MlRoutePreview.Comparison preview, Persistence persistence) {
        public Outcome { candidates = List.copyOf(candidates); }
        public Outcome withRecommendationId(Long id) {
            return new Outcome(evaluationId, scheduleId, id, plannedDepartAt, arriveBy, checkedAt, selectedKey,
                    selected, suggestion, candidates, evidence, trigger, policyVersion, eventRevision, preview, persistence);
        }
        public Outcome withPersistence(Persistence value) {
            return new Outcome(evaluationId, scheduleId, recommendationId, plannedDepartAt, arriveBy, checkedAt,
                    selectedKey, selected, suggestion, candidates, evidence, trigger, policyVersion, eventRevision, preview, value);
        }
    }

    /** 직접 요청과 scheduler가 같은 흐름을 사용한다. userId=null은 서버 scheduler 전용이며 외부 API는 사용자 ID를 전달한다. */
    public Outcome evaluate(long scheduleId, Long userId, TriggerType trigger, Instant now) {
        var job = store.claim(scheduleId, userId, now);
        try {
            var cache = job.cache();
            boolean refresh = monitoring.refreshCandidates(now, cache == null ? null : cache.search().fetchedAt(), false);
            RouteSearchResult search = refresh ? routes.search(new RouteSearchQuery(job.origin(), job.destination())) : cache.search();
            if (search.candidates().isEmpty()) throw new BusinessException(ErrorCode.ROUTE_NOT_AVAILABLE);
            var snapshot = evidence.read(search.candidates(), now);
            Instant departure = job.departure().isBefore(now) ? now : job.departure();
            var comparison = previews.compare(search.candidates(), snapshot, now, departure, job.arriveBy());
            var suggestion = suggestion(comparison);
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
            if (!refresh && newlyAtRisk && !previousAtRisk) {
                search = routes.search(new RouteSearchQuery(job.origin(), job.destination()));
                if (search.candidates().isEmpty()) throw new BusinessException(ErrorCode.ROUTE_NOT_AVAILABLE);
                snapshot = evidence.read(search.candidates(), now);
                comparison = previews.compare(search.candidates(), snapshot, now, departure, job.arriveBy());
                suggestion = suggestion(comparison);
                selectedKey = selectedKey(job, search); selected = selected(suggestion, selectedKey); refresh = true;
            }
            var info = new ArrayList<CandidateInfo>();
            for (RouteCandidate c : search.candidates()) info.add(new CandidateInfo(RouteEvidenceAssembler.key(c), evidence.riskReasons(c, snapshot), c.segments().stream().map(s -> new RouteStep(s.mode().name(), s.fromName(), s.toName(), s.vehicles().stream().map(v -> v.name()).toList())).toList()));
            var result = new Outcome(job.token(), scheduleId, null, job.departure(), job.arriveBy(), now,
                    selectedKey, selected, suggestion, info, snapshot, trigger, "ml-prev-v1", job.eventRevision(), comparison, null);
            // 출발 변경(accept)은 계속 막는다 — 미승인 모델 값으로 사용자의 일정을 바꾸지 않는다.
            // 알림은 설정으로 연다(기본 꺼짐). 보낼 것이 없으면 두 값이 null 이고,
            // 그때 dispatcher 는 아무것도 하지 않는다. EvaluationNotificationPlanner 참고.
            var planned = notifications.plan(job, result);
            Instant proposed = suggestion.proposed() == null ? null : suggestion.proposed().departureAt();
            return store.complete(job, search, refresh, result, monitoring.nextEvaluation(now, job.departure(), proposed),
                    planned.map(p -> store.write(p.command())).orElse(null),
                    planned.map(EvaluationNotificationPlanner.Planned::key).orElse(null),
                    Instant.now());
        } catch (RuntimeException failure) {
            String reason = failure instanceof BusinessException ? "EVALUATION_UNAVAILABLE" : "EVALUATION_FAILED";
            store.fail(job, reason, now);
            throw failure;
        }
    }

    private Result selected(DepartureSuggestionService.Suggestion suggestion, String key) {
        if ("previous-unavailable".equals(key)) {
            return new Result(key, suggestion.originalCandidates().get(0).departureAt(), null, Assessment.BLOCKED,
                    true, List.of(), List.of("SELECTED_ROUTE_UNAVAILABLE"), "timeline-v1");
        }
        return suggestion.originalCandidates().stream().filter(r -> r.journeyKey().equals(key)).findFirst()
                .orElse(suggestion.originalCandidates().get(0));
    }

    /** 캐시 교체 시 차량·방향·정류장열로 기존 선택을 찾는다. 매칭 실패는 선택 소실로 따로 표시할 후속 UI 대상이다. */
    private String selectedKey(ScheduleEvaluationStore.Job job, RouteSearchResult search) {
        if (job.cache() != null) {
            var previous = Optional.ofNullable(job.cache().selectedCandidate());
            if (previous.isPresent()) {
                String signature = signature(previous.get());
                var matches = search.candidates().stream().filter(c -> signature(c).equals(signature)).toList();
                if (matches.size() == 1) return RouteEvidenceAssembler.key(matches.get(0));
            }
            return "previous-unavailable";
        }
        return RouteEvidenceAssembler.key(search.candidates().get(0));
    }

    private String signature(RouteCandidate candidate) {
        return store.write(candidate.segments().stream().map(s -> Arrays.asList(s.mode(), s.providerRouteKey(),
                s.fromName(), s.toName(), s.stopNames(), s.vehicles(), s.geometry())).toList());
    }

    /** 같은 출발시각의 최소시간 결과다. 검증용 단계에서는 조기 출발을 역산하지 않는다. */
    private DepartureSuggestionService.Suggestion suggestion(MlRoutePreview.Comparison comparison) {
        var results = comparison.candidates().stream().map(MlRoutePreview.Candidate::result).toList();
        return new DepartureSuggestionService.Suggestion(results, comparison.fastest(), comparison.fastest(),
                results.size(), comparison.budgetExhausted());
    }
}
