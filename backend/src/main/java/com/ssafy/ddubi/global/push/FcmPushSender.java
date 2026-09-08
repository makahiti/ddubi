package com.ssafy.ddubi.global.push;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FCM 실제 발송. 자격증명이 설정돼 있을 때만 쓰인다(PushConfig).
 */
public class FcmPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);

    private final FirebaseMessaging messaging;

    public FcmPushSender(FirebaseMessaging messaging) {
        this.messaging = messaging;
    }

    @Override
    public PushResult send(PushMessage message) {
        Message.Builder builder = Message.builder()
                .setToken(message.fcmToken())
                .setNotification(Notification.builder()
                        .setTitle(message.title())
                        .setBody(message.body())
                        .build());
        if (message.data() != null) {
            builder.putAllData(message.data());
        }

        try {
            messaging.send(builder.build());
            return PushResult.ok();
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            // 앱을 지웠거나 토큰이 만료된 기기다. 다시 보내도 소용없으므로 토큰을 죽인다.
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                log.info("FCM 토큰 무효 — {}", code);
                return PushResult.invalidToken(String.valueOf(code));
            }
            // 쿼터 초과·FCM 장애 등. 토큰은 살아 있으니 다음 알림 때 다시 시도된다.
            log.warn("FCM 발송 실패 — {}", code);
            return PushResult.failed(String.valueOf(code));
        } catch (RuntimeException e) {
            log.warn("FCM 발송 중 오류: {}", e.getClass().getSimpleName());
            return PushResult.failed(e.getClass().getSimpleName());
        }
    }
}
