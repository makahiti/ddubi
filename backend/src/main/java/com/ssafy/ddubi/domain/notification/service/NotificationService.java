package com.ssafy.ddubi.domain.notification.service;

import com.ssafy.ddubi.domain.notification.entity.Notification;
import com.ssafy.ddubi.domain.notification.entity.NotificationStatus;
import com.ssafy.ddubi.domain.notification.entity.SuppressReason;
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
 * 1. 중복      Redis 에 suppress_key 선점 실패  → SUPPRESSED / DUPLICATE
 * 2. 알림 끔   user.is_alarm = false           → SUPPRESSED / ALARM_OFF
 * 3. 기기 없음 유효한 FCM 토큰 0개              → SUPPRESSED / NO_VALID_DEVICE
 * 4. 그 외      FCM 발송 → 성공 SENT / 실패 FAILED
 * </pre>
 *
 * 사유를 남기는 이유는 셋이 <b>해야 할 일이 다르기</b> 때문이다. status 만 보면 "중복이라
 * 안 보냈다" 와 "받을 기기가 없다" 가 같은 값이라, 연동 초기에 토큰이 없는 상태를 억제가
 * 잘 동작하는 것으로 오독하게 된다(2026-09-14 프론트 요청). {@link SuppressReason} 참고.
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

        // ⓪ 일정 검증을 억제 선점보다 먼저 한다. 거절할 요청이 먼저 키를 잡아 버리면
        //    그 뒤 TTL(10분) 동안 같은 키의 정상 알림까지 중복으로 몰려 막힌다.
        Schedule schedule = ownedSchedule(command, user);

        // ① 중복 — 선점 실패는 TTL 안에 같은 알림을 이미 보냈다는 뜻이다.
        boolean acquired = command.suppressKey() == null
                || suppressionStore.tryAcquire(user.getId(), command.suppressKey(), suppressTtl);
        if (!acquired) {
            log.debug("알림 억제(중복) — userId={}, type={}", user.getId(), command.type());
            return save(command, user, schedule, SuppressReason.DUPLICATE);
        }

        // ② 알림 끔 — 행은 만들고 발송만 하지 않는다.
        if (!user.isAlarm()) {
            log.debug("알림 억제(알림 끔) — userId={}, type={}", user.getId(), command.type());
            return save(command, user, schedule, SuppressReason.ALARM_OFF);
        }

        // ③ 받을 기기가 없다 — 로그인만 하고 앱을 안 깐 경우 등.
        List<DeviceToken> devices = deviceTokenRepository.findAllByUserIdAndValidIsTrue(user.getId());
        if (devices.isEmpty()) {
            log.debug("알림 억제(유효 기기 없음) — userId={}", user.getId());
            return save(command, user, schedule, SuppressReason.NO_VALID_DEVICE);
        }

        // 사유 없이 저장한다 — 억제가 아니라 이제 발송을 시도할 행이다.
        Notification notification = save(command, user, schedule, null);
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

    /** DB 준비 결과. FCM에 필요한 기기 스냅샷만 트랜잭션 밖으로 전달한다. */
    public record Prepared(Notification notification, NotificationCommand command, List<DeviceToken> devices) { }

    /** outbox 발송의 DB 단계다. 사용자 설정과 소유권을 확인하고 notificationId를 먼저 확정한다. */
    @Transactional
    public Prepared prepareForOutbox(NotificationCommand command,Long existingId) {
        User user=userRepository.findById(command.userId()).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Schedule schedule=ownedSchedule(command,user);
        List<DeviceToken> devices=deviceTokenRepository.findAllByUserIdAndValidIsTrue(user.getId());
        SuppressReason reason=!user.isAlarm()?SuppressReason.ALARM_OFF:devices.isEmpty()?SuppressReason.NO_VALID_DEVICE:null;
        // outbox의 DB unique dedup와 lease가 중복을 관리한다. Redis 선점 뒤 crash로 정상 재시도가
        // DUPLICATE 억제로 사라지는 것을 방지하기 위해 이 경로에서는 Redis suppress를 선점하지 않는다.
        Notification notification=existingId==null?save(command,user,schedule,reason):notificationRepository.findById(existingId).orElseThrow();
        if(!notification.isOwnedBy(user.getId()) || !java.util.Objects.equals(notification.scheduleId(),command.scheduleId()))
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        if(notification.getStatus()==NotificationStatus.SENT) return new Prepared(notification,command,List.of());
        // 최신 outbox payload와 인앱 본문을 같은 트랜잭션에서 맞춘다. 실패 후 재시도해도
        // 같은 notificationId를 쓰며, 위 SENT 분기는 과거 발송 이력의 덮어쓰기를 막는다.
        requireText(command.title(), "title");
        requireText(command.body(), "body");
        notification.refreshPendingContent(command.type(),command.title(),command.body(),command.recommendationId());
        if(reason!=null) notification.markSuppressed(reason);
        notificationRepository.flush();
        return new Prepared(notification,command,reason==null?List.copyOf(devices):List.of());
    }

    /**
     * FCM 호출은 반드시 DB 트랜잭션 밖에서 한다. 전송 후 각 repository의 짧은 트랜잭션으로 ack한다.
     * 전송 성공과 DB ack 사이 crash는 재전송 가능성이 있으므로 exactly-once가 아니다.
     */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Notification sendPrepared(Prepared prepared) {
        Notification notification=prepared.notification();
        if(prepared.devices().isEmpty()) return notification;
        boolean sent=false;
        try { sent=dispatch(notification,prepared.command(),prepared.devices()); }
        finally {
            if(sent) notification.markSent(BusinessTime.now()); else notification.markFailed();
            deviceTokenRepository.saveAll(prepared.devices());
            notificationRepository.saveAndFlush(notification);
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

    /**
     * 알림에 붙일 일정. 없으면 {@code null} 이다 — DATA_STALE 처럼 특정 일정과 무관한 알림도 있다.
     *
     * <h3>왜 소유자를 확인하나</h3>
     * 개발용 엔드포인트가 열려 있으면 <b>요청 본문의 scheduleId 가 그대로 여기까지 온다.</b>
     * 확인하지 않으면 남의 일정 ID를 자기 알림에 붙일 수 있고, 알림함에서 그 일정으로
     * 이동하는 화면이 남의 일정을 열게 된다(2026-09-15 리뷰 지적).
     *
     * <p>예전에는 없는 ID를 조용히 {@code null} 로 바꿨다. 그러면 화면은 "일정으로 가기" 가
     * 없는 알림을 받고 <b>왜 없는지 알 수 없다.</b> 잘못된 ID 는 사실대로 거절한다.
     *
     * <p>없음(404)과 남의 것(403)을 나눈 것은 이 저장소의 기존 방식을 따른 것이다
     * ({@code ScheduleEvaluationStore.owned}). ID 존재 여부가 드러나지만, 이 검증을 타는 경로는
     * 전부 인증된 사용자이고 추천 엔진이 부르는 내부 경로라 원인을 아는 편이 낫다고 봤다.
     */
    private Schedule ownedSchedule(NotificationCommand command, User user) {
        if (command.scheduleId() == null) {
            return null;
        }
        Schedule schedule = scheduleRepository.findById(command.scheduleId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        if (!schedule.isOwnedBy(user.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return schedule;
    }

    /** 앱이 알림을 탭했을 때 어디로 갈지 판단할 값. 본문은 이미 title/body 에 있다. */
    private Map<String, String> payload(Notification notification, NotificationCommand command) {
        Map<String, String> data = new HashMap<>();
        data.put("notificationId", String.valueOf(notification.getId()));
        data.put("type", command.type().name());
        if (command.evaluationId() != null) data.put("evaluationId", command.evaluationId());
        if (command.decision() != null) data.put("decision", command.decision());
        if (command.scheduleId() != null) {
            data.put("scheduleId", String.valueOf(command.scheduleId()));
        }
        return data;
    }

    /**
     * 행을 만든다. <b>상태는 항상 SUPPRESSED 로 시작한다.</b>
     *
     * <p>푸시 payload 에 넣을 id 가 먼저 있어야 해서 발송 전에 저장해야 하는데, 그 사이에
     * 프로세스가 죽으면 "보냈다" 로 남는 것보다 "안 보냈다" 로 남는 편이 안전하다.
     *
     * @param schedule 검증을 마친 일정. {@code null} 이면 일정과 무관한 알림이다
     * @param reason 확정된 억제 사유. {@code null} 이면 이제 발송을 시도할 행이라는 뜻이고,
     *               결과에 따라 SENT/FAILED 로 올라간다
     */
    private Notification save(NotificationCommand command, User user, Schedule schedule, SuppressReason reason) {
        requireText(command.title(), "title");
        requireText(command.body(), "body");

        return notificationRepository.save(Notification.builder()
                .user(user)
                .schedule(schedule)
                .recommendationId(command.recommendationId())
                .type(command.type())
                .title(command.title())
                .body(command.body())
                .suppressKey(command.suppressKey())
                .status(NotificationStatus.SUPPRESSED)
                .suppressReason(reason)
                .build());
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, field + " 는 필수입니다.");
        }
    }
}
