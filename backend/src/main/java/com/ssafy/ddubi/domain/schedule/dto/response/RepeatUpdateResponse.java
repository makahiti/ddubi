package com.ssafy.ddubi.domain.schedule.dto.response;

/**
 * REPEAT-004 응답 — 변경된 규칙 + 재생성 결과.
 *
 * @param regeneratedScheduleCount 지우고 다시 만든 미래 인스턴스 수.
 *                                 개별 수정(customized)된 인스턴스는 건드리지 않는다
 */
public record RepeatUpdateResponse(
        RepeatResponse repeat,
        int regeneratedScheduleCount
) {
}
