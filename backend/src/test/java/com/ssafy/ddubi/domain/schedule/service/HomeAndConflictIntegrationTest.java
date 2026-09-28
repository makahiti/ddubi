package com.ssafy.ddubi.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.ddubi.domain.schedule.dto.request.LocationRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleCreateRequest;
import com.ssafy.ddubi.domain.schedule.dto.response.HomeResponse;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
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
    @Autowired private ScheduleRepository scheduleRepository;
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

    private Long schedule(
            String title, LocationRequest origin, LocationRequest dest, LocalDateTime arriveBy) {
        return scheduleService
                .create(
                        userId,
                        new ScheduleCreateRequest(title, origin, dest, arriveBy, null, null))
                .id();
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

    /**
     * 홈에서 완료한 일정이 사라지던 버그를 막는다.
     *
     * <p>앱은 홈을 열 때 이 API 를, 날짜 화살표로 옮길 때는 목록 API 를 쓴다. 이 API 가 UPCOMING 만 주면 같은 날짜인데 두 화면이 서로 다른
     * 목록을 보여준다 — 홈을 다시 누를 때마다 완료한 일정이 나타났다 사라진다.
     */
    @Test
    @DisplayName("오늘 목록에 완료된 일정은 남고 취소된 일정만 빠진다")
    void todayKeepsDoneDropsCanceled() {
        Long done = schedule("끝난 회의", GANGNAM, GANGNAM, base);
        Long canceled = schedule("접은 약속", GANGNAM, GANGNAM, base.plusHours(1));
        schedule("남은 일정", GANGNAM, GANGNAM, base.plusHours(2));
        // 이 검사는 트랜잭션 안에서 돌지 않는다(@SpringBootTest 만 있고 @Transactional 이 없다).
        // findById 로 꺼낸 일정은 영속 상태가 아니라서, 상태만 바꾸고 flush 해도 DB 에 남지
        // 않는다. 그래서 취소한 일정이 그대로 조회돼 이 검사가 깨졌다. 저장까지 해야 한다.
        Schedule doneSchedule = scheduleRepository.findById(done).orElseThrow();
        doneSchedule.markDone();
        scheduleRepository.save(doneSchedule);

        Schedule canceledSchedule = scheduleRepository.findById(canceled).orElseThrow();
        canceledSchedule.cancel();
        scheduleRepository.save(canceledSchedule);
        scheduleRepository.flush();

        HomeResponse home = homeService.summary(userId, base.minusHours(1));

        assertThat(home.todaySchedules()).extracting("title").containsExactly("끝난 회의", "남은 일정");
        // 다음 일정은 여전히 아직 안 지난 UPCOMING 이어야 한다 — 완료한 일정을 다시 안내하지 않는다.
        assertThat(home.nextSchedule().title()).isEqualTo("남은 일정");
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
