package com.ssafy.ddubi.domain.recommendation.controller;

import com.ssafy.ddubi.domain.recommendation.dto.response.RecommendationExplanationResponse;
import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import com.ssafy.ddubi.domain.recommendation.service.*;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/**
 * 인증 사용자의 일정 평가·최근 결과·제안 수락·출발 확인 API다. 소유권은 store가 매 요청 검사한다. 경로·도착시각을 클라이언트에서 받아 신뢰하지 않고 서버에 저장된
 * 평가 ID만 수락한다.
 */
@RestController
@RequestMapping("/api/v1")
public class RecommendationController {
    private final ScheduleEvaluationWorkflow workflow;
    private final ScheduleEvaluationStore store;
    private final RecommendationService recommendationService;
    private final RecommendationGuideService guideService;

    public RecommendationController(
            ScheduleEvaluationWorkflow workflow,
            ScheduleEvaluationStore store,
            RecommendationService recommendationService,
            RecommendationGuideService guideService) {
        this.workflow = workflow;
        this.store = store;
        this.recommendationService = recommendationService;
        this.guideService = guideService;
    }

    /** 기존 프론트 body 형태를 수용한다. forceRealtime는 최신값 확인 요청이지 매번 카카오 호출하라는 뜻이 아니다. */
    public record RefreshRequest(TriggerType trigger, Boolean forceRealtime) {}

    /** 서버가 방금 계산한 불변 평가 ID. arbitrary 출발시각·경로를 수락하지 않는다. */
    public record AcceptRequest(@NotBlank String evaluationId) {}

    @PostMapping("/schedules/{scheduleId}/recommendations")
    public ApiResponse<?> evaluate(
            @LoginUserId Long userId,
            @PathVariable long scheduleId,
            @RequestBody(required = false) RefreshRequest request,
            @RequestParam(defaultValue = "2") int version) {
        var result =
                workflow.evaluate(
                        scheduleId,
                        userId,
                        request == null || request.trigger() == null
                                ? TriggerType.REFRESH
                                : request.trigger(),
                        Instant.now());
        // 평가 성공 응답을 알림 서버 실패로 뒤집지 않는다. dispatcher는 scheduler가 재시도한다.
        return ApiResponse.success(version == 1 ? result : store.response(result, userId));
    }

    @GetMapping("/schedules/{scheduleId}/recommendations/latest")
    public ApiResponse<?> latest(
            @LoginUserId Long userId,
            @PathVariable long scheduleId,
            @RequestParam(defaultValue = "2") int version) {
        return ApiResponse.success(
                version == 1
                        ? store.latest(scheduleId, userId)
                        : store.latestV2(scheduleId, userId));
    }

    /** 계산 계약과 분리된 사용자용 카드 문구다. GMS 장애 시에도 서버 템플릿 결과를 반환한다. */
    @GetMapping("/schedules/{scheduleId}/recommendations/latest/guide")
    public ApiResponse<
                    com.ssafy.ddubi.domain.recommendation.dto.response.RecommendationGuideResponse>
            guide(@LoginUserId Long userId, @PathVariable long scheduleId) {
        return ApiResponse.success(guideService.guide(scheduleId, userId, Instant.now()));
    }

    @PostMapping("/schedules/{scheduleId}/recommendations/accept")
    public ApiResponse<Map<String, String>> accept(
            @LoginUserId Long userId,
            @PathVariable long scheduleId,
            @Valid @RequestBody AcceptRequest request) {
        store.accept(scheduleId, userId, request.evaluationId(), Instant.now());
        return ApiResponse.success(Map.of("state", "PENDING"));
    }

    @PostMapping("/schedules/{scheduleId}/departure")
    public ApiResponse<Map<String, String>> departed(
            @LoginUserId Long userId, @PathVariable long scheduleId) {
        store.stop(scheduleId, userId);
        return ApiResponse.success(Map.of("state", "STOPPED"));
    }

    @Operation(
            summary = "추천 근거",
            description = "저장된 추천의 계산 근거를 조회한다. preview=true면 미승인 모델로 계산한 검증용 추천이다.")
    @GetMapping("/recommendations/{recommendationId}/explanation")
    public ApiResponse<RecommendationExplanationResponse> explanation(
            @LoginUserId Long userId, @PathVariable Long recommendationId) {
        var explanation = recommendationService.explanation(userId, recommendationId);
        return ApiResponse.success(
                RecommendationExplanationResponse.from(
                        explanation.recommendation(), explanation.persistence()));
    }
}
