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
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 구글 로그인 연동.
 *
 * <pre>
 * 1) POST oauth2.googleapis.com/token          인가 코드 → 액세스 토큰
 * 2) GET  www.googleapis.com/oauth2/v3/userinfo 액세스 토큰 → sub·email·name
 * </pre>
 *
 * 고유 ID 는 {@code sub} 다. {@code email} 은 바뀔 수 있으므로 식별자로 쓰지 않는다.
 *
 * userinfo 엔드포인트를 쓰고 id_token 을 검증하지 않는 이유: 토큰을 우리 서버가
 * 구글에서 직접 받았으므로(중간자 없음) 서명 검증이 추가로 주는 것이 없다.
 * 클라이언트가 준 토큰을 믿는 구조였다면 반드시 검증해야 한다.
 */
@Component
public class GoogleOAuthClient extends AbstractOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthClient.class);

    private final GoogleOAuthProperties properties;
    private final RestClient tokenInfoClient;

    public GoogleOAuthClient(RestClient.Builder builder, ObjectMapper objectMapper,
                             GoogleOAuthProperties properties) {
        super(builder, objectMapper, properties.authUrl(), properties.apiUrl());
        this.properties = properties;
        this.tokenInfoClient = builder.clone().baseUrl(properties.authUrl()).build();
    }

    @Override
    public AuthProvider provider() {
        return AuthProvider.GOOGLE;
    }

    @Override
    protected String displayName() {
        return "구글";
    }

    @Override
    protected String clientId() {
        return properties.clientId();
    }

    @Override
    protected String tokenPath() {
        return "/token";
    }

    @Override
    protected String userInfoPath() {
        return "/oauth2/v3/userinfo";
    }

    @Override
    public OAuthUserInfo fetchUserInfoFromIdToken(String idToken) {
        if (!org.springframework.util.StringUtils.hasText(properties.clientId())) {
            log.error("구글 로그인 설정 누락 — client id 없음");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        JsonNode body;
        try {
            body = tokenInfoClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/tokeninfo")
                            .queryParam("id_token", idToken)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("구글 ID 토큰 검증 실패: {}", e.getClass().getSimpleName());
            throw unauthorized("ID 토큰이 유효하지 않습니다.");
        }
        if (body == null || !properties.clientId().equals(text(body, "aud"))) {
            log.warn("구글 ID 토큰의 audience 불일치");
            throw unauthorized("ID 토큰이 유효하지 않습니다.");
        }
        OAuthUserInfo info = parseProfile(body);
        if (!org.springframework.util.StringUtils.hasText(info.providerUserId())) {
            throw unauthorized("로그인에 실패했습니다.");
        }
        return info;
    }

    @Override
    protected MultiValueMap<String, String> tokenForm(OAuthCodeExchange exchange) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", exchange.redirectUri());
        form.add("code", exchange.authorizationCode());
        return form;
    }

    @Override
    protected OAuthUserInfo parseProfile(JsonNode body) {
        // 미인증 이메일로는 계정을 통합하지 않는다(도메인 규칙 3).
        // 구글 개인 계정은 사실상 항상 true 지만, Workspace 관리자가 만든 계정은 아닐 수 있다.
        String email = null;
        if (body.path("email").isTextual()) {
            if (isEmailVerified(body)) {
                email = body.path("email").asText();
            } else {
                log.warn("구글 이메일 미인증 — 계정 통합 대상에서 제외한다");
            }
        }
        return new OAuthUserInfo(text(body, "sub"), email, text(body, "name"));
    }

    /** 구글은 email_verified 를 boolean 으로도 문자열 "true" 로도 준다. */
    private boolean isEmailVerified(JsonNode body) {
        JsonNode verified = body.path("email_verified");
        return verified.isBoolean() ? verified.asBoolean() : "true".equalsIgnoreCase(verified.asText());
    }
}
