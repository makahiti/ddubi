package com.ssafy.ddubi.domain.recommendation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** 추천 카드 전용 계약. 계산 근거·내부 ID·원본 Redis payload는 제외하고 사용자가 행동하는 데 필요한 출발·도착·이동 순서·검증된 교통 영향만 제공한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RecommendationGuideResponse(
        String schemaVersion,
        String evaluationId,
        LocalDateTime departureAt,
        LocalDateTime estimatedArrivalAt,
        Long durationSeconds,
        String headline,
        String routeSummary,
        List<RouteStep> routeSteps,
        Delay delay,
        Instant dataAsOf,
        Instant validUntil,
        String generatedBy) {

    /** 시간과 순서는 서버 계산값이며 LLM이 추가·변경할 수 없다. */
    public record RouteStep(
            int sequence,
            String mode,
            String fromName,
            String toName,
            Long durationSeconds,
            String instruction) {}

    /** 지연 원인이 확인되지 않으면 추측 대신 NO_CONFIRMED_DELAY 또는 POSSIBLE을 반환한다. */
    public record Delay(
            String status, Long delaySeconds, String reason, String source, boolean confirmed) {}
}
