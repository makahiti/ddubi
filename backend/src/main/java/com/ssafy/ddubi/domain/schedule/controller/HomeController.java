package com.ssafy.ddubi.domain.schedule.controller;

import com.ssafy.ddubi.domain.schedule.dto.response.HomeResponse;
import com.ssafy.ddubi.domain.schedule.service.HomeService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Tag(name = "홈", description = "홈 화면 통합 요약")
@RestController
@RequestMapping("/api/v1/home")
public class HomeController {

    private final HomeService homeService;

    public HomeController(HomeService homeService) {
        this.homeService = homeService;
    }

    @Operation(summary = "홈 요약 조회",
            description = "다음 일정 · 오늘 일정 · 안읽음 알림 수 · 데이터 신선도. at 미지정 시 현재 시각. HOME-001")
    @GetMapping
    public ApiResponse<HomeResponse> summary(
            @LoginUserId Long userId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        return ApiResponse.success(homeService.summary(userId, at));
    }
}
