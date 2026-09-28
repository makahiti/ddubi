package com.ssafy.ddubi.domain.schedule.dto.request;

import com.ssafy.ddubi.domain.schedule.entity.Importance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * REPEAT-001 반복 일정 생성.
 *
 * @param localTime 도착 목표 시각(매 발생일 공통). 명세서 필드명이 localTime 이다
 * @param departTime 출발 예정시각(매 발생일 공통). 이 값이 없으면 규칙이 찍어낸 인스턴스는 추천 계산이 돌지 않으므로 생성 시에는 필수다
 * @param rrule FREQ=WEEKLY;BYDAY=... 만 지원 — {@code WeeklyRepeatRule} 참조
 */
public record RepeatCreateRequest(
        @NotBlank(message = "일정 이름은 필수입니다.") @Size(max = 100, message = "일정 이름은 100자를 넘을 수 없습니다.")
                String title,
        @NotNull(message = "출발지는 필수입니다.") @Valid LocationRequest origin,
        @NotNull(message = "목적지는 필수입니다.") @Valid LocationRequest destination,
        @NotNull(message = "도착 목표 시각(localTime)은 필수입니다.") LocalTime localTime,
        @NotNull(message = "출발 예정시각(departTime)은 필수입니다.") LocalTime departTime,
        @NotNull(message = "시작일(startDate)은 필수입니다.") LocalDate startDate,
        @NotBlank(message = "반복 규칙(rrule)은 필수입니다.") String rrule,
        LocalDate endDate,
        Importance importance,
        Boolean considerLastTrip) {}
