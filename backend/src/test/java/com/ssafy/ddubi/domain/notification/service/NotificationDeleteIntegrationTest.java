package com.ssafy.ddubi.domain.notification.service;

import com.ssafy.ddubi.domain.notification.dto.response.NotificationDeleteResponse;
import com.ssafy.ddubi.domain.notification.entity.Notification;
import com.ssafy.ddubi.domain.notification.entity.NotificationStatus;
import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import com.ssafy.ddubi.domain.notification.repository.NotificationRepository;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알림 삭제 (NTF-005).
 *
 * <p>고정하는 것
 * <ul>
 *   <li>지운 알림은 목록·상세·안읽음 뱃지에서 사라진다.</li>
 *   <li>발송 이력은 남는다 — 지운 뒤에도 재발송 억제가 같은 알림을 다시 보내지 않는다.</li>
 *   <li>하나라도 없거나 남의 것이면 아무것도 지우지 않는다(전부 아니면 전무).</li>
 * </ul>
 */
class NotificationDeleteIntegrationTest extends IntegrationTestSupport {

    private static final Pageable FIRST_PAGE =
            PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

    @Autowired
    private NotificationQueryService notificationQueryService;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private UserRepository userRepository;

    private User owner;
    private User stranger;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.builder().email("owner@ddubi.com").nickname("주인").build());
        stranger = userRepository.save(User.builder().email("other@ddubi.com").nickname("남").build());
    }

    private Notification save(User user, String suppressKey) {
        return notificationRepository.save(Notification.builder()
                .user(user)
                .type(NotificationType.DEPART_SOON)
                .title("곧 출발")
                .body("본문")
                .suppressKey(suppressKey)
                .status(NotificationStatus.SENT)
                .sentAt(LocalDateTime.now())
                .build());
    }

    private Notification save(User user) {
        return save(user, null);
    }

    private long visibleCount() {
        return notificationQueryService
                .list(owner.getId(), false, null, FIRST_PAGE).totalElements();
    }

    // ------------------------------------------------------------------
    // 기본 동작
    // ------------------------------------------------------------------

    @Test
    @DisplayName("지운 알림은 목록에서 사라진다")
    void deletedNotificationDisappearsFromList() {
        Long kept = save(owner).getId();
        Long removed = save(owner).getId();
        assertThat(visibleCount()).isEqualTo(2);

        notificationQueryService.delete(owner.getId(), List.of(removed));

        assertThat(notificationQueryService.list(owner.getId(), false, null, FIRST_PAGE).content())
                .extracting("id")
                .containsExactly(kept);
    }

    @Test
    @DisplayName("안읽은 알림을 지우면 뱃지 숫자가 줄어든다")
    void deletingUnreadDropsTheBadge() {
        save(owner);
        Long removed = save(owner).getId();
        assertThat(notificationQueryService.unreadCount(owner.getId())).isEqualTo(2);

        NotificationDeleteResponse response =
                notificationQueryService.delete(owner.getId(), List.of(removed));

        assertThat(response.unreadCount()).isEqualTo(1);
        assertThat(notificationQueryService.unreadCount(owner.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("지운 알림은 상세 조회도 404 다")
    void deletedNotificationIsNotFound() {
        Long id = save(owner).getId();
        notificationQueryService.delete(owner.getId(), List.of(id));

        assertThatThrownBy(() -> notificationQueryService.get(owner.getId(), id))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("여러 건을 한 번에 지운다")
    void deletesManyAtOnce() {
        List<Long> ids = IntStream.range(0, 3).mapToObj(i -> save(owner).getId()).toList();
        save(owner);

        NotificationDeleteResponse response = notificationQueryService.delete(owner.getId(), ids);

        assertThat(response.deletedCount()).isEqualTo(3);
        assertThat(response.deletedIds()).containsExactlyElementsOf(ids);
        assertThat(visibleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 알림을 두 번 지워도 오류가 아니고 처음 지운 시각이 남는다")
    void deletingTwiceIsIdempotent() {
        Long id = save(owner).getId();
        NotificationDeleteResponse first = notificationQueryService.delete(owner.getId(), List.of(id));
        LocalDateTime firstDeletedAt =
                notificationRepository.findById(id).orElseThrow().getDeletedAt();

        NotificationDeleteResponse second = notificationQueryService.delete(owner.getId(), List.of(id));

        assertThat(first.deletedCount()).isEqualTo(1);
        assertThat(second.deletedCount()).as("두 번째는 새로 숨긴 것이 없다").isZero();
        assertThat(notificationRepository.findById(id).orElseThrow().getDeletedAt())
                .isEqualTo(firstDeletedAt);
    }

    @Test
    @DisplayName("중복된 ID 를 보내도 한 번만 센다")
    void duplicateIdsCountOnce() {
        Long id = save(owner).getId();

        NotificationDeleteResponse response =
                notificationQueryService.delete(owner.getId(), List.of(id, id, id));

        assertThat(response.deletedCount()).isEqualTo(1);
        assertThat(response.deletedIds()).containsExactly(id);
    }

    // ------------------------------------------------------------------
    // 전부 아니면 전무
    // ------------------------------------------------------------------

    @Test
    @DisplayName("남의 알림이 섞이면 403 이고 내 알림도 지워지지 않는다")
    void strangerNotificationBlocksTheWholeRequest() {
        Long mine = save(owner).getId();
        Long theirs = save(stranger).getId();

        assertThatThrownBy(() -> notificationQueryService.delete(owner.getId(), List.of(mine, theirs)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN_RESOURCE);

        assertThat(notificationRepository.findById(mine).orElseThrow().getDeletedAt())
                .as("한 건이라도 막히면 아무것도 지우지 않는다").isNull();
    }

    @Test
    @DisplayName("없는 ID 가 섞이면 404 이고 내 알림도 지워지지 않는다")
    void missingIdBlocksTheWholeRequest() {
        Long mine = save(owner).getId();

        assertThatThrownBy(() ->
                notificationQueryService.delete(owner.getId(), List.of(mine, 9_999_999L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);

        assertThat(notificationRepository.findById(mine).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("빈 목록은 400 이다")
    void emptyRequestIsRejected() {
        assertThatThrownBy(() -> notificationQueryService.delete(owner.getId(), List.of()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("한 번에 100건을 넘기면 400 이다")
    void tooManyIdsRejected() {
        List<Long> ids = IntStream.rangeClosed(1, 101).mapToObj(Long::valueOf).toList();

        assertThatThrownBy(() -> notificationQueryService.delete(owner.getId(), ids))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    // ------------------------------------------------------------------
    // 이력 보존 — 이 기능의 존재 이유
    // ------------------------------------------------------------------

    @Test
    @DisplayName("지워도 발송 이력은 남아 같은 알림이 다시 오지 않는다")
    void deletingKeepsTheSuppressionHistory() {
        String suppressKey = "brief-2026-09-18";
        Long id = save(owner, suppressKey).getId();

        notificationQueryService.delete(owner.getId(), List.of(id));

        assertThat(notificationRepository
                .findUserIdsNotifiedWith(NotificationType.DEPART_SOON, suppressKey))
                .as("억제 판단은 지운 알림까지 본다").containsExactly(owner.getId());
        assertThat(notificationRepository.findById(id))
                .as("행 자체는 남는다").isPresent();
    }

    @Test
    @DisplayName("전체 읽음은 지운 알림을 건드리지 않는다")
    void markAllReadSkipsDeleted() {
        Long removed = save(owner).getId();
        save(owner);
        notificationQueryService.delete(owner.getId(), List.of(removed));

        assertThat(notificationQueryService.markAllRead(owner.getId()).updatedCount())
                .as("남아 있는 1건만 읽음 처리된다").isEqualTo(1);
        assertThat(notificationRepository.findById(removed).orElseThrow().getReadAt())
                .as("지운 알림은 안 읽은 채로 이력에 남는다").isNull();
    }
}
