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
import java.util.Collection;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 알림함에 보이는 것만 센다. 사용자가 지운 알림(deleted_at)은 이력으로만 남고
    // 목록·상세·뱃지에서는 없는 것으로 다룬다. 억제 판단만 지운 것까지 본다.

    Page<Notification> findAllByUserIdAndDeletedAtIsNull(Long userId, Pageable pageable);

    Page<Notification> findAllByUserIdAndTypeAndDeletedAtIsNull(
            Long userId, NotificationType type, Pageable pageable);

    Page<Notification> findAllByUserIdAndReadAtIsNullAndDeletedAtIsNull(
            Long userId, Pageable pageable);

    Page<Notification> findAllByUserIdAndTypeAndReadAtIsNullAndDeletedAtIsNull(
            Long userId, NotificationType type, Pageable pageable);

    /** 안읽음 뱃지 — 목록 응답과 홈 요약(HOME-001)이 같이 쓴다. */
    long countByUserIdAndReadAtIsNullAndDeletedAtIsNull(Long userId);

    /**
     * 그 억제 키로 이미 알림을 받은 사용자들.
     *
     * <p>아침 브리핑을 하루 한 번으로 묶는 근거다. Redis 억제 키는 TTL 이 10분이라 하루를
     * 덮지 못하고 서버를 재시작하면 사라진다. 억제된 행도 남으므로(도메인 규칙 9) 발송을
     * <b>시도했다는 사실</b>이 여기 남는다 — 알림을 꺼 둔 사용자에게 하루 종일 재시도하지 않는다.
     *
     * <p>{@code created_at} 대신 키로 묻는 이유: 키에 날짜가 들어 있어 "그날의 브리핑" 을
     * 직접 가리킨다. 저장 시각으로 판단하면 같은 값이 <b>언제 실행하느냐</b>에 따라 달라진다.
     *
     * <p>사용자마다 따로 묻지 않고 한 번에 가져온다. 대상이 늘어도 쿼리는 하나다.
     * (브리핑은 사용자당 하루 한 행이라 대상 수만큼만 나온다.)
     */
    @Query("""
            select distinct n.user.id from Notification n
            where n.type = :type
              and n.suppressKey = :suppressKey
            """)
    List<Long> findUserIdsNotifiedWith(@Param("type") NotificationType type,
                                       @Param("suppressKey") String suppressKey);

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
               and n.deletedAt is null
            """)
    int markAllRead(@Param("userId") Long userId, @Param("readAt") LocalDateTime readAt);

    /**
     * NTF-005 알림함에서 숨기기 — 여러 건도 UPDATE 한 번.
     *
     * 소유권과 존재 여부는 호출 전에 확인한다(서비스가 404·403 을 구분해야 한다).
     * 여기서 user.id 를 다시 거는 것은 마지막 방어선이다 — 검사와 갱신 사이에
     * 다른 요청이 끼어들어도 남의 알림을 건드리지 않는다.
     *
     * 이미 숨긴 것은 건너뛴다. 같은 요청이 두 번 와도 처음 지운 시각이 유지된다(멱등).
     *
     * updated_at 을 명시로 갱신하는 이유는 markAllRead 와 같다 — 벌크 연산은 Auditing 을 타지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
               set n.deletedAt = :deletedAt, n.updatedAt = :deletedAt
             where n.user.id = :userId
               and n.id in :ids
               and n.deletedAt is null
            """)
    int hide(@Param("userId") Long userId,
             @Param("ids") Collection<Long> ids,
             @Param("deletedAt") LocalDateTime deletedAt);
}
