package com.ssafy.ddubi.domain.recommendation.service;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.ssafy.ddubi.domain.recommendation.service.ScheduleEvaluationStore.*;

/**
 * 평가 outbox의 짧은 DB 트랜잭션만 소유한다. 외부 FCM 호출은 이 클래스 밖에서 수행한다.
 * 입력/사건 revision과 실제 이동 상태를 재검사하고 lease 토큰으로 늦은 ack를 차단한다.
 */
@Service
public class EvaluationOutboxStore {
    private final JdbcTemplate jdbc;
    public EvaluationOutboxStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Delivery(long id,long scheduleId,String token,String json,String dedup,int attempts,Long notificationId) { }

    public List<Long> due(Instant now) {
        return jdbc.queryForList("SELECT id FROM evaluation_notification_outbox WHERE status IN ('PENDING','SENDING') AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_attempt_at,id LIMIT 20",Long.class,ts(now),ts(now));
    }
    /** SKIP LOCKED 대신 고유 행 lock과 토큰으로 동일 outbox의 중복 claim을 거부한다. */
    @Transactional
    public Delivery claim(long id,Instant now) {
        var rows=jdbc.queryForList("SELECT * FROM evaluation_notification_outbox WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) return null;
        var r=rows.get(0);
        if(!Set.of("PENDING","SENDING").contains(r.get("status")) || databaseTime(r.get("next_attempt_at")).isAfter(local(now))
                || r.get("lease_until")!=null && databaseTime(r.get("lease_until")).isAfter(local(now))) return null;
        if(!current(id,now)) { cancel(id); return null; }
        if(((Number)r.get("attempts")).intValue()>=5) {
            // lease 보유 중 프로세스가 죽은 경우도 시도 횟수에 포함해 무한 회수를 막는다.
            jdbc.update("UPDATE evaluation_notification_outbox SET status='FAILED',lease_token=NULL,lease_until=NULL,last_error_code='ATTEMPTS_EXHAUSTED' WHERE id=?",id);
            return null;
        }
        String token=UUID.randomUUID().toString();
        jdbc.update("UPDATE evaluation_notification_outbox SET status='SENDING',attempts=attempts+1,lease_token=?,lease_until=? WHERE id=?",token,ts(now.plusSeconds(120)),id);
        return new Delivery(id,((Number)r.get("schedule_id")).longValue(),token,(String)r.get("payload_json"),(String)r.get("dedup_key"),((Number)r.get("attempts")).intValue()+1,r.get("notification_id") instanceof Number n?n.longValue():null);
    }
    /** 외부 전송 전에 notificationId를 먼저 연결하여 crash 이후에도 Client 중복 제거 키를 유지한다. */
    public boolean attach(Delivery d,long notificationId) {
        return jdbc.update("UPDATE evaluation_notification_outbox SET notification_id=? WHERE id=? AND lease_token=? AND status='SENDING'",notificationId,d.id(),d.token())==1;
    }
    /** 전송 직전 읽기. 조회 이후의 사용자 변경과 외부 발송 사이에는 원자성 보장이 없음을 운영 계약에 명시한다. */
    public boolean current(long id,Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM evaluation_notification_outbox o JOIN schedule s ON s.id=o.schedule_id JOIN schedule_evaluation e ON e.schedule_id=s.id WHERE o.id=? AND o.status IN ('PENDING','SENDING') AND s.status='UPCOMING' AND s.journey_state='NOT_STARTED' AND s.input_revision=o.input_revision AND e.event_revision=o.event_revision AND e.stopped=false AND JSON_UNQUOTE(JSON_EXTRACT(e.result_json,'$.evaluationId'))=o.evaluation_id AND s.arrive_by>? AND o.valid_until>?)",Boolean.class,id,ts(now),ts(now)));
    }
    /** 만료 lease를 다른 worker가 회수한 뒤에는 이전 worker가 외부 발송을 시작하지 못한다. */
    public boolean current(Delivery d,Instant now) {
        return current(d.id(),now) && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM evaluation_notification_outbox WHERE id=? AND status='SENDING' AND lease_token=? AND lease_until>?)",
                Boolean.class,d.id(),d.token(),ts(now)));
    }
    private void cancel(long id) { jdbc.update("UPDATE evaluation_notification_outbox SET status='CANCELED',lease_token=NULL,lease_until=NULL WHERE id=?",id); }
    /** 성공 ack만 SENT다. 억제/만료는 CANCELED, 재시도는 최대 5회이며 실패를 성공처럼 기록하지 않는다. */
    @Transactional
    public void finish(Delivery d,String outcome,String messageId,Instant now) {
        String state="SENT".equals(outcome)?"SENT":"CANCELED".equals(outcome)?"CANCELED":d.attempts()>=5?"FAILED":"PENDING";
        jdbc.update("UPDATE evaluation_notification_outbox SET status=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,last_error_code=?,provider_message_id=?,sent_at=? WHERE id=? AND lease_token=? AND status='SENDING'",
                state,ts(now.plusSeconds(Math.min(300,10L << Math.min(d.attempts(),5)))),"SENT".equals(outcome)?null:outcome,messageId,
                "SENT".equals(outcome)?ts(now):null,d.id(),d.token());
    }
}
