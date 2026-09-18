package com.ssafy.ddubi.domain.report.service;

import com.ssafy.ddubi.domain.report.dto.request.ArrivalRequest;
import com.ssafy.ddubi.domain.report.dto.response.ArrivalResponse;
import com.ssafy.ddubi.domain.report.entity.ArrivalResult;
import com.ssafy.ddubi.domain.report.entity.Report;
import com.ssafy.ddubi.domain.report.entity.ReportDetail;
import com.ssafy.ddubi.domain.report.repository.ReportDetailRepository;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.util.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ARRIVAL-001 도착 결과 저장.
 *
 * <h3>추천(Phase 3)을 기다리지 않는 이유</h3>
 * 명세서는 "최신 추천으로 planned_depart 결정" 이라고 하지만, <b>핵심인 result 판정에는
 * 추천이 필요 없다.</b> 목표 시각과 실제 도착만 있으면 나오고 둘 다 이미 있는 값이다.
 * 추천 의존 컬럼은 전부 NULL 허용이라 비워둬도 스키마가 성립한다.
 *
 * <h3>지키는 규칙</h3>
 * <ul>
 *   <li>규칙 1 소유권 — 남의 일정은 403</li>
 *   <li>규칙 9 실측 저장 — 계정 동의 AND 요청 동의</li>
 * </ul>
 */
@Service
public class ArrivalService {

    private static final Logger log = LoggerFactory.getLogger(ArrivalService.class);

    /**
     * 기기 시계가 서버보다 조금 빠른 것까지 막지는 않는다.
     * 이 폭을 넘는 미래 시각은 시계 오류이거나 조작이라 받지 않는다 —
     * 미래 도착을 그대로 저장하면 "정시 도착" 통계가 조용히 부풀려진다.
     */
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(1);

    private final ScheduleRepository scheduleRepository;
    private final ReportDetailRepository reportDetailRepository;
    private final WeeklyReportProvider weeklyReportProvider;

    /** 목표 시각 대비 이만큼 이상 일찍 도착해야 ON_TIME. 그 안쪽은 TIGHT. */
    private final Duration onTimeMargin;

    public ArrivalService(ScheduleRepository scheduleRepository,
                          ReportDetailRepository reportDetailRepository,
                          WeeklyReportProvider weeklyReportProvider,
                          @Value("${ddubi.report.on-time-margin}") Duration onTimeMargin) {
        this.scheduleRepository = scheduleRepository;
        this.reportDetailRepository = reportDetailRepository;
        this.weeklyReportProvider = weeklyReportProvider;
        this.onTimeMargin = onTimeMargin;
    }

    @Transactional
    public ArrivalResponse record(Long userId, Long scheduleId, ArrivalRequest request) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        if (!schedule.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        // 뒤 작업을 하기 전에 막는다 — 취소된 일정에 도착이 있을 수는 없다.
        if (schedule.getStatus() == ScheduleStatus.CANCELED) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "취소된 일정은 도착을 기록할 수 없습니다.");
        }

        User user = schedule.getUser();
        LocalDateTime actualArrival = resolveActualArrival(user, request);
        ArrivalResult result = judge(schedule.getArriveBy(), actualArrival);

        Report report = weeklyReportProvider.findOrCreate(user, schedule.getArriveBy().toLocalDate());

        ReportDetail detail = reportDetailRepository.findByScheduleId(scheduleId)
                .orElse(null);
        if (detail == null) {
            detail = reportDetailRepository.save(ReportDetail.builder()
                    .report(report)
                    .schedule(schedule)
                    .actualArrival(actualArrival)
                    .result(result)
                    .build());
        } else {
            // 재보고 — 행을 늘리면 집계가 2건이 된다(UK_RDETAIL_SCHEDULE 로도 막혀 있다).
            detail.update(actualArrival, result);
        }

        schedule.markDone();
        recount(report);

        log.debug("도착 기록 userId={} scheduleId={} result={} actual={}",
                userId, scheduleId, result, actualArrival);
        return ArrivalResponse.from(detail);
    }

    /**
     * 실측을 저장할지 판단한다 (규칙 9).
     *
     * 계정 동의({@code user.history_consented_at})와 이번 요청의 {@code locationConsent} 가
     * <b>둘 다</b> 있어야 한다. 요청 필드만 믿으면 계정 동의를 우회하게 되고, 계정 동의만
     * 믿으면 "이번엔 남기기 싫다" 를 표현할 방법이 없어진다.
     *
     * <b>저장하지 않아도 성공(200)이다.</b> 미동의를 오류로 만들면 앱이 일정을 영영 종료하지 못한다.
     */
    private LocalDateTime resolveActualArrival(User user, ArrivalRequest request) {
        if (!user.isHistoryConsented() || !Boolean.TRUE.equals(request.locationConsent())) {
            return null;
        }
        LocalDateTime actual = BusinessTime.normalize(request.actualArrival());
        if (actual == null) {
            return null;
        }
        if (actual.isAfter(BusinessTime.now().plus(CLOCK_SKEW_TOLERANCE))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "도착 시각이 미래입니다.");
        }
        return actual;
    }

    /**
     * 목표 시각과 실제 도착을 비교한다. 경계는 {@code ddubi.report.on-time-margin}(기본 10분).
     *
     * 목표 직전 도착을 정시로 세지 않는 이유: 배차 간격을 생각하면 여유가 그보다 적을 때는
     * 한 대만 놓쳐도 지각이다. "성공했지만 운이 좋았다" 를 성공으로 세면 리포트가
     * 실제보다 낙관적으로 보인다.
     */
    private ArrivalResult judge(LocalDateTime arriveBy, LocalDateTime actualArrival) {
        if (actualArrival == null) {
            return null;   // 실측이 없으면 판정할 근거가 없다 — 미동의 사용자
        }
        if (actualArrival.isAfter(arriveBy)) {
            return ArrivalResult.LATE_RISK;
        }
        if (actualArrival.isAfter(arriveBy.minus(onTimeMargin))) {
            return ArrivalResult.TIGHT;
        }
        return ArrivalResult.ON_TIME;
    }

    /**
     * 주간 집계를 다시 센다.
     *
     * 증분(+1)이 아니라 전건 재계산인 이유: 재보고로 판정이 TIGHT → LATE_RISK 로 바뀌면
     * 증분으로는 되돌릴 수 없다. 한 주 분량이라 세는 비용도 무시할 만하다.
     *
     * <b>TIGHT 은 어느 칸에도 넣지 않는다.</b> 정시에 넣으면 리포트가 낙관적으로 보이고,
     * 지연에 넣으면 지각한 적 없는 사용자에게 지연 건수가 뜬다.
     */
    private void recount(Report report) {
        List<ReportDetail> details = reportDetailRepository.findAllByReportId(report.getId());
        int onTime = 0;
        int delayed = 0;
        for (ReportDetail d : details) {
            if (d.getResult() == ArrivalResult.ON_TIME) {
                onTime++;
            } else if (d.getResult() == ArrivalResult.LATE_RISK) {
                delayed++;
            }
        }
        report.recount(details.size(), onTime, delayed, BusinessTime.now());
    }
}
