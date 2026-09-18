package com.ssafy.ddubi.domain.report.repository;

/**
 * HISTORY-001 요약 집계 결과 (조회 구간 전체 기준).
 *
 * 전부 래퍼 타입인 이유: 대상이 한 건도 없으면 SUM·AVG 는 0 이 아니라 <b>null</b> 을 준다.
 * 원시 타입으로 받으면 언박싱에서 NPE 가 난다.
 */
public interface HistoryStats {

    Long getTotalCount();

    Long getOnTimeCount();

    Long getTightCount();

    Long getLateCount();

    /** 추천(Phase 3) 이전에는 proxy_duration_min 이 전부 null 이라 이 값도 null 이다. */
    Double getAvgDurationMin();
}
