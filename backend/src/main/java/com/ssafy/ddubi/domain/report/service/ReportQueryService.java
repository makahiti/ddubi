package com.ssafy.ddubi.domain.report.service;

import com.ssafy.ddubi.domain.report.dto.response.HistoryItemResponse;
import com.ssafy.ddubi.domain.report.dto.response.HistoryResponse;
import com.ssafy.ddubi.domain.report.dto.response.HistorySummaryResponse;
import com.ssafy.ddubi.domain.report.dto.response.ReportDetailResponse;
import com.ssafy.ddubi.domain.report.dto.response.ReportResponse;
import com.ssafy.ddubi.domain.report.entity.ArrivalResult;
import com.ssafy.ddubi.domain.report.entity.Report;
import com.ssafy.ddubi.domain.report.repository.ReportDetailRepository;
import com.ssafy.ddubi.domain.report.repository.ReportRepository;
import com.ssafy.ddubi.global.common.PageResponse;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * HISTORY-001 · REPORT-001 · REPORT-002 조회.
 *
 * <h3>HISTORY 와 REPORT 의 기간 기준이 다르다 — 의도된 것이다</h3>
 * <pre>
 * REPORT   리포트 주차(월~일)와 겹치는 것
 * HISTORY  개별 일정의 arrive_by 가 구간 안에 있는 것
 * </pre>
 * 사용자는 이동 기록을 "지난주 화요일에 어디 갔더라" 로 찾지 집계 주차로 찾지 않는다.
 * 리포트는 반대로 주 단위가 단위 자체다.
 */
@Service
public class ReportQueryService {

    private final ReportRepository reportRepository;
    private final ReportDetailRepository reportDetailRepository;

    public ReportQueryService(ReportRepository reportRepository,
                              ReportDetailRepository reportDetailRepository) {
        this.reportRepository = reportRepository;
        this.reportDetailRepository = reportDetailRepository;
    }

    // ------------------------------------------------------------------
    // HISTORY-001 이동 기록 목록
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public HistoryResponse history(Long userId, LocalDate from, LocalDate to, Pageable pageable) {
        requireRange(from, to);
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.atTime(LocalTime.MAX).withNano(0);   // 그날 23:59:59 까지 포함

        var page = reportDetailRepository.findHistory(userId, start, end, pageable);
        var summary = HistorySummaryResponse.from(reportDetailRepository.summarizeHistory(
                userId, start, end,
                ArrivalResult.ON_TIME, ArrivalResult.TIGHT, ArrivalResult.LATE_RISK));

        return new HistoryResponse(summary, PageResponse.of(page, HistoryItemResponse::from));
    }

    // ------------------------------------------------------------------
    // REPORT-001 리포트 목록
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<ReportResponse> list(Long userId, LocalDate from, LocalDate to, Pageable pageable) {
        if (from == null || to == null) {
            return PageResponse.of(reportRepository.findAllByUserId(userId, pageable), ReportResponse::from);
        }
        requireRange(from, to);
        // 구간에 걸쳐 있기만 하면 준다 — 9/3~9/5 로 물으면 그 주 전체(9/1~9/7) 리포트가 나와야 한다
        var page = reportRepository
                .findAllByUserIdAndPeriodEndGreaterThanEqualAndPeriodStartLessThanEqual(
                        userId, from, to, pageable);
        return PageResponse.of(page, ReportResponse::from);
    }

    // ------------------------------------------------------------------
    // REPORT-002 리포트 상세
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ReportDetailResponse detail(Long userId, Long reportId) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        if (!report.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        }

        List<HistoryItemResponse> details = reportDetailRepository
                .findAllByReportIdWithSchedule(reportId).stream()
                .map(HistoryItemResponse::from)
                .toList();

        return new ReportDetailResponse(ReportResponse.from(report), details);
    }

    private void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "from 과 to 는 함께 지정해야 합니다.");
        }
        if (to.isBefore(from)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "to 는 from 이후여야 합니다.");
        }
    }
}
