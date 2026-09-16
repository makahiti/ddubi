package com.ssafy.ddubi.domain.route.controller;

import com.ssafy.ddubi.domain.route.dto.response.RouteDetailResponse;
import com.ssafy.ddubi.domain.route.dto.response.RouteMapResponse;
import com.ssafy.ddubi.domain.route.dto.response.RouteSummaryResponse;
import com.ssafy.ddubi.domain.route.service.RouteQueryService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 저장된 후보 경로 조회. 여기서는 외부 API 를 부르지 않는다 —
 * 경로를 새로 만드는 것은 {@code POST /schedules/{id}/recommendations} 의 몫이다.
 *
 * <p>소유권은 매 요청 확인한다. {@code routeId} 는 순번이라 남의 것을 찍어 볼 수 있다.
 */
@Tag(name = "경로", description = "저장된 후보 경로의 목록·상세·지도")
@RestController
@RequestMapping("/api/v1")
public class RouteQueryController {

    private final RouteQueryService routeQueryService;

    public RouteQueryController(RouteQueryService routeQueryService) {
        this.routeQueryService = routeQueryService;
    }

    @Operation(summary = "일정의 후보 경로 목록",
            description = "아직 평가하지 않은 일정은 빈 배열이다. 404 가 아니다 — 일정은 있고 경로만 없는 상태다.")
    @GetMapping("/schedules/{scheduleId}/routes")
    public ApiResponse<List<RouteSummaryResponse>> ofSchedule(
            @LoginUserId Long userId, @PathVariable Long scheduleId) {
        return ApiResponse.success(routeQueryService.ofSchedule(userId, scheduleId));
    }

    @Operation(summary = "경로 상세",
            description = "구간 분해를 준다. 지도용 좌표는 무거워서 빠져 있다 — /routes/{routeId}/map 을 쓴다.")
    @GetMapping("/routes/{routeId}")
    public ApiResponse<RouteDetailResponse> detail(
            @LoginUserId Long userId, @PathVariable Long routeId) {
        return ApiResponse.success(routeQueryService.detail(userId, routeId));
    }

    @Operation(summary = "경로 지도 좌표",
            description = "구간별 폴리라인과 전체 bounds. 도보·버스를 다르게 그릴 수 있도록 구간마다 mode 가 붙는다.")
    @GetMapping("/routes/{routeId}/map")
    public ApiResponse<RouteMapResponse> map(
            @LoginUserId Long userId, @PathVariable Long routeId) {
        return ApiResponse.success(routeQueryService.map(userId, routeId));
    }
}
