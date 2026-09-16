package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.notification.entity.Notification;
import com.ssafy.ddubi.domain.notification.entity.NotificationStatus;
import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import com.ssafy.ddubi.domain.notification.repository.NotificationRepository;
import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import com.ssafy.ddubi.domain.recommendation.evaluation.BusPreviewClient;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteEvidenceAssemblerTest;
import com.ssafy.ddubi.domain.route.client.PublicTransitRouteClient;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import com.ssafy.ddubi.domain.schedule.dto.request.LocationRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleCreateRequest;
import com.ssafy.ddubi.domain.schedule.service.ScheduleService;
import com.ssafy.ddubi.domain.user.dto.request.DeviceTokenRegisterRequest;
import com.ssafy.ddubi.domain.user.entity.DevicePlatform;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.domain.user.service.DeviceTokenService;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.push.PushResult;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 평가 결과로 실제 푸시가 나가는 경로 (프론트 연동 질의 1번).
 *
 * <p>여기서만 {@code auto-notify-enabled=true} 다. 기본값은 꺼짐이고, 꺼진 상태는
 * {@code ScheduleEvaluationIntegrationTest} 가 "notification_json 이 null" 로 고정한다.
 *
 * <p>배관 전체를 통과시킨다 — 평가가 outbox 에 쓰고, dispatcher 가 읽어
 * {@code NotificationService} 로 넘기고, 알림 행이 생기는 데까지.
 */
