package com.ssafy.ddubi.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * ddubi.jwt.* 바인딩. 값은 환경변수로 주입하고 코드에 두지 않는다(BACKEND_CONVENTION.md 9장).
 *
 * @param secret                HS256 서명 키. 최소 32바이트여야 한다
 * @param accessTokenValidity   Access 토큰 유효기간 (확정: 60분)
 * @param refreshTokenValidity  Refresh 토큰 유효기간 (확정: 14일)
 */
@ConfigurationProperties(prefix = "ddubi.jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenValidity,
        Duration refreshTokenValidity
) {
}
