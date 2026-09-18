package com.ssafy.ddubi.domain.report.dto.response;

import com.ssafy.ddubi.domain.report.repository.HistoryStats;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * HISTORY-001 목록 위에 얹히는 기간 요약 (명세서 개정안 E).
 *
 * 목록만 주면 프론트가 요약을 낼 수 없다 — 페이지네이션 때문에 1페이지 20건으로 낸 평균은
 * 전체 평균이 아니다. 그래서 <b>페이지가 아니라 조회 구간 전체</b>에 대한 집계를 함께 싣는다.
 *
 * @param tightCount     정시도 지연도 아닌 "빠듯하게 도착". 주간 리포트의 두 칸에는 들어가지
 *                       않지만 화면에는 보여야 해서 여기서는 따로 센다
 * @param onTimeRate     ON_TIME ÷ 전체. 판정이 없는 건(미동의)도 분모에 든다 —
 *                       "기록은 남았지만 정시였는지 모른다" 가 사실이기 때문
 * @param avgDurationMin 실제 소요 평균. 추천(Phase 3) 이전에는 null
 */
public record HistorySummaryResponse(
        long totalCount,
        long onTimeCount,
        long tightCount,
        long lateCount,
        BigDecimal onTimeRate,
        Double avgDurationMin
) {

    public static HistorySummaryResponse from(HistoryStats stats) {
        long total = nz(stats.getTotalCount());
        long onTime = nz(stats.getOnTimeCount());
        return new HistorySummaryResponse(
                total,
                onTime,
                nz(stats.getTightCount()),
                nz(stats.getLateCount()),
                rate(onTime, total),
                stats.getAvgDurationMin());
    }

    /** 집계 대상이 없으면 SUM 은 null 로 온다. 0 과 "값 없음"을 화면에서 구분할 이유가 없다. */
    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static BigDecimal rate(long onTime, long total) {
        if (total == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(onTime)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }
}
