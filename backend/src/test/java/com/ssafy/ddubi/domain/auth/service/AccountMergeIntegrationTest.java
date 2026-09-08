package com.ssafy.ddubi.domain.auth.service;

import com.ssafy.ddubi.domain.auth.client.GoogleOAuthClient;
import com.ssafy.ddubi.domain.auth.client.NaverOAuthClient;
import com.ssafy.ddubi.domain.auth.client.OAuthUserInfo;
import com.ssafy.ddubi.domain.auth.dto.request.LocalSignupRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthAccessTokenLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthIdTokenLoginRequest;
import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.domain.auth.repository.UserAuthRepository;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 도메인 규칙 3 — 계정 통합.
 *
 * <blockquote>
 * 이메일이 계정 키. 소셜 로그인 시 이메일이 기존 user 와 일치하면 새 계정을 만들지 않고
 * user_auth 에 provider 행만 추가한다. 카카오 이메일 미동의(email=null)면 통합 불가 → 별도 계정.
 * 로컬 가입 시 이미 소셜 계정에 쓰인 이메일이면 409.
 * </blockquote>
 */
class AccountMergeIntegrationTest extends IntegrationTestSupport {

    private static final String EMAIL = "merge@ddubi.com";
    private static final String KAKAO_ID = "5066698097";

    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserAuthRepository userAuthRepository;
    @MockitoBean
    private GoogleOAuthClient googleOAuthClient;
    @MockitoBean
    private NaverOAuthClient naverOAuthClient;

    private void givenKakaoReturns(String providerUserId, String email, String nickname) {
        given(kakaoOAuthClient.fetchUserInfo(any()))
                .willReturn(new OAuthUserInfo(providerUserId, email, nickname));
    }

    private OAuthLoginRequest kakaoLogin() {
        return new OAuthLoginRequest(AuthProvider.KAKAO, "code", "http://localhost:3001/oauth/kakao", null, "dev-1");
    }

    private OAuthAccessTokenLoginRequest kakaoTokenLogin() {
        return new OAuthAccessTokenLoginRequest(AuthProvider.KAKAO, "mobile-access-token", "dev-mobile");
    }

    private void signupLocal(String email, String nickname) {
        givenEmailVerified(email);
        authService.signupLocal(new LocalSignupRequest(email, "password123", nickname, "dev-0"));
    }

    @Test
    @DisplayName("같은 이메일로 소셜 로그인하면 계정을 새로 만들지 않고 인증 수단만 추가한다")
    void mergesIntoExistingAccountWhenEmailMatches() {
        signupLocal(EMAIL, "기존닉네임");
        Long existingId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        givenKakaoReturns(KAKAO_ID, EMAIL, "카카오닉네임");

        var result = authService.loginOAuth(kakaoLogin());

        assertThat(result.created()).as("기존 계정이므로 201 이 아니라 200").isFalse();
        assertThat(result.tokens().user().id()).isEqualTo(existingId);
        assertThat(userRepository.count()).as("계정은 늘지 않는다").isEqualTo(1);
        assertThat(userAuthRepository.findProvidersByUserId(existingId))
                .containsExactlyInAnyOrder(AuthProvider.LOCAL, AuthProvider.KAKAO);
    }

    @Test
    @DisplayName("모바일 카카오 토큰 로그인도 기존 이메일 계정에 같은 통합 규칙을 적용한다")
    void mergesMobileTokenLoginIntoExistingAccount() {
        signupLocal(EMAIL, "기존닉네임");
        Long existingId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        given(kakaoOAuthClient.fetchUserInfoFromAccessToken(any()))
                .willReturn(new OAuthUserInfo(KAKAO_ID, EMAIL, "카카오닉네임"));

        var result = authService.loginOAuthToken(kakaoTokenLogin());

        assertThat(result.created()).isFalse();
        assertThat(result.tokens().user().id()).isEqualTo(existingId);
        assertThat(userAuthRepository.findProvidersByUserId(existingId))
                .containsExactlyInAnyOrder(AuthProvider.LOCAL, AuthProvider.KAKAO);
    }

