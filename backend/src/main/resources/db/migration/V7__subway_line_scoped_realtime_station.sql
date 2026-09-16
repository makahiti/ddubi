-- 환승역의 정적 ID가 같아도 실시간 역 ID는 호선별로 다를 수 있다.
-- 기존 역 단위 ID는 유지하고 신규 원본은 노선/방향/패턴별 ID를 보존한다.
ALTER TABLE seoul_subway_line_station
    ADD COLUMN realtime_station_id VARCHAR(50) NULL
    COMMENT '노선별 실시간 역 ID. NULL이면 기존 역 단위 crosswalk 사용';
