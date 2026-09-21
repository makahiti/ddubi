package com.ssafy.ddubi.domain.notification.entity;

/**
 * 알림 종류. ERD notification.type ENUM 과 1:1 이다.
 *
 * 값을 추가하려면 DDL 도 함께 고쳐야 한다(ALTER TABLE).
 */
public enum NotificationType {

    /** 아침 브리핑 (REQ-NTF-003) */
    MORNING_BRIEF,
    /** 곧 출발해야 함 */
    DEPART_SOON,
    /** 예정보다 일찍 출발해야 함 — 지연 발생 */
    EARLY_DEPART,
    /** 더 나은 경로로 바뀜 */
    ROUTE_CHANGE,
    /** 제시간 도착이 어려움 */
    ON_TIME_UNLIKELY,
    /** 막차 위험 (F-12) */
    LAST_TRIP,
    /** 연속 일정 충돌 (SCH-006 / REQ-RSK-001) */
    SCHEDULE_CONFLICT,
    /** 파이프라인 데이터가 낡음 — 추천 신뢰도 하락 */
    DATA_STALE,
    /** 밤 브리핑 — 내일 일정을 전날 밤에 미리 알린다 */
    NIGHT_BRIEF
}
