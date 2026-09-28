package com.ssafy.ddubi.domain.schedule.service;

import com.ssafy.ddubi.global.util.BusinessTime;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 지난 일정을 주기적으로 닫는다.
 *
 * <p>분 단위로 돌 이유가 없다. 유예 시간이 시간 단위라 몇 분 늦게 닫혀도 사용자가 보는 것은 같다. 기본 5분마다 한 바퀴 돈다.
 *
 * <p>판정 시각은 KST 로 명시한다. {@code LocalDateTime.now()} 는 JVM 기본 시간대를 쓰므로, JVM 이 UTC 면 유예 시간이 9시간 어긋난다.
 * 운영은 {@code TZ=Asia/Seoul} 이지만 마감 시각을 그 환경변수 하나에 기대게 두지 않는다.
 */
@Component
@ConditionalOnProperty(
        name = "ddubi.schedule.expiry-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ScheduleExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduleExpiryScheduler.class);

    private final ScheduleExpiryService expiryService;

    public ScheduleExpiryScheduler(ScheduleExpiryService expiryService) {
        this.expiryService = expiryService;
    }

    /** 한 바퀴의 실패가 다음 바퀴를 막지 않는다. 예외가 올라가면 스프링이 이후 실행을 멈춘다. */
    @Scheduled(
            fixedDelayString = "${ddubi.schedule.expiry-delay-ms:300000}",
            initialDelayString = "${ddubi.schedule.expiry-delay-ms:300000}")
    public void tick() {
        try {
            LocalDateTime now =
                    LocalDateTime.now(BusinessTime.ZONE).truncatedTo(ChronoUnit.SECONDS);
            int closed = expiryService.expireOverdue(now);
            if (closed > 0) {
                log.info("지난 일정 자동 마감 {}건", closed);
            }
        } catch (RuntimeException failure) {
            // 예외 종류만 남기면 다음에 또 나도 원인을 못 찾는다. 메시지와 위치를 함께 남긴다.
            log.warn("지난 일정 자동 마감 실패", failure);
        }
    }
}
