package com.ssafy.ddubi.domain.auth.repository;

import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.domain.auth.entity.UserAuth;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserAuthRepository extends JpaRepository<UserAuth, Long> {

    /** 소셜 로그인 1차 조회 — 이미 연결된 소셜 계정인지 본다. */
    Optional<UserAuth> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);

    Optional<UserAuth> findByUserIdAndProvider(Long userId, AuthProvider provider);

    List<UserAuth> findAllByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ua from UserAuth ua where ua.user.id = :userId order by ua.provider")
    List<UserAuth> findAllByUserIdForUpdate(Long userId);

    boolean existsByUserIdAndProvider(Long userId, AuthProvider provider);

    /**
     * 일반 로그인용. user 를 함께 끌어와 N+1 을 피한다
     * (LAZY 라 그냥 조회하면 user 접근 시 쿼리가 한 번 더 나간다).
     */
    @Query("select ua from UserAuth ua join fetch ua.user u "
            + "where u.email = :email and ua.provider = com.ssafy.ddubi.domain.auth.entity.AuthProvider.LOCAL")
    Optional<UserAuth> findLocalAuthByEmail(String email);

    /** 계정에 붙은 인증 수단 목록. 엔티티를 다 끌어오지 않고 provider 만 뽑는다. */
    @Query("select ua.provider from UserAuth ua where ua.user.id = :userId order by ua.provider")
    List<AuthProvider> findProvidersByUserId(Long userId);

    /**
     * 탈퇴 시 비밀번호 확인용. <b>엔티티가 아니라 해시 문자열만</b> 가져온다.
     *
     * UserAuth 엔티티를 영속성 컨텍스트에 올려두면, 뒤이어 User 를 삭제할 때
     * "삭제된 User 를 참조하는 관리 엔티티"가 남아 커밋 시점에 TransientObjectException 이 난다.
     * 비어 있으면 LOCAL 인증 수단이 없는 소셜 전용 계정이다.
     */
    @Query("select ua.passwordHash from UserAuth ua where ua.user.id = :userId "
            + "and ua.provider = com.ssafy.ddubi.domain.auth.entity.AuthProvider.LOCAL")
    Optional<String> findLocalPasswordHash(Long userId);
}
