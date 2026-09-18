package com.ssafy.ddubi.domain.report.entity;

/**
 * 도착 판정. 목표 시각(schedule.arrive_by)과 실제 도착을 비교한 결과다.
 *
 * 경계는 설정값이며 기본 10분이다({@code ddubi.report.on-time-margin}).
 * "목표 4분 전 도착"을 성공으로 볼지 아슬아슬로 볼지는 정책이라 코드에 박지 않는다.
 */
public enum ArrivalResult {

    /** 목표보다 여유 있게 도착 */
    ON_TIME,
    /** 목표 전이지만 빠듯하게 도착 */
    TIGHT,
    /** 목표를 넘겨 도착 */
    LATE_RISK
}
