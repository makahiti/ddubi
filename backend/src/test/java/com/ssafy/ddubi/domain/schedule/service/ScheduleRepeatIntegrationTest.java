package com.ssafy.ddubi.domain.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.schedule.dto.request.LocationRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.RepeatCreateRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.RepeatUpdateRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleUpdateRequest;
import com.ssafy.ddubi.domain.schedule.dto.response.RepeatCreateResponse;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.util.BusinessTime;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 반복 일정 (REPEAT-001~005) — 도메인 규칙 5(2단 구조·개별수정 보호·soft 중지)를 고정한다. */
class ScheduleRepeatIntegrationTest extends IntegrationTestSupport {

    private static final LocationRequest HOME =
            new LocationRequest("집", new BigDecimal("37.5013068"), new BigDecimal("127.0396597"));
    private static final LocationRequest OFFICE =
            new LocationRequest("회사", new BigDecimal("37.5716229"), new BigDecimal("126.9767879"));

    /** 매일 반복 — 지평선(14일) 동안 14건이 나와 검증이 단순하다. */
    private static final String EVERY_DAY = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR,SA,SU";

    @Autowired private ScheduleRepeatService repeatService;
    @Autowired private ScheduleService scheduleService;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Autowired
    private com.ssafy.ddubi.domain.recommendation.service.ScheduleEvaluationStore evaluationStore;

    private Long ownerId;

    @BeforeEach
    void setUp() {
        ownerId =
                userRepository
                        .save(User.builder().email("owner@ddubi.com").nickname("주인").build())
                        .getId();
    }

    private RepeatCreateResponse createDaily() {
        // 도착 시각을 23:59 로 두면 "오늘 것이 이미 지났는지"가 자정 직전 실행이 아닌 한 안 흔들린다.
        return repeatService.create(
                ownerId,
                new RepeatCreateRequest(
                        "출근",
                        HOME,
                        OFFICE,
                        LocalTime.of(23, 59),
                        LocalTime.of(23, 0),
                        LocalDate.now(BusinessTime.ZONE),
                        EVERY_DAY,
                        null,
                        null,
                        null));
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
        RepeatCreateResponse created =
                repeatService.create(
                        ownerId,
                        new RepeatCreateRequest(
                                "단기 반복",
                                HOME,
                                OFFICE,
                                LocalTime.of(23, 59),
                                LocalTime.of(23, 0),
                                LocalDate.now(BusinessTime.ZONE),
                                EVERY_DAY,
                                LocalDate.now(BusinessTime.ZONE).plusDays(2),
                                null,
                                null));

        assertThat(created.generatedScheduleCount()).isEqualTo(3); // 오늘·내일·모레
    }

