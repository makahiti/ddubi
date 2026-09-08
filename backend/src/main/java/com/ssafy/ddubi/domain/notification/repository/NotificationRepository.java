package com.ssafy.ddubi.domain.notification.repository;

import com.ssafy.ddubi.domain.notification.entity.Notification;
import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findAllByUserId(Long userId, Pageable pageable);

    Page<Notification> findAllByUserIdAndType(Long userId, NotificationType type, Pageable pageable);

    Page<Notification> findAllByUserIdAndReadAtIsNull(Long userId, Pageable pageable);

    Page<Notification> findAllByUserIdAndTypeAndReadAtIsNull(
            Long userId, NotificationType type, Pageable pageable);

    /** 안읽음 뱃지 — 목록 응답과 홈 요약(HOME-001)이 같이 쓴다. */
    long countByUserIdAndReadAtIsNull(Long userId);

    /**
     * NTF-004 전체 읽음 — 벌크 UPDATE 한 번.
     *
     * 엔티티를 전부 불러와 반복 처리하지 않는 이유: 알림이 수백 건이면 그만큼 SELECT 가 나가고
     * 영속성 컨텍스트에 쌓인다. 읽음 처리는 되돌릴 것도, 검증할 것도 없는 단순 갱신이다.
     *
     * updated_at 을 명시로 갱신하는 이유: 벌크 연산은 JPA Auditing 을 타지 않는다.
     * 빼면 DB 의 ON UPDATE 가 대신 찍어 서버가 갱신 주체라는 규칙(도메인 규칙 12)이 깨진다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
               set n.readAt = :readAt, n.updatedAt = :readAt
             where n.user.id = :userId
               and n.readAt is null
            """)
    int markAllRead(@Param("userId") Long userId, @Param("readAt") LocalDateTime readAt);
}
