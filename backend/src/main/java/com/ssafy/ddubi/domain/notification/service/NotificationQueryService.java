package com.ssafy.ddubi.domain.notification.service;

import com.ssafy.ddubi.domain.notification.dto.response.NotificationListResponse;
import com.ssafy.ddubi.domain.notification.dto.response.NotificationReadAllResponse;
import com.ssafy.ddubi.domain.notification.dto.response.NotificationReadResponse;
import com.ssafy.ddubi.domain.notification.dto.response.NotificationResponse;
import com.ssafy.ddubi.domain.notification.entity.Notification;
import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import com.ssafy.ddubi.domain.notification.repository.NotificationRepository;
import com.ssafy.ddubi.global.common.PageResponse;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.util.BusinessTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 알림 조회·읽음 (NTF-001~004).
 *
 * 알림 <b>생성·발송</b>은 여기가 아니라 {@code NotificationService} 가 맡는다(S15P21E202-38).
 * 조회는 사용자 요청으로 들어오고 생성은 추천 엔진이 부르는 것이라 진입 경로가 다르다.
 *
 * 지키는 도메인 규칙: 규칙 1 소유권 — 남의 알림은 403.
 */
@Service
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;

    public NotificationQueryService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    // ------------------------------------------------------------------
    // NTF-001 목록
    // ------------------------------------------------------------------

    /**
     * @param unreadOnly true 면 안읽음만
     * @param type       null 이면 전체 종류
     */
    @Transactional(readOnly = true)
    public NotificationListResponse list(Long userId, boolean unreadOnly,
                                         NotificationType type, Pageable pageable) {
        Page<Notification> page = findPage(userId, unreadOnly, type, pageable);
        // unreadCount 는 필터와 무관하게 "안읽음 전체" 다 — 뱃지 숫자이기 때문이다.
        long unreadCount = notificationRepository.countByUserIdAndReadAtIsNull(userId);
        return NotificationListResponse.of(unreadCount,
                PageResponse.of(page, NotificationResponse::from));
    }

    private Page<Notification> findPage(Long userId, boolean unreadOnly,
                                        NotificationType type, Pageable pageable) {
        if (unreadOnly && type != null) {
            return notificationRepository.findAllByUserIdAndTypeAndReadAtIsNull(userId, type, pageable);
        }
        if (unreadOnly) {
            return notificationRepository.findAllByUserIdAndReadAtIsNull(userId, pageable);
        }
        if (type != null) {
            return notificationRepository.findAllByUserIdAndType(userId, type, pageable);
        }
        return notificationRepository.findAllByUserId(userId, pageable);
    }

    // ------------------------------------------------------------------
    // NTF-002 상세
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public NotificationResponse get(Long userId, Long notificationId) {
        // 연결된 일정·추천 요약은 Phase 3(추천 엔티티) 이후 이 응답에 합류한다.
        return NotificationResponse.from(findOwned(userId, notificationId));
    }

    // ------------------------------------------------------------------
    // NTF-003 읽음 / NTF-004 전체 읽음
    // ------------------------------------------------------------------

    /** 멱등 — 이미 읽은 알림에 다시 요청해도 200 과 최초 읽은 시각을 준다. */
    @Transactional
    public NotificationReadResponse markRead(Long userId, Long notificationId) {
        Notification notification = findOwned(userId, notificationId);
        notification.markRead(BusinessTime.now());
        return new NotificationReadResponse(notification.getId(), notification.getReadAt());
    }

    @Transactional
    public NotificationReadAllResponse markAllRead(Long userId) {
        LocalDateTime readAt = BusinessTime.now();
        int updated = notificationRepository.markAllRead(userId, readAt);
        return new NotificationReadAllResponse(updated, readAt);
    }

    // ------------------------------------------------------------------

    /** 홈 요약(HOME-001)이 쓰는 안읽음 개수. */
    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    private Notification findOwned(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        if (!notification.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return notification;
    }
}
