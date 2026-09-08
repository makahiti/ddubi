package com.ssafy.ddubi.domain.user.entity;

import com.ssafy.ddubi.global.entity.BaseTimeEntity;
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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자별 선호·회피 교통수단 (REQ-SCH-004).
 * UNIQUE(user_id, mode) 로 같은 수단에 선호와 회피를 동시에 지정할 수 없다.
 */
@Entity
@Table(name = "user_transport_pref")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserTransportPref extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 10)
    private TransportMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "preference", nullable = false, length = 10)
    private TransportPreference preference;

    @Builder
    private UserTransportPref(User user, TransportMode mode, TransportPreference preference) {
        this.user = user;
        this.mode = mode;
        this.preference = preference;
    }
}
