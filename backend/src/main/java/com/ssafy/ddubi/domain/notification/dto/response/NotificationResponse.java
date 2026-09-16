package com.ssafy.ddubi.domain.notification.dto.response;

import com.ssafy.ddubi.domain.notification.entity.Notification;
import com.ssafy.ddubi.domain.notification.entity.NotificationStatus;
import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import com.ssafy.ddubi.domain.notification.entity.SuppressReason;

import java.time.LocalDateTime;

/**
 * NTF-001 목록 항목 · NTF-002 상세 공통 (명세서 v2.1.1 §8.1).
 *
 * @param suppressReason 보내지 않은 이유. {@code status=SUPPRESSED} 일 때만 채워진다.
 *                       {@code SENT}/{@code FAILED} 와 2026-09-14 이전 행은 null 이다
 */
public record NotificationResponse(
        Long id,
        Long scheduleId,
        Long recommendationId,
        NotificationType type,
        String title,
        String body,
        NotificationStatus status,
        SuppressReason suppressReason,
        LocalDateTime sentAt,
        LocalDateTime readAt,
        LocalDateTime createdAt
) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.scheduleId(),
                n.getRecommendationId(),
                n.getType(),
                n.getTitle(),
                n.getBody(),
                n.getStatus(),
                n.getSuppressReason(),
                n.getSentAt(),
                n.getReadAt(),
                n.getCreatedAt());
    }
}
