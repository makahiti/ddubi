ALTER TABLE report_detail
    ADD UNIQUE KEY UK_RDETAIL_SCHEDULE (schedule_id)
        COMMENT '일정 하나의 이동 결과는 하나. 재보고는 갱신';