    @Test
    @DisplayName("주중 반복은 주말 인스턴스를 만들지 않는다")
    void respectsDaysOfWeek() {
        RepeatCreateResponse created =
                repeatService.create(
                        ownerId,
                        new RepeatCreateRequest(
                                "주중 출근",
                                HOME,
                                OFFICE,
                                LocalTime.of(23, 59),
                                LocalTime.of(23, 0),
                                LocalDate.now(BusinessTime.ZONE),
                                "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
                                null,
                                null,
                                null));

        assertThat(instancesOf(created.repeatId()))
                .allSatisfy(
                        s ->
                                assertThat(s.getArriveBy().getDayOfWeek().getValue())
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
        Schedule survived =
                after.stream()
                        .filter(s -> s.getId().equals(customTarget.getId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(survived.getTitle()).isEqualTo("이날만 재택");
        assertThat(survived.getArriveBy().toLocalTime()).isEqualTo(LocalTime.of(23, 59));
        // 나머지는 새 시각으로 다시 만들어졌다.
        assertThat(result.regeneratedScheduleCount()).isGreaterThan(0);
        assertThat(after.stream().filter(s -> !s.getId().equals(customTarget.getId())))
                .allSatisfy(
                        s ->
                                assertThat(s.getArriveBy().toLocalTime())
                                        .isEqualTo(LocalTime.of(23, 58)));
    }

    @Test
    @DisplayName("제목만 바꾸면 인스턴스를 재생성하지 않는다")
    void titleOnlyUpdateDoesNotRegenerate() {
        RepeatCreateResponse created = createDaily();
        List<Long> idsBefore =
                instancesOf(created.repeatId()).stream().map(Schedule::getId).toList();

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

    // ------------------------------------------------------------------
    // 출발 예정시각 (B3) — 이게 없으면 반복 인스턴스는 추천 계산이 아예 안 돈다
    // ------------------------------------------------------------------

    /**
     * 규칙에 넣은 출발시각이 인스턴스까지 내려가야 한다. 여기가 비면 {@code ScheduleEvaluationStore} 가 DEPARTURE_REQUIRED 로
     * 끝내고, 자동 재계산 대상 조회도 {@code planned_depart_at IS NOT NULL} 로 걸러내 버린다.
     */
    @Test
    @DisplayName("규칙의 출발시각이 인스턴스마다 채워진다")
    void instancesCarryDepartTime() {
        RepeatCreateResponse created = createDaily();

        assertThat(instancesOf(created.repeatId()))
                .isNotEmpty()
                .allSatisfy(
                        instance -> {
                            assertThat(instance.getPlannedDepartAt())
                                    .as("비어 있으면 추천 계산이 돌지 않는다")
                                    .isNotNull();
                            assertThat(instance.getPlannedDepartAt().toLocalTime())
                                    .isEqualTo(LocalTime.of(23, 0));
                            assertThat(instance.getPlannedDepartAt().toLocalDate())
                                    .as("출발이 도착보다 이르므로 같은 날이다")
                                    .isEqualTo(instance.getArriveBy().toLocalDate());
                        });
    }

    /**
     * B3 가 고친 증상 그 자체. 예전에는 반복으로 만든 일정을 열면 앱이 "출발 예정시각이 없어요" 를 띄웠다 — {@code latest()} 가
     * DEPARTURE_REQUIRED 로 끝냈기 때문이다.
     */
    @Test
    @DisplayName("반복으로 만든 일정이 DEPARTURE_REQUIRED 로 막히지 않는다")
    void instancesAreNoLongerBlockedFromEvaluation() {
        RepeatCreateResponse created = createDaily();
        Schedule instance = instancesOf(created.repeatId()).get(0);

        assertThat(evaluationStore.latest(instance.getId(), ownerId))
                .extracting(latest -> latest.get("state"))
                .as("출발시각이 있으므로 계산 단계로 넘어가야 한다")
                .isNotEqualTo("DEPARTURE_REQUIRED");
    }

    /**
     * 빌더로 plannedDepartAt 을 직접 넣으면 plan_source 가 LEGACY_UNKNOWN 으로 남는다. 이 값은 사용자가 규칙에 직접 넣은 것이므로
     * 출처가 USER_INPUT 이어야 한다 — V9 가 출처 추적을 넣은 이유가 "과거 계획의 입력 출처를 추측하지 않는다" 이다.
     */
    @Test
    @DisplayName("출발시각의 출처를 USER_INPUT 으로 기록한다")
    void departTimeIsRecordedAsUserInput() {
        RepeatCreateResponse created = createDaily();

        assertThat(instancesOf(created.repeatId()))
                .allSatisfy(
                        instance -> {
                            assertThat(instance.getPlanSource()).isEqualTo("USER_INPUT");
                            assertThat(instance.getUserInputDepartAt())
                                    .isEqualTo(instance.getPlannedDepartAt());
                        });
    }

    /** "00:30 도착 / 23:50 출발" 은 같은 날 23:50 이 아니다. 그대로 두면 출발이 도착보다 뒤가 되어 계산이 말이 안 된다. */
    @Test
    @DisplayName("출발이 도착보다 늦으면 전날로 잡는다 — 자정을 넘는 일정")
    void departBeforeMidnightBelongsToPreviousDay() {
        RepeatCreateResponse created =
                repeatService.create(
                        ownerId,
                        new RepeatCreateRequest(
                                "심야 근무",
                                HOME,
                                OFFICE,
                                LocalTime.of(0, 30),
                                LocalTime.of(23, 50),
                                LocalDate.now(BusinessTime.ZONE),
                                EVERY_DAY,
                                null,
                                null,
                                null));

        assertThat(instancesOf(created.repeatId()))
                .isNotEmpty()
                .allSatisfy(
                        instance -> {
                            assertThat(instance.getPlannedDepartAt())
                                    .as("출발은 도착보다 반드시 앞서야 한다")
                                    .isBefore(instance.getArriveBy());
                            assertThat(instance.getPlannedDepartAt().toLocalDate())
                                    .isEqualTo(instance.getArriveBy().toLocalDate().minusDays(1));
                        });
    }

    /**
     * 출발시각 도입 이전 규칙은 depart_time 이 NULL 이다. 서버가 도착 시각에서 역산해 채우지 않는다 — 사용자가 정하지 않은 출발 시각으로 알림을 보내면
     * 근거를 댈 수 없다.
     */
    @Test
    @DisplayName("구버전 규칙(출발시각 없음)은 서버가 값을 지어내지 않는다")
    void legacyRuleWithoutDepartTimeIsLeftAlone() {
        RepeatCreateResponse created = createDaily();
        jdbcTemplate.update(
                "UPDATE schedule_repeat SET depart_time = NULL WHERE id = ?", created.repeatId());
        scheduleRepository.deleteAll();

        RepeatUpdateRequest regenerate = new RepeatUpdateRequest();
        regenerate.setLocalTime(LocalTime.of(23, 58));
        repeatService.update(ownerId, created.repeatId(), regenerate);

        assertThat(instancesOf(created.repeatId()))
                .isNotEmpty()
                .allSatisfy(
                        instance ->
                                assertThat(instance.getPlannedDepartAt())
                                        .as("추측해서 채우느니 비워 두고 사용자에게 받는다")
                                        .isNull());
    }

    @Test
    @DisplayName("출발시각을 수정하면 다시 만들어진 인스턴스에 반영된다")
    void updatingDepartTimeRegeneratesInstances() {
        RepeatCreateResponse created = createDaily();

        RepeatUpdateRequest update = new RepeatUpdateRequest();
        update.setDepartTime(LocalTime.of(21, 30));
        repeatService.update(ownerId, created.repeatId(), update);

        assertThat(repeatService.get(ownerId, created.repeatId()).departTime())
                .isEqualTo(LocalTime.of(21, 30));
        assertThat(instancesOf(created.repeatId()))
                .isNotEmpty()
                .allSatisfy(
                        instance ->
                                assertThat(instance.getPlannedDepartAt().toLocalTime())
                                        .isEqualTo(LocalTime.of(21, 30)));
    }

    /** 출발시각을 지우는 것은 계산을 못 하게 만드는 일이라, 사용자가 의도할 동작이 아니다. */
    @Test
    @DisplayName("출발시각을 명시적 null 로 지울 수 없다 — 400")
    void departTimeCannotBeClearedExplicitly() {
        RepeatCreateResponse created = createDaily();

        RepeatUpdateRequest update = new RepeatUpdateRequest();
        update.setDepartTime(null);

        assertThatThrownBy(() -> repeatService.update(ownerId, created.repeatId(), update))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 앱은 초 없이 {@code "09:00"} 으로 보낸다. LocalTime 에 {@code @JsonFormat} 을 붙이면 그 형식이 깨지므로 붙이지 않는다 — 이
     * 테스트가 그 약속을 고정한다.
     */
    @Test
    @DisplayName("앱이 보내는 \"09:00\" 형식을 그대로 읽는다")
    void parsesTimeWithoutSeconds() throws Exception {
        String body =
                """
                {"title":"출근","origin":{"name":"집","lat":37.5013068,"lng":127.0396597},
                 "destination":{"name":"회사","lat":37.5716229,"lng":126.9767879},
                 "localTime":"09:00","departTime":"08:10",
                 "startDate":"2026-10-01","rrule":"FREQ=WEEKLY;BYDAY=MO"}
                """;

        RepeatCreateRequest parsed = objectMapper.readValue(body, RepeatCreateRequest.class);

        assertThat(parsed.localTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(parsed.departTime()).isEqualTo(LocalTime.of(8, 10));
        // 좌표까지 단언한다. 모르는 필드는 조용히 버려지므로, 여기를 비워 두면 필드명을
        // 틀리게 적어도 이 테스트가 통과해 버린다.
        assertThat(parsed.origin().lat()).isEqualByComparingTo("37.5013068");
        assertThat(parsed.destination().lng()).isEqualByComparingTo("126.9767879");
    }

    // ------------------------------------------------------------------
    // 수정·삭제 범위 (B8) — "이 일정만" 은 일정 API 가 맡고, 여기는 "이후 전체" 다
    // ------------------------------------------------------------------

    /**
     * 사용자가 3일 뒤 일정을 열어 "이후 일정 전체" 를 고르면 그 날짜부터 바뀌길 기대한다. 기준이 없으면 오늘·내일 일정까지 같이 바뀐다 — "다음 주부터 바꿀게요"
     * 라고 했는데 오늘 것이 바뀌는 셈이다.
     */
    @Test
    @DisplayName("effectiveFrom 을 주면 그 시각 이전 인스턴스는 건드리지 않는다")
    void effectiveFromLimitsRegeneration() {
        RepeatCreateResponse created = createDaily();
        LocalDateTime boundary = BusinessTime.now().plusDays(3).withHour(0).withMinute(0);
        List<Long> untouched =
                instancesOf(created.repeatId()).stream()
                        .filter(s -> s.getArriveBy().isBefore(boundary))
                        .map(Schedule::getId)
                        .toList();
        assertThat(untouched).as("경계 이전 인스턴스가 있어야 의미 있는 검증이다").isNotEmpty();

        RepeatUpdateRequest update = new RepeatUpdateRequest();
        update.setLocalTime(LocalTime.of(23, 58));
        update.setEffectiveFrom(boundary);
        repeatService.update(ownerId, created.repeatId(), update);

        List<Schedule> after = instancesOf(created.repeatId());
        assertThat(after)
                .filteredOn(s -> untouched.contains(s.getId()))
                .as("경계 이전은 id 도 시각도 그대로여야 한다")
                .hasSize(untouched.size())
                .allSatisfy(
                        s ->
                                assertThat(s.getArriveBy().toLocalTime())
                                        .isEqualTo(LocalTime.of(23, 59)));
        assertThat(after)
                .filteredOn(s -> !s.getArriveBy().isBefore(boundary))
                .isNotEmpty()
                .allSatisfy(
                        s ->
                                assertThat(s.getArriveBy().toLocalTime())
                                        .isEqualTo(LocalTime.of(23, 58)));
    }

    /** 기존 앱은 effectiveFrom 을 보내지 않는다. 예전과 똑같이 동작해야 한다. */
    @Test
    @DisplayName("effectiveFrom 을 안 보내면 예전처럼 지금부터 다시 만든다")
    void updateWithoutEffectiveFromKeepsOldBehaviour() {
        RepeatCreateResponse created = createDaily();

        RepeatUpdateRequest update = new RepeatUpdateRequest();
        update.setLocalTime(LocalTime.of(23, 58));
        repeatService.update(ownerId, created.repeatId(), update);

        assertThat(instancesOf(created.repeatId()))
                .isNotEmpty()
                .allSatisfy(
                        s ->
                                assertThat(s.getArriveBy().toLocalTime())
                                        .isEqualTo(LocalTime.of(23, 58)));
    }

    /** 과거를 그대로 받으면 이미 지난 일정을 다시 만들게 된다. */
    @Test
    @DisplayName("과거 effectiveFrom 은 지금으로 당긴다")
    void pastEffectiveFromIsClampedToNow() {
        RepeatCreateResponse created = createDaily();

        RepeatUpdateRequest update = new RepeatUpdateRequest();
        update.setLocalTime(LocalTime.of(23, 58));
        update.setEffectiveFrom(BusinessTime.now().minusDays(30));
        repeatService.update(ownerId, created.repeatId(), update);

        assertThat(instancesOf(created.repeatId()))
                .isNotEmpty()
                .allSatisfy(
                        instance -> {
                            assertThat(instance.getArriveBy())
                                    .as("과거 일정이 새로 생기면 안 된다")
                                    .isAfter(BusinessTime.now().minusMinutes(1));
                            assertThat(instance.getArriveBy().toLocalTime())
                                    .isEqualTo(LocalTime.of(23, 58));
                        });
    }

    /** 규칙 11 — 삭제는 soft(CANCELED) 다. 물리 삭제는 탈퇴 CASCADE 뿐이다. 행이 사라지면 이미 그 일정을 참조한 알림·추천이 고아가 된다. */
    @Test
    @DisplayName("cancelFutureInstances=true 면 미래 인스턴스를 지우지 않고 CANCELED 로 바꾼다")
    void deactivateCancelsFutureInstancesWithoutDeleting() {
        RepeatCreateResponse created = createDaily();
        int before = instancesOf(created.repeatId()).size();

        repeatService.deactivate(ownerId, created.repeatId(), true);

        assertThat(instancesOf(created.repeatId()))
                .as("행은 남아 있어야 한다")
                .hasSize(before)
                .allSatisfy(
                        instance -> {
                            assertThat(instance.getStatus()).isEqualTo(ScheduleStatus.CANCELED);
                            assertThat(instance.getJourneyState())
                                    .as("status 만 바꾸면 journey_state 가 어긋난 채 남는다")
                                    .isEqualTo("CANCELED");
                        });
    }

    /** 기존 앱은 이 값을 보내지 않는다. 기본이 true 였다면 일정이 통째로 사라졌을 것이다. */
    @Test
    @DisplayName("cancelFutureInstances 를 안 주면 미래 인스턴스는 그대로다")
    void deactivateKeepsFutureInstancesByDefault() {
        RepeatCreateResponse created = createDaily();
        int before = instancesOf(created.repeatId()).size();

        repeatService.deactivate(ownerId, created.repeatId());

        assertThat(instancesOf(created.repeatId()))
                .hasSize(before)
                .allSatisfy(
                        instance ->
                                assertThat(instance.getStatus())
                                        .isEqualTo(ScheduleStatus.UPCOMING));
    }

    /** 도메인 규칙 5 — 개별 수정한 인스턴스는 사용자가 손댄 것이라 취소 대상이 아니다. */
    @Test
    @DisplayName("개별 수정한 인스턴스는 취소하지 않는다")
    void cancelSkipsCustomizedInstances() {
        RepeatCreateResponse created = createDaily();
        Schedule customTarget = instancesOf(created.repeatId()).get(3);
        ScheduleUpdateRequest custom = new ScheduleUpdateRequest();
        custom.setTitle("이날만 재택");
        custom.setUpdatedAt(customTarget.getUpdatedAt());
        scheduleService.update(ownerId, customTarget.getId(), custom);

        repeatService.deactivate(ownerId, created.repeatId(), true);

        Schedule survived =
                instancesOf(created.repeatId()).stream()
                        .filter(s -> s.getId().equals(customTarget.getId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(survived.getStatus()).isEqualTo(ScheduleStatus.UPCOMING);
        assertThat(survived.getTitle()).isEqualTo("이날만 재택");
    }
}
