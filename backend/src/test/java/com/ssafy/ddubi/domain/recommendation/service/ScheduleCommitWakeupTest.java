package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 취소된 일정은 재평가·재발송 대상에서 빠지고, 살아 있는 일정만 평가를 다시 예약한다. */
class ScheduleCommitWakeupTest {
    final ScheduleEvaluationWorkflow workflow = mock(ScheduleEvaluationWorkflow.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final ScheduleCommitWakeup wakeup = new ScheduleCommitWakeup(workflow, jdbc, true);

    @Test void canceledScheduleStopsEvaluationInsteadOfRearmingIt() {
        when(jdbc.queryForList(eq("SELECT status FROM schedule WHERE id=?"), eq(String.class), eq(5L))).thenReturn(List.of("CANCELED"));
        wakeup.invalidate(new ScheduleInputChanged(5));
        verify(jdbc).update(startsWith("UPDATE evaluation_notification_outbox SET status='CANCELED'"), eq(5L));
        verify(jdbc).update(startsWith("UPDATE schedule_evaluation SET stopped=true"), eq(5L));
        verify(jdbc, never()).update(contains("next_check_at=?"), any(), any());
        wakeup.committed(new ScheduleInputChanged(5));
        verify(workflow, after(300).never()).evaluate(anyLong(), any(), any(), any());
        wakeup.close();
    }

    @Test void liveScheduleIsRearmedAndEvaluatedOnce() {
        when(jdbc.queryForList(eq("SELECT status FROM schedule WHERE id=?"), eq(String.class), eq(6L))).thenReturn(List.of("UPCOMING"));
        wakeup.invalidate(new ScheduleInputChanged(6));
        verify(jdbc).update(contains("next_check_at=?"), any(), eq(6L));
        verify(jdbc, never()).update(startsWith("UPDATE schedule_evaluation SET stopped=true"), eq(6L));
        wakeup.committed(new ScheduleInputChanged(6));
        verify(workflow, timeout(2000)).evaluate(eq(6L), isNull(), eq(TriggerType.INITIAL), any());
        wakeup.close();
    }

    @Test void deletedScheduleRowCountsAsCanceled() {
        when(jdbc.queryForList(eq("SELECT status FROM schedule WHERE id=?"), eq(String.class), eq(7L))).thenReturn(List.of());
        wakeup.committed(new ScheduleInputChanged(7));
        verify(workflow, after(300).never()).evaluate(anyLong(), any(), any(), any());
        wakeup.close();
    }
}
