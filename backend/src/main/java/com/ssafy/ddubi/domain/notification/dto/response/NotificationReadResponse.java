package com.ssafy.ddubi.domain.notification.dto.response;

import java.time.LocalDateTime;

/**
 * NTF-003 읽음 처리 응답.
 *
 * 명세서 요약은 필드명을 notificationId 라 했지만 JSON 예시는 id 다.
 * 목록·상세가 id 를 쓰므로 id 로 통일한다(명세서 정정 요청함).
 */
public record NotificationReadResponse(Long id, LocalDateTime readAt) {
}
