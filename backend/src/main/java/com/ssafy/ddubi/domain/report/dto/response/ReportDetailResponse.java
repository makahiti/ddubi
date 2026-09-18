package com.ssafy.ddubi.domain.report.dto.response;

import java.util.List;

/** REPORT-002 상세 — 집계 + 일정별 결과. */
public record ReportDetailResponse(
        ReportResponse report,
        List<HistoryItemResponse> reportDetails
) {
}
