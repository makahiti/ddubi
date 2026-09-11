package com.ssafy.ddubi.domain.auth.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 소셜 클라이언트 3종의 HTTP 경로 검증.
 *
 * 통합 테스트는 이 클래스들을 통째로 목킹하므로 실제 요청 파라미터와 응답 파싱은
 * 여기서만 검증된다. 제공자에게 잘못된 폼을 보내는 실수는 이 테스트로만 잡힌다.
 */
class OAuthClientTest {

    private static final String AUTH_URL = "https://auth.test";
    private static final String API_URL = "https://api.test";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient.Builder builder;
    private MockRestServiceServer server;

    private void setUpServer() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    // ==================================================================
    @Nested
    @DisplayName("카카오")
    class Kakao {

        private KakaoOAuthClient client(String restApiKey, String clientSecret) {
            return client(restApiKey, clientSecret, "12345");
        }

        private KakaoOAuthClient client(String restApiKey, String clientSecret, String appId) {
            setUpServer();
            return new KakaoOAuthClient(builder, objectMapper,
                    new KakaoOAuthProperties(AUTH_URL, API_URL, restApiKey, clientSecret, appId));
        }

        @Test
        @DisplayName("인가 코드를 토큰으로 바꾸고 회원번호·이메일·닉네임을 돌려준다")
        void fetchUserInfo() {
            KakaoOAuthClient client = client("kakao-key", null);
            server.expect(requestTo(AUTH_URL + "/oauth/token"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().formDataContains(Map.of(
                            "grant_type", "authorization_code",
                            "client_id", "kakao-key",
                            "code", "code-1",
                            "redirect_uri", "http://localhost:3001/oauth/kakao")))
                    .andRespond(withSuccess("{\"access_token\":\"at-1\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(API_URL + "/v2/user/me"))
                    .andExpect(header("Authorization", "Bearer at-1"))
                    .andRespond(withSuccess("{\"id\":123456,\"kakao_account\":{\"email\":\"a@b.com\","
                            + "\"is_email_verified\":true,\"profile\":{\"nickname\":\"뚜비\"}}}",
                            MediaType.APPLICATION_JSON));

            OAuthUserInfo info = client.fetchUserInfo(
                    new OAuthCodeExchange("code-1", "http://localhost:3001/oauth/kakao", null));

            assertThat(client.provider()).isEqualTo(AuthProvider.KAKAO);
            assertThat(info.providerUserId()).isEqualTo("123456");
            assertThat(info.email()).isEqualTo("a@b.com");
            assertThat(info.nickname()).isEqualTo("뚜비");
            server.verify();
        }

        @Test
        @DisplayName("클라이언트 시크릿이 설정돼 있으면 토큰 교환에 함께 보낸다")
        void sendsClientSecretWhenConfigured() {
            KakaoOAuthClient client = client("kakao-key", "secret-1");
            server.expect(requestTo(AUTH_URL + "/oauth/token"))
                    .andExpect(content().formDataContains(Map.of("client_secret", "secret-1")))
                    .andRespond(withSuccess("{\"access_token\":\"at-1\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(API_URL + "/v2/user/me"))
                    .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));

            client.fetchUserInfo(new OAuthCodeExchange("c", "r", null));

            server.verify();
        }

        @Test
        @DisplayName("이메일이 미인증이면 계정 통합에 쓰지 않도록 null 로 준다")
        void unverifiedEmailIsDropped() {
            KakaoOAuthClient client = client("kakao-key", null);
            server.expect(requestTo(AUTH_URL + "/oauth/token"))
                    .andRespond(withSuccess("{\"access_token\":\"at-1\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(API_URL + "/v2/user/me"))
                    .andRespond(withSuccess("{\"id\":1,\"kakao_account\":{\"email\":\"a@b.com\","
                            + "\"is_email_verified\":false}}", MediaType.APPLICATION_JSON));

            OAuthUserInfo info = client.fetchUserInfo(new OAuthCodeExchange("c", "r", null));

            // 클라이언트는 값을 버리지 않고 그대로 넘긴다(2026-09-10). 쓸지 말지는 AuthService 가
            // 정한다 — 제공자마다 정책이 흩어져 있으면 나중에 한쪽만 고쳐진다.
            assertThat(info.email()).isEqualTo("a@b.com");
            assertThat(info.emailVerified()).isFalse();
            assertThat(info.hasVerifiedEmail())
                    .as("연동 후보가 되면 안 된다 — 판단은 이 값으로 한다")
                    .isFalse();
        }

        @Test
        @DisplayName("이메일 동의를 안 했으면 email 이 null 이다")
        void missingEmail() {
            KakaoOAuthClient client = client("kakao-key", null);
            server.expect(requestTo(AUTH_URL + "/oauth/token"))
                    .andRespond(withSuccess("{\"access_token\":\"at-1\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(API_URL + "/v2/user/me"))
                    .andRespond(withSuccess("{\"id\":1,\"kakao_account\":{}}", MediaType.APPLICATION_JSON));

            assertThat(client.fetchUserInfo(new OAuthCodeExchange("c", "r", null)).email()).isNull();
        }

        @Test
        @DisplayName("REST API 키가 없으면 제공자를 호출하지 않고 500 으로 끊는다")
        void missingKeyFailsFast() {
            KakaoOAuthClient client = client("", null);

            assertThatThrownBy(() -> client.fetchUserInfo(new OAuthCodeExchange("c", "r", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INTERNAL_ERROR);
            server.verify(); // 아무 요청도 나가지 않았다
        }

        @Test
        @DisplayName("인가 코드가 만료되면 401 로 바꾼다")
        void expiredCode() {
            KakaoOAuthClient client = client("kakao-key", null);
            server.expect(requestTo(AUTH_URL + "/oauth/token"))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"error\":\"invalid_grant\",\"error_code\":\"KOE320\"}"));

            assertThatThrownBy(() -> client.fetchUserInfo(new OAuthCodeExchange("c", "r", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("모바일 액세스 토큰의 앱 ID를 확인한 뒤 사용자 정보를 조회한다")
        void fetchUserInfoFromAccessToken() {
            KakaoOAuthClient client = client("kakao-key", null);
            server.expect(requestTo(API_URL + "/v1/user/access_token_info"))
                    .andExpect(header("Authorization", "Bearer mobile-at"))
                    .andRespond(withSuccess("{\"app_id\":12345}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(API_URL + "/v2/user/me"))
                    .andExpect(header("Authorization", "Bearer mobile-at"))
                    .andRespond(withSuccess("{\"id\":77,\"kakao_account\":{\"email\":\"m@b.com\"," +
                                    "\"is_email_verified\":true,\"profile\":{\"nickname\":\"모바일뚜비\"}}}",
                            MediaType.APPLICATION_JSON));

            OAuthUserInfo info = client.fetchUserInfoFromAccessToken("mobile-at");

            assertThat(info.providerUserId()).isEqualTo("77");
            assertThat(info.email()).isEqualTo("m@b.com");
            server.verify();
        }

        @Test
        @DisplayName("다른 카카오 앱에서 발급된 액세스 토큰은 거절한다")
        void rejectsAccessTokenFromAnotherApp() {
            KakaoOAuthClient client = client("kakao-key", null);
            server.expect(requestTo(API_URL + "/v1/user/access_token_info"))
                    .andRespond(withSuccess("{\"app_id\":99999}", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.fetchUserInfoFromAccessToken("foreign-at"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
            server.verify();
        }

        @Test
        @DisplayName("앱 ID 설정이 없으면 제공자를 호출하지 않고 500으로 끊는다")
        void missingAppIdFailsFast() {
            KakaoOAuthClient client = client("kakao-key", null, "");

            assertThatThrownBy(() -> client.fetchUserInfoFromAccessToken("mobile-at"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INTERNAL_ERROR);
            server.verify();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("구글")
    class Google {

        private GoogleOAuthClient client(String clientId) {
            setUpServer();
            return new GoogleOAuthClient(builder, objectMapper,
                    new GoogleOAuthProperties(AUTH_URL, API_URL, clientId, "g-secret"));
        }

        @Test
        @DisplayName("sub 를 고유 ID 로 쓰고 시크릿을 반드시 보낸다")
        void fetchUserInfo() {
            GoogleOAuthClient client = client("g-id");
            server.expect(requestTo(AUTH_URL + "/token"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().formDataContains(Map.of(
                            "client_id", "g-id",
                            "client_secret", "g-secret",
                            "code", "code-1")))
                    .andRespond(withSuccess("{\"access_token\":\"at-1\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(API_URL + "/oauth2/v3/userinfo"))
                    .andExpect(header("Authorization", "Bearer at-1"))
                    .andRespond(withSuccess("{\"sub\":\"g-999\",\"email\":\"a@b.com\","
                            + "\"email_verified\":true,\"name\":\"정민\"}", MediaType.APPLICATION_JSON));

            OAuthUserInfo info = client.fetchUserInfo(new OAuthCodeExchange("code-1", "r", null));

            assertThat(client.provider()).isEqualTo(AuthProvider.GOOGLE);
            assertThat(info.providerUserId()).isEqualTo("g-999");
            assertThat(info.email()).isEqualTo("a@b.com");
            assertThat(info.nickname()).isEqualTo("정민");
            server.verify();
        }

        @Test
        @DisplayName("email_verified 를 문자열로 줘도 인정한다")
        void emailVerifiedAsString() {
            GoogleOAuthClient client = client("g-id");
            server.expect(requestTo(AUTH_URL + "/token"))
                    .andRespond(withSuccess("{\"access_token\":\"at-1\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(API_URL + "/oauth2/v3/userinfo"))
                    .andRespond(withSuccess("{\"sub\":\"g-1\",\"email\":\"a@b.com\","
                            + "\"email_verified\":\"true\"}", MediaType.APPLICATION_JSON));

            assertThat(client.fetchUserInfo(new OAuthCodeExchange("c", "r", null)).email())
                    .isEqualTo("a@b.com");
        }

        @Test
        @DisplayName("미인증 이메일은 계정 통합에 쓰지 않는다")
        void unverifiedEmailIsDropped() {
            GoogleOAuthClient client = client("g-id");
            server.expect(requestTo(AUTH_URL + "/token"))
                    .andRespond(withSuccess("{\"access_token\":\"at-1\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(API_URL + "/oauth2/v3/userinfo"))
                    .andRespond(withSuccess("{\"sub\":\"g-1\",\"email\":\"a@b.com\","
                            + "\"email_verified\":false}", MediaType.APPLICATION_JSON));

            OAuthUserInfo info = client.fetchUserInfo(new OAuthCodeExchange("c", "r", null));

            assertThat(info.email()).isEqualTo("a@b.com");
            assertThat(info.hasVerifiedEmail())
                    .as("연동 후보가 되면 안 된다")
                    .isFalse();
        }

        @Test
        @DisplayName("모바일 Google ID 토큰의 audience를 검증하고 사용자 정보를 돌려준다")
        void fetchUserInfoFromIdToken() {
            GoogleOAuthClient client = client("google-web-client-id");
            server.expect(requestTo(AUTH_URL + "/tokeninfo?id_token=google-id-token"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"aud\":\"google-web-client-id\",\"sub\":\"google-7\"," +
                                    "\"email\":\"g@b.com\",\"email_verified\":true,\"name\":\"구글뚜비\"}",
                            MediaType.APPLICATION_JSON));

            OAuthUserInfo info = client.fetchUserInfoFromIdToken("google-id-token");

            assertThat(info.providerUserId()).isEqualTo("google-7");
            assertThat(info.email()).isEqualTo("g@b.com");
            server.verify();
        }

        @Test
        @DisplayName("다른 Google OAuth 클라이언트용 ID 토큰은 거절한다")
        void rejectsIdTokenForAnotherAudience() {
            GoogleOAuthClient client = client("google-web-client-id");
            server.expect(requestTo(AUTH_URL + "/tokeninfo?id_token=foreign-token"))
                    .andRespond(withSuccess("{\"aud\":\"another-client\",\"sub\":\"google-7\"}",
                            MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.fetchUserInfoFromIdToken("foreign-token"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
            server.verify();
        }

        @Test
        @DisplayName("클라이언트 ID 가 없으면 호출하지 않고 500 으로 끊는다")
        void missingClientId() {
            GoogleOAuthClient client = client(null);

            assertThatThrownBy(() -> client.fetchUserInfo(new OAuthCodeExchange("c", "r", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INTERNAL_ERROR);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("네이버")
    class Naver {

        private NaverOAuthClient client(String clientId) {
            setUpServer();
            return new NaverOAuthClient(builder, objectMapper,
                    new NaverOAuthProperties(AUTH_URL, API_URL, clientId, "n-secret"));
        }

        @Test
        @DisplayName("state 를 함께 보내고 response 안의 정보를 꺼낸다")
        void fetchUserInfo() {
            NaverOAuthClient client = client("n-id");
            server.expect(requestTo(AUTH_URL + "/oauth2.0/token"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().formDataContains(Map.of(
                            "client_id", "n-id",
                            "client_secret", "n-secret",
                            "code", "code-1",
                            "state", "st-1")))
                    .andRespond(withSuccess("{\"access_token\":\"at-1\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(API_URL + "/v1/nid/me"))
                    .andExpect(header("Authorization", "Bearer at-1"))
                    .andRespond(withSuccess("{\"resultcode\":\"00\",\"message\":\"success\","
                            + "\"response\":{\"id\":\"n-777\",\"email\":\"a@b.com\",\"nickname\":\"뚜비\"}}",
                            MediaType.APPLICATION_JSON));

            OAuthUserInfo info = client.fetchUserInfo(new OAuthCodeExchange("code-1", "r", "st-1"));

            assertThat(client.provider()).isEqualTo(AuthProvider.NAVER);
            assertThat(info.providerUserId()).isEqualTo("n-777");
            assertThat(info.email()).isEqualTo("a@b.com");
            assertThat(info.nickname()).isEqualTo("뚜비");
            server.verify();
        }

        @Test
        @DisplayName("state 가 없으면 네이버를 호출하지 않고 400 으로 끊는다")
        void stateIsRequired() {
            NaverOAuthClient client = client("n-id");

            assertThatThrownBy(() -> client.fetchUserInfo(new OAuthCodeExchange("c", "r", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_REQUEST);
            server.verify(); // 아무 요청도 나가지 않았다
        }

        @Test
        @DisplayName("resultcode 가 00 이 아니면 200 이어도 실패로 본다")
        void nonOkResultCode() {
            NaverOAuthClient client = client("n-id");
            server.expect(requestTo(AUTH_URL + "/oauth2.0/token"))
                    .andRespond(withSuccess("{\"access_token\":\"at-1\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(API_URL + "/v1/nid/me"))
                    .andRespond(withSuccess("{\"resultcode\":\"024\",\"message\":\"Authentication failed\"}",
                            MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.fetchUserInfo(new OAuthCodeExchange("c", "r", "st")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("토큰 응답이 200 이어도 access_token 이 없으면 실패로 본다")
        void tokenErrorWithHttp200() {
            NaverOAuthClient client = client("n-id");
            server.expect(requestTo(AUTH_URL + "/oauth2.0/token"))
                    .andRespond(withSuccess("{\"error\":\"invalid_request\"}", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.fetchUserInfo(new OAuthCodeExchange("c", "r", "st")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
        }
    }
}
