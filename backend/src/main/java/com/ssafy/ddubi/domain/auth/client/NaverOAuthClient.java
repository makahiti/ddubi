package com.ssafy.ddubi.domain.auth.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 네이버 로그인 연동.
 *
 * <pre>
 * 1) POST nid.naver.com/oauth2.0/token    인가 코드 → 액세스 토큰
 * 2) GET  openapi.naver.com/v1/nid/me     액세스 토큰 → id·email·nickname
 * </pre>
 *
 * 다른 두 제공자와 다른 점이 셋 있다.
 * <ul>
 *   <li><b>{@code state} 가 필수</b>다. 클라이언트가 인가 요청에 쓴 값을 그대로 넘겨야 한다</li>
 *   <li>토큰 교환에 {@code redirect_uri} 를 받지 않는다</li>
 *   <li>사용자 정보가 {@code response} 객체 안에 한 겹 더 싸여 있다</li>
 * </ul>
 */
@Component
public class NaverOAuthClient extends AbstractOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(NaverOAuthClient.class);

    /** 정상 응답 코드. 네이버는 실패해도 HTTP 200 을 주고 이 값으로 구분한다. */
    private static final String RESULT_OK = "00";

    private final NaverOAuthProperties properties;

    public NaverOAuthClient(RestClient.Builder builder, ObjectMapper objectMapper,
                            NaverOAuthProperties properties) {
        super(builder, objectMapper, properties.authUrl(), properties.apiUrl());
        this.properties = properties;
    }

    @Override
    public AuthProvider provider() {
        return AuthProvider.NAVER;
    }

    @Override
    protected String displayName() {
        return "네이버";
    }

    @Override
    protected String clientId() {
        return properties.clientId();
    }

    @Override
    protected String tokenPath() {
        return "/oauth2.0/token";
    }

    @Override
    protected String userInfoPath() {
        return "/v1/nid/me";
    }

    @Override
    public OAuthUserInfo fetchUserInfoFromAccessToken(String accessToken) {
        return fetchProfile(accessToken);
    }

    @Override
    protected MultiValueMap<String, String> tokenForm(OAuthCodeExchange exchange) {
        if (!StringUtils.hasText(exchange.state())) {
            // 네이버가 거부하기 전에 우리가 먼저 끊는다. 원인이 클라이언트 요청임을 분명히 한다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "네이버 로그인은 state 가 필수입니다.");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("code", exchange.authorizationCode());
        form.add("state", exchange.state());
        return form;
    }

    @Override
    protected OAuthUserInfo parseProfile(JsonNode body) {
        String resultCode = body.path("resultcode").asText("");
        if (!RESULT_OK.equals(resultCode)) {
            log.warn("네이버 사용자 조회 실패 resultcode={} message={}",
                    resultCode, body.path("message").asText("?"));
            throw unauthorized("로그인에 실패했습니다.");
        }

        // 실제 값은 response 안에 있다.
        JsonNode profile = body.path("response");

        // 네이버는 가입 시 본인확인을 거치므로 프로필 API 에 인증 플래그 자체가 없다.
        // 카카오·구글처럼 확인할 값이 없어 "검증됨" 으로 본다 — 없는 플래그를 false 로 두면
        // 네이버 사용자는 계정 연동을 영영 못 한다.
        //
        // 이 판단이 위험하지 않은 이유: 연동에는 기존 계정의 비밀번호가 필요하다(2026-09-10).
        // 남는 위험은 "그 이메일로 계정이 있다"는 사실이 마스킹된 형태로 노출되는 것뿐이다.
        // 동의 항목에서 이메일을 빼면 값 자체가 오지 않는다.
        return new OAuthUserInfo(
                text(profile, "id"),
                text(profile, "email"),
                true,
                text(profile, "nickname"));
    }
}
