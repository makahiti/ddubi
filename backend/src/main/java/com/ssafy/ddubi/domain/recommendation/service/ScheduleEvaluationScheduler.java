package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DB에 남은 due 작업을 최대 20개씩 가져온다. 여러 서버가 실행돼도 store lease가 중복 계산을 막는다.
 * 한 일정의 API 실패를 다른 일정 성공으로 숨기지 않고 실패 건수를 로그에 남긴다. 서버 재시작 시
 * 오래된 lease와 전송 대기를 다시 처리한다. 실제 출발 후에는 사용자의 출발 확인으로 중단한다.
 */
@Component
@ConditionalOnProperty(name="ddubi.recommendation.monitor-enabled", havingValue="true", matchIfMissing=true)
public class ScheduleEvaluationScheduler {
    private static final Logger log = LoggerFactory.getLogger(ScheduleEvaluationScheduler.class);
    private final ScheduleEvaluationStore store;
    private final ScheduleEvaluationWorkflow workflow;
    private final EvaluationNotificationDispatcher dispatcher;
    private final JdbcTemplate jdbc;

    public ScheduleEvaluationScheduler(ScheduleEvaluationStore store, ScheduleEvaluationWorkflow workflow,
            EvaluationNotificationDispatcher dispatcher, JdbcTemplate jdbc) {
        this.store = store; this.workflow = workflow; this.dispatcher = dispatcher; this.jdbc = jdbc;
    }

    /** 기본 15초는 due 목록 확인 간격이며 각 일정의 10/5/1분 재평가 간격과 다르다. */
    @Scheduled(fixedDelayString="${ddubi.recommendation.scheduler-delay-ms:15000}", initialDelayString="${ddubi.recommendation.scheduler-delay-ms:15000}")
    public void tick() {
        for (Long id : store.due(Instant.now())) {
            try { workflow.evaluate(id, null, TriggerType.REFRESH, Instant.now()); }
            catch (RuntimeException failure) { log.warn("일정 평가 실패 scheduleId={} type={}", id, failure.getClass().getSimpleName()); }
        }
        for (Long id : jdbc.queryForList("SELECT e.schedule_id FROM schedule_evaluation e JOIN schedule s ON s.id=e.schedule_id WHERE s.status='UPCOMING' AND e.stopped=false AND e.last_error IS NULL AND e.input_updated_at=s.updated_at AND s.arrive_by>? AND e.next_check_at>? AND notification_json IS NOT NULL "
                + "AND (notified_key IS NULL OR notified_key<>notification_key) LIMIT 20", Long.class, ScheduleEvaluationStore.ts(Instant.now()), ScheduleEvaluationStore.ts(Instant.now()))) {
            try { dispatcher.dispatch(id); }
            catch (RuntimeException failure) { log.warn("평가 알림 처리 실패 scheduleId={} type={}", id, failure.getClass().getSimpleName()); }
        }
    }
}
