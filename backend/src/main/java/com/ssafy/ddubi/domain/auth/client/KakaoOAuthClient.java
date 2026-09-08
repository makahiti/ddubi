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
 * 카카오 로그인 연동.
 *
 * <pre>
 * 1) POST kauth.kakao.com/oauth/token   인가 코드 → 액세스 토큰
 * 2) GET  kapi.kakao.com/v2/user/me     액세스 토큰 → 회원번호·이메일·닉네임
 * </pre>
 *
 * 이메일은 <b>없을 수 있다.</b> 사용자가 이메일 제공에 동의하지 않으면 카카오가 주지 않으며,
 * 그 경우 기존 계정과 통합되지 않고 별도 계정이 만들어진다(도메인 규칙 3).
 */
@Component
public class KakaoOAuthClient extends AbstractOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoOAuthClient.class);

    private final KakaoOAuthProperties properties;

    public KakaoOAuthClient(RestClient.Builder builder, ObjectMapper objectMapper,
                            KakaoOAuthProperties properties) {
        super(builder, objectMapper, properties.authUrl(), properties.apiUrl());
        this.properties = properties;
    }

    @Override
    public AuthProvider provider() {
        return AuthProvider.KAKAO;
    }

    @Override
    protected String displayName() {
        return "카카오";
    }

    @Override
    protected String clientId() {
        return properties.restApiKey();
    }

    @Override
    protected String tokenPath() {
        return "/oauth/token";
    }

    @Override
    protected String userInfoPath() {
        return "/v2/user/me";
    }

    @Override
    public OAuthUserInfo fetchUserInfoFromAccessToken(String accessToken) {
        if (!StringUtils.hasText(properties.appId())) {
            log.error("카카오 모바일 로그인 설정 누락 — app id 없음");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        JsonNode tokenInfo = fetchApiResource(
                "/v1/user/access_token_info", accessToken, "액세스 토큰 검증");
        String tokenAppId = tokenInfo.path("app_id").asText();
        if (!properties.appId().equals(tokenAppId)) {
            log.warn("카카오 액세스 토큰의 앱 ID 불일치");
            throw unauthorized("액세스 토큰이 유효하지 않습니다.");
        }
        return fetchProfile(accessToken);
    }

    @Override
    protected MultiValueMap<String, String> tokenForm(OAuthCodeExchange exchange) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.restApiKey());
        form.add("redirect_uri", exchange.redirectUri());
        form.add("code", exchange.authorizationCode());
        // 콘솔에서 켰을 때만 값이 있다. 켜 놓고 안 보내면 KOE010 이 난다.
        if (StringUtils.hasText(properties.clientSecret())) {
            form.add("client_secret", properties.clientSecret());
        }
        return form;
    }

    /**
     * error_code(KOE320 등)를 함께 남긴다.
     * 이게 없으면 만료인지, redirect_uri 불일치인지, client_secret 누락인지 구분할 수 없다.
     */
    @Override
    protected String errorDetail(JsonNode body) {
        return "error=%s, error_code=%s, description=%s".formatted(
                body.path("error").asText("?"),
                body.path("error_code").asText("?"),
                body.path("error_description").asText("?"));
    }

    @Override
    protected OAuthUserInfo parseProfile(JsonNode body) {
        String providerUserId = body.path("id").isMissingNode() ? null : body.path("id").asText();
        JsonNode account = body.path("kakao_account");

        // 미인증 이메일로는 계정을 통합하지 않는다 — 남의 이메일을 등록해 두고
        // 그 계정을 가져가는 경로가 생긴다(도메인 규칙 3).
        String email = null;
        if (account.path("email").isTextual()) {
            if (account.path("is_email_verified").asBoolean(false)) {
                email = account.path("email").asText();
            } else {
                log.warn("카카오 이메일 미인증 — 계정 통합 대상에서 제외한다");
            }
        }
        String nickname = text(account.path("profile"), "nickname");

        return new OAuthUserInfo(providerUserId, email, nickname);
    }
}
