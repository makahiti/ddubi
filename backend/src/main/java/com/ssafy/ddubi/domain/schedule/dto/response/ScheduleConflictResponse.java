package com.ssafy.ddubi.domain.schedule.dto.response;

import java.util.List;

/**
 * SCH-006 연속 일정 충돌.
 *
 * @param requiredMin Phase 3 전까지는 좌표 직선거리 기반 추정치다(대중교통 평균 속도 가정).
 *                    선택 경로가 생기면 실제 소요시간으로 대체한다
 */
public record ScheduleConflictResponse(List<Conflict> conflicts) {

    public record Conflict(
            Long previousScheduleId,
            Long nextScheduleId,
            long availableMin,
            long requiredMin,
            long shortageMin,
            String severity
    ) {
    }
}
