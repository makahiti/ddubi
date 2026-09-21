-- 기존 V1~V12 를 수정하지 않는 forward migration 이다.
--
-- 반복 규칙에는 출발 예정시각이 없었다. 그래서 규칙이 찍어낸 인스턴스는
-- planned_depart_at 이 NULL 이었고, 추천 계산이 아예 돌지 않았다
-- (ScheduleEvaluationStore 가 DEPARTURE_REQUIRED 로 끝내고, 자동 재계산 대상
--  조회는 planned_depart_at IS NOT NULL 로 걸러낸다).
--
-- NULL 을 허용한다. 이미 등록된 규칙에는 이 값이 없고, 서버가 도착 1시간 전
-- 같은 값을 지어내지 않는다 — 사용자가 정하지 않은 출발 시각으로 알림을 보내면
-- 그 시각이 왜 그런지 아무도 설명할 수 없다. 기존 규칙은 사용자가 수정 화면에서
-- 직접 채운다.
ALTER TABLE schedule_repeat
    ADD COLUMN depart_time TIME NULL
        COMMENT '출발 예정시각(매 발생일 공통). NULL = 출발시각 도입 이전 규칙' AFTER arrive_time;
