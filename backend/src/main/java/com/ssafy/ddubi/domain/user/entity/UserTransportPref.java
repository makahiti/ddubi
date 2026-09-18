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
 *
 * <h3>추천은 이 값을 읽지 않는다 (2026-09-08 결정)</h3>
 * ML 연동안에서 선호·회피 가중치를 후보 비교에서 뺐다. 저장·조회는 그대로 두되
 * <b>추천 순위에 영향을 주지 않는다.</b>
 *
 * <p>테이블과 API 를 지우지 않은 이유: 이미 동작하고 프론트가 붙어 있어, 걷어내면
 * 데모를 앞두고 회귀 위험만 생긴다. 안 읽으면 그만이다.
 * 정책이 되살아나면 그대로 쓸 수 있다.
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
