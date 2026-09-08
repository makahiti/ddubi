package com.ssafy.ddubi.domain.auth.dto.response;

/**
 * AUTH-001~004 공통 응답. Notion API 명세서 v2.1.1 기준.
 *
 * AUTH-004(재발급)는 명세서상 토큰 묶음만 반환하므로 {@code user} 와 {@code deviceId} 가 없다.
 * ApiResponse 가 NON_NULL 직렬화라 null 필드는 응답에서 빠진다.
 */
public record AuthTokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long accessTokenExpiresInSec,
        long refreshTokenExpiresInSec,
        String deviceId,
        AuthUserResponse user
) {

    public static final String BEARER = "Bearer";

    /** 가입·로그인 응답 — 토큰 + deviceId + user. */
    public static AuthTokenResponse withUser(String accessToken, String refreshToken,
                                             long accessExpiresInSec, long refreshExpiresInSec,
                                             String deviceId, AuthUserResponse user) {
        return new AuthTokenResponse(accessToken, refreshToken, BEARER,
                accessExpiresInSec, refreshExpiresInSec, deviceId, user);
    }

    /** 재발급 응답 — 명세서 AUTH-004 에는 user 가 없다. */
    public static AuthTokenResponse tokensOnly(String accessToken, String refreshToken,
                                               long accessExpiresInSec, long refreshExpiresInSec) {
        return new AuthTokenResponse(accessToken, refreshToken, BEARER,
                accessExpiresInSec, refreshExpiresInSec, null, null);
    }
}
