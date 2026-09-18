package com.ssafy.ddubi.domain.report.dto.response;

import com.ssafy.ddubi.domain.report.entity.ArrivalResult;
import com.ssafy.ddubi.domain.report.entity.ReportDetail;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;

import java.time.LocalDateTime;

/** ARRIVAL-001 응답 (명세서 v2.1.1 §9.1). */
public record ArrivalResponse(
        Long scheduleId,
        ScheduleStatus status,
        Long reportDetailId,
        LocalDateTime plannedDepart,
        Integer proxyDurationMin,
        LocalDateTime proxyArrival,
        LocalDateTime actualArrival,
        ArrivalResult result,
        String mainCause
) {

    public static ArrivalResponse from(ReportDetail detail) {
        return new ArrivalResponse(
                detail.getSchedule().getId(),
                detail.getSchedule().getStatus(),
                detail.getId(),
                detail.getPlannedDepart(),
                detail.getProxyDurationMin(),
                detail.getProxyArrival(),
                detail.getActualArrival(),
                detail.getResult(),
                detail.getMainCause());
    }
}
