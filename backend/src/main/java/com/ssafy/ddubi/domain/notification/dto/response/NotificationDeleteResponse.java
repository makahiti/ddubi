package com.ssafy.ddubi.domain.notification.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * NTF-005 알림 삭제 응답.
 *
 * <p>{@code deletedCount} 는 <b>이번 요청으로 실제 숨긴 개수</b>다. 이미 지운 알림을 다시
 * 보내면 0 이 오지만 오류는 아니다(멱등). 화면은 {@code deletedIds} 로 목록에서 빼면 된다.
 *
 * @param deletedIds   요청한 알림 중 지금 알림함에서 사라진 것
 * @param deletedCount 이번 요청으로 새로 숨긴 개수
 * @param unreadCount  삭제 뒤 안읽음 뱃지 숫자. 안읽은 알림을 지우면 뱃지가 줄어든다
 * @param deletedAt    숨긴 시각
 */
public record NotificationDeleteResponse(List<Long> deletedIds,
                                         int deletedCount,
                                         long unreadCount,
                                         LocalDateTime deletedAt) {
}
