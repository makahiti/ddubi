package com.ssafy.ddubi.domain.recommendation.controller;

import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import com.ssafy.ddubi.domain.notification.service.NotificationCommand;
import com.ssafy.ddubi.domain.notification.service.NotificationService;
import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import com.ssafy.ddubi.domain.recommendation.evaluation.ConditionalDeparturePolicy;
import com.ssafy.ddubi.domain.recommendation.service.RoadClosureDemoSupport;
import com.ssafy.ddubi.domain.recommendation.service.ScheduleEvaluationStore;
import com.ssafy.ddubi.domain.recommendation.service.ScheduleEvaluationWorkflow;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 첫 결과를 그린 클라이언트만 호출하는 발표용 진입점이다. 기본 설정에서는 Bean 자체가 없다. 소유권·시연 제목·서울 좌표·선택 경로/대안 검증은 support에서 서버
 * 저장값으로 확인한다. 임의 도로 링크나 알림 대상 사용자 ID는 요청에서 받지 않는다.
 */
@RestController
@RequestMapping("/api/v1/dev/road-closure-demo")
@ConditionalOnProperty(name = "ddubi.demo.road-closure.enabled", havingValue = "true")
public class RoadClosureDemoController {
    private final RoadClosureDemoSupport demo;
    private final ScheduleEvaluationStore store;
    private final ScheduleEvaluationWorkflow workflow;
    private final NotificationService notifications;

    public RoadClosureDemoController(
            RoadClosureDemoSupport demo,
            ScheduleEvaluationStore store,
            ScheduleEvaluationWorkflow workflow,
            NotificationService notifications) {
        this.demo = demo;
        this.store = store;
        this.workflow = workflow;
        this.notifications = notifications;
    }

    /** Redis 기록 직후 캐시 후보를 재평가하고, 우회 경로가 실제로 나온 경우에만 본인에게 알린다. */
    @PostMapping("/schedules/{scheduleId}/activate")
    public ApiResponse<Map<String, Object>> activate(
            @LoginUserId Long userId, @PathVariable long scheduleId) {
        Instant now = Instant.now();
        // PENDING/RUNNING/STALE 결과를 보고도 시작하도록 허용하면 아직 사용자가 보지 못한
        // 선택 경로를 막을 수 있다. 인증 소유권과 READY 상태를 먼저 읽는다.
        store.latestOutcomeV2(scheduleId, userId);
        boolean inserted = demo.activate(scheduleId, userId, now);
        ScheduleEvaluationWorkflow.Outcome outcome;
        try {
            outcome = workflow.evaluate(scheduleId, userId, TriggerType.EVENT, now);
        } catch (RuntimeException failed) {
            if (inserted) demo.rollback(scheduleId);
            throw failed;
        }
        var alternative = outcome.suggestion().proposed();
        boolean available =
                alternative != null
                        && !alternative.journeyKey().equals(outcome.selectedKey())
                        && ConditionalDeparturePolicy.usable(alternative);
        String notificationStatus = inserted ? "NOT_SENT" : "ALREADY_HANDLED";
        if (available && inserted) {
            // 사용자 알림 설정/FCM 토큰/중복 억제는 기존 알림 서비스가 그대로 검사한다.
            // 평가 결과를 저장한 뒤 발송하므로 알림을 누르면 이미 새 제안이 보인다.
            try {
                notificationStatus =
                        notifications
                                .create(
                                        NotificationCommand.builder()
                                                .userId(userId)
                                                .scheduleId(scheduleId)
                                                .type(NotificationType.ROUTE_CHANGE)
                                                .title("[시연] 버스 경로에 도로 통제가 생겼어요")
                                                .body("선택 경로 대신 이용 가능한 다른 경로를 확인해 주세요.")
                                                .suppressKey(
                                                        "DEMO_TOPIS:"
                                                                + scheduleId
                                                                + ":"
                                                                + outcome.eventRevision())
                                                .evaluationId(outcome.evaluationId())
                                                .build())
                                .getStatus()
                                .name();
            } catch (RuntimeException deliveryFailed) {
                // FCM 장애가 이미 저장된 경로 재평가를 실패로 보이게 하지 않는다.
                notificationStatus = "FAILED";
            }
        }
        var response = new LinkedHashMap<String, Object>();
        response.put(
                "state",
                !inserted ? "ALREADY_APPLIED" : available ? "ALTERNATIVE_READY" : "NO_ALTERNATIVE");
        response.put("demo", true);
        response.put("newEvent", inserted);
        response.put("notificationStatus", notificationStatus);
        return ApiResponse.success(response);
    }
}
