package com.ssafy.ddubi.domain.user.repository;

import com.ssafy.ddubi.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** 계정 통합 판단의 진입점 — 소셜 로그인 시 이메일이 같은 기존 계정을 찾는다. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * 아침 브리핑 대상 후보 — 오늘 브리핑 시각이 이미 지난 사용자.
     *
     * <p>{@code briefing_time} 이 NULL 이면 브리핑을 받지 않겠다는 뜻이라 제외한다.
     *
     * <p><b>같은 날 안에서만 본다.</b> 22시 브리핑을 다음 날 0시 30분에 보내지 않기 위해서다 —
     * {@code briefingTime <= 지금 시각} 이라 자정을 넘으면 어제 것은 자연히 대상에서 빠진다.
     * 놓친 브리핑을 다음 날 배달하는 것은 브리핑이 아니라 소음이다.
     *
     * <p>얼마나 늦었는지는 서비스가 판단한다 — 서버가 아침 내내 죽어 있다가 저녁에 올라와
     * "아침 브리핑" 을 보내면 안 된다.
     */
    @Query("""
            select u from User u
            where u.briefingTime is not null
              and u.briefingTime <= :now
            order by u.id asc
            """)
    List<User> findAllWithBriefingTimeAtOrBefore(@Param("now") LocalTime now);

    /**
     * 밤 브리핑 대상 후보 — 오늘 밤 브리핑 시각이 이미 지난 사용자.
     *
     * <p>아침 브리핑과 같은 규칙이다. NULL 이면 받지 않겠다는 뜻이고, 같은 날 안에서만 본다.
     */
    @Query("""
            select u from User u
            where u.nightBriefingTime is not null
              and u.nightBriefingTime <= :now
            order by u.id asc
            """)
    List<User> findAllWithNightBriefingTimeAtOrBefore(@Param("now") LocalTime now);
}
