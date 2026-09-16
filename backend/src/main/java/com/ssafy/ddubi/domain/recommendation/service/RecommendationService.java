package com.ssafy.ddubi.domain.recommendation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.entity.Recommendation;
import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import com.ssafy.ddubi.domain.recommendation.repository.RecommendationRepository;
import com.ssafy.ddubi.domain.route.entity.RouteProvider;
import com.ssafy.ddubi.domain.route.entity.RouteRequest;
import com.ssafy.ddubi.domain.route.entity.SavedRoute;
import com.ssafy.ddubi.domain.route.model.Coordinate;
import com.ssafy.ddubi.domain.route.model.RouteCandidate;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import com.ssafy.ddubi.domain.route.repository.RouteRequestRepository;
import com.ssafy.ddubi.domain.route.repository.SavedRouteRepository;
import com.ssafy.ddubi.domain.route.service.RouteSearchService;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.util.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 일정의 권장 출발 시각을 계산해 스냅샷으로 남긴다.
 *
 * <h3>흐름</h3>
 * <ol>
 *   <li>외부 길찾기로 후보를 받는다 (원본 호출 1건 = {@code route_request} 1행)</li>
 *   <li>후보마다 구간을 전개해 저장하고, 소요시간을 산정한다</li>
 *   <li>가장 늦게 출발해도 되는 후보를 1순위로 고른다</li>
 *   <li>{@code recommendation} 을 <b>INSERT</b> 한다 — 절대 UPDATE 하지 않는다(도메인 규칙 7)</li>
 * </ol>
 *
 * <h3>왜 "가장 늦게 출발해도 되는" 후보인가</h3>
 * 이 서비스가 답하는 질문은 "언제 나가야 하나" 다. 같은 시각에 도착하는 두 경로라면
 * 사용자는 <b>더 늦게 나가도 되는</b> 쪽을 원한다. 총 소요시간이 짧은 쪽과 같은 말이지만,
 * 기준을 출발 시각으로 두면 나중에 환승 대기 같은 것을 넣어도 비교 방식이 안 바뀐다.
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 추천 정책 버전. 계산 방식이 바뀌면 올린다 — 과거 추천을 지금 기준으로 읽으면 안 된다. */
    private static final String POLICY_VERSION = "v1.0";

    /**
     * 중요도→백분위 정책이 보류라(규약 4장 규칙 7) 지금은 p50 하나만 쓴다.
     * ML 이 p80/p95 를 아직 null 로 주기 때문에, 검증 전의 "p95" 는 이름만 p95 다.
     */
    private static final byte PERCENTILE_USED = 50;

    private final ScheduleRepository scheduleRepository;
    private final RouteSearchService routeSearchService;
    private final RouteRequestRepository routeRequestRepository;
    private final SavedRouteRepository savedRouteRepository;
    private final RecommendationRepository recommendationRepository;
    private final RouteCandidateAssembler assembler;
    private final TravelTimeEstimator estimator;
    private final RecommendationReasonWriter reasonWriter;
    /** 저장된 근거 JSON에서 표시용 요약을 꺼낼 때만 쓴다. 원문은 파싱하지 않고 그대로 내보낸다. */
    private final ObjectMapper objectMapper;

    /**
     * 도착 시각 앞에 두는 공통 안전 여유(분).
     *
     * <h3>왜 중요도별로 다르지 않나</h3>
     * 규약 4장 규칙 7(2026-09-08): 중요도는 백분위가 아니라 <b>감시·알림 빈도</b>에만 쓴다.
     * ML 백분위 커버리지가 검증되기 전에 CRITICAL 일정을 "p95" 로 계산하면, 정확히 늦으면
     * 안 되는 사람이 이름만 p95 인 값으로 늦는다.
     *
     * <p>⚠️ 기본값 5분은 <b>검증된 값이 아니다.</b> 규약이 "검증된 공통 안전 여유" 를
     * 요구하지만 아직 그 값이 정해지지 않았다. 실측이 쌓이면 이 설정을 고친다.
     */
    private final int safetyBufferMin;

    public RecommendationService(ScheduleRepository scheduleRepository,
                                 RouteSearchService routeSearchService,
                                 RouteRequestRepository routeRequestRepository,
                                 SavedRouteRepository savedRouteRepository,
                                 RecommendationRepository recommendationRepository,
                                 RouteCandidateAssembler assembler,
                                 TravelTimeEstimator estimator,
                                 RecommendationReasonWriter reasonWriter,
                                 ObjectMapper objectMapper,
                                 @Value("${ddubi.recommendation.safety-buffer-min:5}") int safetyBufferMin) {
        this.scheduleRepository = scheduleRepository;
        this.routeSearchService = routeSearchService;
        this.routeRequestRepository = routeRequestRepository;
        this.savedRouteRepository = savedRouteRepository;
        this.recommendationRepository = recommendationRepository;
        this.assembler = assembler;
        this.estimator = estimator;
        this.reasonWriter = reasonWriter;
        this.objectMapper = objectMapper;
        this.safetyBufferMin = safetyBufferMin;
    }

    @Transactional
    public Recommendation recommend(Long userId, Long scheduleId, TriggerType triggerType) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        if (!schedule.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        }

        Instant dataCheckedAt = BusinessTime.now().atZone(ZONE).toInstant();
        RouteSearchResult searched = routeSearchService.searchInternal(
                coordinate(schedule.getOrigin().getLng(), schedule.getOrigin().getLat()),
                coordinate(schedule.getDestination().getLng(), schedule.getDestination().getLat()));

        if (searched.candidates().isEmpty()) {
            throw new BusinessException(ErrorCode.ROUTE_NOT_FOUND,
                    "출발지에서 도착지까지 대중교통 경로를 찾지 못했습니다.");
        }

        RouteRequest routeRequest = routeRequestRepository.save(RouteRequest.builder()
                .schedule(schedule)
                .provider(RouteProvider.KAKAO)
                // 원본 미보존 — docs/contracts/recommendation-data-provenance.md 4절 참고.
                .rawResponse(null)
                .requestedAt(LocalDateTime.ofInstant(searched.fetchedAt(), ZONE))
                .build());

        Instant arriveBy = schedule.getArriveBy().atZone(ZONE).toInstant();
        List<Scored> scored = scoreCandidates(schedule, routeRequest, searched.candidates(), arriveBy);

        // 늦게 나가도 되는 순 = 총 소요시간이 짧은 순.
        scored.sort(Comparator.comparing((Scored s) -> s.solution().departAt()).reversed());
        Scored best = scored.get(0);

        persist(scored, best);

        return recommendationRepository.save(build(schedule, best, scored, dataCheckedAt, triggerType));
    }

    @Transactional(readOnly = true)
    public Recommendation latest(Long userId, Long scheduleId) {
        Recommendation recommendation = recommendationRepository
                .findFirstByScheduleIdOrderByCreatedAtDesc(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND));
        if (!recommendation.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return recommendation;
    }

    /** 추천 하나와, 저장된 근거에서 꺼낸 표시용 요약. 요약은 예전 형식이면 null 이다. */
    public record Explanation(Recommendation recommendation, ScheduleEvaluationWorkflow.Persistence persistence) {
    }

    /**
     * 추천 하나의 근거. 소유권은 <b>추천이 매달린 일정</b>으로 판단한다 —
     * 추천 ID 는 순번이라 남의 것을 찍어 볼 수 있다.
     */
    @Transactional(readOnly = true)
    public Explanation explanation(Long userId, Long recommendationId) {
        Recommendation recommendation = recommendationRepository.findById(recommendationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND));
        if (!recommendation.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return new Explanation(recommendation, persistence(recommendation));
    }

    /**
     * 저장된 근거 JSON에서 표시용 요약만 꺼낸다.
     *
     * <p>형식이 다르면 <b>조회를 실패시키지 않고 null 을 준다.</b> 이 컬럼에는 sched-v1 의
     * Outcome 말고 {@link RecommendationReasonWriter} 가 쓴 예전 형식도 들어 있다. 요약 한 줄을
     * 못 읽는다고 근거 조회 전체를 500 으로 만들면, 정작 필요한 원문까지 못 보게 된다.
     */
    private ScheduleEvaluationWorkflow.Persistence persistence(Recommendation recommendation) {
        if (recommendation.getReasons() == null || recommendation.getReasons().isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(recommendation.getReasons()).get("persistence");
            return node == null || node.isNull()
                    ? null
                    : objectMapper.treeToValue(node, ScheduleEvaluationWorkflow.Persistence.class);
        } catch (Exception unreadable) {
            log.debug("추천 근거 요약을 읽지 못했습니다 recommendationId={}", recommendation.getId(), unreadable);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 후보 비교
    // ------------------------------------------------------------------

    /** 후보 하나와 그 후보로 풀어낸 출발 시각. */
    private record Scored(RouteCandidate candidate, SavedRoute route, DepartureSolver.Solution solution) {
    }

    /**
     * 후보마다 출발 시각을 푼다.
     *
     * <p>풀이 한 번이 예측 배치 호출 한 번이라, 후보가 늘면 그만큼 비용이 는다.
     * 그래도 후보별로 다 계산하는 이유는, <b>경로 API 총시간 순서와 실제 순서가 다를 수
     * 있기 때문</b>이다. 카카오가 짧다고 준 경로가 정체 구간을 지나면 실제로는 더 느리다.
     * 그 역전을 잡는 것이 이 서비스의 존재 이유라 여기서 아끼면 안 된다.
     */
    private List<Scored> scoreCandidates(Schedule schedule, RouteRequest routeRequest,
                                         List<RouteCandidate> candidates, Instant arriveBy) {
        long bufferSeconds = safetyBufferMin * 60L;
        List<Scored> scored = new ArrayList<>(candidates.size());

        for (int i = 0; i < candidates.size(); i++) {
            RouteCandidate candidate = candidates.get(i);
            RouteCandidateAssembler.Assembled assembled =
                    assembler.assemble(schedule, routeRequest, candidate, i + 1);
            DepartureSolver.Solution solution =
                    DepartureSolver.solve(estimator, assembled.steps(), arriveBy, bufferSeconds);
            scored.add(new Scored(candidate, assembled.route(), solution));
        }
        return scored;
    }

    /** 순위를 다시 매기고 1순위를 주 경로로 표시한다. */
    private void persist(List<Scored> scored, Scored best) {
        for (int i = 0; i < scored.size(); i++) {
            SavedRoute route = scored.get(i).route();
            route.updateRank(i + 1);
            if (scored.get(i) == best) {
                route.select();
            }
            savedRouteRepository.save(route);
        }
    }

    private Recommendation build(Schedule schedule, Scored best, List<Scored> all,
                                 Instant dataCheckedAt, TriggerType triggerType) {
        TravelTimeEstimate estimate = best.solution().estimate();
        Instant departAt = best.solution().departAt();

        if (departAt.isBefore(dataCheckedAt)) {
            // 계산해 보니 이미 늦었다. 그래도 답은 준다 — "지금 나가도 늦는다" 가 답이다.
            log.info("권장 출발 시각이 이미 지났습니다 scheduleId={} departAt={}",
                    schedule.getId(), departAt);
        }

        return Recommendation.builder()
                .schedule(schedule)
                .route(best.route())
                .departAt(LocalDateTime.ofInstant(departAt, ZONE))
                .eta(LocalDateTime.ofInstant(best.solution().arrivalWithoutBuffer(), ZONE))
                .durationMin(toMinutes(estimate.totalSeconds()))
                .bufferMin(safetyBufferMin)
                .percentileUsed(PERCENTILE_USED)
                .confidence(estimate.confidence())
                .reasons(reasonWriter.reasons(estimate, all.stream().map(Scored::solution).toList()))
                .segmentStatusSnapshot(reasonWriter.snapshot(estimate))
                .dataCheckedAt(LocalDateTime.ofInstant(dataCheckedAt, ZONE))
                .policyVersion(POLICY_VERSION)
                .triggerType(triggerType)
                .build();
    }

    private static Coordinate coordinate(BigDecimal lng, BigDecimal lat) {
        return new Coordinate(lng.doubleValue(), lat.doubleValue());
    }

    /** 올림한다 — 90초를 1분으로 깎으면 그만큼 늦는다. */
    private static int toMinutes(long seconds) {
        return (int) ((seconds + 59) / 60);
    }
}
