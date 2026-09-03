package com.ssafy.ddubi.domain.auth.dto.request;

import com.ssafy.ddubi.domain.auth.client.OAuthCodeExchange;
import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * AUTH-003 소셜 로그인.
 *
 * redirectUri 를 클라이언트가 보내는 이유: 인가 코드를 토큰으로 교환할 때
 * 제공자가 "코드 발급에 쓴 redirect_uri 와 동일한가"를 대조한다.
 * 서버가 임의로 정하면 클라이언트가 실제로 쓴 값과 어긋나 교환이 실패한다.
 *
 * @param state 인가 요청에 쓴 CSRF 방지용 난수. <b>네이버만 필수</b>다.
 *              명세서 v2.1.1 원문에는 없는 필드이며 개정을 요청해 둔 상태다
 *              (docs/contracts/api-spec-auth-amendment.md)
 */
public record OAuthLoginRequest(
        @NotNull(message = "provider 는 필수입니다.")
        AuthProvider provider,

        @NotBlank(message = "authorizationCode 는 필수입니다.")
        String authorizationCode,

        @NotBlank(message = "redirectUri 는 필수입니다.")
        String redirectUri,

        String state,

        String deviceId
) {

    public OAuthCodeExchange toExchange() {
        return new OAuthCodeExchange(authorizationCode, redirectUri, state);
    }
}
