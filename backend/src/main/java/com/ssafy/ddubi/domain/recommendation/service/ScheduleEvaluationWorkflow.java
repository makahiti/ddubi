package com.ssafy.ddubi.domain.recommendation.service;

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
    private final ScheduleMonitoringPolicy monitoring;

    public ScheduleEvaluationWorkflow(ScheduleEvaluationStore store, PublicTransitRouteClient routes,
            RouteEvidenceAssembler evidence, MlRoutePreview previews,
            @Value("${ddubi.recommendation.candidate-ttl-seconds:900}") int candidateTtl) {
        this.store = store; this.routes = routes; this.evidence = evidence; this.previews = previews;
        // 초기 실험 주기이며 검증된 도착 보장 임계값이 아니다. 여유시간으로 가산하지 않는다.
        monitoring = new ScheduleMonitoringPolicy(Duration.ofHours(1), Duration.ofMinutes(30), Duration.ofMinutes(10),
                Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofSeconds(candidateTtl));
    }

    /** 후보별 현재 위험은 수치화하지 않고 별도로 표시한다. */
    public record CandidateInfo(String key, List<String> risks, List<RouteStep> steps) { }
    /** 화면에서 변경 경로를 확인하는 최소 정보. 외부 raw 응답은 포함하지 않는다. */
    public record RouteStep(String mode, String fromName, String toName, List<String> vehicles) { }
    /**
     * 계산 시각의 입력·후보·증거를 보존한다. 내부 Instant는 절대시각이고 프론트는 KST로 표시한다.
     * selected는 기존 선택 경로, preview.fastest는 같은 출발의 최소시간 검증 후보다.
     * suggestion.proposed는 응답 호환용 동일 후보이며 PREVIEW는 수락·알림을 허용하지 않는다.
     */
    public record Outcome(String evaluationId, long scheduleId, Long recommendationId, Instant plannedDepartAt,
            Instant arriveBy, Instant checkedAt, String selectedKey, Result selected,
            DepartureSuggestionService.Suggestion suggestion, List<CandidateInfo> candidates,
            RouteEvidenceAssembler.Snapshot evidence, TriggerType trigger, String policyVersion, long eventRevision, MlRoutePreview.Comparison preview) {
        public Outcome { candidates = List.copyOf(candidates); }
        public Outcome withRecommendationId(Long id) {
            return new Outcome(evaluationId, scheduleId, id, plannedDepartAt, arriveBy, checkedAt, selectedKey,
                    selected, suggestion, candidates, evidence, trigger, policyVersion, eventRevision, preview);
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
                    selectedKey, selected, suggestion, info, snapshot, trigger, "ml-prev-v1", job.eventRevision(), comparison);
            // 검증용 모델은 결과 화면에만 제공한다. 운영 추천/출발 변경/FCM에 전파하지 않는다.
            Instant proposed = suggestion.proposed() == null ? null : suggestion.proposed().departureAt();
            return store.complete(job, search, refresh, result, monitoring.nextEvaluation(now, job.departure(), proposed),
                    null, null, Instant.now());
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
