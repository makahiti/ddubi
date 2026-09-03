package com.ssafy.ddubi.domain.schedule.entity;

/**
 * 일정 중요도. 추천 여유시간 백분위를 결정한다(도메인 규칙 7).
 * NORMAL=p50 / HIGH=p80 / CRITICAL=p95.
 */
public enum Importance {
    NORMAL,
    HIGH,
    CRITICAL
}
