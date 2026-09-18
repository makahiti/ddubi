package com.ssafy.ddubi.domain.report.controller;

import com.ssafy.ddubi.domain.report.dto.response.ReportDetailResponse;
import com.ssafy.ddubi.domain.report.dto.response.ReportResponse;
import com.ssafy.ddubi.domain.report.service.ReportQueryService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.common.PageRequestParam;
import com.ssafy.ddubi.global.common.PageResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "이동 기록", description = "도착 결과 저장 · 이동 이력 · 주간 리포트")
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportQueryService reportQueryService;

    public ReportController(ReportQueryService reportQueryService) {
        this.reportQueryService = reportQueryService;
    }

    @Operation(summary = "리포트 목록",
            description = """
                    주간(월~일) 집계 리포트를 최신 주부터 준다. REPORT-001

                    from·to 를 주면 그 구간에 걸치는 주를 전부 준다(부분만 겹쳐도 포함).
                    둘 다 생략하면 전체다.

                    totalSchedules = 전체, onTimeEstimated = ON_TIME, delayedEstimated = LATE_RISK.
                    TIGHT 은 어느 쪽에도 들어가지 않으므로 전체에서 빼서 구한다.
                    """)
    @GetMapping
    public ApiResponse<PageResponse<ReportResponse>> list(
            @LoginUserId Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Valid @ModelAttribute PageRequestParam pageParam) {
        return ApiResponse.success(reportQueryService.list(userId, from, to,
                pageParam.toPageable(Sort.by(Sort.Direction.DESC, "periodStart"))));
    }

    @Operation(summary = "리포트 상세",
            description = "집계와 그 주의 일정별 결과를 함께 준다. 남의 리포트는 403. REPORT-002")
    @GetMapping("/{reportId}")
    public ApiResponse<ReportDetailResponse> detail(
            @LoginUserId Long userId,
            @PathVariable Long reportId) {
        return ApiResponse.success(reportQueryService.detail(userId, reportId));
    }
}
