package com.ssafy.ddubi.domain.user.repository;

import com.ssafy.ddubi.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** 계정 통합 판단의 진입점 — 소셜 로그인 시 이메일이 같은 기존 계정을 찾는다. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
