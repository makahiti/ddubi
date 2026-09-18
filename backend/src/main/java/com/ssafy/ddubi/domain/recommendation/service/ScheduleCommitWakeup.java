package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import java.time.Instant;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import jakarta.annotation.PreDestroy;

/**
 * commit 이후 외부 I/O를 bounded worker로 넘긴다. 큐 포화/프로세스 종료는 DB due 조회가 복구한다.
 * 이벤트는 최적화일 뿐 작업의 유일한 저장소가 아니며 동일 일정의 중복 실행은 store lease가 막는다.
 */
@Component
public class ScheduleCommitWakeup {
    private final ScheduleEvaluationWorkflow workflow;
    private final boolean enabled;
    private final ThreadPoolExecutor executor;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public ScheduleCommitWakeup(ScheduleEvaluationWorkflow workflow,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            @Value("${ddubi.recommendation.commit-wakeup-enabled:true}") boolean enabled) {
        this.workflow=workflow;this.enabled=enabled;this.jdbc=jdbc;
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(20),
                runnable->{Thread t=new Thread(runnable,"schedule-commit-evaluation");t.setDaemon(true);return t;},
                new ThreadPoolExecutor.AbortPolicy());
    }
    /** 입력 변경과 오래된 발송 취소를 같은 commit에 묶는다. worker 활성화 여부와 관계없이 실행한다. */
    @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
    public void invalidate(ScheduleInputChanged change) {
        jdbc.update("UPDATE evaluation_notification_outbox SET status='CANCELED',lease_token=NULL,lease_until=NULL WHERE schedule_id=? AND status IN ('PENDING','SENDING')",change.scheduleId());
        if(canceled(change.scheduleId())) {
            // 취소된 일정은 다시 평가하지 않는다. 평가 행은 이력으로 남기고 감시만 멈춘다(stopped=true).
            jdbc.update("UPDATE schedule_evaluation SET stopped=true,lease_token=NULL,lease_until=NULL,notification_json=NULL WHERE schedule_id=?",change.scheduleId());
            return;
        }
        jdbc.update("UPDATE schedule_evaluation SET next_check_at=?,last_error=NULL,failure_count=0,retry_exhausted=false WHERE schedule_id=?",ScheduleEvaluationStore.ts(Instant.now()),change.scheduleId());
    }
    /** 취소·삭제된 일정인지 확인한다. 행이 없으면 평가할 대상이 없으므로 취소로 본다. */
    boolean canceled(long scheduleId) {
        var status=jdbc.queryForList("SELECT status FROM schedule WHERE id=?",String.class,scheduleId);
        return status.isEmpty() || "CANCELED".equals(status.get(0));
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void committed(ScheduleInputChanged change) {
        if(!enabled || canceled(change.scheduleId())) return;
        try { executor.execute(()->{
            try { workflow.evaluate(change.scheduleId(),null,TriggerType.INITIAL,Instant.now()); }
            catch(RuntimeException unavailable) { /* store 실패 상태 또는 다른 lease: DB due가 재처리한다. */ }
        }); } catch(RejectedExecutionException full) { /* DB에 커밋된 입력 revision으로 복구한다. */ }
    }
    @PreDestroy public void close() { executor.shutdown(); }
}
