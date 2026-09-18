ALTER TABLE baseline_serving
    ADD INDEX IX_BASELINE_VERSION (data_version)
        COMMENT '버전 단위 집계·삭제·정리용. UK 에서는 data_version 이 마지막이라 단독 조회에 못 쓴다';
