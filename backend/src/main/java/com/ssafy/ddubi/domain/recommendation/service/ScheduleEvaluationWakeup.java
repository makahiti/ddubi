package com.ssafy.ddubi.domain.recommendation.service;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 한 일정의 이벤트 갱신을 독립된 짧은 트랜잭션으로 처리한다.
 * 대상 검색은 락 없이 수행하되 쓰기 직전 schedule → schedule_evaluation 순서로 잠근다.
 * 다중 일정 JOIN UPDATE의 실행 계획에 락 순서를 맡기지 않고 claim/complete/stop과 통일한다.
 * 이 서비스는 외부 I/O를 하지 않는다. DB 예외는 전파하여 Kafka 재전송 대상이 된다.
 */
@Service
public class ScheduleEvaluationWakeup {
    private final JdbcTemplate jdbc;
    public ScheduleEvaluationWakeup(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 검색 이후 일정 취소·경로 변경이 있었으면 최신 조건을 다시 검사해 갱신하지 않는다. */
    @Transactional
    public void wake(long scheduleId, String segmentId, Instant now) {
        var schedules = jdbc.queryForList("SELECT id FROM schedule WHERE id=? FOR UPDATE", Long.class, scheduleId);
        if (schedules.isEmpty()) return;
        jdbc.update("UPDATE schedule_evaluation e JOIN schedule s ON s.id=e.schedule_id "
                + "SET e.next_check_at=?,e.event_revision=e.event_revision+1 "
                + "WHERE e.schedule_id=? AND s.status='UPCOMING' AND e.stopped=false AND s.arrive_by>? AND EXISTS "
                + "(SELECT 1 FROM route r JOIN route_segment rs ON rs.route_id=r.id "
                + "JOIN route_segment_edge edge ON edge.route_segment_id=rs.id "
                + "WHERE r.schedule_id=s.id AND r.is_selected=true AND edge.segment_id=?)",
                ScheduleEvaluationStore.ts(now), scheduleId, ScheduleEvaluationStore.ts(now), segmentId);
    }
}
