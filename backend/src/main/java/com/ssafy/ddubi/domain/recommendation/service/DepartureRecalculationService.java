package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.recommendation.evaluation.DepartureEstimate;
import com.ssafy.ddubi.domain.recommendation.evaluation.JourneyEvaluationService;
import com.ssafy.ddubi.domain.recommendation.evaluation.MlRoutePreview;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteEvidenceAssembler;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 출발 버튼의 실제 시각으로 선택 경로만 다시 평가한다. 출발 사실 저장은 짧은 DB 트랜잭션에서 먼저 끝내고, Redis·대중교통·ML 조회는 그 밖에서 수행한다. 실패해도
 * 출발 기록은 유지하되 오래된 출발 전 ETA를 새 계산 결과로 보여주지 않는다.
 */
@Service
public class DepartureRecalculationService {
    private static final Logger log = LoggerFactory.getLogger(DepartureRecalculationService.class);
    private final ScheduleEvaluationStore store;
    private final RouteEvidenceAssembler evidence;
    private final JourneyEvaluationService journeys;

    public DepartureRecalculationService(
            ScheduleEvaluationStore store,
            RouteEvidenceAssembler evidence,
            JourneyEvaluationService journeys) {
        this.store = store;
        this.evidence = evidence;
        this.journeys = journeys;
    }

    /** 첫 출발 시각을 고정하고 선택 경로를 재계산한다. 재전송 시 이미 저장된 결과는 재사용한다. */
    public DepartureEstimate depart(long scheduleId, long userId) {
        var context = store.stop(scheduleId, userId);
        if (context.alreadyCalculated() || !context.newlyDeparted()) return null;
        Instant departedAt = context.actualDepartAt();
        String key = context.selectedKey();
        DepartureEstimate estimate;
        if (context.selectedCandidate() == null || context.evaluationId() == null) {
            estimate = DepartureEstimate.unavailable(departedAt, key, "SELECTED_ROUTE_UNAVAILABLE");
        } else {
            try {
                // 출발한 시각을 타임라인의 시작으로 고정한다. 버스·지하철 대기와 환승은
                // 기존 여정 계산기가 새 시각에 맞춰 다시 선택하며, 과거 소요시간을 더하지 않는다.
                var selected = context.selectedCandidate();
                var snapshot = evidence.read(List.of(selected), departedAt);
                var comparison =
                        journeys.compare(
                                List.of(selected),
                                snapshot,
                                departedAt,
                                departedAt,
                                context.arriveBy(),
                                journeys.newSession(10_000));
                var result =
                        comparison.candidates().stream()
                                .map(MlRoutePreview.Candidate::result)
                                .filter(candidate -> candidate.journeyKey().equals(key))
                                .findFirst()
                                .orElse(null);
                if (result == null
                        || result.estimatedArrivalAt() == null
                        || !result.estimatedArrivalAt().isAfter(departedAt)
                        || result.assessment() == RouteTimelineEvaluator.Assessment.BLOCKED
                        || result.assessment()
                                == RouteTimelineEvaluator.Assessment.INSUFFICIENT_DATA) {
                    estimate =
                            DepartureEstimate.unavailable(
                                    departedAt, key, "ROUTE_ESTIMATE_UNAVAILABLE");
                } else {
                    estimate =
                            new DepartureEstimate(
                                    "READY",
                                    departedAt,
                                    result.estimatedArrivalAt(),
                                    Duration.between(departedAt, result.estimatedArrivalAt())
                                            .getSeconds(),
                                    Instant.now(),
                                    key,
                                    null);
                }
            } catch (RuntimeException failure) {
                // 외부 교통 원천 장애는 출발 버튼 자체의 실패가 아니다. 비밀값이 섞일 수
                // 있는 예외 문구를 API나 로그에 복제하지 않고 상태만 기록한다.
                log.warn(
                        "출발 시각 기준 재계산 실패 scheduleId={} type={}",
                        scheduleId,
                        failure.getClass().getSimpleName());
                estimate = DepartureEstimate.unavailable(departedAt, key, "DEPENDENCY_UNAVAILABLE");
            }
        }
        if (context.evaluationId() != null) {
            try {
                store.saveDepartureEstimate(scheduleId, userId, context.evaluationId(), estimate);
            } catch (RuntimeException failure) {
                // 출발 사실은 이미 커밋됐다. 부가 ETA 저장 장애 때문에 사용자에게
                // '출발 실패'라고 말하면 재전송으로 첫 출발 시각을 혼동하게 된다.
                log.warn(
                        "출발 ETA 저장 실패 scheduleId={} type={}",
                        scheduleId,
                        failure.getClass().getSimpleName());
            }
        }
        return estimate;
    }
}
