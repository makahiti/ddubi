-- 밤 브리핑: 내일 일정을 전날 밤에 한 번 요약해 보낸다.
--
-- 아침 브리핑(briefing_time)은 "오늘 남은 일정"을 보낸다. 그래서 저녁에 시각을 맞춰도
-- 그날 일정이 이미 끝나 있으면 아무것도 오지 않는다(2026-09-21 확인). 내일을 미리 보는
-- 것은 성격이 다른 알림이라 시각도 따로 둔다.
--
-- NULL 이면 받지 않겠다는 뜻이다. 아침 브리핑과 같은 규칙이다.
ALTER TABLE user
    ADD COLUMN night_briefing_time TIME NULL COMMENT '밤 브리핑 발송 시각(KST). NULL이면 받지 않는다';

-- 알림 종류에 밤 브리핑을 더한다. 기존 값은 그대로 두고 뒤에만 붙인다.
ALTER TABLE notification
    MODIFY COLUMN type ENUM(
        'MORNING_BRIEF',
        'DEPART_SOON',
        'EARLY_DEPART',
        'ROUTE_CHANGE',
        'ON_TIME_UNLIKELY',
        'LAST_TRIP',
        'SCHEDULE_CONFLICT',
        'DATA_STALE',
        'NIGHT_BRIEF'
    ) NOT NULL;
