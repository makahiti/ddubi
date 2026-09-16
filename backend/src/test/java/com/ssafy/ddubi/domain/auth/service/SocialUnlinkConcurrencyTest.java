package com.ssafy.ddubi.domain.auth.service;

import com.ssafy.ddubi.domain.auth.client.GoogleOAuthClient;
import com.ssafy.ddubi.domain.auth.client.NaverOAuthClient;
import com.ssafy.ddubi.domain.auth.client.OAuthUserInfo;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthIdTokenLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.SocialLinkRequest;
import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.domain.auth.repository.UserAuthRepository;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 소셜 해제의 <b>동시 요청</b>. 리뷰에서 지적받아 추가했다(2026-09-14).
 *
 * <h3>왜 필요한가 — 실제로 뚫려 있었다</h3>
 * 처음 구현은 "연결 목록을 읽고 → 개수를 보고 → 지운다" 였다. 읽기와 쓰기 사이가 열려 있어,
 * 수단이 <b>둘뿐인</b> 사용자가 서로 다른 제공자를 동시에 해제하면 둘 다 개수 2를 보고
 * 통과한다. 결과는 로그인 수단 0개 — <b>가드가 막으려던 바로 그 상태</b>다.
 *
 * <p>지금은 {@code SELECT ... FOR UPDATE}(={@code findAllByUserIdForUpdate})로 직렬화한다.
 * 뒤에 온 요청은 앞 트랜잭션이 끝날 때까지 기다렸다가 <b>다시 읽고</b> 판단한다.
 *
 * <h3>단위 테스트로는 못 잡는다</h3>
 * 트랜잭션 경계와 행 잠금이 있어야 재현되므로 실제 MySQL 이 필요하다. 그래서 통합 테스트다.
 */
class SocialUnlinkConcurrencyTest extends IntegrationTestSupport {

    private static final String EMAIL = "concurrent@ddubi.com";

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

    private Long userId;

    /** GOOGLE + NAVER 두 개만 붙은 계정. 하나라도 빠지면 마지막이 된다. */
    @BeforeEach
    void setUp() {
        given(googleOAuthClient.provider()).willReturn(AuthProvider.GOOGLE);
        given(googleOAuthClient.fetchUserInfoFromIdToken(any()))
                .willReturn(new OAuthUserInfo("google-1", EMAIL, true, "구글사용자"));
        authService.loginOAuthIdToken(
                new OAuthIdTokenLoginRequest(AuthProvider.GOOGLE, "google-id-token", "dev-1"));
        userId = userRepository.findByEmail(EMAIL).orElseThrow().getId();

        given(naverOAuthClient.provider()).willReturn(AuthProvider.NAVER);
        given(naverOAuthClient.fetchUserInfoFromAccessToken(any()))
                .willReturn(new OAuthUserInfo("naver-1", EMAIL, true, "네이버사용자"));
        authService.linkSocialToCurrentUser(userId,
                new SocialLinkRequest(AuthProvider.NAVER, "naver-token", null));

        assertThat(userAuthRepository.findProvidersByUserId(userId))
                .containsExactlyInAnyOrder(AuthProvider.GOOGLE, AuthProvider.NAVER);
    }

    @Test
    @DisplayName("서로 다른 소셜을 동시에 해제해도 하나는 남는다 — 계정에서 잠기지 않는다")
    void concurrentUnlinkOfDifferentProvidersKeepsOne() throws Exception {
        List<Outcome> outcomes = runTogether(
                () -> authService.unlinkSocialFromCurrentUser(userId, AuthProvider.GOOGLE),
                () -> authService.unlinkSocialFromCurrentUser(userId, AuthProvider.NAVER));

        assertThat(userAuthRepository.findProvidersByUserId(userId))
                .as("둘 다 통과하면 로그인 수단이 0개가 된다 — 가드가 막아야 하는 상태")
                .hasSize(1);

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded())
                .singleElement()
                .satisfies(failed -> assertThat(failed.errorCode())
                        .as("뒤에 온 쪽은 다시 읽고 마지막임을 알아야 한다")
                        .isEqualTo(ErrorCode.LAST_LOGIN_METHOD));
    }

    @Test
    @DisplayName("같은 소셜을 동시에 두 번 해제하면 한 번만 성공한다")
    void concurrentUnlinkOfSameProviderSucceedsOnce() throws Exception {
        List<Outcome> outcomes = runTogether(
                () -> authService.unlinkSocialFromCurrentUser(userId, AuthProvider.NAVER),
                () -> authService.unlinkSocialFromCurrentUser(userId, AuthProvider.NAVER));

        assertThat(userAuthRepository.findProvidersByUserId(userId))
                .containsExactly(AuthProvider.GOOGLE);

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded())
                .singleElement()
                .satisfies(failed -> assertThat(failed.errorCode())
                        .as("사용자가 없는 게 아니라 연결이 이미 없는 것이다")
                        .isEqualTo(ErrorCode.SOCIAL_LINK_NOT_FOUND));
    }

    // ------------------------------------------------------------------
    // 실행 도구
    // ------------------------------------------------------------------

    /**
     * 두 작업을 <b>가능한 한 같은 순간에</b> 출발시킨다.
     *
     * <p>래치로 맞추지 않으면 한쪽이 먼저 끝나 버려 경합이 재현되지 않는다.
     * 그러면 락이 없어도 테스트가 통과해 버린다 — 있으나 마나 한 테스트가 된다.
     */
    private List<Outcome> runTogether(Runnable first, Runnable second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Outcome>> futures = List.of(
                    pool.submit(task(start, first)), pool.submit(task(start, second)));
            start.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Outcome> task(CountDownLatch start, Runnable work) {
        return () -> {
            start.await();
            try {
                work.run();
                return new Outcome(true, null);
            } catch (BusinessException e) {
                return new Outcome(false, e.errorCode());
            }
        };
    }

    private record Outcome(boolean succeeded, ErrorCode errorCode) {
    }
}
