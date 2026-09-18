package com.ssafy.ddubi.domain.schedule.entity;

/**
 * 일정 중요도.
 *
 * <h3>백분위를 고르는 값이 아니다 (2026-09-08 변경)</h3>
 * 예전에는 {@code NORMAL=p50 / HIGH=p80 / CRITICAL=p95} 로 <b>여유시간</b>을 정했다.
 * 지금은 여유시간을 중요도와 무관하게 <b>검증된 공통 안전 여유</b> 하나로 두고,
 * 중요도는 <b>감시·재계산·알림 빈도</b>에만 쓴다 — CRITICAL 일수록 자주 다시 계산하고
 * 더 민감하게 알린다.
 *
 * <p>바뀐 이유: ML({@code traffic_t03})이 p50/p80/p95 를 아직 {@code null} 로 준다.
 * <b>커버리지 검증 전의 "p95" 는 이름만 p95</b> 다. 100번 중 95번이 그 안에 들어온다는
 * 보장이 없는 값으로 CRITICAL 일정의 출발 시각을 정하면, 정확히 늦으면 안 되는 사람이 늦는다.
 *
 * <p>ML 백분위 커버리지 검증이 끝나면 되살릴 수 있다.
 * 자세한 배경은 BACKEND_CONVENTION.md 규칙 7.
 */
public enum Importance {
    NORMAL,
    HIGH,
    CRITICAL
}
