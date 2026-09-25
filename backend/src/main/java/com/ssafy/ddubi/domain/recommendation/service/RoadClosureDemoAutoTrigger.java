package com.ssafy.ddubi.domain.recommendation.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 시연 일정은 상세 화면을 열지 않아도 도로 통제와 알림이 오게 한다(2026-09-25 요청).
 *
 * <p>일정을 저장하고(입력 시각) 기본 10초가 지나면 서버가 스스로 {@link RoadClosureDemoRunner} 를 부른다. 첫 계산이 아직 안 끝났거나 다른
 * 계산이 잠깐 붙잡고 있으면 5초 뒤 다시 해 보고, 일정·입력 버전마다 최대 {@value #MAX_ATTEMPTS} 번까지만 한다. 이미 통제가 걸린 일정은 다시 계산하지
 * 않는다. 버스 위주 경로가 아니거나 우회 경로가 없는 일정은 RoadClosureDemoSupport 가 거절하고, 그러면 평소 일정처럼 둔다.
 *
 * <p>대상은 제목이 "[시연]" 으로 시작하고, 출발 전이고, 도착까지 3시간 안이고, 30분 안에 저장한 일정뿐이다. 기능이 꺼져 있으면 Bean 자체가 없다.
 */
@Component
@ConditionalOnProperty(name = "ddubi.demo.road-closure.enabled", havingValue = "true")
public class RoadClosureDemoAutoTrigger {
    private static final Logger log = LoggerFactory.getLogger(RoadClosureDemoAutoTrigger.class);
    static final int MAX_ATTEMPTS = 6;
    private static final long FORGET_AFTER_SECONDS = 3600;

    private final JdbcTemplate jdbc;
    private final RoadClosureDemoSupport demo;
    private final RoadClosureDemoRunner runner;
    private final long delaySeconds;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public RoadClosureDemoAutoTrigger(
            JdbcTemplate jdbc,
            RoadClosureDemoSupport demo,
            RoadClosureDemoRunner runner,
            @Value("${ddubi.demo.road-closure.auto-delay-seconds:10}") long delaySeconds) {
        this.jdbc = jdbc;
        this.demo = demo;
        this.runner = runner;
        this.delaySeconds = delaySeconds;
    }

    record Candidate(long scheduleId, long userId, long inputRevision) {}

    record Attempt(int count, boolean done, Instant firstAt) {}

    /** 한 바퀴의 실패가 다음 바퀴를 막지 않는다. 예외가 올라가면 스프링이 이후 실행을 멈춘다. */
    @Scheduled(
            fixedDelayString = "${ddubi.demo.road-closure.auto-poll-ms:5000}",
            initialDelayString = "${ddubi.demo.road-closure.auto-poll-ms:5000}")
    public void tick() {
        try {
            run(candidates(), Instant.now());
        } catch (RuntimeException failed) {
            log.warn("demo auto trigger scan failed: {}", failed.getClass().getSimpleName());
        }
    }

    /** DB 시각(KST)끼리만 비교한다. 일정 저장 뒤 delaySeconds 가 지났고 첫 계산 결과가 같은 입력 버전으로 저장된 것만 고른다. */
    List<Candidate> candidates() {
        return jdbc.query(
                "SELECT s.id, s.user_id, s.input_revision FROM schedule s"
                        + " JOIN schedule_evaluation e ON e.schedule_id = s.id"
                        + " WHERE s.title LIKE '[시연]%' AND s.status = 'UPCOMING'"
                        + " AND s.journey_state = 'NOT_STARTED'"
                        + " AND e.input_revision = s.input_revision AND e.result_json IS NOT NULL"
                        + " AND e.input_updated_at <= NOW() - INTERVAL ? SECOND"
                        + " AND e.input_updated_at >= NOW() - INTERVAL 30 MINUTE"
                        + " AND s.arrive_by > NOW() AND s.arrive_by <= NOW() + INTERVAL 3 HOUR"
                        + " ORDER BY e.input_updated_at LIMIT 20",
                (rs, i) ->
                        new Candidate(
                                rs.getLong("id"),
                                rs.getLong("user_id"),
                                rs.getLong("input_revision")),
                delaySeconds);
    }

    void run(List<Candidate> candidates, Instant now) {
        attempts.values()
                .removeIf(a -> a.firstAt().isBefore(now.minusSeconds(FORGET_AFTER_SECONDS)));
        for (Candidate c : candidates) {
            String key = c.scheduleId() + ":" + c.inputRevision();
            Attempt previous = attempts.get(key);
            if (previous != null && (previous.done() || previous.count() >= MAX_ATTEMPTS)) continue;
            // 앱이 먼저 걸었거나 서버가 재시작된 경우: 같은 입력 버전의 통제가 이미 있으면 다시 계산하지 않는다.
            if (alreadyApplied(c)) {
                attempts.put(key, new Attempt(0, true, now));
                continue;
            }
            int count = previous == null ? 1 : previous.count() + 1;
            Instant firstAt = previous == null ? now : previous.firstAt();
            try {
                Map<String, Object> result = runner.run(c.scheduleId(), c.userId(), now);
                attempts.put(key, new Attempt(count, true, firstAt));
                log.info(
                        "demo auto trigger schedule={} state={} notification={}",
                        c.scheduleId(),
                        result.get("state"),
                        result.get("notificationStatus"));
            } catch (RuntimeException notYet) {
                attempts.put(key, new Attempt(count, false, firstAt));
                if (count >= MAX_ATTEMPTS)
                    log.info(
                            "demo auto trigger gave up schedule={} reason={}",
                            c.scheduleId(),
                            notYet.getMessage());
            }
        }
    }

    private boolean alreadyApplied(Candidate c) {
        return demo.event(c.scheduleId())
                .map(event -> event.path("inputRevision").asLong(-1) == c.inputRevision())
                .orElse(false);
    }
}
