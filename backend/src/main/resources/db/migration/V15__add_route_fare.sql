-- 경로 검색 응답의 예상 요금을 후보 경로와 함께 보존한다.
-- NULL은 제공자가 요금을 주지 않았다는 뜻이며 무료(0원)와 구분한다.
ALTER TABLE route
    ADD COLUMN fare_minimum_won INT NULL
        COMMENT '경로 API가 제공한 최소 예상 요금(원). 미제공은 NULL' AFTER base_duration_min,
    ADD COLUMN fare_maximum_won INT NULL
        COMMENT '경로 API가 제공한 최대 예상 요금(원). 단일 요금은 최소와 동일' AFTER fare_minimum_won;
