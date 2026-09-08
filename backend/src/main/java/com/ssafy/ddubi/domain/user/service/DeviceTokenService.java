package com.ssafy.ddubi.domain.user.service;

import com.ssafy.ddubi.domain.user.dto.request.DeviceTokenDeleteRequest;
import com.ssafy.ddubi.domain.user.dto.request.DeviceTokenRegisterRequest;
import com.ssafy.ddubi.domain.user.dto.response.DeviceTokenResponse;
import com.ssafy.ddubi.domain.user.entity.DeviceToken;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.DeviceTokenRepository;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** DEVICE-001~002. */
@Service
public class DeviceTokenService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenService.class);

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;

    public DeviceTokenService(DeviceTokenRepository deviceTokenRepository,
                              UserRepository userRepository) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.userRepository = userRepository;
    }

    /**
     * DEVICE-001 등록·갱신 — <b>fcm_token 기준 upsert</b>.
     *
     * 같은 기기에 다른 계정이 로그인하면 FCM 토큰은 그대로인데 주인이 바뀐다.
     * 새 행을 만들면 UK_DEVICE_FCM 에 걸리고, 만들어져도 이전 사용자에게 푸시가 계속 간다.
     * 그래서 기존 행의 user_id 를 갈아끼운다(마지막 로그인 사용자가 주인).
     */
    @Transactional
    public DeviceTokenResponse register(Long userId, DeviceTokenRegisterRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        DeviceToken token = deviceTokenRepository.findByFcmToken(request.fcmToken())
                .map(existing -> {
                    if (!existing.getUser().getId().equals(userId)) {
                        log.info("기기 주인 변경 — fcmToken 소유자 {} -> {}",
                                existing.getUser().getId(), userId);
                    }
                    existing.reassignTo(user, request.platform());
                    return existing;
                })
                .orElseGet(() -> deviceTokenRepository.save(DeviceToken.builder()
                        .user(user)
                        .fcmToken(request.fcmToken())
                        .platform(request.platform())
                        .build()));

        return DeviceTokenResponse.of(token);
    }

    /**
     * DEVICE-002 해제 — 내 토큰만 지운다.
     *
     * 소유자를 확인하지 않으면 남의 FCM 토큰을 임의로 해제해 푸시를 끊을 수 있다.
     */
    @Transactional
    public void unregister(Long userId, DeviceTokenDeleteRequest request) {
        DeviceToken token = deviceTokenRepository.findByFcmToken(request.fcmToken())
                .orElseThrow(() -> new BusinessException(ErrorCode.DEVICE_TOKEN_NOT_FOUND));

        if (!token.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        deviceTokenRepository.delete(token);
    }
}
