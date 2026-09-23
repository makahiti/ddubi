package com.ssafy.ddubi.domain.schedule.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * 일정 상세의 이동 변수 응답이다. 각 자료는 갱신 주기와 장애 범위가 다르므로 항목별 {@code available}과 실제 관측시각을 독립적으로 제공한다. 자료가 없거나
 * 만료됐을 때 숫자 0이나 정상 상태를 합성하지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MovementConditionsResponse(
        String schemaVersion,
        Long scheduleId,
        Instant generatedAt,
        Instant observedAt,
        Weather weather,
        Congestion congestion,
        RoadBlock roadBlock,
        SubwayDelay subwayDelay) {

    public record Weather(
            boolean available,
            String reason,
            Integer nx,
            Integer ny,
            List<String> districts,
            Double temperatureC,
            Double humidityPercent,
            Double precipitationMm,
            Integer precipitationTypeCode,
            Double windSpeedMps,
            Instant observedAt,
            Instant validUntil,
            String source) {}

    public record Congestion(
            boolean available,
            String reason,
            String areaId,
            String areaName,
            String level,
            String message,
            Integer score,
            Instant observedAt,
            Instant validUntil,
            String source) {}

    public record RoadBlock(
            boolean available,
            String reason,
            int count,
            String summary,
            List<RoadIncident> incidents,
            Instant observedAt,
            String source,
            String mappingStatus) {}

    public record RoadIncident(
            String incidentId,
            String incidentType,
            String message,
            Instant startsAt,
            Instant expectedEndsAt) {}

    public record SubwayDelay(
            boolean available,
            String reason,
            int count,
            String summary,
            List<String> routeLines,
            List<SubwayAlert> alerts,
            Instant observedAt,
            String source) {}

    public record SubwayAlert(
            String alertId, String lineName, String title, String content, Instant validUntil) {}
}
