package com.ssafy.ddubi.domain.auth.service;

import com.ssafy.ddubi.domain.auth.dto.request.LocalLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.LocalSignupRequest;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.security.JwtTokenProvider;
import com.ssafy.ddubi.global.security.RevokedAccessTokenStore;
import com.ssafy.ddubi.global.security.TokenType;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class AuthTokenSecurityIntegrationTest extends IntegrationTestSupport {

    private static final String EMAIL = "token-security@ddubi.com";
    private static final String PASSWORD = "password123";

    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtTokenProvider tokenProvider;
    @Autowired
    private RevokedAccessTokenStore revokedStore;
    @Autowired
    private RefreshTokenStore refreshTokenStore;

    @Test
    @DisplayName("재사용 감지 뒤 재로그인해도 과거 Access 토큰은 차단되고 새 토큰만 허용된다")
    void reloginDoesNotRevivePreviouslyIssuedAccessToken() {
        givenEmailVerified(EMAIL);
        var signup = authService.signupLocal(
                new LocalSignupRequest(EMAIL, PASSWORD, "토큰테스트", "device-A"));
        Long userId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        var oldPayload = tokenProvider.parse(signup.accessToken(), TokenType.ACCESS).orElseThrow();

        revokedStore.revoke(userId);

        var relogin = authService.loginLocal(
                new LocalLoginRequest(EMAIL, PASSWORD, "device-A"));
        var newPayload = tokenProvider.parse(relogin.accessToken(), TokenType.ACCESS).orElseThrow();

        assertThat(revokedStore.isRevoked(userId, oldPayload.accessVersion())).isTrue();
        assertThat(revokedStore.isRevoked(userId, newPayload.accessVersion())).isFalse();
        assertThat(newPayload.accessVersion()).isGreaterThan(oldPayload.accessVersion());
    }

    @Test
    @DisplayName("같은 Refresh jti 를 동시에 회전하면 정확히 한 요청만 성공한다")
    void refreshRotationIsAtomic() throws Exception {
        Long userId = 42L;
        String deviceId = "device-A";
        String oldTokenId = "old-jti";
        Duration ttl = Duration.ofMinutes(10);
        refreshTokenStore.save(userId, deviceId, oldTokenId, ttl);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> first = rotateAfterSignal(
                    ready, start, userId, deviceId, oldTokenId, "new-jti-A", ttl);
            Callable<Boolean> second = rotateAfterSignal(
                    ready, start, userId, deviceId, oldTokenId, "new-jti-B", ttl);
            List<Future<Boolean>> futures = List.of(
                    executor.submit(first), executor.submit(second));

            ready.await();
            start.countDown();

            boolean firstResult = futures.get(0).get();
            boolean secondResult = futures.get(1).get();
            assertThat(List.of(firstResult, secondResult)).containsExactlyInAnyOrder(true, false);

            String winningTokenId = firstResult ? "new-jti-A" : "new-jti-B";
            assertThat(refreshTokenStore.rotate(
                    userId, deviceId, winningTokenId, "next-jti", ttl)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<Boolean> rotateAfterSignal(
            CountDownLatch ready, CountDownLatch start,
            Long userId, String deviceId, String oldTokenId, String newTokenId, Duration ttl) {
        return () -> {
            ready.countDown();
            start.await();
            return refreshTokenStore.rotate(userId, deviceId, oldTokenId, newTokenId, ttl);
        };
    }
}
