package com.ssafy.ddubi.domain.notification.service;

import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import lombok.Builder;

/**
 * 알림 하나를 만들어 달라는 요청. <b>추천 엔진(Phase 3)이 채우는 유일한 입력</b>이다.
 *
 * 엔티티가 아니라 명령 객체를 받는 이유: 호출부는 "무엇을 알릴지"만 알면 되고
 * 억제 판정·발송·상태 기록은 전부 서비스가 정한다. 호출부가 status 를 정하기
 * 시작하면 도메인 규칙 9(억제해도 행 생성)가 여러 곳으로 흩어진다.
 *
 * @param suppressKey 재발송 방지 키. 같은 값이 TTL 안에 다시 오면 억제된다.
 *                    null 이면 중복 검사를 하지 않는다(항상 보낼 알림)
 */
@Builder
public record NotificationCommand(
        Long userId,
        Long scheduleId,
        Long recommendationId,
        NotificationType type,
        String title,
        String body,
        String suppressKey,
        String evaluationId,
        String decision
) {
    /** 일반 알림과 이전 outbox JSON은 평가 정보를 갖지 않아도 같은 발송 경로를 사용한다. */
    public NotificationCommand(Long userId,Long scheduleId,Long recommendationId,NotificationType type,
            String title,String body,String suppressKey) {
        this(userId,scheduleId,recommendationId,type,title,body,suppressKey,null,null);
    }
}
