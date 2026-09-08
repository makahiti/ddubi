package com.ssafy.ddubi.domain.auth.dto.request;

import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 모바일 소셜 SDK가 발급한 액세스 토큰으로 로그인한다. */
public record OAuthAccessTokenLoginRequest(
        @NotNull(message = "provider 는 필수입니다.")
        AuthProvider provider,

        @NotBlank(message = "accessToken 은 필수입니다.")
        String accessToken,

        String deviceId
) {
}
