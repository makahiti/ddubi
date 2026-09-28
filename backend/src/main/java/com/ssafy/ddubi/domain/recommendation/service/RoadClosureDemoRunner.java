package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import com.ssafy.ddubi.domain.notification.service.NotificationCommand;
import com.ssafy.ddubi.domain.notification.service.NotificationService;
import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import com.ssafy.ddubi.domain.recommendation.evaluation.ConditionalDeparturePolicy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 발표용 도로 통제를 한 번 건다: 일정 전용 Redis 통제 기록 → 저장된 후보 즉시 재평가 → 우회 경로가 나오면 본인에게 알림.
 *
 * <p>앱의 상세 화면(첫 결과를 본 10초 뒤)과 서버의 자동 시작({@link RoadClosureDemoAutoTrigger}, 첫 계산 10초 뒤)이 같이 쓴다. 통제
 * 기록은 일정·입력 버전마다 한 번만 들어가므로 둘이 겹쳐도 알림은 한 번이다. 기본 설정에서는 Bean 자체가 없다.
 */
@Service
@ConditionalOnProperty(name = "ddubi.demo.road-closure.enabled", havingValue = "true")
public class RoadClosureDemoRunner {
    private final RoadClosureDemoSupport demo;
    private final ScheduleEvaluationStore store;
    private final ScheduleEvaluationWorkflow workflow;
    private final NotificationService notifications;

    public RoadClosureDemoRunner(
            RoadClosureDemoSupport demo,
            ScheduleEvaluationStore store,
            ScheduleEvaluationWorkflow workflow,
            NotificationService notifications) {
        this.demo = demo;
        this.store = store;
        this.workflow = workflow;
        this.notifications = notifications;
    }

    /**
     * 결과: state(ALTERNATIVE_READY·NO_ALTERNATIVE·ALREADY_APPLIED), demo, newEvent,
     * notificationStatus.
     */
    public Map<String, Object> run(long scheduleId, long userId, Instant now) {
        // PENDING/RUNNING/STALE 결과를 보고도 시작하도록 허용하면 아직 계산되지 않은
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
            var event = demo.event(scheduleId).orElse(null);
            String route = event == null ? "" : event.path("routeName").asText("");
            String from = event == null ? "" : event.path("fromStop").asText("");
            String to = event == null ? "" : event.path("toStop").asText("");
            String title = "[시연] " + (route.isBlank() ? "버스" : route + "번 버스") + " 경로에 도로 통제가 생겼어요";
            String body =
                    from.isBlank() || to.isBlank()
                            ? "선택 경로 대신 이용 가능한 다른 경로를 확인해 주세요."
                            : from + " → " + to + " 구간이 통제돼요. 다른 경로를 확인해 주세요.";
            try {
                notificationStatus =
                        notifications
                                .create(
                                        NotificationCommand.builder()
                                                .userId(userId)
                                                .scheduleId(scheduleId)
                                                .type(NotificationType.ROUTE_CHANGE)
                                                .title(title)
                                                .body(body)
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
        return response;
    }
}
