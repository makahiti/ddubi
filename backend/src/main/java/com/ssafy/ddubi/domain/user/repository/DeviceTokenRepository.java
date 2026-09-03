package com.ssafy.ddubi.domain.user.repository;

import com.ssafy.ddubi.domain.user.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    /** 토큰 기준 upsert 의 조회 단계. UNIQUE(fcm_token) 이라 단건이다. */
    Optional<DeviceToken> findByFcmToken(String fcmToken);

    /** 발송 대상 조회 — 무효 토큰은 제외한다. */
    List<DeviceToken> findAllByUserIdAndValidIsTrue(Long userId);

    void deleteByFcmToken(String fcmToken);
}
