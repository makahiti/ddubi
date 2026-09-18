package com.ssafy.ddubi.domain.report.repository;

import com.ssafy.ddubi.domain.report.entity.Report;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {

    /** 주간 리포트는 UNIQUE(user_id, period_start, period_end) 라 단건이다. */
    Optional<Report> findByUserIdAndPeriodStartAndPeriodEnd(
            Long userId, LocalDate periodStart, LocalDate periodEnd);

    Page<Report> findAllByUserId(Long userId, Pageable pageable);

    /** REPORT-001 기간 필터 — 조회 구간과 겹치는 주를 전부 준다. */
    Page<Report> findAllByUserIdAndPeriodEndGreaterThanEqualAndPeriodStartLessThanEqual(
            Long userId, LocalDate from, LocalDate to, Pageable pageable);
}
