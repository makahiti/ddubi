-- 알림을 보내지 않은 이유를 남긴다 (2026-09-14, 프론트 연동 질의 2번).
--
-- status='SUPPRESSED' 하나로는 세 가지가 구분되지 않는다.
--   중복(DUPLICATE) / 알림 끔(ALARM_OFF) / 유효 기기 없음(NO_VALID_DEVICE)
-- 프론트가 FCM 을 붙이는 초기에는 토큰이 없어 NO_VALID_DEVICE 가 흔한데, 같은 SUPPRESSED 라
-- "중복 억제가 잘 동작한다" 로 오독된다. 억제율을 세려면 억제 사실이 데이터여야 하듯
-- (도메인 규칙 9), 왜 억제했는지도 데이터여야 한다.
--
-- NULL 인 경우: 발송을 시도한 행(SENT/FAILED)과, 이 컬럼이 생기기 전에 쌓인 행.
-- 과거 행을 특정 사유로 채우지 않는다 — 그때 무엇이었는지 알 수 없고, 추측을 기록으로
-- 남기면 나중에 사실과 구분할 수 없다.

ALTER TABLE notification
    ADD COLUMN suppress_reason ENUM('DUPLICATE', 'ALARM_OFF', 'NO_VALID_DEVICE') NULL
        COMMENT '보내지 않은 이유. SENT/FAILED 와 도입 이전 행은 NULL' AFTER status;
