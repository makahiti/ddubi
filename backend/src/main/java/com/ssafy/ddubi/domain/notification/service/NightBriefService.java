package com.ssafy.ddubi.domain.notification.service;

import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import com.ssafy.ddubi.domain.notification.repository.NotificationRepository;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 밤 브리핑.
 *
 * <p>사용자가 정한 {@code nightBriefingTime} 에 <b>내일 일정</b>을 한 건으로 요약해 보낸다.
 * 아침 브리핑이 "오늘 뭐가 있더라" 를 답한다면, 이쪽은 "내일 뭐 준비해야 하지" 를 답한다.
 *
 * <h3>왜 따로 만들었나</h3>
 * 아침 브리핑은 <b>오늘 남은</b> 일정만 본다. 그래서 저녁 시각으로 맞춰 두면, 그날 일정이
 * 이미 끝나 있어 아무것도 오지 않는다(2026-09-21 운영 확인). 보는 날짜가 다르므로 시각도
 * 설정도 따로 둔다.
 *
 * <h3>아침 브리핑과 같게 지키는 것</h3>
 *
 * <ul>
 *   <li>하루 한 번 — 오늘 만든 {@code NIGHT_BRIEF} 행이 있으면 건너뛴다
 *   <li>너무 늦으면 보내지 않는다 — 22시 브리핑을 새벽 2시에 보내지 않는다
 *   <li>보낼 일정이 없으면 보내지 않는다 — "내일 일정 0건" 으로 폰을 울리지 않는다
 * </ul>
 *
 * <p>자정을 넘기면 조회 단계에서 대상이 아니게 되므로(같은 날 안에서만 본다), "내일" 이
 * 가리키는 날이 도중에 어긋나지 않는다.
 */
@Service
public class NightBriefService {

    private static final Logger log = LoggerFactory.getLogger(NightBriefService.class);
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("HH:mm");

    /** 본문에 제목까지 적는 일정 수. 넘으면 "외 N건" 으로 줄인다 — 푸시는 두세 줄만 보인다. */
    private static final int LISTED = 3;

    private final UserRepository users;
    private final ScheduleRepository schedules;
    private final NotificationRepository notifications;
    private final NotificationService notificationService;
    private final Duration maxLateness;

    public NightBriefService(
            UserRepository users,
            ScheduleRepository schedules,
            NotificationRepository notifications,
            NotificationService notificationService,
            @Value("${ddubi.briefing.max-lateness:2h}") Duration maxLateness) {
        this.users = users;
        this.schedules = schedules;
        this.notifications = notifications;
        this.notificationService = notificationService;
        this.maxLateness = maxLateness;
    }

    /**
     * 밤 브리핑 시각이 지난 사용자에게 오늘 치를 보낸다. 한 사람의 실패가 다른 사람을 막지 않는다.
     *
     * @return 실제로 보낸 건수
     */
    public int dispatchDue(LocalDateTime now) {
        Set<Long> alreadyBriefed =
                Set.copyOf(
                        notifications.findUserIdsNotifiedWith(
                                NotificationType.NIGHT_BRIEF, suppressKey(now)));

        int sent = 0;
        for (User user : users.findAllWithNightBriefingTimeAtOrBefore(now.toLocalTime())) {
            if (alreadyBriefed.contains(user.getId())
                    || tooLate(user.getNightBriefingTime(), now.toLocalTime())) {
                continue;
            }
            try {
                if (brief(user, now)) {
                    sent++;
                }
            } catch (RuntimeException failure) {
                log.warn(
                        "밤 브리핑 실패 userId={} type={}",
                        user.getId(),
                        failure.getClass().getSimpleName());
            }
        }
        return sent;
    }

    /** @return 실제로 보냈으면 true. 내일 일정이 없으면 false 다. */
    @Transactional
    public boolean brief(User user, LocalDateTime now) {
        LocalDateTime tomorrowStart = now.toLocalDate().plusDays(1).atStartOfDay();
        List<Schedule> tomorrow =
                schedules.findAllByUserIdAndStatusAndArriveByBetweenOrderByArriveBy(
                        user.getId(),
                        ScheduleStatus.UPCOMING,
                        tomorrowStart,
                        tomorrowStart.toLocalDate().atTime(LocalTime.MAX));
        if (tomorrow.isEmpty()) {
            return false;
        }
        notificationService.create(
                NotificationCommand.builder()
                        .userId(user.getId())
                        // 탭하면 내일 제일 먼저 할 일로 간다.
                        .scheduleId(tomorrow.get(0).getId())
                        .type(NotificationType.NIGHT_BRIEF)
                        .title("내일 일정 " + tomorrow.size() + "건")
                        .body(body(tomorrow))
                        .suppressKey(suppressKey(now))
                        .build());
        return true;
    }

    /**
     * 그날 밤 브리핑을 가리키는 키.
     *
     * <p>날짜는 <b>보내는 날</b> 기준이다. 대상은 내일이지만, "오늘 이미 보냈는가" 를 묻는
     * 값이라 보내는 날로 묶어야 한다.
     */
    private String suppressKey(LocalDateTime now) {
        return "NIGHT_BRIEF:" + now.toLocalDate();
    }

    /** {@code 09:00 회사 · 14:00 병원 · 19:00 저녁약속 외 2건} */
    private String body(List<Schedule> tomorrow) {
        String listed =
                tomorrow.stream()
                        .limit(LISTED)
                        .map(
                                schedule ->
                                        schedule.getArriveBy().toLocalTime().format(AT)
                                                + " "
                                                + schedule.getTitle())
                        .collect(Collectors.joining(" · "));
        int remaining = tomorrow.size() - LISTED;
        return remaining > 0 ? listed + " 외 " + remaining + "건" : listed;
    }

    /** 브리핑 시각이 한참 지났는가. 자정을 넘긴 경우는 조회 단계에서 이미 걸러진다. */
    private boolean tooLate(LocalTime briefingTime, LocalTime now) {
        return Duration.between(briefingTime, now).compareTo(maxLateness) > 0;
    }
}
