package com.ssafy.ddubi.domain.notification.dto.response;

import com.ssafy.ddubi.global.common.PageResponse;

import java.util.List;

/**
 * NTF-001 목록 응답.
 *
 * PageResponse 를 감싸지 않고 필드를 펼친 이유: 명세서 §8.1 이 unreadCount 와 페이지 정보를
 * 같은 깊이에 두기 때문이다. 뱃지와 목록을 한 화면에 그리므로 호출을 두 번 하게 만들지 않는다.
 */
public record NotificationListResponse(
        long unreadCount,
        List<NotificationResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    public static NotificationListResponse of(long unreadCount, PageResponse<NotificationResponse> page) {
        return new NotificationListResponse(
                unreadCount,
                page.content(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.hasNext());
    }
}
