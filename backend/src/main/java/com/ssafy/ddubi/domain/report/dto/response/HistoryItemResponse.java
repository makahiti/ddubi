package com.ssafy.ddubi.domain.report.dto.response;

import com.ssafy.ddubi.domain.report.entity.ArrivalResult;
import com.ssafy.ddubi.domain.report.entity.ReportDetail;

import java.time.LocalDateTime;

/** HISTORY-001 목록 항목 · REPORT-002 상세의 일정별 결과 (명세서 §9.2). */
public record HistoryItemResponse(
        Long reportDetailId,
        Long scheduleId,
        String title,
        LocalDateTime arriveBy,
        LocalDateTime plannedDepart,
        Integer proxyDurationMin,
        LocalDateTime proxyArrival,
        LocalDateTime actualArrival,
        ArrivalResult result,
        String mainCause
) {

    public static HistoryItemResponse from(ReportDetail d) {
        return new HistoryItemResponse(
                d.getId(),
                d.getSchedule().getId(),
                d.getSchedule().getTitle(),
                d.getSchedule().getArriveBy(),
                d.getPlannedDepart(),
                d.getProxyDurationMin(),
                d.getProxyArrival(),
                d.getActualArrival(),
                d.getResult(),
                d.getMainCause());
    }
}
