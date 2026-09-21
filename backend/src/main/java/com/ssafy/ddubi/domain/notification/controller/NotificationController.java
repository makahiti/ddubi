package com.ssafy.ddubi.domain.notification.controller;

import com.ssafy.ddubi.domain.notification.dto.response.NotificationDeleteResponse;
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
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "알림", description = "알림함 조회 · 읽음 처리 · 삭제")
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

    @Operation(summary = "알림 삭제",
            description = """
                    알림함에서 지운다. 발송 이력은 남는다 — 지운 뒤에도 같은 상황의 알림이
                    다시 오지는 않는다(재발송 억제가 이력을 근거로 판단한다).

                    멱등이다. 이미 지운 알림을 다시 보내도 200 이고 deletedCount 가 0 이다.

                    | 상황 | 응답 |
                    |---|---|
                    | 없는 알림 | 404 `NOTIFICATION_NOT_FOUND` |
                    | 남의 알림 | 403 `FORBIDDEN_RESOURCE` |

                    NTF-005""")
    @DeleteMapping("/{notificationId}")
    public ApiResponse<NotificationDeleteResponse> delete(
            @LoginUserId Long userId, @PathVariable Long notificationId) {
        return ApiResponse.success(notificationQueryService.delete(userId, List.of(notificationId)));
    }

    @Operation(summary = "알림 여러 건 삭제",
            description = """
                    선택 모드에서 고른 알림을 한 번에 지운다. `DELETE /api/v1/notifications?ids=1,2,3`

                    **하나라도 없거나 남의 것이면 아무것도 지우지 않는다.** 일부만 지워진 채로
                    끝나면 화면이 무엇을 지웠는지 알 수 없어서다. 한 번에 100건까지다.

                    NTF-005""")
    @DeleteMapping
    public ApiResponse<NotificationDeleteResponse> deleteMany(
            @LoginUserId Long userId, @RequestParam("ids") List<Long> ids) {
        return ApiResponse.success(notificationQueryService.delete(userId, ids));
    }
}
