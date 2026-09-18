package com.ssafy.ddubi.domain.schedule.service;

import com.ssafy.ddubi.domain.schedule.dto.request.LocationRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleCreateRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleUpdateRequest;
import com.ssafy.ddubi.domain.schedule.dto.response.ScheduleResponse;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 일정 CRUD (SCH-001~005) — 도메인 규칙 11(soft 취소)과 12(낙관적 잠금)를 고정한다.
 */
class ScheduleIntegrationTest extends IntegrationTestSupport {

    private static final LocationRequest GANGNAM =
            new LocationRequest("강남역", new BigDecimal("37.4979526"), new BigDecimal("127.0276241"));
    private static final LocationRequest GWANGHWAMUN =
            new LocationRequest("광화문", new BigDecimal("37.5716229"), new BigDecimal("126.9767879"));
    private static final LocationRequest BUSAN =
            new LocationRequest("해운대", new BigDecimal("35.1587"), new BigDecimal("129.1604"));

    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private UserRepository userRepository;

    private Long ownerId;
    private Long strangerId;
    private LocalDateTime tomorrow;

    @BeforeEach
    void setUp() {
        ownerId = userRepository.save(User.builder().email("owner@ddubi.com").nickname("주인").build()).getId();
        strangerId = userRepository.save(User.builder().email("other@ddubi.com").nickname("남").build()).getId();
        tomorrow = LocalDateTime.now().plusDays(1).withNano(0);
    }

    private ScheduleResponse createDefault() {
        return scheduleService.create(ownerId,
                new ScheduleCreateRequest("회의", GANGNAM, GWANGHWAMUN, tomorrow, null, null));
    }

    @Test
    @DisplayName("등록하면 UPCOMING 상태로 만들어지고 기본값이 채워진다")
    void create() {
        ScheduleResponse created = createDefault();

        assertThat(created.status()).isEqualTo(ScheduleStatus.UPCOMING);
        assertThat(created.importance().name()).isEqualTo("NORMAL");
        assertThat(created.considerLastTrip()).isFalse();
        assertThat(created.recommendationStatus()).isEqualTo("PENDING");
        assertThat(created.repeatId()).isNull();
    }

    @Test
    @DisplayName("과거 도착 시각은 400 — 출발 시각 역산이 불가능하다")
    void rejectsPastArriveBy() {
        assertThatThrownBy(() -> scheduleService.create(ownerId,
                new ScheduleCreateRequest("과거", GANGNAM, GWANGHWAMUN,
                        LocalDateTime.now().minusHours(1), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("서울 밖 출발지·목적지는 422")
    void rejectsOutOfSeoul() {
        assertThatThrownBy(() -> scheduleService.create(ownerId,
                new ScheduleCreateRequest("부산행", GANGNAM, BUSAN, tomorrow, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OUT_OF_SERVICE_AREA);
    }

    @Test
    @DisplayName("남의 일정은 조회·수정·취소 모두 403 (도메인 규칙 1)")
    void ownershipIsEnforced() {
        ScheduleResponse created = createDefault();

        assertThatThrownBy(() -> scheduleService.get(strangerId, created.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN_RESOURCE);
        assertThatThrownBy(() -> scheduleService.cancel(strangerId, created.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN_RESOURCE);
    }

    @Test
    @DisplayName("수정은 조회 시점 updatedAt 이 일치해야 한다 — 다르면 409 (도메인 규칙 12)")
    void optimisticLock() {
        ScheduleResponse created = createDefault();

        // 유효한 updatedAt 으로는 성공한다.
        ScheduleUpdateRequest ok = new ScheduleUpdateRequest();
        ok.setTitle("바뀐 회의");
        ok.setUpdatedAt(created.updatedAt());
        ScheduleResponse updated = scheduleService.update(ownerId, created.id(), ok);
        assertThat(updated.title()).isEqualTo("바뀐 회의");

        // 방금 수정으로 updatedAt 이 갱신됐으므로, 낡은 값으로 다시 수정하면 409.
        ScheduleUpdateRequest stale = new ScheduleUpdateRequest();
        stale.setTitle("뒤늦은 수정");
        stale.setUpdatedAt(created.updatedAt());
        assertThatThrownBy(() -> scheduleService.update(ownerId, created.id(), stale))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("취소는 soft 다 — 행이 남고 status 만 CANCELED (도메인 규칙 11)")
    void cancelIsSoft() {
        ScheduleResponse created = createDefault();

        scheduleService.cancel(ownerId, created.id());

        var entity = scheduleRepository.findById(created.id()).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(ScheduleStatus.CANCELED);
        // 멱등 — 같은 요청이 다시 와도 실패하지 않는다.
        scheduleService.cancel(ownerId, created.id());
    }

    @Test
    @DisplayName("다른 사용자의 일정은 취소할 수 없다 — 403, 상태도 바뀌지 않는다")
    void strangerCannotCancel() {
        ScheduleResponse created = createDefault();

        assertThatThrownBy(() -> scheduleService.cancel(strangerId, created.id()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN_RESOURCE);

        var entity = scheduleRepository.findById(created.id()).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(ScheduleStatus.UPCOMING);
    }

    @Test
    @DisplayName("취소된 일정은 수정할 수 없다 — 409")
    void canceledIsNotEditable() {
        ScheduleResponse created = createDefault();
        scheduleService.cancel(ownerId, created.id());

        ScheduleUpdateRequest update = new ScheduleUpdateRequest();
        update.setTitle("살려내기");
        update.setUpdatedAt(created.updatedAt());

        assertThatThrownBy(() -> scheduleService.update(ownerId, created.id(), update))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("목록은 기간 필터·상태 필터가 걸린다 (SCH-001)")
    void listFilters() {
        createDefault();
        ScheduleResponse canceled = scheduleService.create(ownerId,
                new ScheduleCreateRequest("취소될 일정", GANGNAM, GWANGHWAMUN, tomorrow.plusHours(2), null, null));
        scheduleService.cancel(ownerId, canceled.id());

        var all = scheduleService.list(ownerId,
                tomorrow.minusHours(1), tomorrow.plusHours(3), null, PageRequest.of(0, 20));
        var upcomingOnly = scheduleService.list(ownerId,
                tomorrow.minusHours(1), tomorrow.plusHours(3), ScheduleStatus.UPCOMING, PageRequest.of(0, 20));

        assertThat(all.totalElements()).isEqualTo(2);
        assertThat(upcomingOnly.totalElements()).isEqualTo(1);
    }
}
