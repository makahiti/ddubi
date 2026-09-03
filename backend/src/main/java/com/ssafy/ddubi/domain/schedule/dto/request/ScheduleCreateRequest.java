package com.ssafy.ddubi.domain.schedule.dto.request;

import com.ssafy.ddubi.domain.schedule.entity.Importance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** SCH-002 일정 등록. importance·considerLastTrip 은 선택(기본 NORMAL / false). */
public record ScheduleCreateRequest(
        @NotBlank(message = "일정 이름은 필수입니다.")
        @Size(max = 100, message = "일정 이름은 100자를 넘을 수 없습니다.")
        String title,

        @NotNull(message = "출발지는 필수입니다.")
        @Valid
        LocationRequest origin,

        @NotNull(message = "목적지는 필수입니다.")
        @Valid
        LocationRequest destination,

        @NotNull(message = "도착 목표 시각은 필수입니다.")
        LocalDateTime arriveBy,

        Importance importance,

        Boolean considerLastTrip
) {
}
