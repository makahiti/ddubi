package com.ssafy.ddubi.domain.schedule.dto.response;

import java.util.List;

/**
 * HOME-001 홈 요약.
 *
 * @param dataState 파이프라인 신선도. NORMAL / STALE(마지막 활성화가 오래됨) / UNKNOWN(활성화 이력 없음)
 */
public record HomeResponse(
        ScheduleResponse nextSchedule,
        List<ScheduleResponse> todaySchedules,
        long unreadNotificationCount,
        String dataState
) {
}
