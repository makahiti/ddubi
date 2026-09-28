package com.ssafy.ddubi.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.ddubi.domain.report.dto.request.ArrivalRequest;
import com.ssafy.ddubi.domain.report.dto.response.ArrivalResponse;
import com.ssafy.ddubi.domain.report.dto.response.HistoryResponse;
import com.ssafy.ddubi.domain.report.dto.response.ReportDetailResponse;
import com.ssafy.ddubi.domain.report.dto.response.ReportResponse;
import com.ssafy.ddubi.domain.report.entity.ArrivalResult;
import com.ssafy.ddubi.domain.report.repository.ReportDetailRepository;
import com.ssafy.ddubi.domain.schedule.entity.LocationSnapshot;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.common.PageResponse;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.util.BusinessTime;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * ARRIVAL-001 · HISTORY-001 · REPORT-001~002.
 *
 * <p>고정하려는 것:
 *
 * <ul>
 *   <li>규칙 1 소유권 — 남의 일정·리포트는 403
 *   <li>규칙 9 실측 저장 — 계정 동의 AND 요청 동의
 *   <li>판정 경계(기본 10분)와 TIGHT 이 집계 어디에도 들어가지 않는 것
 *   <li>재보고 멱등 — 행이 늘지 않고 집계도 그대로
 * </ul>
 *
 * <p>일정을 서비스가 아니라 리포지토리로 직접 만드는 이유: SCH-002 가 과거 도착 시각을 거부하는데(출발 시각 역산 불가), 도착 <b>기록</b>은 본질적으로 지나간
 * 일정을 다룬다.
 */
class ArrivalReportIntegrationTest extends IntegrationTestSupport {

    private static final LocationSnapshot GANGNAM =
            new LocationSnapshot(
                    "강남역", new BigDecimal("37.4979526"), new BigDecimal("127.0276241"));
    private static final LocationSnapshot GWANGHWAMUN =
            new LocationSnapshot(
                    "광화문", new BigDecimal("37.5716229"), new BigDecimal("126.9767879"));

    @Autowired private ArrivalService arrivalService;
    @Autowired private ReportQueryService reportQueryService;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private ReportDetailRepository reportDetailRepository;
    @Autowired private UserRepository userRepository;

    private Long ownerId;
    private Long strangerId;

    /** 지난 화요일 09:00 — 주 경계에 붙지 않은 안쪽 날이라 월요일 시작 계산이 흔들리지 않는다. */
    private LocalDateTime arriveBy;

    @BeforeEach
    void setUp() {
        User owner =
                userRepository.save(User.builder().email("owner@ddubi.com").nickname("주인").build());
        owner.changeHistoryConsent(true);
        userRepository.save(owner);
        ownerId = owner.getId();

        strangerId =
                userRepository
                        .save(User.builder().email("other@ddubi.com").nickname("남").build())
                        .getId();

        arriveBy =
                WeeklyReportProvider.weekStart(LocalDate.now(BusinessTime.ZONE))
                        .minusWeeks(1)
                        .plusDays(1)
                        .atTime(9, 0);
    }

    private Long givenSchedule(LocalDateTime arriveBy) {
        return scheduleRepository
                .save(
                        Schedule.builder()
                                .user(userRepository.getReferenceById(ownerId))
                                .title("회의")
                                .origin(GANGNAM)
                                .destination(GWANGHWAMUN)
                                .arriveBy(arriveBy)
                                .build())
                .getId();
    }

    private ArrivalResponse report(Long scheduleId, LocalDateTime actual) {
        return arrivalService.record(ownerId, scheduleId, new ArrivalRequest(actual, true));
    }

    // ------------------------------------------------------------------
    // 판정
    // ------------------------------------------------------------------

