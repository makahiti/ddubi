package com.ssafy.ddubi.domain.schedule.entity;

import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.global.entity.BaseTimeEntity;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
 * 일정 인스턴스 — 추천의 출발점 (F-01, F-03, F-05).
 *
 * 이 엔티티가 지키는 도메인 규칙:
 *  - 규칙 5: 반복 인스턴스를 개별 수정하면 {@code customized=true} → 반복 전체 수정이 건너뛴다
 *  - 규칙 11: 삭제는 soft(CANCELED). 물리 삭제는 탈퇴 CASCADE 뿐
 *  - 규칙 12: updated_at 이 낙관적 잠금 비교값이다 — SCH-004 가 요청의 updatedAt 과 대조한다
 */
@Entity
@Table(name = "schedule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Schedule extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 반복 생성분만 값 존재. 단일 일정은 NULL. FK 는 ON DELETE SET NULL. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repeat_id")
    private ScheduleRepeat repeat;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name", column = @Column(name = "origin_name", nullable = false, length = 100)),
            @AttributeOverride(name = "lat", column = @Column(name = "origin_lat", nullable = false, precision = 10, scale = 7)),
            @AttributeOverride(name = "lng", column = @Column(name = "origin_lng", nullable = false, precision = 10, scale = 7))
    })
    private LocationSnapshot origin;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name", column = @Column(name = "dest_name", nullable = false, length = 100)),
            @AttributeOverride(name = "lat", column = @Column(name = "dest_lat", nullable = false, precision = 10, scale = 7)),
            @AttributeOverride(name = "lng", column = @Column(name = "dest_lng", nullable = false, precision = 10, scale = 7))
    })
    private LocationSnapshot destination;

    /** 반드시 도착해야 하는 시각 — 출발시각 역산의 기준점. */
    @Column(name = "arrive_by", nullable = false)
    private LocalDateTime arriveBy;

    /** 사용자 입력 출발시각(KST). 추천 depart_at으로 자동 덮어쓰지 않는다. 기존 null은 감시 대기다. */
    @Column(name = "planned_depart_at")
    private LocalDateTime plannedDepartAt;

    /** 사용자 등록/수정 또는 명시적 제안 수락에서만 변경한다. 추천 계산 자체는 호출하지 않는다. */
    public void changePlannedDepartAt(LocalDateTime value) { this.plannedDepartAt = value; }

    @Enumerated(EnumType.STRING)
    @Column(name = "importance", nullable = false)
    private Importance importance;

    /** 막차 위험 계산 대상 여부 (F-12). */
    @Column(name = "consider_last_trip", nullable = false)
    private boolean considerLastTrip;

    /** true = 반복 전체 수정(REPEAT-004)이 이 행을 건너뛴다. */
    @Column(name = "is_customized", nullable = false)
    private boolean customized;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ScheduleStatus status;

    @Builder
    private Schedule(User user, ScheduleRepeat repeat, String title,
                     LocationSnapshot origin, LocationSnapshot destination,
                     LocalDateTime arriveBy, Importance importance, boolean considerLastTrip) {
        this.user = user;
        this.repeat = repeat;
        this.title = title;
        this.origin = origin;
        this.destination = destination;
        this.arriveBy = arriveBy;
        this.importance = importance == null ? Importance.NORMAL : importance;
        this.considerLastTrip = considerLastTrip;
        this.customized = false;
        this.status = ScheduleStatus.UPCOMING;
    }

    public boolean isOwnedBy(Long userId) {
        return user.getId().equals(userId);
    }

    public Long repeatId() {
        return repeat == null ? null : repeat.getId();
    }

    /**
     * SCH-005 취소. 이미 취소된 일정은 그대로 둔다(멱등) —
     * 같은 요청이 두 번 와도 두 번째가 실패하면 클라이언트 재시도가 불가능해진다.
     * DONE 은 되돌릴 수 없는 사실이므로 취소를 거부한다.
     */
    public void cancel() {
        if (status == ScheduleStatus.DONE) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "이미 완료된 일정은 취소할 수 없습니다.");
        }
        this.status = ScheduleStatus.CANCELED;
    }

    /**
     * 도착 보고가 들어오면 완료 처리한다 (ARRIVAL-001).
     *
     * 이미 DONE 이어도 예외를 던지지 않는다 — 같은 일정을 두 번 보고하는 일은
     * 앱 재시도로 실제로 생기고, 여기서 막으면 클라이언트가 재시도할 방법이 없다.
     * 취소한 일정에 도착이 있을 수는 없으므로 그때만 거부한다.
     */
    public void markDone() {
        if (status == ScheduleStatus.CANCELED) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "취소된 일정은 도착을 기록할 수 없습니다.");
        }
        this.status = ScheduleStatus.DONE;
    }

    /** UPCOMING 이 아니면 수정을 거부한다 — 끝난·취소된 일정의 수정은 의미가 없다. */
    public void requireEditable() {
        if (status != ScheduleStatus.UPCOMING) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "진행 예정(UPCOMING) 일정만 수정할 수 있습니다.");
        }
    }

    /** 반복 인스턴스가 개별 수정되면 호출 — 이후 반복 전체 수정에서 제외된다(규칙 5). */
    public void markCustomizedIfRepeatInstance() {
        if (repeat != null) {
            this.customized = true;
        }
    }

    public void changeTitle(String title) {
        this.title = title;
    }

    public void changeOrigin(LocationSnapshot origin) {
        this.origin = origin;
    }

    public void changeDestination(LocationSnapshot destination) {
        this.destination = destination;
    }

    public void changeArriveBy(LocalDateTime arriveBy) {
        this.arriveBy = arriveBy;
    }

    public void changeImportance(Importance importance) {
        this.importance = importance;
    }

    public void changeConsiderLastTrip(boolean considerLastTrip) {
        this.considerLastTrip = considerLastTrip;
    }
}
