package com.ssafy.ddubi.domain.schedule.controller;

import com.ssafy.ddubi.domain.schedule.dto.response.MovementConditionsResponse;
import com.ssafy.ddubi.domain.schedule.service.MovementConditionsService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 일정 상세 화면에 날씨·혼잡도·도로 통제·지하철 공지를 한 번에 제공한다. */
@Tag(name = "일정", description = "일정 CRUD · 연속 일정 충돌 · 이동 변수")
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleConditionsController {
    private final MovementConditionsService service;

    public ScheduleConditionsController(MovementConditionsService service) {
        this.service = service;
    }

    @Operation(
            summary = "일정 이동 변수 조회",
            description = "선택 경로와 목적지에 해당하는 최신 Redis 자료만 반환하며, 결측·만료는 항목별 available=false로 표시한다.")
    @GetMapping("/{scheduleId}/conditions")
    public ApiResponse<MovementConditionsResponse> get(
            @LoginUserId Long userId, @PathVariable Long scheduleId) {
        return ApiResponse.success(service.get(userId, scheduleId));
    }
}
