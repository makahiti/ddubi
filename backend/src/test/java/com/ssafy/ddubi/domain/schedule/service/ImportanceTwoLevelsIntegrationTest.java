package com.ssafy.ddubi.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.ddubi.domain.schedule.dto.request.LocationRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.RepeatCreateRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleCreateRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleUpdateRequest;
import com.ssafy.ddubi.domain.schedule.entity.Importance;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.util.BusinessTime;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 중요도 2단계 (B7).
 *
 * <h3>DB 는 건드리지 않는다</h3>
 *
 * {@code CRITICAL} 은 ENUM 에 그대로 남아 있고 기존 행도 그대로다. 지우려면 되돌릴 수 없는 {@code UPDATE} 가 필요해서 사용자 승인 대상이다.
 * 그때까지 경계에서 흡수한다 — 들어올 때 HIGH 로 접고, 나갈 때 HIGH 로 보여 준다. 이 테스트가 그 두 방향을 고정한다.
 */
class ImportanceTwoLevelsIntegrationTest extends IntegrationTestSupport {

    private static final LocationRequest HOME =
            new LocationRequest("집", new BigDecimal("37.5013068"), new BigDecimal("127.0396597"));
    private static final LocationRequest OFFICE =
            new LocationRequest("회사", new BigDecimal("37.5716229"), new BigDecimal("126.9767879"));

    @Autowired private ScheduleService scheduleService;
    @Autowired private ScheduleRepeatService repeatService;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long ownerId;

    /**
     * 한 검사 안에서 만드는 일정들이 <b>같은</b> 도착 시각을 쓰게 한다.
     *
     * <p>{@code BusinessTime.now()} 는 초 단위다. 일정을 만들 때마다 다시 읽으면 두 번의 생성 사이에 초가 넘어가는 순간 도착 시각이 1초
     * 어긋나고, 그러면 출발 예정시각도 1초 어긋난다. "중요도를 바꿔도 출발 예정시각은 같다" 는 검사가 그 1초 때문에 가끔 빨간불이 됐다. 검사 대상은 중요도이지
     * 시계가 아니므로, 시각은 여기서 한 번만 정한다.
     */
    private LocalDateTime arriveBy;

    @BeforeEach
    void setUp() {
        ownerId =
                userRepository
                        .save(User.builder().email("importance@ddubi.com").nickname("중요도").build())
                        .getId();
        arriveBy = BusinessTime.now().plusDays(1).withNano(0);
    }

    private Long createSchedule(Importance importance) {
        return scheduleService
                .create(
                        ownerId,
                        new ScheduleCreateRequest(
                                "회의",
                                HOME,
                                OFFICE,
                                arriveBy,
                                importance,
                                false,
                                arriveBy.minusHours(1)))
                .id();
    }

    private String storedImportance(Long scheduleId) {
        return jdbcTemplate.queryForObject(
                "SELECT importance FROM schedule WHERE id = ?", String.class, scheduleId);
    }

    // ------------------------------------------------------------------
    // 들어올 때 — CRITICAL 을 HIGH 로 접는다
    // ------------------------------------------------------------------

    /** 400 으로 거절하지 않는다. 거절하면 앱이 업데이트될 때까지 일정 등록이 막힌다. */
    @Test
    @DisplayName("CRITICAL 로 보내도 거절하지 않고 HIGH 로 저장한다")
    void criticalIsAcceptedAndStoredAsHigh() {
        Long scheduleId = createSchedule(Importance.CRITICAL);

        assertThat(storedImportance(scheduleId)).as("새 CRITICAL 행이 더 생기면 안 된다").isEqualTo("HIGH");
        assertThat(scheduleService.get(ownerId, scheduleId).importance())
                .isEqualTo(Importance.HIGH);
    }

