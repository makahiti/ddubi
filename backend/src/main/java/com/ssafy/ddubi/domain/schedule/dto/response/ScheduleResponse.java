package com.ssafy.ddubi.domain.schedule.dto.response;

import com.ssafy.ddubi.domain.schedule.entity.Importance;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;

import java.time.LocalDateTime;

/**
 * SCH-001~004 공통 일정 응답.
 *
 * @param displayState  화면 표시용 파생 상태. DB status 에 없는 OVERDUE(도착 시각 지남)를 구분한다
 * @param recommendationStatus 저장 직후는 PENDING. 실제 평가 상태는 recommendations/latest에서 조회하며,
 *                             조회 응답에서는 null(미노출 — jackson non_null)
 */
public record ScheduleResponse(
        Long id,
        Long repeatId,
        String title,
        LocationResponse origin,
        LocationResponse destination,
        LocalDateTime arriveBy,
        Importance importance,
        boolean considerLastTrip,
        boolean customized,
        ScheduleStatus status,
        String displayState,
        String recommendationStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime plannedDepartAt,
        LocalDateTime userInputDepartAt,
        String planSource,
        LocalDateTime actualDepartAt,
        String journeyState,
        long inputRevision
) {

    public static ScheduleResponse from(Schedule schedule, LocalDateTime now) {
        return of(schedule, now, null);
    }

    public static ScheduleResponse of(Schedule schedule, LocalDateTime now, String recommendationStatus) {
        return new ScheduleResponse(
                schedule.getId(),
                schedule.repeatId(),
                schedule.getTitle(),
                LocationResponse.from(schedule.getOrigin()),
                LocationResponse.from(schedule.getDestination()),
                schedule.getArriveBy(),
                schedule.getImportance(),
                schedule.isConsiderLastTrip(),
                schedule.isCustomized(),
                schedule.getStatus(),
                displayState(schedule, now),
                recommendationStatus,
                schedule.getCreatedAt(),
                schedule.getUpdatedAt(), schedule.getPlannedDepartAt(),schedule.getUserInputDepartAt(),
                schedule.getPlanSource(),schedule.getActualDepartAt(),schedule.getJourneyState(),schedule.getInputRevision());
    }

    private static String displayState(Schedule schedule, LocalDateTime now) {
        if (schedule.getStatus() == ScheduleStatus.CANCELED) {
            return "CANCELED";
        }
        if (schedule.getStatus() == ScheduleStatus.DONE) {
            return "DONE";
        }
        // UPCOMING 인데 도착 시각이 지났다 = 상태 갱신 전이거나 놓친 일정. 화면에서 구분해 보여준다.
        return schedule.getArriveBy().isBefore(now) ? "OVERDUE" : "UPCOMING";
    }
}
