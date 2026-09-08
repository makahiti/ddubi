package com.ssafy.ddubi.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;

/** DEVICE-002 FCM 토큰 해제. */
public record DeviceTokenDeleteRequest(
        @NotBlank(message = "fcmToken 은 필수입니다.")
        String fcmToken
) {
}
