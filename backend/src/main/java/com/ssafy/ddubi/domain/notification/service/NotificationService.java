package com.ssafy.ddubi.domain.notification.service;

import com.ssafy.ddubi.domain.notification.entity.Notification;
import com.ssafy.ddubi.domain.notification.entity.NotificationStatus;
import com.ssafy.ddubi.domain.notification.repository.NotificationRepository;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.user.entity.DeviceToken;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.DeviceTokenRepository;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.push.PushMessage;
import com.ssafy.ddubi.global.push.PushResult;
import com.ssafy.ddubi.global.push.PushSender;
import com.ssafy.ddubi.global.util.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 알림 생성·억제·발송 (도메인 규칙 9).
 *
 * <b>추천 엔진(Phase 3)이 부르는 유일한 진입점</b>이다. 무엇을·언제 알릴지는 그쪽이 정하고,
 * 여기서는 "보낼지 말지 판정 → 행 생성 → FCM 전송 → 결과 기록" 만 한다.
 *
 * <h3>억제되어도 행은 만든다</h3>
 * 알림을 꺼둔 기간에도 행이 쌓여야 다시 켰을 때 알림함에서 그동안의 일을 볼 수 있다.
 * 억제율을 세려면 억제 사실 자체가 데이터여야 한다.
 *
 * <pre>
 * 1. 중복      Redis 에 suppress_key 선점 실패  → SUPPRESSED
 * 2. 알림 끔   user.is_alarm = false           → SUPPRESSED
 * 3. 기기 없음 유효한 FCM 토큰 0개              → SUPPRESSED
 * 4. 그 외      FCM 발송 → 성공 SENT / 실패 FAILED
 * </pre>
 *
 * 알림 on/off 는 사용자 토글이다. 서버가 시간대를 정해 자동 억제하는 DND 는 두지 않는다
 * (2026-09-03 확정) — 새벽 일정의 출발 알림이 조용히 사라진다.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final SuppressionStore suppressionStore;
    private final PushSender pushSender;
    private final Duration suppressTtl;

    public NotificationService(NotificationRepository notificationRepository,
                               DeviceTokenRepository deviceTokenRepository,
                               ScheduleRepository scheduleRepository,
                               UserRepository userRepository,
                               SuppressionStore suppressionStore,
                               PushSender pushSender,
                               @Value("${ddubi.notification.suppress-ttl}") Duration suppressTtl) {
        this.notificationRepository = notificationRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository;
        this.suppressionStore = suppressionStore;
        this.pushSender = pushSender;
        this.suppressTtl = suppressTtl;
    }

    /**
     * 알림을 만들고, 억제 대상이 아니면 보낸다.
     *
     * @return 생성된 알림. 억제됐어도 행은 존재한다
     */
    @Transactional
    public Notification create(NotificationCommand command) {
        User user = userRepository.findById(command.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // ① 중복 — 선점 실패는 TTL 안에 같은 알림을 이미 보냈다는 뜻이다.
        boolean acquired = command.suppressKey() == null
                || suppressionStore.tryAcquire(user.getId(), command.suppressKey(), suppressTtl);
        if (!acquired) {
            log.debug("알림 억제(중복) — userId={}, type={}", user.getId(), command.type());
            return save(command, user, NotificationStatus.SUPPRESSED);
        }

        // ② 알림 끔 — 행은 만들고 발송만 하지 않는다.
        if (!user.isAlarm()) {
            log.debug("알림 억제(알림 끔) — userId={}, type={}", user.getId(), command.type());
            return save(command, user, NotificationStatus.SUPPRESSED);
        }

        // ③ 받을 기기가 없다 — 로그인만 하고 앱을 안 깐 경우 등.
        List<DeviceToken> devices = deviceTokenRepository.findAllByUserIdAndValidIsTrue(user.getId());
        if (devices.isEmpty()) {
            log.debug("알림 억제(유효 기기 없음) — userId={}", user.getId());
            return save(command, user, NotificationStatus.SUPPRESSED);
        }

        Notification notification = save(command, user, NotificationStatus.SUPPRESSED);
        boolean anySent = dispatch(notification, command, devices);

        if (anySent) {
            notification.markSent(BusinessTime.now());
        } else {
            notification.markFailed();
            // 자리를 잡아둔 채로 두면 TTL 동안 재시도조차 막힌다.
            if (command.suppressKey() != null) {
                suppressionStore.release(user.getId(), command.suppressKey());
            }
        }
        return notification;
    }

    /**
     * 사용자의 모든 유효 기기로 보낸다. 기기 하나만 성공해도 발송으로 친다 —
     * 폰과 태블릿을 함께 쓰는데 태블릿이 죽었다고 알림을 실패로 볼 이유가 없다.
     */
    private boolean dispatch(Notification notification, NotificationCommand command,
                             List<DeviceToken> devices) {
        Map<String, String> data = payload(notification, command);
        boolean anySent = false;

        for (DeviceToken device : devices) {
            PushResult result = pushSender.send(new PushMessage(
                    device.getFcmToken(), command.title(), command.body(), data));

            if (result.sent()) {
                anySent = true;
            } else if (result.tokenInvalid()) {
                // REQ-NTF-004: 앱을 지운 기기에 계속 쏘면 실패율이 오르고 쿼터를 낭비한다.
                device.invalidate();
                log.info("기기 토큰 무효화 — deviceTokenId={}, 사유={}",
                        device.getId(), result.failureReason());
            }
        }
        return anySent;
    }

    /** 앱이 알림을 탭했을 때 어디로 갈지 판단할 값. 본문은 이미 title/body 에 있다. */
    private Map<String, String> payload(Notification notification, NotificationCommand command) {
        Map<String, String> data = new HashMap<>();
        data.put("notificationId", String.valueOf(notification.getId()));
        data.put("type", command.type().name());
        if (command.scheduleId() != null) {
            data.put("scheduleId", String.valueOf(command.scheduleId()));
        }
        return data;
    }

    private Notification save(NotificationCommand command, User user, NotificationStatus status) {
        requireText(command.title(), "title");
        requireText(command.body(), "body");
        // 일정은 선택이다 — DATA_STALE 처럼 특정 일정과 무관한 알림도 있다.
        Schedule schedule = command.scheduleId() == null ? null
                : scheduleRepository.findById(command.scheduleId()).orElse(null);

        return notificationRepository.save(Notification.builder()
                .user(user)
                .schedule(schedule)
                .recommendationId(command.recommendationId())
                .type(command.type())
                .title(command.title())
                .body(command.body())
                .suppressKey(command.suppressKey())
                .status(status)
                .build());
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, field + " 는 필수입니다.");
        }
    }
}
