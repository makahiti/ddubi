package com.ssafy.ddubi.domain.notification.service;

import com.ssafy.ddubi.domain.notification.entity.Notification;
import com.ssafy.ddubi.domain.notification.entity.NotificationStatus;
import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import com.ssafy.ddubi.domain.notification.repository.NotificationRepository;
import com.ssafy.ddubi.domain.user.dto.request.DeviceTokenRegisterRequest;
import com.ssafy.ddubi.domain.user.entity.DevicePlatform;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.DeviceTokenRepository;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.domain.user.service.DeviceTokenService;
import com.ssafy.ddubi.global.push.PushMessage;
import com.ssafy.ddubi.global.push.PushResult;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 알림 생성·억제·발송 (도메인 규칙 9).
 *
 * 고정하는 것: 억제 3경우 모두 행이 생성되고 status 가 정확할 것,
 * 껐다 켜도 알림함에 남을 것, 무효 토큰이 정리될 것(REQ-NTF-004).
 */
class NotificationSendIntegrationTest extends IntegrationTestSupport {

    private static final String FCM_TOKEN = "fcm-token-1";

    @Autowired
    private NotificationService notificationService;
    @Autowired
    private NotificationQueryService notificationQueryService;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private DeviceTokenService deviceTokenService;
    @Autowired
    private DeviceTokenRepository deviceTokenRepository;
    @Autowired
    private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder().email("push@ddubi.com").nickname("푸시").build());
        given(pushSender.send(any())).willReturn(PushResult.ok());
    }

    private void registerDevice() {
        deviceTokenService.register(user.getId(),
                new DeviceTokenRegisterRequest(FCM_TOKEN, DevicePlatform.ANDROID));
    }

    private NotificationCommand command(String suppressKey) {
        return NotificationCommand.builder()
                .userId(user.getId())
                .type(NotificationType.DEPART_SOON)
                .title("곧 출발하세요")
                .body("10분 뒤 출발하면 제시간에 도착해요.")
                .suppressKey(suppressKey)
                .build();
    }

    // ------------------------------------------------------------------
    // 정상 발송
    // ------------------------------------------------------------------

    @Test
    @DisplayName("유효 기기가 있고 알림이 켜져 있으면 발송하고 SENT 로 남긴다")
    void sends() {
        registerDevice();

        Notification result = notificationService.create(command("key-1"));

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(result.getSentAt()).isNotNull();
        verify(pushSender).send(any());
    }

    @Test
    @DisplayName("푸시 본문에 알림 id·type 을 실어 보낸다 — 앱이 탭했을 때 이동할 근거")
    void payloadCarriesRoutingData() {
        registerDevice();

        Notification result = notificationService.create(command("key-1"));

        ArgumentCaptor<PushMessage> captor = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushSender).send(captor.capture());
        PushMessage sent = captor.getValue();
        assertThat(sent.fcmToken()).isEqualTo(FCM_TOKEN);
        assertThat(sent.title()).isEqualTo("곧 출발하세요");
        assertThat(sent.data()).containsEntry("notificationId", String.valueOf(result.getId()));
        assertThat(sent.data()).containsEntry("type", "DEPART_SOON");
    }

    // ------------------------------------------------------------------
    // 억제 — 어느 경우에도 행은 만든다 (도메인 규칙 9)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("같은 suppressKey 가 TTL 안에 다시 오면 억제한다")
    void suppressesDuplicate() {
        registerDevice();
        notificationService.create(command("same-key"));

        Notification second = notificationService.create(command("same-key"));

        assertThat(second.getStatus()).isEqualTo(NotificationStatus.SUPPRESSED);
        assertThat(second.getSentAt()).isNull();
        verify(pushSender).send(any()); // 첫 건만 발송
        assertThat(notificationRepository.count()).as("억제돼도 행은 2건").isEqualTo(2);
    }

    @Test
    @DisplayName("suppressKey 가 다르면 중복이 아니다")
    void differentKeyIsNotDuplicate() {
        registerDevice();
        notificationService.create(command("key-1"));

        Notification second = notificationService.create(command("key-2"));

        assertThat(second.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("알림을 끄면 발송하지 않지만 행은 쌓이고, 다시 켜면 알림함에서 보인다")
    void suppressesWhenAlarmOff() {
        registerDevice();
        user.changeAlarm(false);
        userRepository.saveAndFlush(user);

        Notification result = notificationService.create(command("key-1"));

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SUPPRESSED);
        verify(pushSender, never()).send(any());
        // 껐다 켠 뒤에도 알림함에 남아 있다.
        assertThat(notificationQueryService.unreadCount(user.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("유효한 기기가 없으면 억제한다 — 로그인만 하고 앱을 안 깐 경우")
    void suppressesWhenNoDevice() {
        Notification result = notificationService.create(command("key-1"));

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SUPPRESSED);
        verify(pushSender, never()).send(any());
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 발송 실패
    // ------------------------------------------------------------------

    @Test
    @DisplayName("발송이 전부 실패하면 FAILED 다 — 억제와 구분해야 원인 추적이 된다")
    void marksFailed() {
        registerDevice();
        given(pushSender.send(any())).willReturn(PushResult.failed("UNAVAILABLE"));

        Notification result = notificationService.create(command("key-1"));

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(result.getSentAt()).isNull();
    }

    @Test
    @DisplayName("발송 실패 시 억제 선점을 풀어 재시도를 막지 않는다")
    void releasesSuppressionOnFailure() {
        registerDevice();
        given(pushSender.send(any())).willReturn(PushResult.failed("UNAVAILABLE"));
        notificationService.create(command("retry-key"));

        // 같은 키로 다시 시도 — 선점이 풀렸으므로 발송을 재시도해야 한다.
        given(pushSender.send(any())).willReturn(PushResult.ok());
        Notification retry = notificationService.create(command("retry-key"));

        assertThat(retry.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    // ------------------------------------------------------------------
    // 무효 토큰 정리 (REQ-NTF-004)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("FCM 이 토큰 무효를 알리면 해당 기기를 무효화한다")
    void invalidatesDeadToken() {
        registerDevice();
        given(pushSender.send(any())).willReturn(PushResult.invalidToken("UNREGISTERED"));

        Notification result = notificationService.create(command("key-1"));

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(deviceTokenRepository.findByFcmToken(FCM_TOKEN).orElseThrow().isValid())
                .as("앱을 지운 기기에 계속 쏘지 않는다").isFalse();
        // 다음 알림은 유효 기기가 없어 억제된다.
        assertThat(notificationService.create(command("key-2")).getStatus())
                .isEqualTo(NotificationStatus.SUPPRESSED);
    }

    @Test
    @DisplayName("suppressKey 가 없으면 중복 검사를 건너뛴다 — 항상 보내야 하는 알림")
    void noSuppressKeyAlwaysSends() {
        registerDevice();
        notificationService.create(command(null));

        Notification second = notificationService.create(command(null));

        assertThat(second.getStatus()).isEqualTo(NotificationStatus.SENT);
    }
}
