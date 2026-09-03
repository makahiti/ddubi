package com.ssafy.ddubi.domain.user.dto.request;

import com.ssafy.ddubi.domain.user.entity.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** DEVICE-001 FCM 토큰 등록·갱신. */
public record DeviceTokenRegisterRequest(
        @NotBlank(message = "fcmToken 은 필수입니다.")
        @Size(max = 512, message = "fcmToken 은 512자를 넘을 수 없습니다.")
        String fcmToken,

        @NotNull(message = "platform 은 필수입니다.")
        DevicePlatform platform
) {
}
