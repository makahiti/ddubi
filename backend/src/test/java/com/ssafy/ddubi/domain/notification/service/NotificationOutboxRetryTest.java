package com.ssafy.ddubi.domain.notification.service;

import com.ssafy.ddubi.domain.notification.entity.*;
import com.ssafy.ddubi.domain.notification.repository.NotificationRepository;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.user.entity.*;
import com.ssafy.ddubi.domain.user.repository.*;
import com.ssafy.ddubi.global.push.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DB/FCM 경계를 mock하여 재시도 준비부터 발송까지의 본문 일치와 이미 발송한 이력 보존을 검증한다. */
class NotificationOutboxRetryTest {
    final NotificationRepository notifications=mock(NotificationRepository.class);
    final DeviceTokenRepository devices=mock(DeviceTokenRepository.class);
    final UserRepository users=mock(UserRepository.class);
    final PushSender sender=mock(PushSender.class);
    final User user=mock(User.class);
    final NotificationService service=new NotificationService(notifications,devices,mock(ScheduleRepository.class),
            users,mock(SuppressionStore.class),sender,Duration.ofMinutes(10));
    final Notification row=Notification.builder().user(user).type(NotificationType.ON_TIME_UNLIKELY)
            .title("이전 제목").body("예상 도착 09:10").status(NotificationStatus.FAILED).build();
    final NotificationCommand latest=NotificationCommand.builder().userId(1L).type(NotificationType.ON_TIME_UNLIKELY)
            .title("최신 제목").body("예상 도착 09:20").evaluationId("latest-evaluation").build();

    NotificationOutboxRetryTest() {
        when(user.getId()).thenReturn(1L); when(user.isAlarm()).thenReturn(true);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        ReflectionTestUtils.setField(row,"id",7L);
        when(notifications.findById(7L)).thenReturn(Optional.of(row));
        when(devices.findAllByUserIdAndValidIsTrue(1L)).thenReturn(List.of(
                DeviceToken.builder().user(user).fcmToken("fixture-token").platform(DevicePlatform.ANDROID).build()));
    }

    @Test void failedNotificationUsesLatestContentForBothPushAndPersistedHistory() {
        when(sender.send(any())).thenAnswer(call->{
            var push=call.getArgument(0,PushMessage.class);
            assertThat(push.title()).isEqualTo(row.getTitle()).isEqualTo("최신 제목");
            assertThat(push.body()).isEqualTo(row.getBody()).isEqualTo("예상 도착 09:20");
            assertThat(push.data()).containsEntry("notificationId","7").containsEntry("evaluationId","latest-evaluation");
            return PushResult.ok();
        });
        service.sendPrepared(service.prepareForOutbox(latest,7L));
        assertThat(row.getId()).isEqualTo(7L);
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.SENT);
        verify(notifications).saveAndFlush(row);
        verify(notifications,never()).save(any());
    }

    @Test void sentHistoryIsNotOverwrittenOrSentAgain() {
        row.markSent(LocalDateTime.of(2026,9,17,9,0));
        service.sendPrepared(service.prepareForOutbox(latest,7L));
        assertThat(row.getBody()).isEqualTo("예상 도착 09:10");
        assertThat(row.getTitle()).isEqualTo("이전 제목");
        verifyNoInteractions(sender);
        verify(notifications,never()).saveAndFlush(any());
    }
}