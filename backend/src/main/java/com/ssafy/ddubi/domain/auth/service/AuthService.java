package com.ssafy.ddubi.domain.auth.service;

import com.ssafy.ddubi.domain.auth.client.OAuthClient;
import com.ssafy.ddubi.domain.auth.client.OAuthUserInfo;
import com.ssafy.ddubi.domain.auth.dto.request.LocalLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.LocalSignupRequest;
import com.ssafy.ddubi.domain.auth.dto.request.LogoutRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthAccessTokenLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthIdTokenLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.TokenRefreshRequest;
import com.ssafy.ddubi.domain.auth.dto.response.AuthTokenResponse;
import com.ssafy.ddubi.domain.auth.dto.response.AuthUserResponse;
import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.domain.auth.entity.UserAuth;
import com.ssafy.ddubi.domain.auth.repository.UserAuthRepository;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.DeviceTokenRepository;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.security.JwtTokenProvider;
import com.ssafy.ddubi.global.security.RevokedAccessTokenStore;
import com.ssafy.ddubi.global.security.TokenPayload;
import com.ssafy.ddubi.global.security.TokenType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * 인증 흐름 전부. AUTH-001~005.
 *
 * 이 클래스가 지키는 도메인 규칙:
 *  - 규칙 3 계정 통합: 이메일이 계정 키. 같은 이메일로 소셜 로그인하면 user 를 새로 만들지 않고
 *    user_auth 에 provider 행만 추가한다. 카카오가 이메일을 안 주면 통합할 수 없어 별도 계정이다.
 *  - 리프레시 회전: 재발급마다 jti 를 교체하고, 옛 jti 가 다시 오면 탈취로 보고 전 기기를 폐기한다.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 소셜이 닉네임도 안 줄 때 쓰는 기본값. 사용자가 나중에 바꾸면 된다. */
    private static final String FALLBACK_NICKNAME_PREFIX = "뚜비";

    private final UserRepository userRepository;
    private final UserAuthRepository userAuthRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final RevokedAccessTokenStore revokedStore;
    private final EmailVerificationService emailVerificationService;
    /**
     * 등록된 소셜 클라이언트. 생성 시점에 provider 별 맵으로 굳히지 않고 호출 때 찾는다 —
     * 프록시·목으로 대체된 빈은 생성 시점에 provider() 가 아직 제 값을 주지 못한다.
     * 구현체가 몇 개뿐이라 순회 비용은 무시할 수 있다.
     */
    private final List<OAuthClient> oauthClients;

    public AuthService(UserRepository userRepository,
                       UserAuthRepository userAuthRepository,
                       DeviceTokenRepository deviceTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider,
                       RefreshTokenStore refreshTokenStore,
                       RevokedAccessTokenStore revokedStore,
                       EmailVerificationService emailVerificationService,
                       List<OAuthClient> clients) {
        this.userRepository = userRepository;
        this.userAuthRepository = userAuthRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.revokedStore = revokedStore;
        this.emailVerificationService = emailVerificationService;
        this.oauthClients = List.copyOf(clients);
    }

    private OAuthClient clientFor(AuthProvider provider) {
        return oauthClients.stream()
                .filter(c -> c.provider() == provider)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "지원하지 않는 소셜 로그인입니다."));
    }

    // ------------------------------------------------------------------
    // AUTH-001 로컬 회원가입
    // ------------------------------------------------------------------

    @Transactional
    public AuthTokenResponse signupLocal(LocalSignupRequest request) {
        // 규칙 3 의 전제: 이메일이 계정 통합의 유일한 키다. 소유가 확인되지 않은 이메일로
        // 계정을 만들게 두면, 남의 이메일을 선점해 두었다가 그 사람이 소셜 로그인하는
        // 순간 자동 통합으로 계정을 가져갈 수 있다.
        emailVerificationService.requireVerified(request.email());

        userRepository.findByEmail(request.email()).ifPresent(existing -> {
            // 규칙 3: 이미 소셜로 쓰이는 이메일이면 어떤 수단으로 가입했는지 알려준다.
            boolean hasLocal = userAuthRepository.existsByUserIdAndProvider(existing.getId(), AuthProvider.LOCAL);
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, hasLocal
                    ? "이미 가입된 이메일입니다."
                    : "이미 소셜 로그인으로 가입된 이메일입니다. " + socialGuide(existing.getId()));
        });

        User user = userRepository.save(User.builder()
                .email(request.email())
                .nickname(request.nickname())
                .build());
        userAuthRepository.save(UserAuth.ofLocal(user, passwordEncoder.encode(request.password())));
        // 가입이 성립한 뒤에 통행증을 쓴다 — 한 번 인증으로 계정을 여러 개 만들지 못하게 한다.
        emailVerificationService.consume(request.email());

        return issueTokens(user, request.deviceId(), AuthProvider.LOCAL);
    }

    // ------------------------------------------------------------------
    // AUTH-002 로컬 로그인
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AuthTokenResponse loginLocal(LocalLoginRequest request) {
        UserAuth auth = userAuthRepository.findLocalAuthByEmail(request.email())
                .orElseThrow(() -> localLoginFailure(request.email()));

        if (!passwordEncoder.matches(request.password(), auth.getPasswordHash())) {
            // 존재하지 않는 계정과 같은 응답을 준다 — 어느 이메일이 가입돼 있는지 흘리지 않는다.
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다.");
        }
        return issueTokens(auth.getUser(), request.deviceId(), AuthProvider.LOCAL);
    }

    /**
     * 소셜로만 가입한 계정은 예외적으로 안내한다 — 비밀번호가 아예 없어서
     * "비밀번호가 틀렸다"고만 하면 사용자가 영원히 로그인하지 못한다(규칙 3).
     */
    private BusinessException localLoginFailure(String email) {
        Optional<User> found = userRepository.findByEmail(email);
        boolean socialOnly = found
                .map(u -> !userAuthRepository.existsByUserIdAndProvider(u.getId(), AuthProvider.LOCAL))
                .orElse(false);
        return new BusinessException(ErrorCode.UNAUTHORIZED, socialOnly
                ? "소셜 로그인으로 가입된 계정입니다. " + socialGuide(found.get().getId())
                : "이메일 또는 비밀번호가 일치하지 않습니다.");
    }

    /**
     * 그 계정에 실제로 붙어 있는 소셜 수단을 이름으로 안내한다.
     *
     * 제공자를 고정 문구로 박으면 구글로 가입한 사용자에게 카카오를 안내하게 된다.
     * 안내가 틀리면 사용자는 맞는 버튼을 못 찾고 계정에 영영 못 들어간다.
     */
    private String socialGuide(Long userId) {
        String names = userAuthRepository.findAllByUserId(userId).stream()
                .map(UserAuth::getProvider)
                .filter(p -> !p.isLocal())
                .map(AuthProvider::displayName)
                .collect(Collectors.joining("·"));
        return StringUtils.hasText(names)
                ? names + "로 로그인해 주세요."
                : "소셜 로그인을 이용해 주세요.";
    }

    // ------------------------------------------------------------------
    // AUTH-003 소셜 로그인 (계정 통합 분기)
    // ------------------------------------------------------------------

    /** @return 신규 가입이면 created=true → 컨트롤러가 201, 기존 계정이면 200 */
    @Transactional
    public OAuthResult loginOAuth(OAuthLoginRequest request) {
        OAuthClient client = clientFor(request.provider());
        OAuthUserInfo info = client.fetchUserInfo(request.toExchange());
        return loginOAuthUser(request.provider(), request.deviceId(), info);
    }

    /** 모바일 SDK 토큰 로그인. 계정 통합 규칙은 인가 코드 로그인과 동일하게 적용한다. */
    @Transactional
    public OAuthResult loginOAuthToken(OAuthAccessTokenLoginRequest request) {
        OAuthClient client = clientFor(request.provider());
        OAuthUserInfo info = client.fetchUserInfoFromAccessToken(request.accessToken());
        return loginOAuthUser(request.provider(), request.deviceId(), info);
    }

    /** 모바일 OIDC SDK의 ID 토큰 로그인. */
    @Transactional
    public OAuthResult loginOAuthIdToken(OAuthIdTokenLoginRequest request) {
        OAuthClient client = clientFor(request.provider());
        OAuthUserInfo info = client.fetchUserInfoFromIdToken(request.idToken());
        return loginOAuthUser(request.provider(), request.deviceId(), info);
    }

    private OAuthResult loginOAuthUser(AuthProvider provider, String deviceId, OAuthUserInfo info) {

        // ① 이미 연결된 소셜 계정 — 그냥 로그인
        Optional<UserAuth> linked =
                userAuthRepository.findByProviderAndProviderUserId(provider, info.providerUserId());
        if (linked.isPresent()) {
            return new OAuthResult(issueTokens(linked.get().getUser(), deviceId, provider), false);
        }

        // ② 같은 이메일의 기존 계정 — user 를 새로 만들지 않고 인증 수단만 추가(계정 통합)
        if (info.hasEmail()) {
            Optional<User> existing = userRepository.findByEmail(info.email());
            if (existing.isPresent()) {
                User user = existing.get();
                userAuthRepository.save(
                        UserAuth.ofSocial(user, provider, info.providerUserId()));
                log.info("계정 통합 — userId={} 에 {} 인증 수단 추가", user.getId(), provider);
                return new OAuthResult(issueTokens(user, deviceId, provider), false);
            }
        } else {
            // 이메일이 없으면 통합 판단 자체가 불가능하다. 별도 계정이 생기는 것은 정상 동작이다.
            log.info("{} 로그인에 이메일이 없어 계정 통합을 건너뜀 — 별도 계정 생성", provider);
        }

        // ③ 완전 신규
        User user = userRepository.save(User.builder()
                .email(info.email())
                .nickname(resolveNickname(info))
                .build());
        userAuthRepository.save(UserAuth.ofSocial(user, provider, info.providerUserId()));
        return new OAuthResult(issueTokens(user, deviceId, provider), true);
    }

    private String resolveNickname(OAuthUserInfo info) {
        if (StringUtils.hasText(info.nickname())) {
            return info.nickname();
        }
        // 닉네임은 NOT NULL 이라 비워둘 수 없다. 충돌하지 않을 임시 이름을 만든다.
        return FALLBACK_NICKNAME_PREFIX + info.providerUserId();
    }

    public record OAuthResult(AuthTokenResponse tokens, boolean created) {
    }

    // ------------------------------------------------------------------
    // AUTH-004 토큰 재발급 (회전 + 재사용 감지)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AuthTokenResponse refresh(TokenRefreshRequest request) {
        TokenPayload payload = tokenProvider.parse(request.refreshToken(), TokenType.REFRESH)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED,
                        "리프레시 토큰이 유효하지 않거나 만료되었습니다."));

        // 토큰에 박힌 기기와 요청한 기기가 달라선 안 된다.
        if (!request.deviceId().equals(payload.deviceId())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "기기 정보가 일치하지 않습니다.");
        }

        User user = userRepository.findById(payload.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        var nextRefresh = tokenProvider.createRefreshToken(payload.userId(), payload.deviceId());
        boolean rotated = refreshTokenStore.rotate(
                payload.userId(), payload.deviceId(), payload.tokenId(), nextRefresh.tokenId(),
                tokenProvider.refreshTokenValidity());
        if (!rotated) {
            // 이미 회전된 옛 토큰이 다시 왔다 = 토큰이 두 곳에서 쓰이고 있다.
            // 공격자와 정상 사용자를 함께 로그아웃시키는 편이 탈취 상태를 방치하는 것보다 낫다.
            long revoked = refreshTokenStore.revokeAll(payload.userId());
            // 세션만 지우면 이미 발급된 액세스 토큰이 60분간 살아 있다. 함께 차단한다.
            revokedStore.revoke(payload.userId());
            log.warn("리프레시 재사용 감지 — userId={}, 세션 {}건 폐기", payload.userId(), revoked);
            throw new BusinessException(ErrorCode.UNAUTHORIZED,
                    "보안을 위해 모든 기기에서 로그아웃되었습니다. 다시 로그인해 주세요.");
        }

        return issueTokensOnly(payload.userId(), nextRefresh.token());
    }

    // ------------------------------------------------------------------
    // AUTH-005 로그아웃
    // ------------------------------------------------------------------

    @Transactional
    public void logout(Long userId, LogoutRequest request) {
        // 만료된 리프레시로도 로그아웃은 되어야 한다. 토큰 검증 실패를 막지 않고,
        // 인증된 사용자(userId)와 요청한 deviceId 기준으로 세션만 지운다.
        refreshTokenStore.revoke(userId, request.deviceId());

        if (StringUtils.hasText(request.fcmToken())) {
            // 로그아웃한 기기로 푸시가 가지 않게 한다. 남의 토큰을 무효화하지 못하도록 소유자를 확인한다.
            deviceTokenRepository.findByFcmToken(request.fcmToken())
                    .filter(token -> token.getUser().getId().equals(userId))
                    .ifPresent(token -> token.invalidate());
        }
    }

    // ------------------------------------------------------------------
    // 공통
    // ------------------------------------------------------------------

    /** 가입·로그인 — 토큰 + deviceId + user(이번에 쓴 provider 포함). */
    private AuthTokenResponse issueTokens(User user, String requestedDeviceId, AuthProvider usedProvider) {
        String deviceId = resolveDeviceId(requestedDeviceId);
        var issued = issue(user.getId(), deviceId);

        return AuthTokenResponse.withUser(
                issued.accessToken(),
                issued.refreshToken(),
                tokenProvider.accessTokenValidity().toSeconds(),
                tokenProvider.refreshTokenValidity().toSeconds(),
                deviceId,
                AuthUserResponse.of(user, usedProvider));
    }

    /** 재발급 — 명세서 AUTH-004 에는 user·deviceId 가 없다. */
    private AuthTokenResponse issueTokensOnly(Long userId, String rotatedRefreshToken) {
        String accessToken = tokenProvider.createAccessToken(
                userId, revokedStore.versionForIssue(userId));
        return AuthTokenResponse.tokensOnly(
                accessToken,
                rotatedRefreshToken,
                tokenProvider.accessTokenValidity().toSeconds(),
                tokenProvider.refreshTokenValidity().toSeconds());
    }

    /**
     * 클라이언트가 기기 식별자를 안 주면 서버가 발급한다.
     * 이후 재발급·로그아웃에서 같은 값을 써야 세션이 맞으므로 응답에 돌려준다.
     */
    private String resolveDeviceId(String requestedDeviceId) {
        return StringUtils.hasText(requestedDeviceId)
                ? requestedDeviceId
                : UUID.randomUUID().toString();
    }

    private IssuedPair issue(Long userId, String deviceId) {
        long accessVersion = revokedStore.versionForIssue(userId);
        String accessToken = tokenProvider.createAccessToken(userId, accessVersion);
        var refresh = tokenProvider.createRefreshToken(userId, deviceId);
        // 저장은 발급 직후 — 이 시점 이후 이전 토큰은 재사용으로 판정된다(회전).
        refreshTokenStore.save(userId, deviceId, refresh.tokenId(),
                tokenProvider.refreshTokenValidity());
        return new IssuedPair(accessToken, refresh.token());
    }

    private record IssuedPair(String accessToken, String refreshToken) {
    }
}
