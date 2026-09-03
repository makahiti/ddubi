package com.ssafy.ddubi.domain.auth.dto.request;

import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 모바일 OpenID Connect SDK가 발급한 ID 토큰으로 로그인한다. */
public record OAuthIdTokenLoginRequest(
        @NotNull(message = "provider 는 필수입니다.")
        AuthProvider provider,

        @NotBlank(message = "idToken 은 필수입니다.")
        String idToken,

        String deviceId
) {
}
