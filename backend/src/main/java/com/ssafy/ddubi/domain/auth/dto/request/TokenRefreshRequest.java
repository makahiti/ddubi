package com.ssafy.ddubi.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/** AUTH-004 토큰 재발급. deviceId 필수 — 세션이 기기 단위라 어느 세션을 회전할지 알아야 한다. */
public record TokenRefreshRequest(
        @NotBlank(message = "refreshToken 은 필수입니다.")
        String refreshToken,

        @NotBlank(message = "deviceId 는 필수입니다.")
        String deviceId
) {
}
