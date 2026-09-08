package com.ssafy.ddubi.domain.auth.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ddubi.external.naver.oauth.* 바인딩.
 *
 * 토큰 교환(nid.naver.com)과 사용자 조회(openapi.naver.com)의 호스트가 다르다.
 */
@ConfigurationProperties(prefix = "ddubi.external.naver.oauth")
public record NaverOAuthProperties(
        String authUrl,
        String apiUrl,
        String clientId,
        String clientSecret
) {
}
