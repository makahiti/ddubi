package com.ssafy.ddubi.domain.notification.controller;

import com.ssafy.ddubi.domain.notification.dto.response.NotificationListResponse;
import com.ssafy.ddubi.domain.notification.dto.response.NotificationReadAllResponse;
import com.ssafy.ddubi.domain.notification.dto.response.NotificationReadResponse;
import com.ssafy.ddubi.domain.notification.dto.response.NotificationResponse;
import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import com.ssafy.ddubi.domain.notification.service.NotificationQueryService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.common.PageRequestParam;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "알림", description = "알림함 조회 · 읽음 처리")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    public NotificationController(NotificationQueryService notificationQueryService) {
        this.notificationQueryService = notificationQueryService;
    }

    @Operation(summary = "알림 목록",
            description = "unreadCount 를 함께 준다. unreadCount 는 필터와 무관한 안읽음 전체 개수다. NTF-001")
    @GetMapping
    public ApiResponse<NotificationListResponse> list(
            @LoginUserId Long userId,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) NotificationType type,
            @Valid @ModelAttribute PageRequestParam pageParam) {
        return ApiResponse.success(notificationQueryService.list(userId, unreadOnly, type,
                pageParam.toPageable(Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @Operation(summary = "알림 상세", description = "NTF-002")
    @GetMapping("/{notificationId}")
    public ApiResponse<NotificationResponse> get(
            @LoginUserId Long userId, @PathVariable Long notificationId) {
        return ApiResponse.success(notificationQueryService.get(userId, notificationId));
    }

    @Operation(summary = "알림 전체 읽음",
            description = "안읽음 알림을 한 번에 처리하고 갱신 건수를 준다. NTF-004")
    @PatchMapping("/read-all")
    public ApiResponse<NotificationReadAllResponse> markAllRead(@LoginUserId Long userId) {
        return ApiResponse.success(notificationQueryService.markAllRead(userId));
    }

    @Operation(summary = "알림 읽음 처리",
            description = "멱등 — 이미 읽은 알림은 최초 읽은 시각을 그대로 준다. NTF-003")
    @PatchMapping("/{notificationId}/read")
    public ApiResponse<NotificationReadResponse> markRead(
            @LoginUserId Long userId, @PathVariable Long notificationId) {
        return ApiResponse.success(notificationQueryService.markRead(userId, notificationId));
    }
}
