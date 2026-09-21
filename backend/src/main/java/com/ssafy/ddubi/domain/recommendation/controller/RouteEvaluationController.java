package com.ssafy.ddubi.domain.recommendation.controller;

import com.ssafy.ddubi.domain.recommendation.evaluation.*;
import com.ssafy.ddubi.domain.route.client.PublicTransitRouteClient;
import com.ssafy.ddubi.domain.route.model.*;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.exception.*;
import com.ssafy.ddubi.global.security.LoginUserId;
import com.ssafy.ddubi.global.util.SupportedServiceArea;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * 일정 등록 전 조회 전용 평가. schedule/route/recommendation 쓰기는 하지 않는다. Redis 비용 한도는 사용자·분 단위이며 다중 서버에서도
 * 공유한다. Redis 장애 시 비용 제한을 우회하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/routes")
public class RouteEvaluationController {
    private final PublicTransitRouteClient routes;
    private final RouteEvidenceAssembler evidence;
    private final ConditionalDepartureEvaluator evaluator;
    private final StringRedisTemplate redis;
    private final int limit;
    private final com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService walking;

    public RouteEvaluationController(
            PublicTransitRouteClient routes,
            RouteEvidenceAssembler evidence,
            ConditionalDepartureEvaluator evaluator,
            StringRedisTemplate redis,
            com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService walking,
            @Value("${ddubi.recommendation.readonly-per-minute:6}") int limit) {
        if (limit < 1) throw new IllegalArgumentException("조회 비용 한도 오류");
        this.routes = routes;
        this.evidence = evidence;
        this.evaluator = evaluator;
        this.redis = redis;
        this.limit = limit;
        this.walking = walking;
    }

    public record Request(
            @NotNull Coordinate origin,
            @NotNull Coordinate destination,
            @NotNull OffsetDateTime plannedDepartAt,
            @NotNull OffsetDateTime arriveBy) {}

    /** 오프셋이 포함된 P/D를 절대시각으로 비교한다. 원천 장애는 기존 503, 비용 초과는 429를 유지한다. */
    @PostMapping("/evaluate")
    public ApiResponse<ScheduleEvaluationV2> evaluate(
            @LoginUserId Long userId, @Valid @RequestBody Request request) {
        if (userId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        Instant now = Instant.now(),
                p = request.plannedDepartAt().toInstant(),
                d = request.arriveBy().toInstant();
        if (!p.isBefore(d) || !d.isAfter(now))
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        validate(request.origin());
        validate(request.destination());
        String key = "evaluation:readonly:" + userId + ":" + (now.getEpochSecond() / 60);
        try {
            Long count =
                    redis.execute(
                            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                                    "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],120) end; return n",
                                    Long.class),
                            List.of(key));
            if (count == null) throw new BusinessException(ErrorCode.REALTIME_DATA_UNAVAILABLE);
            if (count > limit) throw new BusinessException(ErrorCode.RATE_LIMITED);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.REALTIME_DATA_UNAVAILABLE);
        }
        var search =
                BusAlternativeExpansion.expand(
                        routes.search(
                                new RouteSearchQuery(request.origin(), request.destination())));
        var snapshot = evidence.read(search.candidates(), now);
        var enriched =
                walking.prepare(search.candidates(), request.origin(), request.destination(), now);
        var result =
                evaluator
                        .evaluate(enriched.candidates(), snapshot, now, p, d)
                        .withWalking(enriched.evidence());
        return ApiResponse.success(
                ScheduleEvaluationV2.from(
                        result,
                        UUID.randomUUID().toString(),
                        null,
                        null,
                        0,
                        "READ_ONLY",
                        p,
                        p,
                        d,
                        now,
                        ScheduleEvaluationV2.validity(result, snapshot, now, null),
                        null,
                        null,
                        null,
                        snapshot.reasons(),
                        snapshot,
                        search.candidates()));
    }

    private void validate(Coordinate point) {
        if (!Double.isFinite(point.longitude())
                || !Double.isFinite(point.latitude())
                || Math.abs(point.longitude()) > 180
                || Math.abs(point.latitude()) > 90)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (!SupportedServiceArea.contains(
                BigDecimal.valueOf(point.latitude()), BigDecimal.valueOf(point.longitude())))
            throw new BusinessException(ErrorCode.OUT_OF_SERVICE_AREA);
    }
}
