-- 알림함에서 개별 알림 숨기기 (NTF-005, 2026-09-18)
--
-- 왜 행을 지우지 않나
--   notification 은 알림함이면서 동시에 "언제 무엇을 보냈는가" 의 발송 이력이다.
--   재발송 억제(suppress_key)와 아침 브리핑의 "오늘 이미 받음" 판단이 이 이력을 근거로 한다.
--   행을 지우면 사용자가 알림을 지운 직후 같은 상황에서 같은 알림이 다시 온다.
--   그래서 사용자 화면에서만 감추고 이력은 남긴다.
ALTER TABLE notification
    ADD COLUMN deleted_at DATETIME NULL
        COMMENT '사용자가 알림함에서 지운 시각. NULL 이면 알림함에 보인다'
        AFTER read_at;

-- 알림함 목록·안읽음 뱃지는 항상 deleted_at IS NULL 을 함께 건다.
-- 기존 IX_NTF_USER_CREATED(user_id, created_at) 는 숨김 여부를 모르므로 따로 둔다.
CREATE INDEX IX_NTF_USER_VISIBLE
    ON notification (user_id, deleted_at, created_at)
    COMMENT '알림함 목록 — 숨긴 것을 뺀 최신순';
