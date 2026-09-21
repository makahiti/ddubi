package com.ssafy.ddubi.domain.user.service;

import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.domain.auth.repository.UserAuthRepository;
import com.ssafy.ddubi.domain.auth.service.RefreshTokenStore;
import com.ssafy.ddubi.domain.user.dto.request.UserUpdateRequest;
import com.ssafy.ddubi.domain.user.dto.request.UserWithdrawRequest;
import com.ssafy.ddubi.domain.user.dto.response.UserResponse;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.security.RevokedAccessTokenStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** USER-001~003. */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final UserAuthRepository userAuthRepository;
    private final RefreshTokenStore refreshTokenStore;
    private final PasswordEncoder passwordEncoder;
    private final RevokedAccessTokenStore revokedStore;

    public UserService(
            UserRepository userRepository,
            UserAuthRepository userAuthRepository,
            RefreshTokenStore refreshTokenStore,
            PasswordEncoder passwordEncoder,
            RevokedAccessTokenStore revokedStore) {
        this.userRepository = userRepository;
        this.userAuthRepository = userAuthRepository;
        this.refreshTokenStore = refreshTokenStore;
        this.passwordEncoder = passwordEncoder;
        this.revokedStore = revokedStore;
    }

    @Transactional(readOnly = true)
    public UserResponse getMe(Long userId) {
        return UserResponse.of(findUser(userId), representativeProvider(userId));
    }

    @Transactional
    public UserResponse update(Long userId, UserUpdateRequest request) {
        User user = findUser(userId);
        if (request.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "변경할 항목이 없습니다.");
        }
        // null 은 "안 보냄"이므로 건너뛴다. 부분 수정(PATCH) 의미를 지킨다.
        if (request.getNickname() != null) {
            user.changeNickname(request.getNickname());
        }
        if (request.getNotifyLeadMin() != null) {
            user.changeNotifyLeadMin(request.getNotifyLeadMin());
        }
        if (request.getAlarmEnabled() != null) {
            user.changeAlarm(request.getAlarmEnabled());
        }
        if (request.getHistoryConsent() != null) {
            user.changeHistoryConsent(request.getHistoryConsent());
        }
        // 지울 수 있는 필드는 "키가 왔는가"로 판단한다. 값이 null 이면 지우라는 뜻이다.
        if (request.isBriefingTimePresent()) {
            user.changeBriefingTime(request.getBriefingTime());
        }
        if (request.isMobilityNotePresent()) {
            user.changeMobilityNote(request.getMobilityNote());
        }
        return UserResponse.of(user, representativeProvider(userId));
    }

    /**
     * USER-003 탈퇴 — 되돌릴 수 없다.
     *
     * <p>user 행 하나를 지우면 일정·경로·추천·알림·리포트가 DB 의 ON DELETE CASCADE 로 함께 사라진다. 보관본은 없다(도메인 규칙 4).
     */
    @Transactional
    public void withdraw(Long userId, UserWithdrawRequest request) {
        if (!request.isConfirmed()) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "탈퇴하려면 confirmation 에 %s 를 보내야 합니다."
                            .formatted(UserWithdrawRequest.CONFIRM_KEYWORD));
        }
        User user = findUser(userId);
        verifyPasswordIfLocal(userId, request.password());

        // Redis 세션을 먼저 지운다. DB 삭제가 먼저면 그 사이 리프레시로 토큰이 재발급될 수 있다.
        refreshTokenStore.revokeAll(userId);
        // 이미 발급된 액세스 토큰은 만료 전까지 서명이 유효하다. 즉시 차단하지 않으면
        // 사라진 user_id 로 INSERT 를 시도해 FK 위반이 난다.
        revokedStore.revoke(userId);
        userRepository.delete(user);

        // reason 은 보관하지 않는다 — 탈퇴자 관련 데이터를 남기지 않기로 한 팀 합의 때문이다.
        log.info("회원 탈퇴 완료 — userId={}", userId);
    }

    /**
     * LOCAL 인증 수단이 있으면 비밀번호를 확인한다. 소셜 전용 계정은 비밀번호가 없어 건너뛴다.
     *
     * <p>엔티티가 아니라 해시 문자열만 가져오는 이유: UserAuth 를 영속 상태로 들고 있으면 뒤에서 User 를 삭제할 때 그 참조 때문에 커밋이
     * 실패한다(TransientObjectException).
     */
    private void verifyPasswordIfLocal(Long userId, String rawPassword) {
        String passwordHash = userAuthRepository.findLocalPasswordHash(userId).orElse(null);
        if (passwordHash == null) {
            return;
        }
        if (!StringUtils.hasText(rawPassword)
                || !passwordEncoder.matches(rawPassword, passwordHash)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "비밀번호가 일치하지 않습니다.");
        }
    }

    /**
     * 응답의 provider 는 단일값인데 계정에는 여러 수단이 붙을 수 있다(계정 통합). 가입 경로를 대표값으로 보아 LOCAL 을 우선하고, 없으면 첫 소셜 수단을
     * 쓴다.
     */
    private AuthProvider representativeProvider(Long userId) {
        List<AuthProvider> providers = userAuthRepository.findProvidersByUserId(userId);
        if (providers.isEmpty()) {
            // user 는 있는데 인증 수단이 없다 = 데이터 정합성 문제. 로그인 자체가 불가능한 상태다.
            log.error("인증 수단이 없는 사용자 — userId={}", userId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return providers.contains(AuthProvider.LOCAL) ? AuthProvider.LOCAL : providers.get(0);
    }

    /**
     * 인증된 본인을 찾는다. 없으면 404 가 아니라 <b>401</b> 이다.
     *
     * <p>탈퇴 직후에도 액세스 토큰은 만료 전까지 서명이 유효해 필터를 통과한다. 그 경우 "리소스를 못 찾았다"가 아니라 "토큰이 가리키는 계정이 사라졌다"이므로
     * 클라이언트가 재로그인해야 한다는 신호를 줘야 한다.
     */
    private User findUser(Long userId) {
        return userRepository
                .findById(userId)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.UNAUTHORIZED, "계정을 찾을 수 없습니다. 다시 로그인해 주세요."));
    }
}
