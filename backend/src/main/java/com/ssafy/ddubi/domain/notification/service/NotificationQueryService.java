package com.ssafy.ddubi.domain.notification.service;

import com.ssafy.ddubi.domain.notification.dto.response.NotificationDeleteResponse;
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
import java.util.List;
import java.util.Objects;

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

    /**
     * 한 번에 지울 수 있는 알림 수. 화면의 선택 모드가 한 쪽(20건)을 넘겨 고르기 어렵고,
     * 열어 두면 ID 목록만으로 큰 UPDATE 를 만들 수 있다.
     */
    private static final int MAX_DELETE_AT_ONCE = 100;

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
        long unreadCount = notificationRepository.countByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId);
        return NotificationListResponse.of(unreadCount,
                PageResponse.of(page, NotificationResponse::from));
    }

    private Page<Notification> findPage(Long userId, boolean unreadOnly,
                                        NotificationType type, Pageable pageable) {
        if (unreadOnly && type != null) {
            return notificationRepository
                    .findAllByUserIdAndTypeAndReadAtIsNullAndDeletedAtIsNull(userId, type, pageable);
        }
        if (unreadOnly) {
            return notificationRepository.findAllByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId, pageable);
        }
        if (type != null) {
            return notificationRepository.findAllByUserIdAndTypeAndDeletedAtIsNull(userId, type, pageable);
        }
        return notificationRepository.findAllByUserIdAndDeletedAtIsNull(userId, pageable);
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
    // NTF-005 삭제 (알림함에서 숨기기)
    // ------------------------------------------------------------------

    /**
     * 알림을 알림함에서 지운다. 한 건이든 여러 건이든 같은 경로를 쓴다.
     *
     * <p>실제로 행을 지우지는 않는다. 이 표는 "언제 무엇을 보냈는가" 의 발송 이력이기도 해서,
     * 지워버리면 재발송 억제와 아침 브리핑의 "오늘 이미 받음" 판단이 근거를 잃는다.
     * 사용자 화면에서만 감춘다(V12).
     *
     * <p>먼저 전부 확인하고 그다음에 숨긴다. 일부만 지워진 상태로 끝나면 화면이 무엇을
     * 지웠는지 알 수 없다.
     *
     * @throws BusinessException 없는 알림이면 404, 남의 알림이면 403 — 한 건이라도 걸리면 아무것도 지우지 않는다
     */
    @Transactional
    public NotificationDeleteResponse delete(Long userId, List<Long> notificationIds) {
        List<Long> ids = notificationIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지울 알림을 하나 이상 지정해 주세요.");
        }
        if (ids.size() > MAX_DELETE_AT_ONCE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "한 번에 %d건까지 지울 수 있습니다.".formatted(MAX_DELETE_AT_ONCE));
        }

        List<Notification> found = notificationRepository.findAllById(ids);
        if (found.size() != ids.size()) {
            // 어느 것이 없는지는 알려주지 않는다 — 남의 알림 ID 를 찍어보는 데 쓰일 수 있다.
            throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        for (Notification notification : found) {
            if (!notification.isOwnedBy(userId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
            }
        }

        LocalDateTime deletedAt = BusinessTime.now();
        int deletedCount = notificationRepository.hide(userId, ids, deletedAt);
        long unreadCount = notificationRepository.countByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId);
        return new NotificationDeleteResponse(ids, deletedCount, unreadCount, deletedAt);
    }

    // ------------------------------------------------------------------

    /** 홈 요약(HOME-001)이 쓰는 안읽음 개수. */
    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId);
    }

    private Notification findOwned(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        if (!notification.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        // 사용자가 지운 알림은 이력으로만 남는다. 조회·읽음 처리에서는 없는 것으로 다룬다.
        if (notification.isHidden()) {
            throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        return notification;
    }
}
