package com.ssafy.ddubi.domain.schedule.dto.response;

import java.time.LocalDateTime;

/** REPEAT-001 응답 — 명세서 필드 그대로. */
public record RepeatCreateResponse(
        Long repeatId,
        int generatedScheduleCount,
        LocalDateTime nextOccurrenceAt
) {
}
