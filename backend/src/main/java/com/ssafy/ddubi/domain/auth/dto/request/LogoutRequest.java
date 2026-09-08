package com.ssafy.ddubi.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * AUTH-005 로그아웃. fcmToken 은 선택 — 주면 해당 기기 토큰도 무효화해서
 * 로그아웃한 기기로 푸시가 가지 않게 한다.
 */
public record LogoutRequest(
        @NotBlank(message = "refreshToken 은 필수입니다.")
        String refreshToken,

        @NotBlank(message = "deviceId 는 필수입니다.")
        String deviceId,

        String fcmToken
) {
}
