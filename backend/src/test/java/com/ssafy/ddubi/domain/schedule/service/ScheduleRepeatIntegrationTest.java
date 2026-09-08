package com.ssafy.ddubi.domain.schedule.service;

import com.ssafy.ddubi.domain.schedule.dto.request.LocationRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.RepeatCreateRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.RepeatUpdateRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleUpdateRequest;
import com.ssafy.ddubi.domain.schedule.dto.response.RepeatCreateResponse;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 반복 일정 (REPEAT-001~005) — 도메인 규칙 5(2단 구조·개별수정 보호·soft 중지)를 고정한다.
 */
class ScheduleRepeatIntegrationTest extends IntegrationTestSupport {

    private static final LocationRequest HOME =
            new LocationRequest("집", new BigDecimal("37.5013068"), new BigDecimal("127.0396597"));
    private static final LocationRequest OFFICE =
            new LocationRequest("회사", new BigDecimal("37.5716229"), new BigDecimal("126.9767879"));
    /** 매일 반복 — 지평선(14일) 동안 14건이 나와 검증이 단순하다. */
    private static final String EVERY_DAY = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR,SA,SU";

    @Autowired
    private ScheduleRepeatService repeatService;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private UserRepository userRepository;

    private Long ownerId;

    @BeforeEach
    void setUp() {
        ownerId = userRepository.save(User.builder().email("owner@ddubi.com").nickname("주인").build()).getId();
    }

    private RepeatCreateResponse createDaily() {
        // 도착 시각을 23:59 로 두면 "오늘 것이 이미 지났는지"가 자정 직전 실행이 아닌 한 안 흔들린다.
        return repeatService.create(ownerId, new RepeatCreateRequest(
                "출근", HOME, OFFICE, LocalTime.of(23, 59),
                LocalDate.now(), EVERY_DAY, null, null, null));
    }

    private List<Schedule> instancesOf(Long repeatId) {
        return scheduleRepository.findAll().stream()
                .filter(s -> repeatId.equals(s.repeatId()))
                .sorted((a, b) -> a.getArriveBy().compareTo(b.getArriveBy()))
                .toList();
    }

    @Test
    @DisplayName("생성하면 지평선(14일)만큼 인스턴스가 선생성된다")
    void materializesOnCreate() {
        RepeatCreateResponse created = createDaily();

        // 오늘 23:59 포함 + 14일 뒤까지 = 15건.
        assertThat(created.generatedScheduleCount()).isEqualTo(15);
        assertThat(instancesOf(created.repeatId())).hasSize(15);
        assertThat(created.nextOccurrenceAt()).isNotNull();
    }

    @Test
    @DisplayName("endDate 가 지평선보다 짧으면 거기까지만 만든다")
    void endDateCapsMaterialization() {
        RepeatCreateResponse created = repeatService.create(ownerId, new RepeatCreateRequest(
                "단기 반복", HOME, OFFICE, LocalTime.of(23, 59),
                LocalDate.now(), EVERY_DAY, LocalDate.now().plusDays(2), null, null));

        assertThat(created.generatedScheduleCount()).isEqualTo(3); // 오늘·내일·모레
    }

    @Test
    @DisplayName("주중 반복은 주말 인스턴스를 만들지 않는다")
    void respectsDaysOfWeek() {
        RepeatCreateResponse created = repeatService.create(ownerId, new RepeatCreateRequest(
                "주중 출근", HOME, OFFICE, LocalTime.of(23, 59),
                LocalDate.now(), "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", null, null, null));

        assertThat(instancesOf(created.repeatId()))
                .allSatisfy(s -> assertThat(s.getArriveBy().getDayOfWeek().getValue())
                        .as("월(1)~금(5)만 존재해야 한다")
                        .isLessThanOrEqualTo(5));
    }

