package com.ssafy.ddubi.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.ddubi.domain.schedule.dto.request.LocationRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleCreateRequest;
import com.ssafy.ddubi.domain.schedule.dto.response.HomeResponse;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.util.BusinessTime;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** HOME-001 홈 요약 + SCH-006 연속 일정 충돌. */
class HomeAndConflictIntegrationTest extends IntegrationTestSupport {

    private static final LocationRequest GANGNAM =
            new LocationRequest("강남역", new BigDecimal("37.4979526"), new BigDecimal("127.0276241"));
    private static final LocationRequest SANGGYE =
            new LocationRequest("상계역", new BigDecimal("37.6598"), new BigDecimal("127.0733"));

    @Autowired private HomeService homeService;
    @Autowired private ScheduleConflictService conflictService;
    @Autowired private ScheduleService scheduleService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long userId;
    private LocalDateTime base;

    @BeforeEach
    void setUp() {
        userId =
                userRepository
                        .save(User.builder().email("home@ddubi.com").nickname("홈").build())
                        .getId();
        base = BusinessTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
    }

    private void schedule(
            String title, LocationRequest origin, LocationRequest dest, LocalDateTime arriveBy) {
        scheduleService.create(
                userId, new ScheduleCreateRequest(title, origin, dest, arriveBy, null, null));
    }

    // ------------------------------------------------------------------
    // SCH-006
    // ------------------------------------------------------------------

    @Test
    @DisplayName("이동이 물리적으로 불가능한 연속 일정을 잡아낸다")
    void detectsConflict() {
        // 강남 도착 09:00 → 5분 뒤 상계(직선 ~18km) 도착은 불가능하다.
        schedule("강남 회의", GANGNAM, GANGNAM, base);
        schedule("상계 약속", SANGGYE, SANGGYE, base.plusMinutes(5));

        var result = conflictService.findConflicts(userId, base.minusHours(1), base.plusHours(3));

        assertThat(result.conflicts()).hasSize(1);
        var conflict = result.conflicts().get(0);
        assertThat(conflict.availableMin()).isEqualTo(5);
        assertThat(conflict.shortageMin()).isPositive();
        assertThat(conflict.severity()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("여유가 충분하면 충돌이 아니다")
    void noConflictWhenEnoughTime() {
        schedule("강남 회의", GANGNAM, GANGNAM, base);
        schedule("상계 약속", SANGGYE, SANGGYE, base.plusHours(4));

        var result = conflictService.findConflicts(userId, base.minusHours(1), base.plusHours(5));

        assertThat(result.conflicts()).isEmpty();
    }

    @Test
    @DisplayName("같은 자리 연속 일정은 이동 시간이 0 이라 충돌이 아니다")
    void sameSpotIsFine() {
        schedule("1차 회의", GANGNAM, GANGNAM, base);
        schedule("2차 회의", GANGNAM, GANGNAM, base.plusMinutes(1));

        assertThat(
                        conflictService
                                .findConflicts(userId, base.minusHours(1), base.plusHours(1))
                                .conflicts())
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // HOME-001
    // ------------------------------------------------------------------

    @Test
    @DisplayName("다음 일정은 기준 시각 이후 가장 이른 UPCOMING 이다")
    void nextSchedule() {
        schedule("아침 회의", GANGNAM, GANGNAM, base);
        schedule("점심 약속", GANGNAM, GANGNAM, base.plusHours(3));

        HomeResponse home = homeService.summary(userId, base.minusHours(1));

        assertThat(home.nextSchedule().title()).isEqualTo("아침 회의");
        assertThat(home.todaySchedules()).hasSize(2);
    }

    @Test
    @DisplayName("일정이 없으면 nextSchedule 은 null 이고 나머지 필드는 채워진다")
    void emptyHome() {
        HomeResponse home = homeService.summary(userId, BusinessTime.now());

        assertThat(home.nextSchedule()).isNull();
        assertThat(home.todaySchedules()).isEmpty();
        assertThat(home.unreadNotificationCount()).isZero();
        // 파이프라인 활성화 이력이 없는 초기 상태.
        assertThat(home.dataState()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("dataState 는 마지막 기준선 활성화 시각으로 판정한다 — 최근이면 NORMAL, 오래되면 STALE")
    void dataStateFollowsActivation() {
        jdbcTemplate.update(
                "INSERT INTO pipeline_config (config_key, config_value) VALUES ('baseline_last_activated_at', ?)",
                BusinessTime.now().minusHours(1).toString());
        assertThat(homeService.summary(userId, BusinessTime.now()).dataState()).isEqualTo("NORMAL");

        jdbcTemplate.update(
                "UPDATE pipeline_config SET config_value = ? WHERE config_key = 'baseline_last_activated_at'",
                BusinessTime.now().minusDays(5).toString());
        assertThat(homeService.summary(userId, BusinessTime.now()).dataState()).isEqualTo("STALE");
    }
}
