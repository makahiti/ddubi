package com.ssafy.ddubi.domain.report.dto.response;

import com.ssafy.ddubi.domain.report.entity.Report;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** REPORT-001 목록 항목. */
public record ReportResponse(
        Long id,
        LocalDate periodStart,
        LocalDate periodEnd,
        int totalSchedules,
        int onTimeEstimated,
        int delayedEstimated,
        LocalDateTime computedAt
) {

    public static ReportResponse from(Report report) {
        return new ReportResponse(
                report.getId(),
                report.getPeriodStart(),
                report.getPeriodEnd(),
                report.getTotalSchedules(),
                report.getOnTimeEst(),
                report.getDelayedEst(),
                report.getComputedAt());
    }
}
