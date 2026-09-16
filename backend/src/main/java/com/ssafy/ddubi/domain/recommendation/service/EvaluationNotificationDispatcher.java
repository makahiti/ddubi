package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.notification.service.NotificationCommand;
import com.ssafy.ddubi.domain.notification.service.NotificationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 평가 저장 이후 대기 중인 알림을 기존 NotificationService로 전달한다. 외부 FCM의 exactly-once는
 * 보장하지 않으며 기존 suppressKey로 중복을 억제한다. 전송 실패도 기존 notification 상태에 기록된다.
 * 취소·완료·입력 변경 뒤에는 오래된 알림을 보내지 않는다.
 */
@Service
public class EvaluationNotificationDispatcher {
    private final JdbcTemplate jdbc;
    private final ScheduleEvaluationStore store;
    private final NotificationService notifications;

    public EvaluationNotificationDispatcher(JdbcTemplate jdbc, ScheduleEvaluationStore store, NotificationService notifications) {
        this.jdbc = jdbc; this.store = store; this.notifications = notifications;
    }

    /**
     * 한 일정의 outbox를 잠그고 기존 알림 생성 결과까지 기록한다. 호출 예외 시 대기 상태를 유지한다.
     *
     * <h3>입력 버전은 SQL이 아니라 자바에서 비교한다</h3>
     * {@code e.input_updated_at}은 생 JDBC로, {@code s.updated_at}은 Hibernate로 저장된다.
     * hibernate.jdbc.time_zone 때문에 <b>JVM 기본 시간대가 KST가 아니면 두 값이 9시간
     * 어긋나</b> SQL 비교가 항상 거짓이 된다. 운영은 TZ=Asia/Seoul이라 드러나지 않지만
     * JVM이 UTC인 CI에서는 알림이 하나도 나가지 않았다(2026-09-15).
     *
     * <p>그래서 조건을 SQL에서 빼고, Hibernate가 읽은 값끼리 비교한다. 두 값 모두 같은 경로를
     * 거치므로 시간대와 무관하게 짝이 맞는다.
     */
    @Transactional
    public void dispatch(long scheduleId) {
        jdbc.queryForList("SELECT id FROM schedule WHERE id=? FOR UPDATE", scheduleId);
        var now = ScheduleEvaluationStore.ts(java.time.Instant.now());
        var rows = jdbc.queryForList("SELECT e.* FROM schedule_evaluation e JOIN schedule s ON s.id=e.schedule_id "
                + "WHERE e.schedule_id=? AND s.status='UPCOMING' AND e.stopped=false AND s.arrive_by>? AND e.next_check_at>? "
                + "AND e.last_error IS NULL FOR UPDATE", scheduleId, now, now);
        if (rows.isEmpty()) return;
        var row = rows.get(0);
        // 계산이 끝난 뒤 일정이 수정됐으면 그때의 알림은 이미 낡았다.
        if (!java.util.Objects.equals(ScheduleEvaluationStore.databaseTime(row.get("input_updated_at")),
                store.owned(scheduleId, null).getUpdatedAt())) return;
        if (row.get("notification_json") == null || java.util.Objects.equals(row.get("notification_key"), row.get("notified_key"))) return;
        notifications.create(store.read((String) row.get("notification_json"), NotificationCommand.class));
        jdbc.update("UPDATE schedule_evaluation SET notified_key=notification_key WHERE schedule_id=?", scheduleId);
    }
}
