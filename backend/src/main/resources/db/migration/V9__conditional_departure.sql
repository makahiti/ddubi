-- 기존 V1~V8을 수정하지 않는 forward migration이다. 과거 계획의 입력 출처는 추측하지 않는다.
ALTER TABLE schedule
    ADD COLUMN user_input_depart_at DATETIME(6) NULL,
    ADD COLUMN plan_source VARCHAR(40) NOT NULL DEFAULT 'LEGACY_UNKNOWN',
    ADD COLUMN actual_depart_at DATETIME(6) NULL,
    ADD COLUMN journey_state VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN input_revision BIGINT NOT NULL DEFAULT 0;
UPDATE schedule SET journey_state=CASE WHEN status='DONE' THEN 'ARRIVED' WHEN status='CANCELED' THEN 'CANCELED' ELSE 'NOT_STARTED' END;
ALTER TABLE schedule_evaluation
    ADD COLUMN input_revision BIGINT NULL,
    ADD COLUMN valid_until DATETIME(6) NULL,
    ADD COLUMN accepted_evaluation_id VARCHAR(64) NULL;

CREATE TABLE evaluation_notification_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT NOT NULL,
    evaluation_id VARCHAR(64) NOT NULL,
    input_revision BIGINT NOT NULL,
    event_revision BIGINT NOT NULL,
    dedup_key VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    valid_until DATETIME(6) NOT NULL,
    lease_token VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    last_error_code VARCHAR(80) NULL,
    provider_message_id VARCHAR(255) NULL,
    notification_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6) NULL,
    UNIQUE KEY uk_eval_outbox_dedup (dedup_key),
    KEY ix_eval_outbox_due (status,next_attempt_at,lease_until),
    KEY ix_eval_outbox_input (schedule_id,input_revision),
    CONSTRAINT fk_eval_outbox_schedule FOREIGN KEY (schedule_id) REFERENCES schedule(id) ON DELETE CASCADE
);
