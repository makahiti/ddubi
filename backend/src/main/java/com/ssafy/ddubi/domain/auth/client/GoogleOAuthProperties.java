package com.ssafy.ddubi.domain.auth.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ddubi.external.google.oauth.* 바인딩.
 *
 * 토큰 교환(oauth2.googleapis.com)과 사용자 조회(www.googleapis.com)의 호스트가 다르다.
 *
 * @param clientSecret 구글은 웹 애플리케이션 유형에서 <b>항상 필수</b>다(카카오와 달리 선택이 아니다)
 */
@ConfigurationProperties(prefix = "ddubi.external.google.oauth")
public record GoogleOAuthProperties(
        String authUrl,
        String apiUrl,
        String clientId,
        String clientSecret
) {
}
