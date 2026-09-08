package com.ssafy.ddubi.domain.user.service;

import com.ssafy.ddubi.domain.auth.client.OAuthUserInfo;
import com.ssafy.ddubi.domain.auth.dto.request.LocalSignupRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthLoginRequest;
import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.domain.auth.repository.UserAuthRepository;
import com.ssafy.ddubi.domain.auth.service.AuthService;
import com.ssafy.ddubi.domain.auth.service.RefreshTokenStore;
import com.ssafy.ddubi.domain.user.dto.request.DeviceTokenRegisterRequest;
import com.ssafy.ddubi.domain.user.dto.request.UserWithdrawRequest;
import com.ssafy.ddubi.domain.user.entity.DevicePlatform;
import com.ssafy.ddubi.domain.user.repository.DeviceTokenRepository;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.security.RevokedAccessTokenStore;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 도메인 규칙 4 — 탈퇴는 하드 삭제.
 *
 * <blockquote>
 * DELETE user 1건 → FK CASCADE 로 전체 삭제. 보관·마스킹 없음. Redis 세션도 전부 폐기.
 * </blockquote>
 *
 * CASCADE 는 애플리케이션이 아니라 DB 가 수행한다. 그래서 이 테스트는 롤백이 아니라
 * 실제 커밋 후 남은 행을 센다.
 */
class WithdrawIntegrationTest extends IntegrationTestSupport {

    private static final String EMAIL = "withdraw@ddubi.com";
    private static final String PASSWORD = "password123";

    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private DeviceTokenService deviceTokenService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserAuthRepository userAuthRepository;
    @Autowired
    private DeviceTokenRepository deviceTokenRepository;
    @Autowired
    private RefreshTokenStore refreshTokenStore;
    @Autowired
    private RevokedAccessTokenStore revokedStore;

    /** LOCAL + KAKAO 인증 수단과 기기 토큰까지 붙은 계정을 만든다. */
    private Long givenFullyPopulatedUser() {
        givenEmailVerified(EMAIL);
        authService.signupLocal(new LocalSignupRequest(EMAIL, PASSWORD, "탈퇴대상", "dev-1"));
        given(kakaoOAuthClient.fetchUserInfo(any()))
                .willReturn(new OAuthUserInfo("kakao-1", EMAIL, "카카오"));
        authService.loginOAuth(new OAuthLoginRequest(
                AuthProvider.KAKAO, "code", "http://localhost:3001/oauth/kakao", null, "dev-2"));

        Long userId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        deviceTokenService.register(userId,
                new DeviceTokenRegisterRequest("fcm-token-1", DevicePlatform.ANDROID));
        return userId;
    }

    private UserWithdrawRequest validRequest() {
        return new UserWithdrawRequest(PASSWORD, "WITHDRAW", "테스트");
    }

    @Test
    @DisplayName("탈퇴하면 user 행 하나를 지우는 것으로 하위 데이터가 전부 사라진다")
    void cascadeDeletesAllOwnedRows() {
        Long userId = givenFullyPopulatedUser();
        assertThat(userAuthRepository.count()).isEqualTo(2);
        assertThat(deviceTokenRepository.count()).isEqualTo(1);

        userService.withdraw(userId, validRequest());

        assertThat(userRepository.count()).isZero();
        assertThat(userAuthRepository.count()).as("LOCAL·KAKAO 인증 수단이 CASCADE 로 삭제").isZero();
        assertThat(deviceTokenRepository.count()).as("기기 토큰도 CASCADE 로 삭제").isZero();
    }

    @Test
    @DisplayName("탈퇴하면 모든 기기의 리프레시 세션이 폐기된다")
    void revokesEveryRefreshSession() {
        Long userId = givenFullyPopulatedUser();
        assertThat(refreshTokenStore.exists(userId, "dev-1")).isTrue();
        assertThat(refreshTokenStore.exists(userId, "dev-2")).isTrue();

        userService.withdraw(userId, validRequest());

        assertThat(refreshTokenStore.exists(userId, "dev-1")).isFalse();
        assertThat(refreshTokenStore.exists(userId, "dev-2")).isFalse();
    }

    @Test
    @DisplayName("탈퇴하면 이미 발급된 액세스 토큰도 즉시 무효화된다")
    void revokesLiveAccessTokens() {
        Long userId = givenFullyPopulatedUser();
        assertThat(revokedStore.currentVersion(userId)).isZero();

        userService.withdraw(userId, validRequest());

        assertThat(revokedStore.currentVersion(userId)).isPositive();
    }

    @Test
    @DisplayName("탈퇴한 이메일로 다시 가입할 수 있다 — 행이 남지 않으므로 UNIQUE 충돌이 없다")
    void allowsResignupWithSameEmail() {
        Long userId = givenFullyPopulatedUser();
        userService.withdraw(userId, validRequest());

        givenEmailVerified(EMAIL);
        authService.signupLocal(new LocalSignupRequest(EMAIL, PASSWORD, "재가입", "dev-3"));

        assertThat(userRepository.findByEmail(EMAIL)).isPresent();
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("confirmation 이 WITHDRAW 가 아니면 거부한다")
    void requiresConfirmationKeyword() {
        Long userId = givenFullyPopulatedUser();

        assertThatThrownBy(() -> userService.withdraw(userId,
                new UserWithdrawRequest(PASSWORD, "YES", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        assertThat(userRepository.count()).as("거부됐으므로 그대로 남아 있다").isEqualTo(1);
    }

    @Test
    @DisplayName("LOCAL 회원은 비밀번호가 틀리면 탈퇴되지 않는다")
    void requiresCorrectPasswordForLocalUser() {
        Long userId = givenFullyPopulatedUser();

        assertThatThrownBy(() -> userService.withdraw(userId,
                new UserWithdrawRequest("wrong-password", "WITHDRAW", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("소셜 전용 계정은 비밀번호 없이 탈퇴할 수 있다")
    void allowsWithdrawWithoutPasswordForSocialOnlyUser() {
        given(kakaoOAuthClient.fetchUserInfo(any()))
                .willReturn(new OAuthUserInfo("kakao-2", "social@ddubi.com", "소셜전용"));
        Long userId = authService.loginOAuth(new OAuthLoginRequest(
                AuthProvider.KAKAO, "code", "http://localhost:3001/oauth/kakao", null, "dev-1"))
                .tokens().user().id();

        userService.withdraw(userId, new UserWithdrawRequest(null, "WITHDRAW", null));

        assertThat(userRepository.count()).isZero();
    }
}
