package com.ssafy.ddubi.domain.notification.dto.response;

import com.ssafy.ddubi.domain.notification.entity.Notification;
import com.ssafy.ddubi.domain.notification.entity.NotificationStatus;
import com.ssafy.ddubi.domain.notification.entity.NotificationType;

import java.time.LocalDateTime;

/** NTF-001 목록 항목 · NTF-002 상세 공통 (명세서 v2.1.1 §8.1). */
public record NotificationResponse(
        Long id,
        Long scheduleId,
        Long recommendationId,
        NotificationType type,
        String title,
        String body,
        NotificationStatus status,
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
                n.getSentAt(),
                n.getReadAt(),
                n.getCreatedAt());
    }
}
