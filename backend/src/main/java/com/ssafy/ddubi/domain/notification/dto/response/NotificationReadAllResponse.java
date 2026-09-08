package com.ssafy.ddubi.domain.notification.dto.response;

import java.time.LocalDateTime;

/** NTF-004 전체 읽음 응답 (명세서 §8.4). */
public record NotificationReadAllResponse(int updatedCount, LocalDateTime readAt) {
}
