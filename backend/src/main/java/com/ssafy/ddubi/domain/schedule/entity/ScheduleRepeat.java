package com.ssafy.ddubi.domain.schedule.entity;

import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.global.entity.BaseTimeEntity;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 반복 일정 마스터(규칙). 인스턴스는 {@link Schedule} 로 따로 생성된다(도메인 규칙 5).
 *
 * <p>중지(REPEAT-005)는 행 삭제가 아니라 {@code active=false} 다 — 이미 만들어진 인스턴스는 유지하고 신규 생성만 멈춘다.
 */
@Entity
@Table(name = "schedule_repeat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduleRepeat extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    /** 반복 요일 CSV (예: MON,TUE,WED). 서버가 정렬·중복 제거·검증한다 — {@code WeeklyRepeatRule}. */
    @Column(name = "days_of_week", nullable = false, length = 30)
    private String daysOfWeek;

    @Column(name = "arrive_time", nullable = false)
    private LocalTime arriveTime;

    /**
     * 출발 예정시각. NULL 이면 이 규칙이 찍어낸 인스턴스는 추천 계산이 돌지 않는다.
     *
     * <p>출발시각 도입 이전에 만들어진 규칙만 NULL 이다. 서버가 도착 시각에서 역산해 채우지 않는다 — 사용자가 정하지 않은 출발 시각으로 알림을 보내면 그 시각의
     * 근거를 설명할 수 없다. 기존 규칙은 사용자가 수정 화면에서 직접 채운다.
     */
    @Column(name = "depart_time")
    private LocalTime departTime;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** 반복 종료일. NULL = 무기한. */
    @Column(name = "until_date")
    private LocalDate untilDate;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(
                name = "name",
                column = @Column(name = "origin_name", nullable = false, length = 100)),
        @AttributeOverride(
                name = "lat",
                column = @Column(name = "origin_lat", nullable = false, precision = 10, scale = 7)),
        @AttributeOverride(
                name = "lng",
                column = @Column(name = "origin_lng", nullable = false, precision = 10, scale = 7))
    })
    private LocationSnapshot origin;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(
                name = "name",
                column = @Column(name = "dest_name", nullable = false, length = 100)),
        @AttributeOverride(
                name = "lat",
                column = @Column(name = "dest_lat", nullable = false, precision = 10, scale = 7)),
        @AttributeOverride(
                name = "lng",
                column = @Column(name = "dest_lng", nullable = false, precision = 10, scale = 7))
    })
    private LocationSnapshot destination;

    /** 생성될 인스턴스의 기본 중요도. */
    @Enumerated(EnumType.STRING)
    @Column(name = "importance", nullable = false)
    private Importance importance;

    /** 생성될 인스턴스의 막차 고려 기본값. */
    @Column(name = "consider_last_trip", nullable = false)
    private boolean considerLastTrip;

    /** false = 신규 인스턴스 생성 중단. 기존 인스턴스는 유지. */
    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Builder
    private ScheduleRepeat(
            User user,
            String title,
            String daysOfWeek,
            LocalTime arriveTime,
            LocalTime departTime,
            LocalDate startDate,
            LocalDate untilDate,
            LocationSnapshot origin,
            LocationSnapshot destination,
            Importance importance,
            boolean considerLastTrip) {
        this.user = user;
        this.title = title;
        this.daysOfWeek = daysOfWeek;
        this.arriveTime = arriveTime;
        this.departTime = departTime;
        this.startDate = startDate;
        this.untilDate = untilDate;
        this.origin = origin;
        this.destination = destination;
        this.importance = Importance.stored(importance);
        this.considerLastTrip = considerLastTrip;
        this.active = true;
    }

    public boolean isOwnedBy(Long userId) {
        return user.getId().equals(userId);
    }

    public void deactivate() {
        this.active = false;
    }

    public void changeTitle(String title) {
        this.title = title;
    }

    public void changeRule(
            String daysOfWeek,
            LocalTime arriveTime,
            LocalTime departTime,
            LocalDate startDate,
            LocalDate untilDate) {
        this.daysOfWeek = daysOfWeek;
        this.arriveTime = arriveTime;
        this.departTime = departTime;
        this.startDate = startDate;
        this.untilDate = untilDate;
    }

    /**
     * 이 규칙이 만드는 인스턴스의 출발 시각. 출발이 도착보다 늦거나 같으면 전날로 본다 — "00:30 도착 / 23:50 출발" 같은 규칙은 같은 날 23:50 이
     * 아니라 전날 23:50 이다.
     *
     * @return 출발시각이 없는(도입 이전) 규칙이면 null
     */
    public LocalDateTime departAtOn(LocalDate date) {
        if (departTime == null) {
            return null;
        }
        LocalDateTime departAt = date.atTime(departTime);
        return departAt.isBefore(date.atTime(arriveTime)) ? departAt : departAt.minusDays(1);
    }

    public void changeOrigin(LocationSnapshot origin) {
        this.origin = origin;
    }

    public void changeDestination(LocationSnapshot destination) {
        this.destination = destination;
    }

    public void changeImportance(Importance importance) {
        this.importance = Importance.stored(importance);
    }

    public void changeConsiderLastTrip(boolean considerLastTrip) {
        this.considerLastTrip = considerLastTrip;
    }
}
