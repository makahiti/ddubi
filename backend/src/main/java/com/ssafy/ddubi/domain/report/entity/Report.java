package com.ssafy.ddubi.domain.report.entity;

import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 주간 집계 리포트.
 *
 * 기간은 월요일~일요일로 고정한다. UNIQUE(user_id, period_start, period_end) 라
 * 한 사용자의 같은 주는 한 행뿐이고, 도착 기록이 들어올 때마다 집계를 다시 센다.
 *
 * 집계값(total/onTime/delayed)을 매번 조회로 계산하지 않고 컬럼에 두는 이유:
 * 리포트 목록은 여러 주를 한 번에 보여주는데, 주마다 상세를 세면 N+1 이 된다.
 */
@Entity
@Table(name = "report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "total_schedules", nullable = false)
    private int totalSchedules;

    /** 정시 도착 추정 건수 (ON_TIME). */
    @Column(name = "on_time_est", nullable = false)
    private int onTimeEst;

    /** 지연 추정 건수 (LATE_RISK). TIGHT 는 어느 쪽도 아니다. */
    @Column(name = "delayed_est", nullable = false)
    private int delayedEst;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    @Builder
    private Report(User user, LocalDate periodStart, LocalDate periodEnd, LocalDateTime computedAt) {
        this.user = user;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.totalSchedules = 0;
        this.onTimeEst = 0;
        this.delayedEst = 0;
        this.computedAt = computedAt;
    }

    public boolean isOwnedBy(Long userId) {
        return user.getId().equals(userId);
    }

    /** 도착 기록이 들어올 때마다 다시 센 값으로 갈아끼운다. */
    public void recount(int total, int onTime, int delayed, LocalDateTime computedAt) {
        this.totalSchedules = total;
        this.onTimeEst = onTime;
        this.delayedEst = delayed;
        this.computedAt = computedAt;
    }
}
