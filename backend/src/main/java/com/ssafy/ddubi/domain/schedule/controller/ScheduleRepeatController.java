package com.ssafy.ddubi.domain.schedule.controller;

import com.ssafy.ddubi.domain.schedule.dto.request.RepeatCreateRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.RepeatUpdateRequest;
import com.ssafy.ddubi.domain.schedule.dto.response.RepeatCreateResponse;
import com.ssafy.ddubi.domain.schedule.dto.response.RepeatResponse;
import com.ssafy.ddubi.domain.schedule.dto.response.RepeatUpdateResponse;
import com.ssafy.ddubi.domain.schedule.service.ScheduleRepeatService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.common.PageRequestParam;
import com.ssafy.ddubi.global.common.PageResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
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

@Tag(name = "반복 일정", description = "반복 규칙 CRUD — 인스턴스는 지평선만큼 선생성")
@RestController
@RequestMapping("/api/v1/schedule-repeats")
public class ScheduleRepeatController {

    private final ScheduleRepeatService repeatService;

    public ScheduleRepeatController(ScheduleRepeatService repeatService) {
        this.repeatService = repeatService;
    }

    @Operation(
            summary = "반복 일정 생성",
            description = "rrule 은 FREQ=WEEKLY;BYDAY=... 만 지원. 인스턴스를 즉시 만들어 개수를 돌려준다. REPEAT-001")
    @PostMapping
    public ResponseEntity<ApiResponse<RepeatCreateResponse>> create(
            @LoginUserId Long userId, @Valid @RequestBody RepeatCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(repeatService.create(userId, request)));
    }

    @Operation(summary = "반복 일정 목록", description = "REPEAT-002")
    @GetMapping
    public ApiResponse<PageResponse<RepeatResponse>> list(
            @LoginUserId Long userId,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @Valid @ModelAttribute PageRequestParam pageParam) {
        return ApiResponse.success(
                repeatService.list(
                        userId,
                        activeOnly,
                        pageParam.toPageable(Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @Operation(summary = "반복 일정 상세", description = "REPEAT-003")
    @GetMapping("/{repeatId}")
    public ApiResponse<RepeatResponse> get(@LoginUserId Long userId, @PathVariable Long repeatId) {
        return ApiResponse.success(repeatService.get(userId, repeatId));
    }

    @Operation(
            summary = "반복 일정 수정",
            description = "미래의 미수정 인스턴스를 지우고 다시 만든다. 개별 수정된 인스턴스는 유지. REPEAT-004")
    @PatchMapping("/{repeatId}")
    public ApiResponse<RepeatUpdateResponse> update(
            @LoginUserId Long userId,
            @PathVariable Long repeatId,
            @Valid @RequestBody RepeatUpdateRequest request) {
        return ApiResponse.success(repeatService.update(userId, repeatId, request));
    }

    @Operation(
            summary = "반복 일정 중지",
            description =
                    "기본은 비활성화만 한다 — 이미 만들어진 인스턴스는 남는다. "
                            + "cancelFutureInstances=true 면 아직 시작하지 않은 미래 인스턴스를 CANCELED 로 바꾼다"
                            + "(개별 수정한 일정은 남긴다). REPEAT-005")
    @DeleteMapping("/{repeatId}")
    public ResponseEntity<Void> deactivate(
            @LoginUserId Long userId,
            @PathVariable Long repeatId,
            // 기본 false 를 바꾸지 말 것. 기존 앱은 이 값을 보내지 않는다 —
            // true 가 기본이면 "중지" 만 누른 사용자의 미래 일정이 통째로 사라진다.
            @RequestParam(defaultValue = "false") boolean cancelFutureInstances) {
        repeatService.deactivate(userId, repeatId, cancelFutureInstances);
        return ResponseEntity.noContent().build();
    }
}
