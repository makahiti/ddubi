package com.ssafy.ddubi.domain.notification.service;

import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import com.ssafy.ddubi.domain.notification.repository.NotificationRepository;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 아침 브리핑 (REQ-NTF-003).
 *
 * <p>사용자가 정한 {@code briefingTime} 에 <b>그날 남은 일정</b>을 한 건으로 요약해 보낸다.
 * 추천 시각이나 지연 예측은 넣지 않는다 — 그건 일정마다 따로 알리는 것이고, 아침에 한 번
 * 보내는 이 알림의 역할은 "오늘 뭐가 있더라" 를 답하는 것이다.
 *
 * <h3>하루 한 번을 DB 로 보장한다</h3>
 * Redis 억제 키는 TTL 이 10분이라 하루를 덮지 못한다. 그래서 오늘 이미 만든
 * {@code MORNING_BRIEF} 행이 있는지로 판단한다. 서버를 재시작해도, 스케줄러가 분마다
 * 돌아도 하루 한 번이다. (억제 키도 그대로 걸어 둔다 — 여러 인스턴스가 같은 분에
 * 동시에 도는 짧은 경합은 그쪽이 막는다.)
 *
 * <h3>너무 늦으면 보내지 않는다</h3>
 * 서버가 아침 내내 죽어 있다가 저녁에 올라왔다고 "아침 브리핑" 을 보내면 안 된다.
 * {@code briefingTime} 에서 {@code max-lateness} 를 넘기면 그날은 건너뛴다.
 *
 * <h3>보낼 일정이 없으면 보내지 않는다</h3>
 * "오늘 일정 0건" 을 알리려고 폰을 울릴 이유가 없다. 행도 만들지 않으므로, 그날 늦게
 * 일정을 추가하면 아직 창 안이라면 그때 브리핑이 나간다.
 */
@Service
public class MorningBriefService {

    private static final Logger log = LoggerFactory.getLogger(MorningBriefService.class);
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("HH:mm");
    /** 본문에 제목까지 적는 일정 수. 넘으면 "외 N건" 으로 줄인다 — 푸시는 두세 줄만 보인다. */
    private static final int LISTED = 3;

    private final UserRepository users;
    private final ScheduleRepository schedules;
    private final NotificationRepository notifications;
    private final NotificationService notificationService;
    private final Duration maxLateness;

    public MorningBriefService(UserRepository users, ScheduleRepository schedules,
                               NotificationRepository notifications, NotificationService notificationService,
                               @Value("${ddubi.briefing.max-lateness:2h}") Duration maxLateness) {
        this.users = users;
        this.schedules = schedules;
        this.notifications = notifications;
        this.notificationService = notificationService;
        this.maxLateness = maxLateness;
    }

    /**
     * 브리핑 시각이 지난 사용자에게 오늘 치를 보낸다. 한 사람의 실패가 다른 사람을 막지 않는다.
     *
     * @return 실제로 보낸 건수
     */
    public int dispatchDue(LocalDateTime now) {
        Set<Long> alreadyBriefed = Set.copyOf(
                notifications.findUserIdsNotifiedWith(NotificationType.MORNING_BRIEF, suppressKey(now)));

        int sent = 0;
        for (User user : users.findAllWithBriefingTimeAtOrBefore(now.toLocalTime())) {
            if (alreadyBriefed.contains(user.getId()) || tooLate(user.getBriefingTime(), now.toLocalTime())) {
                continue;
            }
            try {
                if (brief(user, now)) {
                    sent++;
                }
            } catch (RuntimeException failure) {
                log.warn("브리핑 실패 userId={} type={}", user.getId(), failure.getClass().getSimpleName());
            }
        }
        return sent;
    }

    /** @return 실제로 보냈으면 true. 오늘 남은 일정이 없으면 false 다. */
    @Transactional
    public boolean brief(User user, LocalDateTime now) {
        List<Schedule> today = schedules.findAllByUserIdAndStatusAndArriveByBetweenOrderByArriveBy(
                user.getId(), ScheduleStatus.UPCOMING, now, now.toLocalDate().atTime(LocalTime.MAX));
        if (today.isEmpty()) {
            return false;
        }
        notificationService.create(NotificationCommand.builder()
                .userId(user.getId())
                // 탭하면 제일 먼저 할 일로 간다. 브리핑은 여러 일정을 묶지만 갈 곳은 하나여야 한다.
                .scheduleId(today.get(0).getId())
                .type(NotificationType.MORNING_BRIEF)
                .title("오늘 일정 " + today.size() + "건")
                .body(body(today))
                // 이 키가 두 가지를 한다. 같은 분에 두 인스턴스가 겹치는 짧은 경합은 Redis 가
                // 막고, 하루 한 번은 이 값이 남은 행으로 판단한다. 날짜가 키 안에 있어야 한다.
                .suppressKey(suppressKey(now))
                .build());
        return true;
    }

    /** 그날의 브리핑을 가리키는 키. 날짜가 들어 있어 "오늘 이미 보냈는가" 를 이 값으로 묻는다. */
    private String suppressKey(LocalDateTime now) {
        return "MORNING_BRIEF:" + now.toLocalDate();
    }

    /** {@code 09:00 회사 · 14:00 병원 외 2건 · 오늘 일정을 확인해 보세요} */
    private String body(List<Schedule> today) {
        String listed = today.stream().limit(LISTED)
                .map(schedule -> schedule.getArriveBy().toLocalTime().format(AT) + " " + schedule.getTitle())
                .collect(Collectors.joining(" · "));
        int remaining = today.size() - LISTED;
        String times = remaining > 0 ? listed + " 외 " + remaining + "건" : listed;
        return times + " · 오늘 일정을 확인해 보세요";
    }

    /**
     * 브리핑 시각이 한참 지났는가.
     *
     * <p>자정을 넘긴 경우는 조회 단계에서 이미 걸러진다 — {@code briefing_time <= 지금 시각}
     * 이라 어제 놓친 브리핑은 애초에 대상이 아니다. 지난 브리핑을 다음 날 보내지 않는다.
     */
    private boolean tooLate(LocalTime briefingTime, LocalTime now) {
        return Duration.between(briefingTime, now).compareTo(maxLateness) > 0;
    }
}
