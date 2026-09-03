package com.ssafy.ddubi.domain.schedule.entity;

/**
 * 일정 상태. 재계산·알림 대상은 UPCOMING 뿐이다.
 * 삭제(SCH-005)는 CANCELED 로의 전이일 뿐 행을 지우지 않는다(도메인 규칙 11).
 */
public enum ScheduleStatus {
    UPCOMING,
    DONE,
    CANCELED
}
