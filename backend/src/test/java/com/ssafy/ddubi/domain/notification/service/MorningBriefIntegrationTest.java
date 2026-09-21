package com.ssafy.ddubi.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.ssafy.ddubi.domain.notification.entity.Notification;
import com.ssafy.ddubi.domain.notification.entity.NotificationStatus;
import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import com.ssafy.ddubi.domain.notification.repository.NotificationRepository;
import com.ssafy.ddubi.domain.schedule.dto.request.LocationRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleCreateRequest;
import com.ssafy.ddubi.domain.schedule.service.ScheduleService;
import com.ssafy.ddubi.domain.user.dto.request.DeviceTokenRegisterRequest;
import com.ssafy.ddubi.domain.user.entity.DevicePlatform;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.domain.user.service.DeviceTokenService;
import com.ssafy.ddubi.global.push.PushResult;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 아침 브리핑 (REQ-NTF-003).
 *
 * <h3>시각을 인자로 넘긴다</h3>
 *
 * {@code dispatchDue(now)} 가 시각을 받으므로 실제 시계에 기대지 않는다. 실제 시각에 의존하면 자정 근처에 돌릴 때 "오늘 일정" 의 범위가 갈라져
 * 테스트가 흔들린다.
 *
 * <p>날짜는 <b>내일</b>로 잡는다. 일정 생성이 도착 시각을 미래로 강제해서 과거 날짜는 쓸 수 없다. 하루 한 번 판정은 저장 시각이 아니라 억제 키(날짜 포함)로
 * 하므로, 날짜가 미래여도 그대로 동작한다.
 */
class MorningBriefIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate DAY = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
    private static final LocalTime BRIEF_AT = LocalTime.of(7, 0);

    /** 브리핑 시각 직후. 여기서 대상이 되어야 한다. */
    private static final LocalDateTime JUST_AFTER = DAY.atTime(7, 1);

    @Autowired private MorningBriefService morningBriefService;
    @Autowired private NotificationRepository notifications;
    @Autowired private UserRepository users;
    @Autowired private ScheduleService scheduleService;
    @Autowired private DeviceTokenService deviceTokens;
    @Autowired private MorningBriefScheduler scheduler;

    private User user;

    @BeforeEach
    void setUp() {
        given(pushSender.send(any())).willReturn(PushResult.ok());
        user = users.save(User.builder().email("brief@ddubi.com").nickname("브리핑").build());
        user.changeBriefingTime(BRIEF_AT);
        users.saveAndFlush(user);
        deviceTokens.register(
                user.getId(), new DeviceTokenRegisterRequest("fcm-brief", DevicePlatform.ANDROID));
    }

    private long schedule(String title, LocalTime arriveAt) {
        return scheduleService
                .create(
                        user.getId(),
                        new ScheduleCreateRequest(
                                title,
                                new LocationRequest(
                                        "강남",
                                        new BigDecimal("37.4979"),
                                        new BigDecimal("127.0276")),
                                new LocationRequest(
                                        "역삼",
                                        new BigDecimal("37.5006"),
                                        new BigDecimal("127.0364")),
                                DAY.atTime(arriveAt),
                                null,
                                null,
                                null))
                .id();
    }

    private List<Notification> briefs() {
        return notifications.findAll().stream()
                .filter(notification -> notification.getType() == NotificationType.MORNING_BRIEF)
                .toList();
    }

    // ------------------------------------------------------------------
    // 보내는 경우
    // ------------------------------------------------------------------

    @Test
    @DisplayName("브리핑 시각이 지나면 그날 남은 일정을 한 건으로 묶어 보낸다")
    void sendsOneSummaryForTheDay() {
        schedule("회사", LocalTime.of(9, 0));
        schedule("병원", LocalTime.of(14, 0));

        assertThat(morningBriefService.dispatchDue(JUST_AFTER)).isEqualTo(1);

        assertThat(briefs())
                .singleElement()
                .satisfies(
                        brief -> {
                            assertThat(brief.getStatus()).isEqualTo(NotificationStatus.SENT);
                            assertThat(brief.getTitle()).isEqualTo("오늘 일정 2건");
                            assertThat(brief.getBody()).isEqualTo("09:00 회사 · 14:00 병원 · 오늘 일정을 확인해 보세요");
                        });
    }

    @Test
    @DisplayName("일정이 많으면 앞 세 건만 적고 나머지는 개수로 줄인다")
    void shortensWhenThereAreManySchedules() {
        schedule("회사", LocalTime.of(9, 0));
        schedule("점심", LocalTime.of(12, 0));
        schedule("병원", LocalTime.of(14, 0));
        schedule("저녁약속", LocalTime.of(19, 0));
        schedule("운동", LocalTime.of(21, 0));

        morningBriefService.dispatchDue(JUST_AFTER);

        assertThat(briefs())
                .singleElement()
                .satisfies(
                        brief -> {
                            assertThat(brief.getTitle()).isEqualTo("오늘 일정 5건");
                            assertThat(brief.getBody())
                                    .isEqualTo("09:00 회사 · 12:00 점심 · 14:00 병원 외 2건 · 오늘 일정을 확인해 보세요");
                        });
    }

    @Test
    @DisplayName("이미 지난 일정은 빼고 남은 것만 센다")
    void countsOnlyWhatIsLeftToday() {
        schedule("새벽운동", LocalTime.of(6, 0));
        schedule("회사", LocalTime.of(9, 0));

        morningBriefService.dispatchDue(JUST_AFTER);

        assertThat(briefs())
                .singleElement()
                .satisfies(
                        brief -> {
                            assertThat(brief.getTitle())
                                    .as("07:01 기준으로 06:00 은 이미 지났다")
                                    .isEqualTo("오늘 일정 1건");
                            assertThat(brief.getBody()).isEqualTo("09:00 회사 · 오늘 일정을 확인해 보세요");
                        });
    }

    @Test
    @DisplayName("탭하면 갈 곳으로 가장 이른 일정을 붙인다")
    void attachesTheEarliestSchedule() {
        long earliest = schedule("회사", LocalTime.of(9, 0));
        long later = schedule("병원", LocalTime.of(14, 0));

        morningBriefService.dispatchDue(JUST_AFTER);

        assertThat(briefs())
                .singleElement()
                .satisfies(
                        brief ->
                                assertThat(brief.scheduleId())
                                        .as("브리핑은 여러 일정을 묶지만 갈 곳은 하나여야 한다")
                                        .isEqualTo(earliest)
                                        .isNotEqualTo(later));
    }

    // ------------------------------------------------------------------
    // 보내지 않는 경우
    // ------------------------------------------------------------------

    /**
     * Redis 억제 키는 TTL 이 10분이라 하루를 덮지 못하고 서버를 재시작하면 사라진다. 그래서 오늘 이미 만든 행이 있는지로 판단한다 — 분마다 도는 스케줄러가
     * 하루 종일 같은 브리핑을 쏘면 안 된다.
     */
    @Test
    @DisplayName("하루에 한 번만 보낸다 — 스케줄러가 분마다 돌아도")
    void sendsAtMostOncePerDay() {
        schedule("회사", LocalTime.of(9, 0));

        assertThat(morningBriefService.dispatchDue(JUST_AFTER)).isEqualTo(1);
        assertThat(morningBriefService.dispatchDue(DAY.atTime(7, 2))).isZero();
        assertThat(morningBriefService.dispatchDue(DAY.atTime(8, 30))).isZero();

        assertThat(briefs()).hasSize(1);
    }

    @Test
    @DisplayName("브리핑 시각 전에는 보내지 않는다")
    void waitsUntilTheBriefingTime() {
        schedule("회사", LocalTime.of(9, 0));

        assertThat(morningBriefService.dispatchDue(DAY.atTime(6, 59))).isZero();
        assertThat(briefs()).isEmpty();
    }

    /** 서버가 아침 내내 죽어 있다가 저녁에 올라왔다고 "아침 브리핑" 을 보내면 안 된다. */
    @Test
    @DisplayName("너무 늦으면 그날은 건너뛴다")
    void skipsWhenTooLate() {
        schedule("저녁약속", LocalTime.of(19, 0));

        assertThat(morningBriefService.dispatchDue(DAY.atTime(8, 59)))
                .as("2시간 이내면 늦어도 보낸다")
                .isEqualTo(1);

        notifications.deleteAll();
        assertThat(morningBriefService.dispatchDue(DAY.atTime(9, 1)))
                .as("2시간을 넘기면 보내지 않는다")
                .isZero();
        assertThat(briefs()).isEmpty();
    }

    /** 빈 브리핑으로 폰을 울릴 이유가 없다. 행도 만들지 않으므로, 그날 늦게 일정을 추가하면 아직 창 안이라면 그때 브리핑이 나간다. */
    @Test
    @DisplayName("오늘 남은 일정이 없으면 보내지 않고, 일정이 생기면 그때 보낸다")
    void sendsNothingUntilThereIsSomethingToSay() {
        assertThat(morningBriefService.dispatchDue(JUST_AFTER)).isZero();
        assertThat(notifications.count()).as("행도 만들지 않는다").isZero();

        schedule("갑자기 생긴 일정", LocalTime.of(11, 0));

        assertThat(morningBriefService.dispatchDue(DAY.atTime(7, 30))).isEqualTo(1);
        assertThat(briefs()).hasSize(1);
    }

    @Test
    @DisplayName("브리핑 시각을 정하지 않은 사용자는 대상이 아니다")
    void skipsUsersWithoutBriefingTime() {
        user.changeBriefingTime(null);
        users.saveAndFlush(user);
        schedule("회사", LocalTime.of(9, 0));

        assertThat(morningBriefService.dispatchDue(JUST_AFTER)).isZero();
        assertThat(notifications.count()).isZero();
    }

    /**
     * 22시 브리핑을 다음 날 0시 30분에 보내면 안 된다. 조회가 {@code briefingTime <= 지금 시각} 이라 자정을 넘으면 어제 것은 대상에서 빠진다 —
     * 놓친 브리핑을 다음 날 배달하지 않는다.
     */
    @Test
    @DisplayName("자정을 넘기면 어제 브리핑을 배달하지 않는다")
    void doesNotDeliverYesterdaysBrief() {
        user.changeBriefingTime(LocalTime.of(22, 0));
        users.saveAndFlush(user);
        schedule("회사", LocalTime.of(23, 0));

        assertThat(morningBriefService.dispatchDue(DAY.plusDays(1).atTime(0, 30))).isZero();
        assertThat(notifications.count()).isZero();
    }

    @Test
    @DisplayName("남의 브리핑이 내 알림함에 들어가지 않는다")
    void briefsAreDeliveredToTheirOwnerOnly() {
        User other = users.save(User.builder().email("other@ddubi.com").nickname("다른 사람").build());
        other.changeBriefingTime(BRIEF_AT);
        users.saveAndFlush(other);
        schedule("내 일정", LocalTime.of(9, 0));
        scheduleService.create(
                other.getId(),
                new ScheduleCreateRequest(
                        "남의 일정",
                        new LocationRequest(
                                "강남", new BigDecimal("37.4979"), new BigDecimal("127.0276")),
                        new LocationRequest(
                                "역삼", new BigDecimal("37.5006"), new BigDecimal("127.0364")),
                        DAY.atTime(15, 0),
                        null,
                        null,
                        null));

        assertThat(morningBriefService.dispatchDue(JUST_AFTER)).isEqualTo(2);

        assertThat(briefs()).hasSize(2);
        assertThat(briefs())
                .filteredOn(brief -> brief.isOwnedBy(user.getId()))
                .singleElement()
                .satisfies(mine -> assertThat(mine.getBody()).isEqualTo("09:00 내 일정 · 오늘 일정을 확인해 보세요"));
        assertThat(briefs())
                .filteredOn(brief -> brief.isOwnedBy(other.getId()))
                .singleElement()
                .satisfies(theirs -> assertThat(theirs.getBody()).isEqualTo("15:00 남의 일정 · 오늘 일정을 확인해 보세요"));
    }

    /**
     * 스케줄러가 시각을 어디서 얻느냐가 이 기능의 전부다.
     *
     * <p>{@code BusinessTime.now()} 는 {@code LocalDateTime.now()} 라 <b>JVM 기본 시간대</b>를 쓴다. JVM 이
     * UTC 면 07:00 브리핑이 한국 시간 16:00 에 나간다. 운영은 {@code TZ=Asia/Seoul} 이라 안 드러나지만, 알림 발송 시각을 그 환경변수
     * 하나에 기대게 두지 않는다.
     *
     * <p>JVM 이 KST 면 아무것도 증명하지 못하므로 <b>건너뛴다.</b> 실패시키면 내 PC 에서 빨간불이 뜬다. CI 는 UTC 라 거기서 실제로 돈다.
     */
    @Test
    @DisplayName("스케줄러는 JVM 시간대가 아니라 KST 로 판정한다")
    void schedulerJudgesInKstNotJvmZone() {
        Assumptions.assumeFalse(
                ZoneId.systemDefault().getRules().equals(ZoneId.of("Asia/Seoul").getRules()),
                "JVM 이 KST 라 건너뛴다. -Duser.timezone=UTC 로 돌리면 실제로 검증된다");
        LocalDateTime nowKst = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        // 자정이 가까우면 '오늘 남은 일정' 을 만들 수 없다. 그때는 판정할 거리가 없다.
        Assumptions.assumeTrue(
                nowKst.toLocalTime().isAfter(LocalTime.of(0, 10))
                        && nowKst.toLocalTime().isBefore(LocalTime.of(22, 0)),
                "자정 근처에는 오늘 남은 일정을 만들 수 없어 건너뛴다");
        // 15:00 KST 근처만 피한다. briefing_time 은 TIME 컬럼이라 JVM 이 UTC 면 +9h 시프트가
        // 걸리는데, 기준 시각과 브리핑 시각이 그 경계(=24:00)를 사이에 두면 한쪽만 한 바퀴 돌아
        // SQL 대소 비교가 뒤집힌다. 이 테스트가 볼 문제가 아니라 별도 이슈다.
        Assumptions.assumeFalse(
                nowKst.toLocalTime().isAfter(LocalTime.of(14, 55))
                        && nowKst.toLocalTime().isBefore(LocalTime.of(15, 5)),
                "TIME 컬럼 시프트가 자정을 넘는 구간이라 건너뛴다");

        user.changeBriefingTime(nowKst.toLocalTime().minusMinutes(1).withSecond(0).withNano(0));
        users.saveAndFlush(user);
        scheduleService.create(
                user.getId(),
                new ScheduleCreateRequest(
                        "오늘 남은 일정",
                        new LocationRequest(
                                "강남", new BigDecimal("37.4979"), new BigDecimal("127.0276")),
                        new LocationRequest(
                                "역삼", new BigDecimal("37.5006"), new BigDecimal("127.0364")),
                        nowKst.toLocalDate().atTime(23, 30),
                        null,
                        null,
                        null));

        scheduler.tick();

        assertThat(briefs()).as("KST 로 판정하면 방금 지난 브리핑이 나가야 한다").hasSize(1);
    }
}
