package com.ssafy.ddubi.domain.notification.service;

import com.ssafy.ddubi.domain.notification.dto.response.NotificationListResponse;
import com.ssafy.ddubi.domain.notification.dto.response.NotificationReadResponse;
import com.ssafy.ddubi.domain.notification.entity.Notification;
import com.ssafy.ddubi.domain.notification.entity.NotificationStatus;
import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import com.ssafy.ddubi.domain.notification.repository.NotificationRepository;
import com.ssafy.ddubi.domain.schedule.entity.Importance;
import com.ssafy.ddubi.domain.schedule.entity.LocationSnapshot;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알림 조회·읽음 (NTF-001~004).
 *
 * 고정하는 것: 도메인 규칙 1(소유권), 규칙 9(억제해도 행은 남는다),
 * 일정 삭제 후에도 알림 이력이 유지되는 FK SET NULL 동작.
 */
class NotificationQueryIntegrationTest extends IntegrationTestSupport {

    private static final Pageable FIRST_PAGE =
            PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

    @Autowired
    private NotificationQueryService notificationQueryService;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private UserRepository userRepository;

    private User owner;
    private Long strangerId;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.builder().email("owner@ddubi.com").nickname("주인").build());
        strangerId = userRepository.save(
                User.builder().email("other@ddubi.com").nickname("남").build()).getId();
    }

    private Notification save(NotificationType type, NotificationStatus status, Schedule schedule) {
        return notificationRepository.save(Notification.builder()
                .user(owner)
                .schedule(schedule)
                .type(type)
                .title(type.name() + " 제목")
                .body("본문")
                .status(status)
                .sentAt(status == NotificationStatus.SENT ? LocalDateTime.now() : null)
                .build());
    }

    private Notification save(NotificationType type) {
        return save(type, NotificationStatus.SENT, null);
    }

    private Schedule saveSchedule() {
        return scheduleRepository.save(Schedule.builder()
                .user(owner)
                .title("회의")
                .origin(new LocationSnapshot("강남역", new BigDecimal("37.4979526"), new BigDecimal("127.0276241")))
                .destination(new LocationSnapshot("광화문", new BigDecimal("37.5716229"), new BigDecimal("126.9767879")))
                .arriveBy(LocalDateTime.now().plusDays(1).withNano(0))
                .importance(Importance.NORMAL)
                .considerLastTrip(false)
                .build());
    }

    // ------------------------------------------------------------------
    // NTF-001 목록
    // ------------------------------------------------------------------

    @Test
    @DisplayName("목록에 안읽음 개수가 함께 온다")
    void listWithUnreadCount() {
        save(NotificationType.DEPART_SOON);
        Notification read = save(NotificationType.MORNING_BRIEF);
        notificationQueryService.markRead(owner.getId(), read.getId());

        NotificationListResponse result =
                notificationQueryService.list(owner.getId(), false, null, FIRST_PAGE);

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.unreadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("unreadOnly 로 안읽음만 거를 수 있다")
    void filterUnreadOnly() {
        save(NotificationType.DEPART_SOON);
        Notification read = save(NotificationType.MORNING_BRIEF);
        notificationQueryService.markRead(owner.getId(), read.getId());

        NotificationListResponse result =
                notificationQueryService.list(owner.getId(), true, null, FIRST_PAGE);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).type()).isEqualTo(NotificationType.DEPART_SOON);
    }

    @Test
    @DisplayName("type 으로 종류를 거를 수 있다")
    void filterByType() {
        save(NotificationType.DEPART_SOON);
        save(NotificationType.LAST_TRIP);

        NotificationListResponse result = notificationQueryService.list(
                owner.getId(), false, NotificationType.LAST_TRIP, FIRST_PAGE);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).type()).isEqualTo(NotificationType.LAST_TRIP);
    }

    @Test
    @DisplayName("unreadCount 는 필터와 무관한 전체 안읽음 수다 — 뱃지 숫자이기 때문")
    void unreadCountIgnoresFilter() {
        save(NotificationType.DEPART_SOON);
        save(NotificationType.LAST_TRIP);

        NotificationListResponse filtered = notificationQueryService.list(
                owner.getId(), false, NotificationType.LAST_TRIP, FIRST_PAGE);

        assertThat(filtered.content()).hasSize(1);
        assertThat(filtered.unreadCount()).as("목록은 1건이어도 뱃지는 2").isEqualTo(2);
    }

    @Test
    @DisplayName("남의 알림은 목록에 섞이지 않는다")
    void listIsScopedToOwner() {
        save(NotificationType.DEPART_SOON);

        assertThat(notificationQueryService.list(strangerId, false, null, FIRST_PAGE).totalElements())
                .isZero();
    }

    @Test
    @DisplayName("발송이 억제된 알림도 목록에 나온다 (도메인 규칙 9)")
    void suppressedIsStillListed() {
        save(NotificationType.DEPART_SOON, NotificationStatus.SUPPRESSED, null);

        NotificationListResponse result =
                notificationQueryService.list(owner.getId(), false, null, FIRST_PAGE);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).status()).isEqualTo(NotificationStatus.SUPPRESSED);
        assertThat(result.content().get(0).sentAt()).as("보내지 않았으므로 발송 시각이 없다").isNull();
        assertThat(result.unreadCount()).as("억제된 알림도 안읽음으로 센다").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // NTF-002 상세 · 소유권
    // ------------------------------------------------------------------

    @Test
    @DisplayName("남의 알림은 상세·읽음 모두 403 (도메인 규칙 1)")
    void ownershipIsEnforced() {
        Long id = save(NotificationType.DEPART_SOON).getId();

        assertThatThrownBy(() -> notificationQueryService.get(strangerId, id))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN_RESOURCE);
        assertThatThrownBy(() -> notificationQueryService.markRead(strangerId, id))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN_RESOURCE);
    }

    @Test
    @DisplayName("없는 알림은 404")
    void notFound() {
        assertThatThrownBy(() -> notificationQueryService.get(owner.getId(), 99999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // NTF-003 읽음
    // ------------------------------------------------------------------

    @Test
    @DisplayName("읽음 처리는 멱등 — 두 번 불러도 최초 읽은 시각이 유지된다")
    void markReadIsIdempotent() {
        Long id = save(NotificationType.DEPART_SOON).getId();

        NotificationReadResponse first = notificationQueryService.markRead(owner.getId(), id);
        NotificationReadResponse second = notificationQueryService.markRead(owner.getId(), id);

        assertThat(first.readAt()).isNotNull();
        assertThat(second.readAt()).isEqualTo(first.readAt());
        assertThat(notificationQueryService.unreadCount(owner.getId())).isZero();
    }

    // ------------------------------------------------------------------
    // NTF-004 전체 읽음
    // ------------------------------------------------------------------

    @Test
    @DisplayName("전체 읽음은 안읽음만 갱신하고 그 건수를 준다")
    void markAllRead() {
        save(NotificationType.DEPART_SOON);
        save(NotificationType.LAST_TRIP);
        Notification already = save(NotificationType.MORNING_BRIEF);
        notificationQueryService.markRead(owner.getId(), already.getId());

        var result = notificationQueryService.markAllRead(owner.getId());

        assertThat(result.updatedCount()).as("이미 읽은 1건은 세지 않는다").isEqualTo(2);
        assertThat(notificationQueryService.unreadCount(owner.getId())).isZero();
    }

    @Test
    @DisplayName("전체 읽음은 남의 알림을 건드리지 않는다")
    void markAllReadIsScopedToOwner() {
        save(NotificationType.DEPART_SOON);
        Notification others = notificationRepository.save(Notification.builder()
                .user(userRepository.findById(strangerId).orElseThrow())
                .type(NotificationType.DEPART_SOON)
                .title("남의 알림").body("본문")
                .status(NotificationStatus.SENT)
                .build());

        notificationQueryService.markAllRead(owner.getId());

        assertThat(notificationRepository.findById(others.getId()).orElseThrow().getReadAt())
                .isNull();
    }

    @Test
    @DisplayName("안읽음이 없으면 전체 읽음은 0 건이다")
    void markAllReadWithNothingUnread() {
        assertThat(notificationQueryService.markAllRead(owner.getId()).updatedCount()).isZero();
    }

    // ------------------------------------------------------------------
    // 이력 보존
    // ------------------------------------------------------------------

    @Test
    @DisplayName("일정을 지워도 알림 이력은 남고 scheduleId 만 null 이 된다 (FK SET NULL)")
    void notificationSurvivesScheduleDeletion() {
        Schedule schedule = saveSchedule();
        Long notificationId = save(NotificationType.DEPART_SOON, NotificationStatus.SENT, schedule).getId();
        assertThat(notificationQueryService.get(owner.getId(), notificationId).scheduleId())
                .isEqualTo(schedule.getId());

        // SCH-005 는 soft 취소지만, 탈퇴 CASCADE 나 관리 작업으로 물리 삭제될 수 있다.
        scheduleRepository.delete(schedule);
        scheduleRepository.flush();

        assertThat(notificationQueryService.get(owner.getId(), notificationId).scheduleId())
                .as("알림 행은 남고 연결만 끊긴다").isNull();
    }
}
