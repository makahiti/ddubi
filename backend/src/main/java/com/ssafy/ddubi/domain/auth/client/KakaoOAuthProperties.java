package com.ssafy.ddubi.domain.auth.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ddubi.external.kakao.oauth.* 바인딩.
 *
 * 길찾기(dapi.kakao.com)와 호스트가 다르다 — 로그인은 kauth(토큰 교환)와 kapi(사용자 조회)를 쓴다.
 * restApiKey 는 길찾기와 같은 값이지만(같은 앱), 설정 클래스는 도메인별로 나눈다.
 *
 * @param clientSecret 콘솔에서 켰을 때만 값이 있다. 비어 있으면 토큰 교환에 보내지 않는다
 * @param appId 카카오 앱의 숫자 ID. 모바일 SDK 토큰이 같은 앱에서 발급됐는지 검증한다
 */
@ConfigurationProperties(prefix = "ddubi.external.kakao.oauth")
public record KakaoOAuthProperties(
        String authUrl,
        String apiUrl,
        String restApiKey,
        String clientSecret,
        String appId
) {
}
