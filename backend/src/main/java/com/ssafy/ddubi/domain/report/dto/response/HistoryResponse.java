package com.ssafy.ddubi.domain.report.dto.response;

import com.ssafy.ddubi.global.common.PageResponse;

/**
 * HISTORY-001 응답 — 기간 요약 + 목록.
 *
 * 알림 목록이 unreadCount 를 함께 싣는 것과 같은 판단이다(명세서 §8.1).
 * 한 화면을 그리는 데 호출을 두 번 하게 만들 이유가 없다.
 */
public record HistoryResponse(
        HistorySummaryResponse summary,
        PageResponse<HistoryItemResponse> items
) {
}