    @Test
    @DisplayName("목표보다 10분 이상 일찍 도착하면 ON_TIME, 그 안쪽은 TIGHT, 넘기면 LATE_RISK")
    void judgesByMargin() {
        assertThat(report(givenSchedule(arriveBy), arriveBy.minusMinutes(30)).result())
                .isEqualTo(ArrivalResult.ON_TIME);
        assertThat(report(givenSchedule(arriveBy), arriveBy.minusMinutes(3)).result())
                .isEqualTo(ArrivalResult.TIGHT);
        assertThat(report(givenSchedule(arriveBy), arriveBy.plusMinutes(5)).result())
                .isEqualTo(ArrivalResult.LATE_RISK);
    }

    @Test
    @DisplayName("경계값 — 정확히 10분 전은 ON_TIME, 목표 시각 정각은 TIGHT")
    void boundaryIsInclusiveOnTheEarlySide() {
        assertThat(report(givenSchedule(arriveBy), arriveBy.minusMinutes(10)).result())
                .isEqualTo(ArrivalResult.ON_TIME);
        assertThat(report(givenSchedule(arriveBy), arriveBy).result())
                .isEqualTo(ArrivalResult.TIGHT);
    }

    @Test
    @DisplayName("도착 시각이 미래면 400 — 그대로 받으면 정시 통계가 조용히 부풀려진다")
    void rejectsFutureArrival() {
        Long scheduleId = givenSchedule(BusinessTime.now().plusDays(1).withNano(0));

        assertThatThrownBy(() -> report(scheduleId, BusinessTime.now().plusHours(2)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    // ------------------------------------------------------------------
    // 규칙 9 — 실측 저장 조건
    // ------------------------------------------------------------------

    @Test
    @DisplayName("계정 미동의면 요청이 true 여도 실측을 저장하지 않는다 — 그래도 200 이고 일정은 완료된다")
    void accountConsentIsRequired() {
        User owner = userRepository.findById(ownerId).orElseThrow();
        owner.changeHistoryConsent(false);
        userRepository.save(owner);

        Long scheduleId = givenSchedule(arriveBy);
        ArrivalResponse response = report(scheduleId, arriveBy.minusMinutes(30));

        assertThat(response.actualArrival()).isNull();
        assertThat(response.result()).isNull();
        assertThat(response.status()).isEqualTo(ScheduleStatus.DONE);
    }

    @Test
    @DisplayName("계정 동의가 있어도 이번 요청이 false 면 저장하지 않는다 — 요청 필드는 거부권이다")
    void requestConsentCanOptOut() {
        Long scheduleId = givenSchedule(arriveBy);

        ArrivalResponse response =
                arrivalService.record(
                        ownerId, scheduleId, new ArrivalRequest(arriveBy.minusMinutes(30), false));

        assertThat(response.actualArrival()).isNull();
        assertThat(response.result()).isNull();
    }

    @Test
    @DisplayName("추천 의존 필드는 Phase 3 까지 null 이다")
    void recommendationFieldsStayNull() {
        ArrivalResponse response = report(givenSchedule(arriveBy), arriveBy.minusMinutes(30));

        assertThat(response.plannedDepart()).isNull();
        assertThat(response.proxyDurationMin()).isNull();
        assertThat(response.proxyArrival()).isNull();
        assertThat(response.mainCause()).isNull();
    }

    // ------------------------------------------------------------------
    // 멱등 · 소유권
    // ------------------------------------------------------------------

    @Test
    @DisplayName("같은 일정을 두 번 보고해도 행이 늘지 않고 판정만 갱신된다")
    void reReportUpdatesInsteadOfInserting() {
        Long scheduleId = givenSchedule(arriveBy);

        ArrivalResponse first = report(scheduleId, arriveBy.minusMinutes(30));
        ArrivalResponse second = report(scheduleId, arriveBy.plusMinutes(20));

        assertThat(second.reportDetailId()).isEqualTo(first.reportDetailId());
        assertThat(second.result()).isEqualTo(ArrivalResult.LATE_RISK);
        assertThat(reportDetailRepository.count()).isEqualTo(1);

        // 집계도 2건이 되면 안 된다 — 이게 행을 늘리지 않는 진짜 이유다
        ReportResponse report = reports().content().get(0);
        assertThat(report.totalSchedules()).isEqualTo(1);
        assertThat(report.onTimeEstimated()).isZero();
        assertThat(report.delayedEstimated()).isEqualTo(1);
    }

    @Test
    @DisplayName("남의 일정에는 도착을 기록할 수 없다 (규칙 1)")
    void ownershipIsEnforcedOnArrival() {
        Long scheduleId = givenSchedule(arriveBy);

        assertThatThrownBy(
                        () ->
                                arrivalService.record(
                                        strangerId, scheduleId, new ArrivalRequest(arriveBy, true)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN_RESOURCE);
    }

    @Test
    @DisplayName("취소된 일정에는 도착을 기록할 수 없다")
    void canceledScheduleRejectsArrival() {
        Long scheduleId = givenSchedule(arriveBy);
        Schedule schedule = scheduleRepository.findById(scheduleId).orElseThrow();
        schedule.cancel();
        scheduleRepository.save(schedule);

        assertThatThrownBy(() -> report(scheduleId, arriveBy))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
    }

    // ------------------------------------------------------------------
    // 집계
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TIGHT 은 정시에도 지연에도 들어가지 않는다 — 전체에서 빼야 나온다")
    void tightBelongsToNeitherBucket() {
        report(givenSchedule(arriveBy), arriveBy.minusMinutes(30)); // ON_TIME
        report(givenSchedule(arriveBy.plusHours(1)), arriveBy.plusMinutes(58)); // TIGHT
        report(givenSchedule(arriveBy.plusHours(2)), arriveBy.plusHours(3)); // LATE_RISK

        ReportResponse report = reports().content().get(0);

        assertThat(report.totalSchedules()).isEqualTo(3);
        assertThat(report.onTimeEstimated()).isEqualTo(1);
        assertThat(report.delayedEstimated()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 주의 일정들은 리포트 한 행에 모인다 (월~일)")
    void oneReportPerWeek() {
        report(givenSchedule(arriveBy), arriveBy.minusMinutes(30));
        report(givenSchedule(arriveBy.plusDays(2)), arriveBy.plusDays(2).minusMinutes(30));

        PageResponse<ReportResponse> reports = reports();

        assertThat(reports.totalElements()).isEqualTo(1);
        assertThat(reports.content().get(0).periodStart().getDayOfWeek().getValue()).isEqualTo(1);
        assertThat(reports.content().get(0).periodEnd())
                .isEqualTo(reports.content().get(0).periodStart().plusDays(6));
        assertThat(reports.content().get(0).totalSchedules()).isEqualTo(2);
    }

    @Test
    @DisplayName("귀속 주는 보고 시각이 아니라 일정의 도착 목표일로 정해진다")
    void weekIsDecidedByArriveByNotReportTime() {
        report(givenSchedule(arriveBy), arriveBy.minusMinutes(30));

        LocalDate expected = WeeklyReportProvider.weekStart(arriveBy.toLocalDate());
        assertThat(reports().content().get(0).periodStart()).isEqualTo(expected);
    }

    // ------------------------------------------------------------------
    // 조회
    // ------------------------------------------------------------------

    @Test
    @DisplayName("이동 기록 요약은 페이지가 아니라 조회 구간 전체를 센다")
    void historySummaryCoversWholeRange() {
        report(givenSchedule(arriveBy), arriveBy.minusMinutes(30)); // ON_TIME
        report(givenSchedule(arriveBy.plusHours(1)), arriveBy.plusMinutes(58)); // TIGHT
        report(givenSchedule(arriveBy.plusHours(2)), arriveBy.plusHours(3)); // LATE_RISK

        LocalDate day = arriveBy.toLocalDate();
        // 정렬까지 컨트롤러와 같은 조건으로 — fetch join 과 중첩 속성 정렬이 함께 쓰이는 쿼리다
        HistoryResponse history =
                reportQueryService.history(
                        ownerId,
                        day,
                        day,
                        PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "schedule.arriveBy")));

        assertThat(history.items().content()).hasSize(1);
        // 내림차순이므로 가장 늦은 일정이 먼저 온다
        assertThat(history.items().content().get(0).arriveBy()).isEqualTo(arriveBy.plusHours(2));
        assertThat(history.summary().totalCount()).isEqualTo(3);
        assertThat(history.summary().onTimeCount()).isEqualTo(1);
        assertThat(history.summary().tightCount()).isEqualTo(1);
        assertThat(history.summary().lateCount()).isEqualTo(1);
        assertThat(history.summary().onTimeRate()).isEqualByComparingTo("0.33");
        assertThat(history.summary().avgDurationMin()).isNull(); // Phase 3 이전
    }

    @Test
    @DisplayName("기록이 없는 기간도 200 이고 요약은 0 이다 — SUM 이 null 로 와도 터지지 않는다")
    void emptyRangeIsNotAnError() {
        LocalDate day = LocalDate.now(BusinessTime.ZONE).plusYears(1);
        HistoryResponse history =
                reportQueryService.history(ownerId, day, day, PageRequest.of(0, 20));

        assertThat(history.items().content()).isEmpty();
        assertThat(history.summary().totalCount()).isZero();
        assertThat(history.summary().onTimeRate()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("이동 기록은 일정 도착일 기준으로 걸러진다 — 구간 밖 일정은 안 나온다")
    void historyFiltersByArriveBy() {
        report(givenSchedule(arriveBy), arriveBy.minusMinutes(30));
        report(givenSchedule(arriveBy.plusDays(2)), arriveBy.plusDays(2).minusMinutes(30));

        LocalDate day = arriveBy.toLocalDate();
        HistoryResponse history =
                reportQueryService.history(ownerId, day, day, PageRequest.of(0, 20));

        assertThat(history.summary().totalCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("리포트 상세는 그 주의 일정별 결과를 함께 준다")
    void reportDetailIncludesItems() {
        report(givenSchedule(arriveBy), arriveBy.minusMinutes(30));
        report(givenSchedule(arriveBy.plusDays(1)), arriveBy.plusDays(1).plusHours(1));

        Long reportId = reports().content().get(0).id();
        ReportDetailResponse detail = reportQueryService.detail(ownerId, reportId);

        assertThat(detail.report().totalSchedules()).isEqualTo(2);
        assertThat(detail.reportDetails()).hasSize(2);
        assertThat(detail.reportDetails().get(0).title()).isEqualTo("회의");
        assertThat(detail.reportDetails().get(0).arriveBy()).isEqualTo(arriveBy);
    }

    @Test
    @DisplayName("남의 리포트는 403, 없는 리포트는 404")
    void ownershipIsEnforcedOnReport() {
        report(givenSchedule(arriveBy), arriveBy.minusMinutes(30));
        Long reportId = reports().content().get(0).id();

        assertThatThrownBy(() -> reportQueryService.detail(strangerId, reportId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN_RESOURCE);

        assertThatThrownBy(() -> reportQueryService.detail(ownerId, 999_999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
    }

    @Test
    @DisplayName("리포트 목록의 기간 필터는 걸치기만 해도 포함한다")
    void reportListIncludesOverlappingWeeks() {
        report(givenSchedule(arriveBy), arriveBy.minusMinutes(30));

        LocalDate midWeek = arriveBy.toLocalDate();
        PageResponse<ReportResponse> filtered =
                reportQueryService.list(ownerId, midWeek, midWeek, PageRequest.of(0, 20));

        assertThat(filtered.totalElements()).isEqualTo(1);
        assertThat(filtered.content().get(0).periodStart()).isBefore(midWeek.plusDays(1));
    }

    private PageResponse<ReportResponse> reports() {
        return reportQueryService.list(ownerId, null, null, PageRequest.of(0, 20));
    }
}
