package com.ssafy.ddubi.domain.schedule.dto.response;

import com.ssafy.ddubi.domain.schedule.entity.Importance;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleRepeat;
import com.ssafy.ddubi.domain.schedule.service.WeeklyRepeatRule;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** REPEAT-002~004 공통 응답. rrule 은 저장된 CSV 에서 복원한다. */
public record RepeatResponse(
        Long id,
        String title,
        String rrule,
        String daysOfWeek,
        LocalTime localTime,
        LocalTime departTime,
        LocalDate startDate,
        LocalDate endDate,
        LocationResponse origin,
        LocationResponse destination,
        Importance importance,
        boolean considerLastTrip,
        boolean active,
        LocalDateTime nextOccurrenceAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static RepeatResponse of(ScheduleRepeat repeat, LocalDateTime nextOccurrenceAt) {
        return new RepeatResponse(
                repeat.getId(),
                repeat.getTitle(),
                WeeklyRepeatRule.fromCsv(repeat.getDaysOfWeek()).toRrule(),
                repeat.getDaysOfWeek(),
                repeat.getArriveTime(),
                repeat.getDepartTime(),
                repeat.getStartDate(),
                repeat.getUntilDate(),
                LocationResponse.from(repeat.getOrigin()),
                LocationResponse.from(repeat.getDestination()),
                repeat.getImportance().exposed(),
                repeat.isConsiderLastTrip(),
                repeat.isActive(),
                nextOccurrenceAt,
                repeat.getCreatedAt(),
                repeat.getUpdatedAt());
    }
}
