package com.ssafy.ddubi.domain.route.controller;

import com.ssafy.ddubi.domain.route.service.TransitDataStatusService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.exception.*;
import com.ssafy.ddubi.global.security.LoginUserId;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 인증된 경로 API 사용자가 매핑 미적재와 ML 미지원을 구분하는 조회 경계다. 상태 변경·외부 호출은 없다. */
@RestController
@RequestMapping("/api/v1/routes")
public class TransitDataStatusController {
    private final TransitDataStatusService service;
    public TransitDataStatusController(TransitDataStatusService service) { this.service=service; }
    /** 사용자/일정 데이터나 설정 비밀값 없이 현재 활성 데이터의 범위만 반환한다. */
    @GetMapping("/data-status")
    public ApiResponse<Map<String,Object>> status(@LoginUserId Long userId) {
        if(userId==null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return ApiResponse.success(service.status(Instant.now()));
    }
}
