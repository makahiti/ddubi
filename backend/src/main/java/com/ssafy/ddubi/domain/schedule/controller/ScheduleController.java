package com.ssafy.ddubi.domain.schedule.controller;

import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleCreateRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleUpdateRequest;
import com.ssafy.ddubi.domain.schedule.dto.response.ScheduleConflictResponse;
import com.ssafy.ddubi.domain.schedule.dto.response.ScheduleResponse;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;
import com.ssafy.ddubi.domain.schedule.service.ScheduleConflictService;
import com.ssafy.ddubi.domain.schedule.service.ScheduleService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.common.PageRequestParam;
import com.ssafy.ddubi.global.common.PageResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "일정", description = "일정 CRUD · 연속 일정 충돌")
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final ScheduleConflictService conflictService;

    public ScheduleController(
            ScheduleService scheduleService, ScheduleConflictService conflictService) {
        this.scheduleService = scheduleService;
        this.conflictService = conflictService;
    }

    @Operation(summary = "일정 목록", description = "from~to 기간 조회. status 미지정 시 전체. SCH-001")
    @GetMapping
    public ApiResponse<PageResponse<ScheduleResponse>> list(
            @LoginUserId Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) ScheduleStatus status,
            @Valid @ModelAttribute PageRequestParam pageParam) {
        return ApiResponse.success(
                scheduleService.list(
                        userId, from, to, status, pageParam.toPageable(Sort.by("arriveBy"))));
    }

    @Operation(summary = "연속 일정 충돌 조회", description = "앞 일정에서 다음 일정으로 이동이 불가능한 쌍을 계산해 준다. SCH-006")
    @GetMapping("/conflicts")
    public ApiResponse<ScheduleConflictResponse> conflicts(
            @LoginUserId Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.success(conflictService.findConflicts(userId, from, to));
    }

    @Operation(summary = "일정 등록", description = "수도권·부산 밖 좌표 422, 과거 도착 시각 400. SCH-002")
    @PostMapping
    public ResponseEntity<ApiResponse<ScheduleResponse>> create(
            @LoginUserId Long userId, @Valid @RequestBody ScheduleCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(scheduleService.create(userId, request)));
    }

    @Operation(summary = "일정 상세", description = "SCH-003")
    @GetMapping("/{scheduleId}")
    public ApiResponse<ScheduleResponse> get(
            @LoginUserId Long userId, @PathVariable Long scheduleId) {
        return ApiResponse.success(scheduleService.get(userId, scheduleId));
    }

    @Operation(
            summary = "일정 수정",
            description =
                    "updatedAt 필수(낙관적 잠금 — 다르면 409). 반복 인스턴스를 수정하면 "
                            + "개별 수정으로 표시되어 반복 전체 수정에서 제외된다. SCH-004")
    @PatchMapping("/{scheduleId}")
    public ApiResponse<ScheduleResponse> update(
            @LoginUserId Long userId,
            @PathVariable Long scheduleId,
            @Valid @RequestBody ScheduleUpdateRequest request) {
        return ApiResponse.success(scheduleService.update(userId, scheduleId, request));
    }

    @Operation(summary = "일정 취소", description = "soft 삭제(status=CANCELED). 완료된 일정은 409. SCH-005")
    @DeleteMapping("/{scheduleId}")
    public ResponseEntity<Void> cancel(@LoginUserId Long userId, @PathVariable Long scheduleId) {
        scheduleService.cancel(userId, scheduleId);
        return ResponseEntity.noContent().build();
    }
}
