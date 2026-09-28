package com.ssafy.ddubi.domain.user.entity;

import com.ssafy.ddubi.global.entity.BaseTimeEntity;
import com.ssafy.ddubi.global.util.BusinessTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * FCM 푸시 기기.
 *
 * <p>fcm_token 이 UNIQUE 이고 등록은 토큰 기준 upsert 다 — 공용 기기에서 다른 계정으로 로그인하면 같은 토큰 행의 주인이 바뀐다(마지막 로그인 사용자가
 * 주인).
 */
@Entity
@Table(name = "device_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceToken extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "fcm_token", nullable = false, length = 512)
    private String fcmToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 10)
    private DevicePlatform platform;

    /** FCM 이 UNREGISTERED 를 주면 즉시 false. 이후 발송 대상에서 제외한다 (REQ-NTF-004). */
    @Column(name = "is_valid", nullable = false)
    private boolean valid;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Builder
    private DeviceToken(User user, String fcmToken, DevicePlatform platform) {
        this.user = user;
        this.fcmToken = fcmToken;
        this.platform = platform;
        this.valid = true;
        this.lastUsedAt = BusinessTime.now();
    }

    /** 토큰 기준 upsert 의 갱신 경로. 주인을 갈아끼우고 다시 유효 상태로 되돌린다. */
    public void reassignTo(User user, DevicePlatform platform) {
        this.user = user;
        this.platform = platform;
        this.valid = true;
        this.lastUsedAt = BusinessTime.now();
    }

    public void invalidate() {
        this.valid = false;
    }
}
