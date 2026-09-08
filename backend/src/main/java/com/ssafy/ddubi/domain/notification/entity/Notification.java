package com.ssafy.ddubi.domain.notification.entity;

import com.ssafy.ddubi.domain.schedule.entity.Schedule;
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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 알림 이력 + 인앱 알림함.
 *
 * <h3>발송하지 않아도 행은 만든다 (도메인 규칙 9)</h3>
 * 알림을 꺼둔 기간에도 행이 쌓여야, 다시 켰을 때 알림함에서 그동안의 일을 볼 수 있다.
 * 토글은 <b>푸시를 받을지</b>만 정하는 것이지 알림함을 비우는 것이 아니다.
 *
 * <h3>연결 대상이 사라져도 이력은 남는다</h3>
 * schedule_id·recommendation_id 는 FK ON DELETE SET NULL 이다. 일정을 지워도
 * "그때 이런 알림을 보냈다"는 사실은 남아야 한다.
 *
 * <h3>recommendation_id 를 지금 매핑하지 않는 이유</h3>
 * recommendation 엔티티가 Phase 3 산출물이라 아직 없다. 컬럼은 DDL 에 있으므로
 * 여기서는 원시 Long 으로만 들고 있다가, 엔티티가 생기면 ManyToOne 으로 바꾼다.
 */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 일정 삭제 시 NULL 로 남아 이력이 유지된다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;

    /** 이 알림의 근거가 된 추천 스냅샷. Phase 3 전까지는 null. */
    @Column(name = "recommendation_id")
    private Long recommendationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "body", nullable = false, length = 500)
    private String body;

    /** 일정·이유·행동 조합 해시 — 재발송 방지 (REQ-NTF-002). */
    @Column(name = "suppress_key", length = 64)
    private String suppressKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private NotificationStatus status;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** NULL 개수 = 안읽음 뱃지. */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Builder
    private Notification(User user, Schedule schedule, Long recommendationId,
                         NotificationType type, String title, String body,
                         String suppressKey, NotificationStatus status, LocalDateTime sentAt) {
        this.user = user;
        this.schedule = schedule;
        this.recommendationId = recommendationId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.suppressKey = suppressKey;
        this.status = status;
        this.sentAt = sentAt;
    }

    public boolean isOwnedBy(Long userId) {
        return user.getId().equals(userId);
    }

    public Long scheduleId() {
        return schedule == null ? null : schedule.getId();
    }

    public boolean isRead() {
        return readAt != null;
    }

    /** 발송 성공. 억제 상태로 만들어 둔 행을 SENT 로 올린다. */
    public void markSent(LocalDateTime sentAt) {
        this.status = NotificationStatus.SENT;
        this.sentAt = sentAt;
    }

    /** 발송 시도했으나 전부 실패. 억제(보내지 않음)와 구분해야 원인 추적이 된다. */
    public void markFailed() {
        this.status = NotificationStatus.FAILED;
    }

    /**
     * 읽음 처리. 이미 읽었으면 시각을 덮어쓰지 않는다 —
     * 같은 요청이 두 번 와도 처음 읽은 시각이 유지되어야 하고(멱등),
     * 클라이언트 재시도가 실패하면 안 된다.
     */
    public void markRead(LocalDateTime readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }
}
