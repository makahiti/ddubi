package com.ssafy.ddubi.domain.notification.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 밤 브리핑을 분마다 확인해 보낸다.
 *
 * <p>아침 브리핑과 같은 주기·같은 조건으로 돈다. 다른 것은 보는 날짜뿐이다 — 이쪽은 내일을
 * 본다. 자세한 근거는 {@link NightBriefService} 참고.
 *
 * <p>스위치도 아침과 같은 {@code ddubi.briefing.enabled} 를 쓴다. 브리핑을 끄겠다는 뜻은
 * 보통 둘 다 끄겠다는 뜻이고, 스위치가 둘이면 한쪽만 켜진 상태를 알아차리기 어렵다.
 */
@Component
@ConditionalOnProperty(name = "ddubi.briefing.enabled", havingValue = "true", matchIfMissing = true)
public class NightBriefScheduler {

    private static final Logger log = LoggerFactory.getLogger(NightBriefScheduler.class);

    /** 사용자가 정한 브리핑 시각은 KST 업무시각이다. JVM 기본 시간대에 기대지 않는다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final NightBriefService nightBriefService;

    public NightBriefScheduler(NightBriefService nightBriefService) {
        this.nightBriefService = nightBriefService;
    }

    /** 한 바퀴의 실패가 다음 바퀴를 막지 않는다. 예외가 올라가면 스프링이 이 작업을 더 실행하지 않는다. */
    @Scheduled(
            fixedDelayString = "${ddubi.briefing.scheduler-delay-ms:60000}",
            initialDelayString = "${ddubi.briefing.scheduler-delay-ms:60000}")
    public void tick() {
        try {
            int sent =
                    nightBriefService.dispatchDue(
                            LocalDateTime.now(KST).truncatedTo(ChronoUnit.SECONDS));
            if (sent > 0) {
                log.info("밤 브리핑 발송 {}건", sent);
            }
        } catch (RuntimeException failure) {
            log.warn("밤 브리핑 처리 실패 type={}", failure.getClass().getSimpleName(), failure);
        }
    }
}
