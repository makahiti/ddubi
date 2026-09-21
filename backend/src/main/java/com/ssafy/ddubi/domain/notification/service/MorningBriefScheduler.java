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
 * 아침 브리핑을 분마다 확인해 보낸다 (REQ-NTF-003).
 *
 * <h3>왜 분 단위로 도나</h3>
 *
 * 사용자가 정한 시각은 분 단위다. 더 자주 돌면 같은 사용자를 몇 번씩 훑고, 더 드물게 돌면 07:00 으로 맞춰 둔 사람이 07:05 에 받는다. 대상 조회는 인덱스
 * 하나짜리 쿼리라 분마다 돌아도 싸다.
 *
 * <h3>여러 서버가 떠 있어도 하루 한 번이다</h3>
 *
 * 이미 오늘 브리핑 행이 있는 사용자는 대상에서 빠진다. 같은 분에 두 인스턴스가 겹치는 짧은 경합은 Redis 억제 키가 막는다. 자세한 근거는 {@link
 * MorningBriefService} 참고.
 */
@Component
@ConditionalOnProperty(name = "ddubi.briefing.enabled", havingValue = "true", matchIfMissing = true)
public class MorningBriefScheduler {

    private static final Logger log = LoggerFactory.getLogger(MorningBriefScheduler.class);

    /** 사용자가 정한 브리핑 시각은 KST 업무시각이다. JVM 기본 시간대에 기대지 않는다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MorningBriefService morningBriefService;

    public MorningBriefScheduler(MorningBriefService morningBriefService) {
        this.morningBriefService = morningBriefService;
    }

    /**
     * 한 바퀴의 실패가 다음 바퀴를 막지 않는다. 스케줄러 메서드에서 예외가 올라가면 스프링이 그 뒤로 이 작업을 더 실행하지 않는다.
     *
     * <h3>판정 시각을 KST로 명시한다</h3>
     *
     * {@code BusinessTime.now()}는 {@code LocalDateTime.now()}라 <b>JVM 기본 시간대</b>를 쓴다. 사용자가 정한
     * {@code briefingTime}은 KST 업무시각이므로, JVM이 UTC면 07:00 브리핑을 한국 시간 16:00에 보낸다. 운영은 {@code
     * TZ=Asia/Seoul}이라 드러나지 않지만, 알림을 보내는 시각을 그 환경변수 하나에 기대게 두지 않는다.
     */
    @Scheduled(
            fixedDelayString = "${ddubi.briefing.scheduler-delay-ms:60000}",
            initialDelayString = "${ddubi.briefing.scheduler-delay-ms:60000}")
    public void tick() {
        try {
            int sent =
                    morningBriefService.dispatchDue(
                            LocalDateTime.now(KST).truncatedTo(ChronoUnit.SECONDS));
            if (sent > 0) {
                log.info("아침 브리핑 발송 {}건", sent);
            }
        } catch (RuntimeException failure) {
            log.warn("아침 브리핑 처리 실패 type={}", failure.getClass().getSimpleName(), failure);
        }
    }
}
