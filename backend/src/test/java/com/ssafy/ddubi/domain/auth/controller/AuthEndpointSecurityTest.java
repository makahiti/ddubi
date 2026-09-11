package com.ssafy.ddubi.domain.auth.controller;

import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증 엔드포인트가 <b>로그인 전에 열려 있는지</b>를 HTTP 레벨에서 고정한다.
 *
 * <h3>왜 필요한가 — 실제로 놓쳤다</h3>
 * {@code /auth/oauth/link} 를 만들면서 {@code SecurityConfig.PUBLIC_PATHS} 에 넣는 것을
 * 빠뜨렸다. 서비스 계층 통합 테스트는 {@code AuthService} 를 직접 부르므로 필터 체인을
 * 지나지 않아 <b>전부 통과했다.</b> 그런데 실제 요청은 컨트롤러에 닿기도 전에 401 이었다 —
 * 기능이 통째로 죽어 있었는데 테스트는 초록불이었다.
 *
 * <p>그래서 "열려 있어야 하는 것" 과 "닫혀 있어야 하는 것" 을 여기서 함께 본다.
 * 401 이 아니기만 하면 된다 — 본문 검증·비즈니스 로직은 서비스 테스트의 몫이고,
 * 여기서 보는 것은 <b>문이 열렸는가</b> 하나다.
 */
@AutoConfigureMockMvc
class AuthEndpointSecurityTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("계정 연동 확정은 로그인 없이 접근할 수 있어야 한다")
    void oauthLinkIsPublic() throws Exception {
        // 없는 티켓이라 401 LINK_TOKEN_INVALID 가 나오는 것이 정상이다.
        // 중요한 건 그 판단을 서비스가 했다는 것 — 필터가 막았다면 code 가 UNAUTHORIZED 다.
        mockMvc.perform(post("/api/v1/auth/oauth/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"linkToken":"not-a-real-ticket","password":"pw","deviceId":"d1"}
                                """))
                .andExpect(jsonPath("$.error.code").value("LINK_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("연동 확정에 필수값이 빠지면 400 — 필터가 아니라 검증이 응답한다")
    void oauthLinkValidatesBody() throws Exception {
        mockMvc.perform(post("/api/v1/auth/oauth/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"linkToken":"","password":"","deviceId":"d1"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("마이페이지 소셜 연결은 로그인이 필요하다 — 이건 닫혀 있어야 한다")
    void socialLinkRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/social-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"KAKAO","accessToken":"t"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("연동 확정의 CORS preflight 는 토큰 없이 통과한다")
    void oauthLinkPreflightPasses() throws Exception {
        // 브라우저는 본 요청 전에 OPTIONS 를 먼저 보내는데, 여기에는 Authorization 이 없다.
        // 이게 401 이면 프론트는 본 요청을 아예 보내지 못한다.
        mockMvc.perform(options("/api/v1/auth/oauth/link")
                        .header("Origin", "http://localhost:3001")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3001"));
    }

    @Test
    @DisplayName("소셜 로그인 세 경로도 열려 있다")
    void oauthLoginPathsArePublic() throws Exception {
        // 잘못된 본문이라 400 이 정상이다. 401 이면 필터가 먼저 막은 것이다.
        for (String path : new String[]{"/api/v1/auth/oauth",
                "/api/v1/auth/oauth/token", "/api/v1/auth/oauth/id-token"}) {
            mockMvc.perform(post(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }
}
