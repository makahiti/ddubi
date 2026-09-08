package com.ssafy.ddubi.domain.auth.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 소셜 로그인 3종(카카오·구글·네이버)의 공통 흐름.
 *
 * <pre>
 * 1) POST {authUrl}{tokenPath}    인가 코드 → 액세스 토큰
 * 2) GET  {apiUrl}{userInfoPath}  액세스 토큰 → 사용자 정보
 * </pre>
 *
 * 세 제공자가 모두 OAuth 2.0 authorization code 방식이라 뼈대가 같다.
 * 다른 것은 <b>엔드포인트·요청 파라미터·응답 JSON 모양</b> 뿐이므로 그것만 하위 클래스가 채운다.
 *
 * 오류를 전부 401 로 내리는 이유: 인가 코드 만료·redirect_uri 불일치·시크릿 누락은
 * 클라이언트 입장에서 모두 "다시 로그인하라"로 귀결된다. 구분에 필요한 정보는 서버 로그에만 남긴다.
 */
public abstract class AbstractOAuthClient implements OAuthClient {

    private static final Logger log = LoggerFactory.getLogger(AbstractOAuthClient.class);

    private final ObjectMapper objectMapper;
    private final RestClient authClient;
    private final RestClient apiClient;

    protected AbstractOAuthClient(RestClient.Builder builder, ObjectMapper objectMapper,
                                  String authUrl, String apiUrl) {
        this.objectMapper = objectMapper;
        this.authClient = builder.clone().baseUrl(authUrl).build();
        this.apiClient = builder.clone().baseUrl(apiUrl).build();
    }

    // ------------------------------------------------------------------
    // 하위 클래스가 채우는 것
    // ------------------------------------------------------------------

    /** 로그·오류 문구에 쓰는 이름("카카오"). */
    protected abstract String displayName();

    /** 미설정이면 기동은 되지만 로그인이 불가하다. 비어 있으면 500 으로 끊는다. */
    protected abstract String clientId();

    protected abstract String tokenPath();

    protected abstract String userInfoPath();

    /** 토큰 교환 폼. 제공자마다 요구 항목이 다르다. */
    protected abstract MultiValueMap<String, String> tokenForm(OAuthCodeExchange exchange);

    /** 사용자 조회 응답 → 공통 모델. */
    protected abstract OAuthUserInfo parseProfile(JsonNode body);

    /**
     * 오류 응답에서 진단 정보를 뽑는다.
     * 본문을 통째로 찍지 않는 이유: description 에 인가 코드가 섞여 나올 수 있다.
     */
    protected String errorDetail(JsonNode body) {
        return "error=%s, description=%s".formatted(
                body.path("error").asText("?"),
                body.path("error_description").asText("?"));
    }

    // ------------------------------------------------------------------

    @Override
    public OAuthUserInfo fetchUserInfo(OAuthCodeExchange exchange) {
        if (!StringUtils.hasText(clientId())) {
            // 설정 누락을 제공자의 401 로 위장하지 않는다. 원인이 우리 쪽임을 분명히 남긴다.
            log.error("{} 로그인 설정 누락 — client id 없음", displayName());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return fetchProfile(exchangeCodeForToken(exchange));
    }

    private String exchangeCodeForToken(OAuthCodeExchange exchange) {
        JsonNode body;
        try {
            body = authClient.post()
                    .uri(tokenPath())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(tokenForm(exchange))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpStatusCodeException e) {
            log.warn("{} 토큰 교환 실패 [{}] {}", displayName(), e.getStatusCode(), parseError(e));
            throw unauthorized("인가 코드가 유효하지 않거나 만료되었습니다.");
        } catch (RestClientException e) {
            log.warn("{} 토큰 교환 통신 실패: {}", displayName(), e.getClass().getSimpleName());
            throw unauthorized("로그인에 실패했습니다.");
        }

        // 네이버는 실패해도 200 에 error 필드만 담아 준다. 상태코드만 믿으면 놓친다.
        if (body == null || !body.path("access_token").isTextual()) {
            log.warn("{} 토큰 응답에 access_token 이 없음 {}",
                    displayName(), body == null ? "(본문 없음)" : errorDetail(body));
            throw unauthorized("로그인에 실패했습니다.");
        }
        return body.path("access_token").asText();
    }

    protected final OAuthUserInfo fetchProfile(String accessToken) {
        JsonNode body = fetchApiResource(userInfoPath(), accessToken, "사용자 조회");

        OAuthUserInfo info = parseProfile(body);
        if (info == null || !StringUtils.hasText(info.providerUserId())) {
            log.warn("{} 사용자 조회 응답에 고유 ID 가 없음", displayName());
            throw unauthorized("로그인에 실패했습니다.");
        }
        return info;
    }

    /** 제공자 API를 Bearer 토큰으로 호출하고 공통 오류 형식으로 변환한다. */
    protected final JsonNode fetchApiResource(String path, String accessToken, String action) {
        try {
            JsonNode body = apiClient.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw unauthorized("로그인에 실패했습니다.");
            }
            return body;
        } catch (RestClientException e) {
            log.warn("{} {} 실패: {}", displayName(), action, e.getClass().getSimpleName());
            throw unauthorized("로그인에 실패했습니다.");
        }
    }

    private String parseError(HttpStatusCodeException e) {
        try {
            return errorDetail(objectMapper.readTree(e.getResponseBodyAsString()));
        } catch (Exception ignored) {
            return "(응답 파싱 실패)";
        }
    }

    protected BusinessException unauthorized(String message) {
        return new BusinessException(ErrorCode.UNAUTHORIZED, displayName() + " " + message);
    }

    /** 응답에서 문자열 필드를 꺼낸다. 없거나 문자열이 아니면 null. */
    protected static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText() : null;
    }
}
