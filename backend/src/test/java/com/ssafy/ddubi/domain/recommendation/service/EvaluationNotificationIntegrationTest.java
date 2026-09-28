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
import com.ssafy.ddubi.domain.route.service.SegmentResolveRequest;
import com.ssafy.ddubi.domain.route.service.MasterDataTransitMappingResolver;
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
@TestPropertySource(properties = {"ddubi.recommendation.auto-notify-enabled=true", "ddubi.recommendation.shadow-preview-notify-enabled=true"})
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
    @Autowired EvaluationOutboxStore outbox;
    @MockitoBean PublicTransitRouteClient routeClient;
    @MockitoBean BusPreviewClient mlClient;
    // 알림 테스트는 가상 노선 A/B를 사용한다. 실제 재매핑 흐름은 실행하되 마스터 조회 경계는
    // 최초 카카오 fixture와 같은 판정을 반환하여 '상황 불변'을 명시적으로 보장한다.
    @MockitoBean MasterDataTransitMappingResolver mappingResolver;

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
        // 25분짜리와 10분짜리. 최초 평가에서는 15분 빠른 둘째 후보를 선택한다.
        var candidates = List.of(RouteEvidenceAssemblerTest.candidate(0, 1500, 30),
                RouteEvidenceAssemblerTest.candidate(1, 600, 30));
        when(routeClient.search(any())).thenReturn(new RouteSearchResult("KAKAO", now, null, candidates));
        when(mappingResolver.resolve(any())).thenAnswer(call -> {
            SegmentResolveRequest request = call.getArgument(0);
            return candidates.stream().flatMap(candidate -> candidate.segments().stream())
                    .filter(step -> step.mode() == request.mode() && step.providerRouteKey().equals(request.providerRouteKey()))
                    .findFirst().orElseThrow().mapping();
        });
    }

    private String outboxJson() {
        return jdbc.queryForObject("SELECT payload_json FROM evaluation_notification_outbox WHERE schedule_id=? ORDER BY id LIMIT 1",
                String.class, scheduleId);
    }

    private String outboxKey() {
        return jdbc.queryForObject("SELECT dedup_key FROM evaluation_notification_outbox WHERE schedule_id=? ORDER BY id LIMIT 1",
                String.class, scheduleId);
    }

    private void dispatch() {
        for(Long id:jdbc.queryForList("SELECT id FROM evaluation_notification_outbox WHERE schedule_id=?",Long.class,scheduleId)) dispatcher.dispatchOutbox(id);
    }
    private List<Notification> sent() {
        return notifications.findAll();
    }

    @Test void retryKeepsNotificationIdAndSendsOutsideTransaction() {
        schedule(600); workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        when(pushSender.send(any())).thenAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return PushResult.failed("TEMPORARY");
        });
        dispatch();
        long notificationId=sent().get(0).getId();
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_notification_outbox",String.class)).isEqualTo("PENDING");
        jdbc.update("UPDATE evaluation_notification_outbox SET next_attempt_at=?",ScheduleEvaluationStore.ts(now.minusSeconds(1)));
        when(pushSender.send(any())).thenReturn(PushResult.ok()); dispatch();
        assertThat(sent()).singleElement().satisfies(n->{assertThat(n.getId()).isEqualTo(notificationId);assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);});
    }

    @Test void retryAfterAlarmDisabledIsCanceledWithoutAnotherPush() {
        schedule(600); workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        when(pushSender.send(any())).thenReturn(PushResult.failed("TEMPORARY")); dispatch();
        jdbc.update("UPDATE user SET is_alarm=false WHERE id=?",owner);
        jdbc.update("UPDATE evaluation_notification_outbox SET next_attempt_at=?",ScheduleEvaluationStore.ts(now.minusSeconds(1)));
        dispatch();
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_notification_outbox",String.class)).isEqualTo("CANCELED");
        org.mockito.Mockito.verify(pushSender,org.mockito.Mockito.times(1)).send(any());
    }

    @Test void canceledInputInvalidatesPendingOutboxAtCommit() {
        schedule(600); workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        scheduleService.cancel(owner,scheduleId);
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_notification_outbox",String.class)).isEqualTo("CANCELED");
        dispatch(); org.mockito.Mockito.verifyNoInteractions(pushSender);
    }

    @Test void replacementEvaluationUpdatesPendingPayloadAndRejectsPreviousLease() {
        schedule(600);
        var first=workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        long id=jdbc.queryForObject("SELECT id FROM evaluation_notification_outbox",Long.class);
        var previous=outbox.claim(id,Instant.now());
        var latest=workflow.evaluate(scheduleId,owner,TriggerType.REFRESH,now.plusSeconds(1));
        assertThat(latest.evaluationId()).isNotEqualTo(first.evaluationId());
        assertThat(latest.selectedKey()).isEqualTo(first.selectedKey());
        assertThat(outbox.current(previous,Instant.now())).isFalse();
        assertThat(jdbc.queryForObject("SELECT evaluation_id FROM evaluation_notification_outbox",String.class)).isEqualTo(latest.evaluationId());
        when(pushSender.send(any())).thenAnswer(call->{
            var message=call.getArgument(0,com.ssafy.ddubi.global.push.PushMessage.class);
            assertThat(message.data()).containsEntry("evaluationId",latest.evaluationId())
                    .containsEntry("decision",latest.conditional().decision().name()).containsKey("notificationId");
            return PushResult.ok();
        });
        dispatch();
        assertThat(sent()).singleElement().satisfies(n->assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT));
    }

    @Test void expiredWorkerCannotSendOrAckReclaimedLease() {
        schedule(600); workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        long id=jdbc.queryForObject("SELECT id FROM evaluation_notification_outbox",Long.class);
        Instant claimAt=Instant.now();
        var first=outbox.claim(id,claimAt);
        jdbc.update("UPDATE evaluation_notification_outbox SET lease_until=?",ScheduleEvaluationStore.ts(claimAt.minusSeconds(1)));
        var second=outbox.claim(id,claimAt);
        assertThat(outbox.current(first,claimAt)).isFalse(); assertThat(outbox.current(second,claimAt)).isTrue();
        outbox.finish(first,"SENT",null,claimAt);
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_notification_outbox",String.class)).isEqualTo("SENDING");
        jdbc.update("UPDATE evaluation_notification_outbox SET attempts=5,lease_until=?",ScheduleEvaluationStore.ts(now.minusSeconds(1)));
        assertThat(outbox.claim(id,claimAt)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_notification_outbox",String.class)).isEqualTo("FAILED");
    }

    // ------------------------------------------------------------------


    @Test
    @DisplayName("제시간 도착이 어려우면 평가가 알림을 예약하고 dispatcher 가 보낸다")
    void latenessBecomesAPush() {
        schedule(600);

        workflow.evaluate(scheduleId, owner, TriggerType.INITIAL, now);

        assertThat(outboxKey()).as("평가가 outbox 에 남긴다").hasSize(64);
        assertThat(sent()).as("dispatcher 를 돌리기 전에는 아직 안 보낸다").isEmpty();

        dispatch();

        assertThat(sent()).singleElement().satisfies(notification -> {
            assertThat(notification.getType()).isEqualTo(NotificationType.ON_TIME_UNLIKELY);
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(notification.getTitle()).isEqualTo("제시간 도착이 어려울 수 있어요");
            assertThat(notification.getBody()).contains("도착 예상", "목표");
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
        var first = workflow.evaluate(scheduleId, owner, TriggerType.INITIAL, now);
        dispatch();
        String firstKey = outboxKey();

        var latest = workflow.evaluate(scheduleId, owner, TriggerType.REFRESH, now.plusSeconds(60));
        assertThat(latest.selectedKey()).isEqualTo(first.selectedKey());
        assertThat(latest.selected().assessment()).isEqualTo(first.selected().assessment());
        dispatch();
        dispatch();

        assertThat(outboxKey()).as("상황이 같으면 키도 같다").isEqualTo(firstKey);
        assertThat(sent()).hasSize(1);
    }

    @Test
    @DisplayName("최초 계산에서 이미 최선 후보를 선택했다면 불필요한 경로 변경을 알리지 않는다")
    void initialBestRouteDoesNotSendRedundantChange() {
        schedule(7200);

        workflow.evaluate(scheduleId, owner, TriggerType.INITIAL, now);
        dispatch();

        assertThat(sent()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_notification_outbox",Integer.class)).isZero();
    }

    /**
     * 기본 예상 수락은 모델 승인·알림 활성화와 독립된 사용자 동작이다.
     */
    @Test
    @DisplayName("기본 예상의 수락은 모델 운영 승인과 별개다")
    void providerAcceptanceIsIndependentOfNotificationFlag() {
        schedule(600);
        var result = workflow.evaluate(scheduleId, owner, TriggerType.INITIAL, now);
        dispatch();

        assertThat(sent()).hasSize(1);
        assertThat(store.response(result,owner).guidance().canAccept()).isTrue();
        store.accept(scheduleId,owner,result.evaluationId(),now);
    }

    /** 출발 확인으로 감시를 끝낸 일정에 남아 있던 알림이 뒤늦게 나가면 안 된다. */
    @Test
    @DisplayName("출발을 확인하면 대기 중이던 알림도 사라진다")
    void departureConfirmationClearsPendingNotification() {
        schedule(600);
        workflow.evaluate(scheduleId, owner, TriggerType.INITIAL, now);
        assertThat(outboxKey()).isNotNull();

        store.stop(scheduleId, owner);
        dispatch();

        // 발송을 막는 것은 notification_json 이고 stop 은 그것을 지운다. notification_key 는
        // 남지만 dispatcher 가 json 부터 보므로 나가지 않는다.
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_notification_outbox WHERE schedule_id=?",String.class,scheduleId)).isEqualTo("CANCELED");
        assertThat(sent()).isEmpty();
    }
    /** 같은 dedup의 outbox를 새 평가로 교체할 때 DB 알림 이력과 실제 푸시도 같은 본문이어야 한다. */
    @Test void replacementAfterPushFailureUpdatesExistingNotificationBody() {
        schedule(600);
        workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        when(pushSender.send(any())).thenReturn(PushResult.failed("TEMPORARY"));
        dispatch();
        long notificationId=sent().get(0).getId();
        String oldBody=sent().get(0).getBody();
        String dedup=outboxKey();
        // 계획 출발이 지나 현재시각 기준으로 재평가하면 ETA도 2분 늦어지지만 알림 의미/키는 같다.
        var latest=workflow.evaluate(scheduleId,owner,TriggerType.REFRESH,now.plusSeconds(420));
        assertThat(latest.selectedKey()).isEqualTo("candidate-1");
        assertThat(outboxKey()).isEqualTo(dedup);
        var command=store.read(outboxJson(),com.ssafy.ddubi.domain.notification.service.NotificationCommand.class);
        assertThat(command.body()).isNotEqualTo(oldBody);
        when(pushSender.send(any())).thenAnswer(call->{
            var message=call.getArgument(0,com.ssafy.ddubi.global.push.PushMessage.class);
            assertThat(message.body()).isEqualTo(command.body());
            assertThat(message.data()).containsEntry("notificationId",Long.toString(notificationId))
                    .containsEntry("evaluationId",latest.evaluationId());
            return PushResult.ok();
        });
        jdbc.update("UPDATE evaluation_notification_outbox SET next_attempt_at=?",ScheduleEvaluationStore.ts(now.minusSeconds(1)));
        dispatch();
        assertThat(sent()).singleElement().satisfies(n->{
            assertThat(n.getId()).isEqualTo(notificationId);
            assertThat(n.getBody()).isEqualTo(command.body());
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        });
    }
}