    @Test
    @DisplayName("모바일 Google ID 토큰 로그인도 기존 이메일 계정에 통합된다")
    void mergesGoogleIdTokenLoginIntoExistingAccount() {
        signupLocal(EMAIL, "기존닉네임");
        Long existingId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        given(googleOAuthClient.provider()).willReturn(AuthProvider.GOOGLE);
        given(googleOAuthClient.fetchUserInfoFromIdToken(any()))
                .willReturn(new OAuthUserInfo("google-1", EMAIL, "구글닉네임"));

        var result = authService.loginOAuthIdToken(
                new OAuthIdTokenLoginRequest(AuthProvider.GOOGLE, "google-id-token", "dev-mobile"));

        assertThat(result.created()).isFalse();
        assertThat(result.tokens().user().id()).isEqualTo(existingId);
        assertThat(userAuthRepository.findProvidersByUserId(existingId))
                .containsExactlyInAnyOrder(AuthProvider.LOCAL, AuthProvider.GOOGLE);
    }

    @Test
    @DisplayName("모바일 네이버 액세스 토큰 로그인도 기존 이메일 계정에 통합된다")
    void mergesNaverAccessTokenLoginIntoExistingAccount() {
        signupLocal(EMAIL, "기존닉네임");
        Long existingId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        given(naverOAuthClient.provider()).willReturn(AuthProvider.NAVER);
        given(naverOAuthClient.fetchUserInfoFromAccessToken(any()))
                .willReturn(new OAuthUserInfo("naver-1", EMAIL, "네이버닉네임"));

        var result = authService.loginOAuthToken(
                new OAuthAccessTokenLoginRequest(AuthProvider.NAVER, "naver-access-token", "dev-mobile"));

        assertThat(result.created()).isFalse();
        assertThat(result.tokens().user().id()).isEqualTo(existingId);
        assertThat(userAuthRepository.findProvidersByUserId(existingId))
                .containsExactlyInAnyOrder(AuthProvider.LOCAL, AuthProvider.NAVER);
    }

    @Test
    @DisplayName("계정 통합 시 기존 닉네임을 소셜 프로필로 덮어쓰지 않는다")
    void keepsExistingNicknameOnMerge() {
        signupLocal(EMAIL, "기존닉네임");
        givenKakaoReturns(KAKAO_ID, EMAIL, "카카오닉네임");

        var result = authService.loginOAuth(kakaoLogin());

        assertThat(result.tokens().user().nickname()).isEqualTo("기존닉네임");
    }

    @Test
    @DisplayName("이메일이 다르면 통합하지 않고 별도 계정을 만든다")
    void createsSeparateAccountWhenEmailDiffers() {
        signupLocal(EMAIL, "로컬사용자");
        givenKakaoReturns(KAKAO_ID, "other@ddubi.com", "카카오사용자");

        var result = authService.loginOAuth(kakaoLogin());

        assertThat(result.created()).as("신규 가입이므로 201").isTrue();
        assertThat(userRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("카카오가 이메일을 주지 않으면 통합할 수 없어 별도 계정이 된다")
    void createsSeparateAccountWhenEmailIsNull() {
        signupLocal(EMAIL, "로컬사용자");
        givenKakaoReturns(KAKAO_ID, null, "카카오사용자");

        var result = authService.loginOAuth(kakaoLogin());

        assertThat(result.created()).isTrue();
        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(result.tokens().user().email()).isNull();
    }

    @Test
    @DisplayName("이미 연결된 소셜 계정으로 다시 로그인하면 그대로 로그인된다")
    void logsInWhenSocialAccountAlreadyLinked() {
        givenKakaoReturns(KAKAO_ID, EMAIL, "카카오사용자");
        Long firstId = authService.loginOAuth(kakaoLogin()).tokens().user().id();

        var second = authService.loginOAuth(kakaoLogin());

        assertThat(second.created()).isFalse();
        assertThat(second.tokens().user().id()).isEqualTo(firstId);
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(userAuthRepository.count()).as("인증 수단도 늘지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("소셜로만 가입된 이메일로 로컬 가입하면 409 와 안내 메시지를 준다")
    void rejectsLocalSignupOnSocialOnlyEmail() {
        givenKakaoReturns(KAKAO_ID, EMAIL, "카카오사용자");
        authService.loginOAuth(kakaoLogin());

        assertThatThrownBy(() -> signupLocal(EMAIL, "새사용자"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("카카오")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }

    @Test
    @DisplayName("소셜 전용 계정에 로컬 로그인을 시도하면 카카오로 로그인하라고 안내한다")
    void guidesToSocialLoginWhenNoLocalAuth() {
        givenKakaoReturns(KAKAO_ID, EMAIL, "카카오사용자");
        authService.loginOAuth(kakaoLogin());

        assertThatThrownBy(() -> authService.loginLocal(
                new com.ssafy.ddubi.domain.auth.dto.request.LocalLoginRequest(EMAIL, "password123", "dev-2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("카카오");
    }
}
