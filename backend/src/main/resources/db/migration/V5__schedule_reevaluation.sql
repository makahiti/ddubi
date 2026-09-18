-- 사용자 출발 입력과 추천 결과를 분리한다. 기존 일정의 출발을 임의 역산하지 않는다.
ALTER TABLE schedule ADD COLUMN planned_depart_at DATETIME(6) NULL COMMENT '사용자 입력 출발 예정시각';

-- API 평균 추정은 분위수가 아니다. 미사용 백분위를 50으로 꾸미지 않고 null로 남긴다.
ALTER TABLE recommendation MODIFY COLUMN percentile_used TINYINT NULL COMMENT '검증된 백분위 사용 시에만 기록';

-- 일정 수정 버전과 작업 lease를 함께 보존한다. 프로세스 재시작 시 미완료 작업을 다시 가져온다.
CREATE TABLE schedule_evaluation (
    schedule_id BIGINT NOT NULL PRIMARY KEY,
    input_updated_at DATETIME(6) NULL,
    next_check_at DATETIME(6) NOT NULL,
    lease_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    candidates_json LONGTEXT NULL,
    result_json LONGTEXT NULL,
    last_error VARCHAR(64) NULL,
    notification_json LONGTEXT NULL,
    notification_key VARCHAR(64) NULL,
    notified_key VARCHAR(64) NULL,
    stopped BOOLEAN NOT NULL DEFAULT FALSE,
    event_revision BIGINT NOT NULL DEFAULT 0,
    KEY IX_EVAL_DUE (next_check_at, lease_until),
    CONSTRAINT FK_EVAL_SCHEDULE FOREIGN KEY (schedule_id) REFERENCES schedule(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
