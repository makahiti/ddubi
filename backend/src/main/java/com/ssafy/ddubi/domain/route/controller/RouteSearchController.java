package com.ssafy.ddubi.domain.route.controller;

import com.ssafy.ddubi.domain.route.dto.RouteSearchRequest;
import com.ssafy.ddubi.domain.route.dto.RouteSearchResponse;
import com.ssafy.ddubi.domain.route.service.RouteSearchService;
import com.ssafy.ddubi.global.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 프론트가 호출하는 대중교통 경로 검색 HTTP API. */
@RestController
@RequestMapping("/api/v1/routes")
public class RouteSearchController {

    // Controller는 직접 카카오를 호출하지 않고 Service에 일을 맡긴다.
    private final RouteSearchService routeSearchService;

    public RouteSearchController(RouteSearchService routeSearchService) {
        this.routeSearchService = routeSearchService;
    }

    /**
     * 프론트가 출발·도착 좌표를 보내는 우리 서버의 길찾기 입구다.
     *
     * <p>{@code @Valid}가 경도/위도 범위를 먼저 검사한다. 정상 요청은 공통 응답 형식인
     * {@link ApiResponse}로 감싸서 돌려준다.</p>
     */
    @PostMapping("/search")
    public ResponseEntity<ApiResponse<RouteSearchResponse>> search(
            @Valid @RequestBody RouteSearchRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(routeSearchService.search(request)));
    }
}
