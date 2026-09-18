package com.ssafy.ddubi.domain.report.entity;

import com.ssafy.ddubi.domain.schedule.entity.Schedule;
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
 * 일정 하나의 이동 결과.
 *
 * <h3>지금 채워지는 것과 Phase 3 이후 채워지는 것</h3>
 * <pre>
 * result          목표 시각과 실제 도착을 비교해 지금 계산한다
 * actualArrival   지오펜스 실측. 동의자만 (도메인 규칙 — user.history_consented_at)
 * plannedDepart   당시 권장 출발 시각 → 추천 엔진이 생기면 채운다
 * proxyDuration   실제 구간 소요 합산 → 경로가 생기면 채운다
 * </pre>
 *
 * 뒤 둘이 비어 있어도 스키마가 성립한다(전부 NULL 허용). 추천을 기다리지 않고
 * 도착 기록을 먼저 받을 수 있는 이유다.
 */
@Entity
@Table(name = "report_detail")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportDetail extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private Report report;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    /** 당시 권장 출발 시각. 추천 엔진(Phase 3) 이전에는 null. */
    @Column(name = "planned_depart")
    private LocalDateTime plannedDepart;

    /** 실제 구간 소요 합산(프록시 추정, 분). 경로(Phase 3) 이전에는 null. */
    @Column(name = "proxy_duration_min")
    private Integer proxyDurationMin;

    @Column(name = "proxy_arrival")
    private LocalDateTime proxyArrival;

    /** 지오펜스 실측. NULL = 미동의 사용자이거나 이번 요청에서 위치 제공을 거부했다. */
    @Column(name = "actual_arrival")
    private LocalDateTime actualArrival;

    @Enumerated(EnumType.STRING)
    @Column(name = "result")
    private ArrivalResult result;

    /** 주요 원인 (예: 2호선 지연). 추천의 reasons 에서 뽑아 온다 — Phase 3. */
    @Column(name = "main_cause", length = 100)
    private String mainCause;

    @Builder
    private ReportDetail(Report report, Schedule schedule, LocalDateTime plannedDepart,
                         Integer proxyDurationMin, LocalDateTime proxyArrival,
                         LocalDateTime actualArrival, ArrivalResult result, String mainCause) {
        this.report = report;
        this.schedule = schedule;
        this.plannedDepart = plannedDepart;
        this.proxyDurationMin = proxyDurationMin;
        this.proxyArrival = proxyArrival;
        this.actualArrival = actualArrival;
        this.result = result;
        this.mainCause = mainCause;
    }

    /**
     * 같은 일정의 도착을 다시 보고했을 때 값을 갈아끼운다.
     *
     * 행을 새로 만들지 않는 이유: 일정 하나의 이동 결과는 하나여야 한다.
     * 두 번 보고했다고 리포트 집계가 2건으로 늘면 통계가 틀린다.
     */
    public void update(LocalDateTime actualArrival, ArrivalResult result) {
        this.actualArrival = actualArrival;
        this.result = result;
    }
}
