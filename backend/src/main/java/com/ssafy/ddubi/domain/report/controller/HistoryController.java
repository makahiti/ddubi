package com.ssafy.ddubi.domain.report.controller;

import com.ssafy.ddubi.domain.report.dto.response.HistoryResponse;
import com.ssafy.ddubi.domain.report.service.ReportQueryService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.common.PageRequestParam;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "이동 기록", description = "도착 결과 저장 · 이동 이력 · 주간 리포트")
@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    private final ReportQueryService reportQueryService;

    public HistoryController(ReportQueryService reportQueryService) {
        this.reportQueryService = reportQueryService;
    }

    @Operation(summary = "이동 기록 목록",
            description = """
                    기간 안의 이동 결과를 최근 순으로 준다. HISTORY-001

                    기간 기준은 일정의 도착 목표일(arriveBy)이지 리포트 주차가 아니다.
                    summary 는 페이지가 아니라 조회 구간 전체에 대한 집계다.
                    """)
    @GetMapping
    public ApiResponse<HistoryResponse> history(
            @LoginUserId Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Valid @ModelAttribute PageRequestParam pageParam) {
        return ApiResponse.success(reportQueryService.history(userId, from, to,
                pageParam.toPageable(Sort.by(Sort.Direction.DESC, "schedule.arriveBy"))));
    }
}
