package com.ssafy.ddubi.domain.auth.service;

import com.ssafy.ddubi.domain.auth.client.OAuthClient;
import com.ssafy.ddubi.domain.auth.client.OAuthUserInfo;
import com.ssafy.ddubi.domain.auth.dto.request.EmailChangeRequest;
import com.ssafy.ddubi.domain.auth.dto.request.LocalLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.LocalSignupRequest;
import com.ssafy.ddubi.domain.auth.dto.request.LogoutRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthAccessTokenLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthIdTokenLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthLinkRequest;
import com.ssafy.ddubi.domain.auth.dto.request.PasswordChangeRequest;
import com.ssafy.ddubi.domain.auth.dto.request.SocialLinkRequest;
import com.ssafy.ddubi.domain.auth.dto.response.EmailChangeResponse;
import com.ssafy.ddubi.domain.auth.dto.response.SocialLinkResponse;
import com.ssafy.ddubi.domain.auth.dto.request.TokenRefreshRequest;
import com.ssafy.ddubi.domain.auth.dto.response.AuthTokenResponse;
import com.ssafy.ddubi.domain.auth.dto.response.OAuthLoginResponse;
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
 *  - 규칙 3 계정 연동: 이메일이 계정 키다. 같은 <b>검증된</b> 이메일로 소셜 로그인하면 계정을
 *    새로 만들지 않지만, <b>자동으로 붙이지도 않는다</b>(2026-09-10 변경). 기존 계정의 비밀번호로
 *    소유를 증명해야 user_auth 에 provider 행이 추가된다. 이메일이 없거나 미검증이면 별도 계정이다.
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
    private final AccountLinkStore accountLinkStore;

    public AuthService(UserRepository userRepository,
                       UserAuthRepository userAuthRepository,
                       DeviceTokenRepository deviceTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider,
                       RefreshTokenStore refreshTokenStore,
                       RevokedAccessTokenStore revokedStore,
                       EmailVerificationService emailVerificationService,
                       List<OAuthClient> clients,
                       AccountLinkStore accountLinkStore) {
        this.userRepository = userRepository;
        this.userAuthRepository = userAuthRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.revokedStore = revokedStore;
        this.emailVerificationService = emailVerificationService;
        this.oauthClients = List.copyOf(clients);
        this.accountLinkStore = accountLinkStore;
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
        // 규칙 3 의 전제: 이메일이 계정의 유일한 키다(UK_USER_EMAIL). 소유가 확인되지 않은
        // 이메일로 계정을 만들게 두면 남의 이메일을 선점할 수 있고, 그러면 진짜 주인은
        // 그 이메일로 영영 가입하지 못한다.
        //
        // 예전에는 여기에 "선점해 두면 자동 통합으로 계정을 가져간다" 는 위험도 있었다.
        // 자동 통합을 없앴으므로(2026-09-10) 그 경로는 사라졌지만, 선점 자체를 막을 이유는 그대로다.
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

    /**
     * <h3>자동 통합을 하지 않는다 (2026-09-10 변경)</h3>
     * 예전에는 소셜 이메일이 기존 계정과 같으면 <b>묻지 않고</b> 그 계정에 붙였다. 그러면
     * 제공자가 이메일 소유를 잘못 확인한 순간, 남의 계정으로 그대로 들어간다. 이제는
     * <b>기존 계정의 비밀번호로 소유를 증명</b>해야 붙는다.
     */
    private OAuthResult loginOAuthUser(AuthProvider provider, String deviceId, OAuthUserInfo info) {

        // ① 이미 연결된 소셜 계정 — 그냥 로그인
        Optional<UserAuth> linked =
                userAuthRepository.findByProviderAndProviderUserId(provider, info.providerUserId());
        if (linked.isPresent()) {
            return OAuthResult.authenticated(
                    issueTokens(linked.get().getUser(), deviceId, provider), false);
        }

        // ② 같은 이메일의 기존 계정 — 바로 붙이지 않고 연동 티켓을 낸다
        if (info.hasVerifiedEmail()) {
            Optional<User> existing = userRepository.findByEmail(info.email());
            if (existing.isPresent()) {
                return requireLink(existing.get(), provider, info);
            }
        } else if (info.hasEmail()) {
            // 값은 왔지만 제공자가 소유를 확인해 주지 않았다. 이 이메일로는 아무 판단도 하지 않고,
            // 새 계정에 저장하지도 않는다 — 저장하면 UK_USER_EMAIL 을 차지해 진짜 주인이 막힌다.
            log.info("{} 이메일 미검증 — 연동 판단과 저장에서 모두 제외한다", provider);
        } else {
            log.info("{} 로그인에 이메일이 없어 연동 판단을 건너뜀 — 별도 계정 생성", provider);
        }

        // ③ 완전 신규
        User user = userRepository.save(User.builder()
                .email(info.hasVerifiedEmail() ? info.email() : null)
                .nickname(resolveNickname(info))
                .build());
        userAuthRepository.save(UserAuth.ofSocial(user, provider, info.providerUserId()));
        return OAuthResult.authenticated(issueTokens(user, deviceId, provider), true);
    }

    /**
     * 기존 계정을 찾았을 때. 비밀번호를 확인할 수 있으면 티켓을 내고, 없으면 안내한다.
     *
     * <h3>LOCAL 인증 수단이 없으면 왜 새 계정을 만들지 않나</h3>
     * {@code UK_USER_EMAIL} 때문에 같은 이메일로 두 번째 사용자를 만들 수 없다. 이메일을
     * 비워 만들 수는 있지만, 그러면 사용자는 <b>자기 일정이 사라진 빈 계정</b>을 보게 된다.
     * 그래서 이미 쓰던 소셜로 들어가라고 알려 준다.
     */
    private OAuthResult requireLink(User user, AuthProvider provider, OAuthUserInfo info) {
        boolean hasPassword = userAuthRepository.findLocalAuthByEmail(user.getEmail()).isPresent();
        if (!hasPassword) {
            List<AuthProvider> usable = userAuthRepository.findAllByUserId(user.getId()).stream()
                    .map(UserAuth::getProvider)
                    .toList();
            log.info("연동 불가(비밀번호 없음) — userId={} 사용 가능 수단={}", user.getId(), usable);
            throw new BusinessException(ErrorCode.ACCOUNT_LINK_UNAVAILABLE, String.format(
                    "이 이메일은 %s 로 가입되어 있습니다. 해당 방식으로 로그인한 뒤 "
                            + "마이페이지에서 %s 을(를) 연결해 주세요.", describe(usable), provider));
        }

        String linkToken = accountLinkStore.issue(user.getId(), provider, info.providerUserId());
        log.info("계정 연동 필요 — userId={} provider={}", user.getId(), provider);
        return OAuthResult.linkRequired(linkToken, provider, EmailMask.mask(user.getEmail()));
    }

    private static String describe(List<AuthProvider> providers) {
        return providers.isEmpty() ? "다른 방식" : providers.stream()
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    // ------------------------------------------------------------------
    // AUTH-003d 마이페이지 소셜 해제
    // ------------------------------------------------------------------

    /**
     * 연결된 소셜 인증 수단을 뗀다.
     *
     * <h3>마지막 하나는 뗄 수 없다</h3>
     * 떼는 순간 <b>사용자가 자기 계정에서 잠긴다.</b> 그리고 되돌릴 방법이 없다 — 들어갈 수가
     * 없으니 다시 연결할 화면에도 못 간다. 그래서 남은 수단이 하나뿐이면 거절한다.
     * 계정을 없애려는 것이면 탈퇴({@code DELETE /users/me})가 맞는 길이다.
     *
     * <h3>LOCAL 은 여기서 못 뗀다</h3>
     * 이 API 는 <b>소셜</b> 연결 관리용이다. 비밀번호 로그인을 없애는 것은 성격이 다른 일이라
     * (다시 만들려면 이메일 인증부터 해야 한다) 같은 문으로 처리하지 않는다.
     *
     * <h3>세션은 건드리지 않는다</h3>
     * 토큰은 사용자에게 발급된 것이지 제공자에게 발급된 것이 아니다. 카카오 연결을 뗐다고
     * 지금 쓰고 있는 앱에서 튕겨 나갈 이유가 없다.
     *
     * @return 해제 후 남은 로그인 수단
     */
    @Transactional
    public SocialLinkResponse unlinkSocialFromCurrentUser(Long userId, AuthProvider provider) {
        if (provider == AuthProvider.LOCAL) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "비밀번호 로그인은 이 API 로 해제할 수 없습니다.");
        }

        List<UserAuth> current = userAuthRepository.findAllByUserIdForUpdate(userId);
        UserAuth target = current.stream()
                .filter(userAuth -> userAuth.getProvider() == provider)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SOCIAL_LINK_NOT_FOUND,
                        provider + " 계정이 연결되어 있지 않습니다."));

        if (current.size() <= 1) {
            throw new BusinessException(ErrorCode.LAST_LOGIN_METHOD);
        }

        userAuthRepository.delete(target);
        log.info("소셜 연결 해제 — userId={} provider={}", userId, provider);

        return SocialLinkResponse.of(userAuthRepository.findProvidersByUserId(userId));
    }

    // ------------------------------------------------------------------
    // AUTH-003c 마이페이지 소셜 연결
    // ------------------------------------------------------------------

    /**
     * 로그인한 사용자가 소셜 계정을 <b>추가로</b> 연결한다.
     *
     * <h3>비밀번호를 묻지 않는다</h3>
     * 로그인 전 연동은 "이 계정이 정말 당신 것인가" 를 물어야 해서 비밀번호가 필요했다.
     * 여기서는 이미 그 계정으로 인증된 상태다. 다시 물으면 <b>소셜로만 가입한 사용자</b>는
     * 비밀번호 자체가 없어 영영 연결하지 못하는데, 그 사람들이 바로 이 화면으로 안내받는
     * 대상이다({@link ErrorCode#ACCOUNT_LINK_UNAVAILABLE}).
     *
     * @return 연결 후 이 계정의 전체 로그인 수단
     */
    @Transactional
    public SocialLinkResponse linkSocialToCurrentUser(Long userId, SocialLinkRequest request) {
        if (request.hasNoCredential()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "accessToken · idToken · authorizationCode 중 하나는 필요합니다.");
        }
        if (request.usesAuthorizationCode() && !StringUtils.hasText(request.redirectUri())) {
            // 제공자가 "코드 발급에 쓴 redirect_uri 와 같은가" 를 대조한다. 서버가 임의로 정할 수 없다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "authorizationCode 를 보낼 때는 redirectUri 도 함께 보내야 합니다.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        OAuthClient client = clientFor(request.provider());
        // 네이버는 앱이 웹 창으로 로그인해 인가 코드만 받는다. 코드를 토큰으로 바꾸는 데
        // 클라이언트 비밀키가 필요하므로 서버가 교환한다 — 로그인(AUTH-003)과 같은 경로다.
        OAuthUserInfo info = request.usesAuthorizationCode()
                ? client.fetchUserInfo(request.toExchange())
                : request.usesIdToken()
                ? client.fetchUserInfoFromIdToken(request.idToken())
                : client.fetchUserInfoFromAccessToken(request.accessToken());

        Optional<UserAuth> linked = userAuthRepository
                .findByProviderAndProviderUserId(request.provider(), info.providerUserId());

        if (linked.isPresent()) {
            if (!linked.get().getUser().getId().equals(userId)) {
                throw new BusinessException(ErrorCode.SOCIAL_ALREADY_LINKED);
            }
            // 같은 소셜을 또 눌렀다. 이미 원하는 상태이므로 오류로 만들지 않는다.
            log.debug("이미 연결된 소셜 — userId={} provider={}", userId, request.provider());
        } else {
            // UK_UAUTH_USER_PROVIDER (user_id, provider) — 한 사용자에게 제공자당 한 행뿐이다.
            // 다른 카카오 계정이 이미 붙어 있으면 조용히 갈아치우지 않는다. 갈아치우면
            // 원래 그 계정으로 로그인하던 사람이 어느 날 못 들어온다.
            if (userAuthRepository.existsByUserIdAndProvider(userId, request.provider())) {
                throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_ALREADY_BOUND, String.format(
                        "이미 다른 %s 계정이 연결되어 있습니다. 먼저 해제한 뒤 연결해 주세요.",
                        request.provider()));
            }
            userAuthRepository.save(
                    UserAuth.ofSocial(user, request.provider(), info.providerUserId()));
            log.info("마이페이지 소셜 연결 — userId={} provider={}", userId, request.provider());
        }

        return SocialLinkResponse.of(userAuthRepository.findProvidersByUserId(userId));
    }

    // ------------------------------------------------------------------
    // AUTH-003b 계정 연동 확정
    // ------------------------------------------------------------------

    /**
     * 기존 계정의 비밀번호를 확인하고 소셜 인증 수단을 추가한다.
     *
     * <p>티켓은 <b>성공·실패와 무관하게</b> 소비된다({@link AccountLinkStore#consume}).
     * 비밀번호가 틀렸다고 티켓을 살려 두면 이 화면이 비밀번호 대입 창구가 된다.
     */
    @Transactional
    public AuthTokenResponse linkOAuthAccount(OAuthLinkRequest request) {
        AccountLinkStore.Ticket ticket = accountLinkStore.consume(request.linkToken())
                .orElseThrow(() -> new BusinessException(ErrorCode.LINK_TOKEN_INVALID));

        User user = userRepository.findById(ticket.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.LINK_TOKEN_INVALID));

        UserAuth local = userAuthRepository.findLocalAuthByEmail(user.getEmail())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_LINK_UNAVAILABLE));

        if (!passwordEncoder.matches(request.password(), local.getPasswordHash())) {
            // 티켓은 위에서 이미 소비됐다. 프론트가 입력창만 비우고 재시도시키면 안 되므로
            // 일반 로그인 실패와 다른 코드로 준다.
            throw new BusinessException(ErrorCode.LINK_PASSWORD_MISMATCH);
        }

        // 티켓을 받아 든 사이에 그 소셜이 다른 사용자에게 붙었을 수 있다.
        // 조용히 옮기면 먼저 붙여 둔 쪽이 로그인 수단을 잃는다.
        userAuthRepository.findByProviderAndProviderUserId(ticket.provider(), ticket.providerUserId())
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.SOCIAL_ALREADY_LINKED);
                });

        userAuthRepository.save(UserAuth.ofSocial(user, ticket.provider(), ticket.providerUserId()));
        log.info("계정 연동 완료 — userId={} 에 {} 추가", user.getId(), ticket.provider());

        return issueTokens(user, request.deviceId(), ticket.provider());
    }

    private String resolveNickname(OAuthUserInfo info) {
        if (StringUtils.hasText(info.nickname())) {
            return info.nickname();
        }
        // 닉네임은 NOT NULL 이라 비워둘 수 없다. 충돌하지 않을 임시 이름을 만든다.
        return FALLBACK_NICKNAME_PREFIX + info.providerUserId();
    }

    /**
     * 소셜 로그인 결과. 토큰이 나오거나, 연동을 요구하거나 둘 중 하나다.
     *
     * @param created 신규 가입이면 true → 컨트롤러가 201. 연동 요구일 때는 항상 false
     */
    public record OAuthResult(OAuthLoginResponse response, boolean created) {

        static OAuthResult authenticated(AuthTokenResponse tokens, boolean created) {
            return new OAuthResult(OAuthLoginResponse.authenticated(tokens), created);
        }

        static OAuthResult linkRequired(String linkToken, AuthProvider provider, String maskedEmail) {
            return new OAuthResult(
                    OAuthLoginResponse.linkRequired(linkToken, provider, maskedEmail), false);
        }

        /** 연동을 요구했는가. 컨트롤러가 상태 코드를 정할 때 쓴다. */
        public boolean isLinkRequired() {
            return response.status() == OAuthLoginResponse.Status.ACCOUNT_LINK_REQUIRED;
        }
    }

    // ------------------------------------------------------------------
    // AUTH-004 토큰 재발급 (회전 + 재사용 감지)
    // ------------------------------------------------------------------
    // AUTH-008 비밀번호 변경 / AUTH-009 이메일 변경
    // ------------------------------------------------------------------

    /**
     * 비밀번호를 바꾼다. 바꾼 뒤 <b>다른 기기의 세션은 전부 끊는다.</b>
     *
     * <p>비밀번호를 바꾸는 흔한 이유가 "누가 내 계정을 쓰는 것 같다" 이다. 그런데 세션을
     * 그대로 두면 이미 들어와 있는 쪽은 계속 들어와 있다. 바꾼 의미가 없다.
     *
     * <p>지금 조작한 기기만 새 토큰을 받아 이어서 쓴다 — 자기 비밀번호를 바꾼 사람이
     * 로그인 화면으로 튕겨 나가지 않게 한다.
     */
    @Transactional
    public AuthTokenResponse changePassword(Long userId, PasswordChangeRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        UserAuth local = userAuthRepository.findByUserIdAndProvider(userId, AuthProvider.LOCAL)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "소셜 로그인으로만 가입한 계정에는 바꿀 비밀번호가 없습니다."));

        if (!passwordEncoder.matches(request.currentPassword(), local.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "현재 비밀번호가 일치하지 않습니다.");
        }
        if (passwordEncoder.matches(request.newPassword(), local.getPasswordHash())) {
            // 바꾸지 않은 것을 바꿨다고 안내하면, 사용자는 위험이 사라졌다고 믿는다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "지금 쓰는 비밀번호와 다른 값을 입력해 주세요.");
        }

        local.changePassword(passwordEncoder.encode(request.newPassword()));

        // 새 토큰을 먼저 만들면 곧바로 지워진다. 전부 끊고 나서 이 기기 것만 다시 발급한다.
        refreshTokenStore.revokeAll(userId);
        log.info("비밀번호 변경 — userId={} 전체 세션 폐기", userId);

        return issueTokens(user, request.deviceId(), AuthProvider.LOCAL);
    }

    /**
     * 이메일을 바꾼다. 새 주소는 <b>인증을 마친 뒤</b>여야 한다(가입과 같은 흐름).
     *
     * <p>토큰은 다시 주지 않는다. 토큰은 사용자 번호로 발급되므로 이메일이 바뀌어도 그대로 쓴다.
     * 세션도 끊지 않는다 — 비밀번호와 달리 이메일 변경은 다른 기기의 접근 권한과 무관하다.
     */
    @Transactional
    public EmailChangeResponse changeEmail(Long userId, EmailChangeRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (request.newEmail().equalsIgnoreCase(user.getEmail())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지금 쓰는 이메일과 같습니다.");
        }

        // 비밀번호가 있는 계정이면 본인 확인을 먼저 한다. 소셜로만 가입한 계정은 물을 것이 없다.
        Optional<UserAuth> local = userAuthRepository.findByUserIdAndProvider(userId, AuthProvider.LOCAL);
        if (local.isPresent()) {
            if (!StringUtils.hasText(request.password())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "비밀번호를 입력해 주세요.");
            }
            if (!passwordEncoder.matches(request.password(), local.get().getPasswordHash())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "비밀번호가 일치하지 않습니다.");
            }
        }

        // 소유 확인이 먼저다. 확인 없이 바꾸게 두면 남의 이메일을 자기 계정에 달아 선점할 수 있다.
        emailVerificationService.requireVerified(request.newEmail());

        userRepository.findByEmail(request.newEmail()).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "이미 사용 중인 이메일입니다.");
        });

        user.changeEmail(request.newEmail());
        // 변경이 성립한 뒤에 통행증을 쓴다 — 한 번 인증으로 여러 계정의 이메일을 바꾸지 못하게 한다.
        emailVerificationService.consume(request.newEmail());
        log.info("이메일 변경 — userId={}", userId);

        return new EmailChangeResponse(request.newEmail(), local.isPresent());
    }

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
