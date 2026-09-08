package com.ssafy.ddubi.domain.auth.entity;

import com.ssafy.ddubi.domain.user.entity.User;
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
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 수단. 한 계정에 여러 개가 붙을 수 있다(일반 + 카카오).
 *
 * 계정 통합의 구현체다 — 같은 이메일로 소셜 로그인하면 새 user 를 만들지 않고
 * 기존 user 에 provider 행만 추가한다(BACKEND_CONVENTION.md 도메인 규칙 3).
 *
 * 제약:
 *  - UNIQUE(provider, provider_user_id): 한 카카오 계정이 두 user 에 붙는 것 방지.
 *    LOCAL 은 provider_user_id 가 NULL 인데 MySQL 은 NULL 을 중복으로 보지 않아 충돌하지 않는다.
 *  - UNIQUE(user_id, provider): 같은 수단 중복 등록 방지. LOCAL 중복은 이쪽이 막는다.
 */
@Entity
@Table(name = "user_auth")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAuth extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 10)
    private AuthProvider provider;

    /** 소셜의 외부 고유 ID(카카오 회원번호 등). LOCAL 은 NULL. */
    @Column(name = "provider_user_id", length = 100)
    private String providerUserId;

    /** bcrypt 해시. LOCAL 행에만 값이 있다. 어떤 응답에도 노출 금지. */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    private UserAuth(User user, AuthProvider provider, String providerUserId, String passwordHash) {
        this.user = user;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.passwordHash = passwordHash;
    }

    /** 일반 가입 수단. 비밀번호 해시는 호출부(서비스)가 인코딩해서 넘긴다. */
    public static UserAuth ofLocal(User user, String passwordHash) {
        return new UserAuth(user, AuthProvider.LOCAL, null, passwordHash);
    }

    /** 소셜 가입 수단. 비밀번호를 갖지 않는다. */
    public static UserAuth ofSocial(User user, AuthProvider provider, String providerUserId) {
        return new UserAuth(user, provider, providerUserId, null);
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
