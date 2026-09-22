package com.ssafy.ddubi.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.ddubi.domain.schedule.dto.request.LocationRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleCreateRequest;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;
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

/**
 * 지난 일정 자동 마감.
 *
 * <h3>왜 이 검사가 필요한가</h3>
 *
 * 일정이 UPCOMING 에서 벗어나는 길은 도착 보고와 취소뿐이었다. 도착을 누르지 않으면 며칠 지난 일정이 계속 "예정" 으로 남는다. 여기가 깨지면 목록·홈에 지난 일정이
 * 다시 섞여 보인다.
 *
 * <h3>시각은 인자로 넘긴다</h3>
 *
 * {@code expireOverdue(now)} 가 시각을 받으므로 실제 시계에 기대지 않는다. 일정 생성은 도착 시각을 미래로 강제하므로, 과거 일정을 SQL 로 만들어
 * 넣는 대신 <b>판정 시각을 미래로</b> 넘겨서 "지난 일정" 을 만든다.
 */
class ScheduleExpiryIntegrationTest extends IntegrationTestSupport {

    private static final LocationRequest HOME =
            new LocationRequest("집", new BigDecimal("37.5013068"), new BigDecimal("127.0396597"));
    private static final LocationRequest OFFICE =
            new LocationRequest("회사", new BigDecimal("37.5716229"), new BigDecimal("126.9767879"));

    @Autowired private ScheduleExpiryService expiryService;
    @Autowired private ScheduleService scheduleService;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private UserRepository userRepository;

    private Long ownerId;

    /** 두 일정이 같은 시각을 쓰도록 한 번만 구한다. 호출마다 다시 읽으면 초가 넘어갈 때 값이 어긋난다. */
    private LocalDateTime arriveBy;

    @BeforeEach
    void setUp() {
        ownerId =
                userRepository
                        .save(User.builder().email("expiry@ddubi.com").nickname("마감").build())
                        .getId();
        arriveBy = BusinessTime.now().plusDays(1).withNano(0);
    }

    private Long createSchedule() {
        return scheduleService
                .create(
                        ownerId,
                        new ScheduleCreateRequest(
                                "회의", HOME, OFFICE, arriveBy, null, false, arriveBy.minusHours(1)))
                .id();
    }

    private Schedule stored(Long scheduleId) {
        return scheduleRepository.findById(scheduleId).orElseThrow();
    }

    // ------------------------------------------------------------------
    // 닫는 경우
    // ------------------------------------------------------------------

    @Test
    @DisplayName("유예 시간까지 지나면 닫는다")
    void closesWhatIsWellPast() {
        Long scheduleId = createSchedule();

        assertThat(expiryService.expireOverdue(arriveBy.plusHours(7))).isEqualTo(1);
        assertThat(stored(scheduleId).getStatus()).isEqualTo(ScheduleStatus.DONE);
    }

    /**
     * 자동으로 닫힌 일정과 사용자가 도착을 확인한 일정은 구분할 수 있어야 한다. {@code markDone()} 은 {@code journeyState} 를
     * ARRIVED 로 바꾸지만 자동 마감은 건드리지 않는다. 여기가 깨지면 "도착했다" 와 "그냥 시간이 지났다" 가 같은 값이 된다.
     */
    @Test
    @DisplayName("자동으로 닫은 일정은 도착 확인한 일정과 구분된다")
    void keepsJourneyStateSoItStaysDistinguishable() {
        Long scheduleId = createSchedule();

        expiryService.expireOverdue(arriveBy.plusHours(7));

        assertThat(stored(scheduleId).getJourneyState())
                .as("도착을 누른 적이 없으므로 ARRIVED 가 되면 안 된다")
                .isEqualTo("NOT_STARTED");
    }

    @Test
    @DisplayName("여러 건이 밀려 있어도 한 번에 닫는다")
    void closesEveryOverdueSchedule() {
        createSchedule();
        createSchedule();
        createSchedule();

        assertThat(expiryService.expireOverdue(arriveBy.plusHours(7))).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // 닫지 않는 경우
    // ------------------------------------------------------------------

    /** 늦게 도착한 사람이 뒤늦게 도착을 누를 여지를 남긴다. */
    @Test
    @DisplayName("유예 시간 안쪽이면 그대로 둔다")
    void leavesWhatIsStillWithinGrace() {
        Long scheduleId = createSchedule();

        assertThat(expiryService.expireOverdue(arriveBy.plusHours(1))).isZero();
        assertThat(stored(scheduleId).getStatus()).isEqualTo(ScheduleStatus.UPCOMING);
    }

    @Test
    @DisplayName("아직 오지 않은 일정은 건드리지 않는다")
    void leavesFutureSchedules() {
        Long scheduleId = createSchedule();

        assertThat(expiryService.expireOverdue(BusinessTime.now())).isZero();
        assertThat(stored(scheduleId).getStatus()).isEqualTo(ScheduleStatus.UPCOMING);
    }

    /** 취소는 일어나지 않은 일이다. 뒤늦게 완료로 바꾸면 주간 리포트의 집계 대상이 달라진다. */
    @Test
    @DisplayName("취소한 일정은 완료로 바꾸지 않는다")
    void leavesCanceledSchedules() {
        Long scheduleId = createSchedule();
        scheduleService.cancel(ownerId, scheduleId);

        assertThat(expiryService.expireOverdue(arriveBy.plusHours(7))).isZero();
        assertThat(stored(scheduleId).getStatus()).isEqualTo(ScheduleStatus.CANCELED);
    }

    @Test
    @DisplayName("두 번 돌아도 같은 일정을 다시 세지 않는다")
    void isIdempotent() {
        createSchedule();

        assertThat(expiryService.expireOverdue(arriveBy.plusHours(7))).isEqualTo(1);
        assertThat(expiryService.expireOverdue(arriveBy.plusHours(7))).isZero();
    }
}