    @Test
    @DisplayName("NORMAL 과 HIGH 는 그대로 저장된다")
    void twoLevelsAreStoredVerbatim() {
        assertThat(storedImportance(createSchedule(Importance.NORMAL))).isEqualTo("NORMAL");
        assertThat(storedImportance(createSchedule(Importance.HIGH))).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("중요도를 안 보내면 NORMAL 이다")
    void defaultsToNormal() {
        assertThat(storedImportance(createSchedule(null))).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("수정으로도 CRITICAL 을 되살릴 수 없다")
    void updateCannotReintroduceCritical() {
        Long scheduleId = createSchedule(Importance.NORMAL);

        ScheduleUpdateRequest update = new ScheduleUpdateRequest();
        update.setImportance(Importance.CRITICAL);
        update.setUpdatedAt(scheduleRepository.findById(scheduleId).orElseThrow().getUpdatedAt());
        scheduleService.update(ownerId, scheduleId, update);

        assertThat(storedImportance(scheduleId)).isEqualTo("HIGH");
    }

    // ------------------------------------------------------------------
    // 나갈 때 — 도입 이전 CRITICAL 행을 HIGH 로 보여 준다
    // ------------------------------------------------------------------

    /**
     * 2단계로 줄이기 전에 "매우 높음" 으로 저장된 일정이 사라지거나 오류를 내지 않아야 한다. DB 를 직접 고쳐 그 상황을 만든다 — 애플리케이션 경로로는 더 이상
     * 만들 수 없다.
     */
    @Test
    @DisplayName("이미 저장된 CRITICAL 행은 중요(HIGH)로 보인다")
    void legacyCriticalRowIsExposedAsHigh() {
        Long scheduleId = createSchedule(Importance.HIGH);
        jdbcTemplate.update("UPDATE schedule SET importance = 'CRITICAL' WHERE id = ?", scheduleId);

        assertThat(storedImportance(scheduleId)).as("DB 값은 그대로 둔다").isEqualTo("CRITICAL");
        assertThat(scheduleService.get(ownerId, scheduleId).importance())
                .as("앱에는 두 단계만 보여야 한다")
                .isEqualTo(Importance.HIGH);
    }

    // ------------------------------------------------------------------
    // 반복 일정도 같다
    // ------------------------------------------------------------------

    @Test
    @DisplayName("반복 규칙과 그 인스턴스도 CRITICAL 을 만들지 않는다")
    void repeatRuleAndInstancesCollapseCritical() {
        var created =
                repeatService.create(
                        ownerId,
                        new RepeatCreateRequest(
                                "출근",
                                HOME,
                                OFFICE,
                                LocalTime.of(23, 59),
                                LocalTime.of(23, 0),
                                LocalDate.now(BusinessTime.ZONE),
                                "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR,SA,SU",
                                null,
                                Importance.CRITICAL,
                                false));

        assertThat(repeatService.get(ownerId, created.repeatId()).importance())
                .isEqualTo(Importance.HIGH);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT importance FROM schedule_repeat WHERE id = ?",
                                String.class,
                                created.repeatId()))
                .isEqualTo("HIGH");
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT DISTINCT importance FROM schedule WHERE repeat_id = ?",
                                String.class,
                                created.repeatId()))
                .containsExactly("HIGH");
    }

    // ------------------------------------------------------------------
    // 계산에 영향이 없다
    // ------------------------------------------------------------------

    /**
     * 중요도는 저장·표시용일 뿐이다. {@code Job} 이 값을 실어 나르지만 읽는 코드가 없고, {@code
     * ddubi.recommendation.percentile.*} 도 읽는 코드가 없다. 여기가 깨지면 누군가 중요도에 계산 로직을 붙인 것이므로, ML 백분위 커버리지
     * 검증(D3)부터 확인해야 한다.
     */
    @Test
    @DisplayName("중요도를 바꿔도 출발 예정시각은 달라지지 않는다")
    void importanceDoesNotAffectPlannedDeparture() {
        Long normal = createSchedule(Importance.NORMAL);
        Long high = createSchedule(Importance.HIGH);

        assertThat(scheduleRepository.findById(high).orElseThrow().getPlannedDepartAt())
                .isEqualTo(scheduleRepository.findById(normal).orElseThrow().getPlannedDepartAt());
    }
}