    @Test
    @DisplayName("반복 수정은 미래 인스턴스를 다시 만들되, 개별 수정된 것은 건너뛴다 (도메인 규칙 5)")
    void updateSkipsCustomizedInstances() {
        RepeatCreateResponse created = createDaily();
        List<Schedule> before = instancesOf(created.repeatId());

        // 인스턴스 하나를 개별 수정 → customized=true
        Schedule customTarget = before.get(3);
        ScheduleUpdateRequest custom = new ScheduleUpdateRequest();
        custom.setTitle("이날만 재택");
        custom.setUpdatedAt(customTarget.getUpdatedAt());
        scheduleService.update(ownerId, customTarget.getId(), custom);

        // 반복 전체의 도착 시각을 바꾼다 → 재생성
        RepeatUpdateRequest update = new RepeatUpdateRequest();
        update.setLocalTime(LocalTime.of(23, 58));
        var result = repeatService.update(ownerId, created.repeatId(), update);

        List<Schedule> after = instancesOf(created.repeatId());
        // 개별 수정본은 살아남고 제목·시각이 유지된다.
        Schedule survived = after.stream()
                .filter(s -> s.getId().equals(customTarget.getId()))
                .findFirst().orElseThrow();
        assertThat(survived.getTitle()).isEqualTo("이날만 재택");
        assertThat(survived.getArriveBy().toLocalTime()).isEqualTo(LocalTime.of(23, 59));
        // 나머지는 새 시각으로 다시 만들어졌다.
        assertThat(result.regeneratedScheduleCount()).isGreaterThan(0);
        assertThat(after.stream().filter(s -> !s.getId().equals(customTarget.getId())))
                .allSatisfy(s -> assertThat(s.getArriveBy().toLocalTime()).isEqualTo(LocalTime.of(23, 58)));
    }

    @Test
    @DisplayName("제목만 바꾸면 인스턴스를 재생성하지 않는다")
    void titleOnlyUpdateDoesNotRegenerate() {
        RepeatCreateResponse created = createDaily();
        List<Long> idsBefore = instancesOf(created.repeatId()).stream().map(Schedule::getId).toList();

        RepeatUpdateRequest update = new RepeatUpdateRequest();
        update.setTitle("새 이름");
        var result = repeatService.update(ownerId, created.repeatId(), update);

        assertThat(result.regeneratedScheduleCount()).isZero();
        assertThat(instancesOf(created.repeatId()).stream().map(Schedule::getId).toList())
                .containsExactlyElementsOf(idsBefore);
    }

    @Test
    @DisplayName("중지는 비활성화일 뿐 — 기존 인스턴스는 남는다 (REPEAT-005)")
    void deactivateKeepsInstances() {
        RepeatCreateResponse created = createDaily();
        int instanceCount = instancesOf(created.repeatId()).size();

        repeatService.deactivate(ownerId, created.repeatId());

        assertThat(repeatService.get(ownerId, created.repeatId()).active()).isFalse();
        assertThat(instancesOf(created.repeatId())).hasSize(instanceCount);
        // 중지된 규칙은 다음 발생이 없다.
        assertThat(repeatService.get(ownerId, created.repeatId()).nextOccurrenceAt()).isNull();
    }

    @Test
    @DisplayName("중지된 반복은 수정할 수 없다 — 409")
    void deactivatedIsNotEditable() {
        RepeatCreateResponse created = createDaily();
        repeatService.deactivate(ownerId, created.repeatId());

        RepeatUpdateRequest update = new RepeatUpdateRequest();
        update.setTitle("부활");

        assertThatThrownBy(() -> repeatService.update(ownerId, created.repeatId(), update))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("인스턴스가 이미 있으면 중복 생성하지 않는다 — UNIQUE(repeat_id, arrive_by)")
    void materializeIsIdempotent() {
        RepeatCreateResponse created = createDaily();
        int before = instancesOf(created.repeatId()).size();

        // 규칙 필드를 그대로 두고 재생성을 강제해도(도착 시각 동일) 개수가 늘지 않는다.
        RepeatUpdateRequest update = new RepeatUpdateRequest();
        update.setLocalTime(LocalTime.of(23, 59)); // 같은 값 — affectsInstances 는 true
        repeatService.update(ownerId, created.repeatId(), update);

        assertThat(instancesOf(created.repeatId())).hasSize(before);
    }
}
