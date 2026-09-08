package com.ssafy.ddubi.domain.notification.controller;

import com.ssafy.ddubi.domain.notification.dto.response.NotificationResponse;
import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import com.ssafy.ddubi.domain.notification.service.NotificationCommand;
import com.ssafy.ddubi.domain.notification.service.NotificationService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개발용 테스트 발송. <b>운영에는 존재하지 않는다.</b>
 *
 * <h3>왜 있나</h3>
 * 알림 생성({@code NotificationService.create})은 추천 엔진만 부르는 내부 진입점이라
 * HTTP 로 열려 있지 않다. 그런데 프론트가 FCM 을 붙일 때, 백엔드가 억제 동작을 확인할 때
 * "지금 나에게 한 건 쏴보기" 가 반드시 필요하다. 없으면 그때 급하게 만들게 된다.
 *
 * <h3>안전장치 두 가지</h3>
 * <ul>
 *   <li>{@code @Profile("dev")} — 운영 프로파일에서는 빈 자체가 만들어지지 않는다.
 *       경로를 막는 게 아니라 <b>존재하지 않게</b> 한다</li>
 *   <li>대상이 항상 {@code @LoginUserId} 다 — 남에게 보낼 수 없다.
 *       userId 를 파라미터로 받으면 아무나 남의 폰에 푸시를 쏘는 구멍이 된다</li>
 * </ul>
 *
 * 억제도 그대로 적용된다. 같은 suppressKey 로 두 번 보내면 두 번째는 SUPPRESSED 다 —
 * 테스트용이라고 규칙을 우회하면 확인할 값어치가 없다.
 */
@Profile("dev")
@Tag(name = "개발용", description = "dev 프로파일 전용. 운영에는 없다")
@RestController
@RequestMapping("/api/v1/dev/notifications")
public class DevNotificationController {

    private final NotificationService notificationService;

    public DevNotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(summary = "테스트 알림 발송 (dev 전용)",
            description = "로그인한 본인에게 알림을 하나 만든다. 억제 규칙이 그대로 적용되므로 "
                    + "같은 suppressKey 를 두 번 보내면 두 번째는 SUPPRESSED 다. "
                    + "유효한 FCM 토큰이 등록돼 있으면 실제로 푸시가 간다.")
    @PostMapping("/test")
    public ApiResponse<NotificationResponse> sendTest(
            @LoginUserId Long userId,
            @Valid @RequestBody TestNotificationRequest request) {
        var notification = notificationService.create(NotificationCommand.builder()
                .userId(userId)
                .scheduleId(request.scheduleId())
                .type(request.type())
                .title(request.title())
                .body(request.body())
                .suppressKey(request.suppressKey())
                .build());
        return ApiResponse.success(NotificationResponse.from(notification));
    }

    /**
     * @param suppressKey null 이면 중복 검사를 건너뛴다 — 연달아 여러 번 쏘고 싶을 때 비운다
     */
    public record TestNotificationRequest(
            @NotNull(message = "type 은 필수입니다.")
            NotificationType type,

            @NotNull(message = "title 은 필수입니다.")
            @Size(max = 100, message = "title 은 100자를 넘을 수 없습니다.")
            String title,

            @NotNull(message = "body 는 필수입니다.")
            @Size(max = 500, message = "body 는 500자를 넘을 수 없습니다.")
            String body,

            Long scheduleId,
            String suppressKey
    ) {
    }
}
