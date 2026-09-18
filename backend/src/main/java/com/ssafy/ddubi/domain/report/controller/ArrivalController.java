package com.ssafy.ddubi.domain.report.controller;

import com.ssafy.ddubi.domain.report.dto.request.ArrivalRequest;
import com.ssafy.ddubi.domain.report.dto.response.ArrivalResponse;
import com.ssafy.ddubi.domain.report.service.ArrivalService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "이동 기록", description = "도착 결과 저장 · 이동 이력 · 주간 리포트")
@RestController
@RequestMapping("/api/v1/schedules")
public class ArrivalController {

    private final ArrivalService arrivalService;

    public ArrivalController(ArrivalService arrivalService) {
        this.arrivalService = arrivalService;
    }

    @Operation(summary = "도착 결과 저장",
            description = """
                    일정을 완료 처리하고 이동 결과를 남긴다. ARRIVAL-001

                    · 실측(actualArrival)은 계정 동의와 요청의 locationConsent 가 둘 다 있어야 저장된다.
                      저장하지 않아도 200 이며 actualArrival·result 가 null 로 온다
                    · 같은 일정을 다시 보고하면 기존 결과를 갱신하고 같은 reportDetailId 를 준다(멱등)
                    · plannedDepart·proxyDurationMin·proxyArrival·mainCause 는 추천 엔진(Phase 3)
                      전까지 항상 null 이다
                    """)
    @PostMapping("/{scheduleId}/arrival")
    public ApiResponse<ArrivalResponse> record(
            @LoginUserId Long userId,
            @PathVariable Long scheduleId,
            @Valid @RequestBody ArrivalRequest request) {
        return ApiResponse.success(arrivalService.record(userId, scheduleId, request));
    }
}
