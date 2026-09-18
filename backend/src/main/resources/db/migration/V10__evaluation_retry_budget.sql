-- 외부 오류를 같은 입력으로 무한 호출하지 않도록 평가 재시도 예산을 영속화한다.
-- 결과 입력 revision과 실패 입력 revision은 다르다. 실패가 과거 결과를 최신으로 승격해서는 안 된다.
ALTER TABLE schedule_evaluation
    ADD COLUMN failure_count INT NOT NULL DEFAULT 0,
    ADD COLUMN failed_input_revision BIGINT NULL,
    ADD COLUMN retry_exhausted BOOLEAN NOT NULL DEFAULT FALSE;