@TestPropertySource(properties = "ddubi.recommendation.auto-notify-enabled=true")
class EvaluationNotificationIntegrationTest extends IntegrationTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleEvaluationWorkflow workflow;
    @Autowired ScheduleEvaluationStore store;
    @Autowired EvaluationNotificationDispatcher dispatcher;
    @Autowired NotificationRepository notifications;
    @Autowired UserRepository users;
    @Autowired DeviceTokenService deviceTokens;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PublicTransitRouteClient routeClient;
    @MockitoBean BusPreviewClient mlClient;

    long owner;
    long scheduleId;
    Instant now;

    @BeforeEach
    void fixture() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        when(mlClient.predict(any())).thenReturn(BusPreviewClient.Estimate.unavailable("ML_NOT_CONFIGURED"));
        when(pushSender.send(any())).thenReturn(PushResult.ok());
        owner = users.save(User.builder().email("auto-notify@example.test").nickname("자동 알림").build()).getId();
        deviceTokens.register(owner, new DeviceTokenRegisterRequest("fcm-auto", DevicePlatform.ANDROID));
    }

    /** 도착 목표까지 {@code arriveInSeconds}. 짧게 잡으면 선택 경로가 지각으로 판정된다. */
    private void schedule(long arriveInSeconds) {
        scheduleId = scheduleService.create(owner, new ScheduleCreateRequest("자동 알림",
                new LocationRequest("강남", new BigDecimal("37.4979"), new BigDecimal("127.0276")),
                new LocationRequest("역삼", new BigDecimal("37.5006"), new BigDecimal("127.0364")),
                LocalDateTime.ofInstant(now.plusSeconds(arriveInSeconds), KST), null, null,
                LocalDateTime.ofInstant(now.plusSeconds(300), KST))).id();
        // 25분짜리와 10분짜리. 첫 후보가 선택되고 둘째가 15분 빠르다.
        when(routeClient.search(any())).thenReturn(new RouteSearchResult("KAKAO", now, null,
                List.of(RouteEvidenceAssemblerTest.candidate(0, 1500, 30),
                        RouteEvidenceAssemblerTest.candidate(1, 600, 30))));
    }

    private String outboxJson() {
        return jdbc.queryForObject("SELECT notification_json FROM schedule_evaluation WHERE schedule_id=?",
                String.class, scheduleId);
    }

    private String outboxKey() {
        return jdbc.queryForObject("SELECT notification_key FROM schedule_evaluation WHERE schedule_id=?",
                String.class, scheduleId);
    }

    private List<Notification> sent() {
        return notifications.findAll();
    }

    // ------------------------------------------------------------------


    @Test
    @DisplayName("제시간 도착이 어려우면 평가가 알림을 예약하고 dispatcher 가 보낸다")
    void latenessBecomesAPush() {
        schedule(600);

        workflow.evaluate(scheduleId, owner, TriggerType.INITIAL, now);

        assertThat(outboxKey()).as("평가가 outbox 에 남긴다").startsWith("ON_TIME_UNLIKELY:");
        assertThat(sent()).as("dispatcher 를 돌리기 전에는 아직 안 보낸다").isEmpty();

        dispatcher.dispatch(scheduleId);

        assertThat(sent()).singleElement().satisfies(notification -> {
            assertThat(notification.getType()).isEqualTo(NotificationType.ON_TIME_UNLIKELY);
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(notification.getTitle()).isEqualTo("제시간 도착이 어려울 수 있어요");
            assertThat(notification.getBody()).contains("예상 도착", "목표")
                    .as("대안이 있으면 알려 준다").contains("더 빠른 경로가 있습니다");
            assertThat(notification.scheduleId()).isEqualTo(scheduleId);
        });
    }

    /**
     * 평가는 분마다 돈다. 상황이 그대로인데 매번 알리면 사용자는 앱을 지운다.
     * 키에 시각을 넣지 않은 이유가 이것이다 — 1초씩 밀리는 값을 키에 넣으면 매번 새 알림이 된다.
     */
    @Test
    @DisplayName("같은 상황이 이어지면 다시 보내지 않는다")
    void doesNotRepeatWhileNothingChanges() {
        schedule(600);
        workflow.evaluate(scheduleId, owner, TriggerType.INITIAL, now);
        dispatcher.dispatch(scheduleId);
        String firstKey = outboxKey();

        workflow.evaluate(scheduleId, owner, TriggerType.REFRESH, now.plusSeconds(60));
        dispatcher.dispatch(scheduleId);
        dispatcher.dispatch(scheduleId);

        assertThat(outboxKey()).as("상황이 같으면 키도 같다").isEqualTo(firstKey);
        assertThat(sent()).hasSize(1);
    }

    @Test
    @DisplayName("지각은 아니지만 훨씬 빠른 경로가 있으면 경로 변경을 알린다")
    void suggestsAFasterRoute() {
        schedule(7200);

        workflow.evaluate(scheduleId, owner, TriggerType.INITIAL, now);
        dispatcher.dispatch(scheduleId);

        assertThat(outboxKey()).startsWith("ROUTE_CHANGE:");
        assertThat(sent()).singleElement().satisfies(notification -> {
            assertThat(notification.getType()).isEqualTo(NotificationType.ROUTE_CHANGE);
            assertThat(notification.getTitle()).isEqualTo("더 빠른 경로가 있어요");
            assertThat(notification.getBody()).contains("15분 빠른 경로");
        });
    }

    /**
     * 알림을 열었다고 수락까지 열리지 않는다. 알림은 "확인해 보세요" 지만 수락은 사용자의
     * 출발 시각을 실제로 바꾼다 — 미승인 모델 값으로 할 일이 아니다.
     */
    @Test
    @DisplayName("알림을 켜도 추천 수락은 여전히 막혀 있다")
    void enablingNotificationDoesNotUnblockAccept() {
        schedule(600);
        var result = workflow.evaluate(scheduleId, owner, TriggerType.INITIAL, now);
        dispatcher.dispatch(scheduleId);

        assertThat(sent()).hasSize(1);
        assertThatThrownBy(() -> store.accept(scheduleId, owner, result.evaluationId(), now))
                .isInstanceOf(BusinessException.class);
    }

    /** 출발 확인으로 감시를 끝낸 일정에 남아 있던 알림이 뒤늦게 나가면 안 된다. */
    @Test
    @DisplayName("출발을 확인하면 대기 중이던 알림도 사라진다")
    void departureConfirmationClearsPendingNotification() {
        schedule(600);
        workflow.evaluate(scheduleId, owner, TriggerType.INITIAL, now);
        assertThat(outboxKey()).isNotNull();

        store.stop(scheduleId, owner);
        dispatcher.dispatch(scheduleId);

        // 발송을 막는 것은 notification_json 이고 stop 은 그것을 지운다. notification_key 는
        // 남지만 dispatcher 가 json 부터 보므로 나가지 않는다.
        assertThat(outboxJson()).as("대기 중이던 알림 내용이 사라진다").isNull();
        assertThat(sent()).isEmpty();
    }
}
