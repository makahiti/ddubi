-- =====================================================================
-- 뚜비(DDUBI) 서비스 DB 스키마 — ERD_master (구 ERD v2)
-- =====================================================================
-- 원천: Notion「ERD_master」(mermaid) + 「ERD 설명서」 + backend/CLAUDE.md
-- 대상: MySQL 8.0+ / InnoDB / utf8mb4
-- 소유: 백엔드(서비스) — 단, 15~17번 테이블은 분산 담당 소유(아래 주석 참조)
--
-- 이 파일이 Flyway `backend/src/main/resources/db/migration/V1__init.sql`의
-- 원천이다. 두 파일은 항상 동일해야 하며, 스키마 변경 시
-- (1) 이 파일  (2) 마이그레이션  (3) Notion ERD_master 를 동시에 고친다.
--
-- 공통 규칙
--   - PK는 `id BIGINT AUTO_INCREMENT` (예외: pipeline_config — 17번 참조)
--   - created_at / updated_at 은 전 테이블 공통, DATETIME(6)
--     updated_at 은 서버(JPA)가 갱신하며 DB의 ON UPDATE 는 안전망이다
--   - 업무 시각 컬럼은 DATETIME(초 정밀도), 좌표는 DECIMAL(10,7) WGS84
--   - 소요시간 단위: route/route_segment/recommendation = **분**,
--                    baseline_serving p50/p80/p95 = **초**
--   - 예약어 회피: trigger_type (NOT trigger), rank_no (NOT rank)
--
-- 제약 총계: UNIQUE 9 / FK 19 / 조회 인덱스 7
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. user — 사용자 계정
--    탈퇴 = 이 행 DELETE 1건 → 하위 전체 CASCADE 삭제(보관·마스킹 없음)
-- ---------------------------------------------------------------------
CREATE TABLE `user` (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    email                VARCHAR(255) NULL       COMMENT '계정 통합 키. 소셜 이메일 미동의 시 NULL → 통합 불가, 별도 계정',
    nickname             VARCHAR(50)  NOT NULL   COMMENT '표시 이름',
    mobility_note        VARCHAR(100) NULL       COMMENT '이동 시 어려운 점 자유 입력 — 여유시간 가중 (REQ-SCH-004)',
    notify_lead_min      INT          NOT NULL DEFAULT 30  COMMENT '출발 몇 분 전에 알릴지 (0~180)',
    briefing_time        TIME         NULL       COMMENT '아침 브리핑 수신 시각. NULL = 브리핑 끔 (REQ-NTF-003)',
    is_alarm             BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '알림 전체 토글. FALSE여도 notification 행은 생성하고 발송만 억제',
    history_consented_at DATETIME     NULL       COMMENT '지오펜스 실측 동의 시각. NULL = 미동의 → actual_arrival 수집 안 함',
    created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY UK_USER_EMAIL (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '사용자 계정';

-- ---------------------------------------------------------------------
-- 2. user_auth — 로그인 수단 (계정 통합의 구현체)
--    같은 이메일로 소셜 로그인 → 새 user 생성 없이 여기 provider 행만 추가
-- ---------------------------------------------------------------------
CREATE TABLE user_auth (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    provider         ENUM('LOCAL','KAKAO','GOOGLE','NAVER') NOT NULL COMMENT 'v1 구현 범위는 LOCAL + KAKAO',
    provider_user_id VARCHAR(100) NULL     COMMENT '소셜 외부 고유 ID(카카오 회원번호 등). LOCAL은 NULL',
    password_hash    VARCHAR(255) NULL     COMMENT 'bcrypt 해시. LOCAL 행에만 값 존재',
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- MySQL은 NULL을 중복으로 보지 않으므로 LOCAL 행(provider_user_id=NULL)끼리는 충돌하지 않는다.
    -- LOCAL 중복 등록은 아래 UK_UAUTH_USER_PROVIDER 가 막는다.
    UNIQUE KEY UK_UAUTH_PROVIDER_UID (provider, provider_user_id),
    UNIQUE KEY UK_UAUTH_USER_PROVIDER (user_id, provider),
    CONSTRAINT FK_UAUTH_USER FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '로그인 수단(1 계정 : N 인증수단)';

-- ---------------------------------------------------------------------
-- 3. user_transport_pref — 선호·회피 교통수단 (추천 순위 가중)
-- ---------------------------------------------------------------------
CREATE TABLE user_transport_pref (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    mode       ENUM('BUS','SUBWAY')   NOT NULL,
    preference ENUM('PREFER','AVOID') NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY UK_UTPREF_USER_MODE (user_id, mode) COMMENT '같은 수단에 선호·회피 동시 지정 방지',
    CONSTRAINT FK_UTPREF_USER FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '선호·회피 교통수단 (REQ-SCH-004)';

-- ---------------------------------------------------------------------
-- 4. place — 자주 쓰는 장소
--    schedule 은 이 테이블을 FK 참조하지 않는다(값 스냅샷). REQ-SCH-006
-- ---------------------------------------------------------------------
CREATE TABLE place (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    user_id    BIGINT        NOT NULL,
    name       VARCHAR(50)   NOT NULL COMMENT '별칭 (예: 집, 회사)',
    address    VARCHAR(255)  NULL     COMMENT '도로명 주소',
    lat        DECIMAL(10,7) NOT NULL,
    lng        DECIMAL(10,7) NOT NULL,
    created_at DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT FK_PLACE_USER FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '저장 장소 (일정과 FK 관계 없음)';

-- ---------------------------------------------------------------------
-- 5. device_token — FCM 푸시 기기
--    fcm_token UNIQUE 기준 upsert → 공용 기기는 마지막 로그인 사용자가 주인
-- ---------------------------------------------------------------------
CREATE TABLE device_token (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    fcm_token    VARCHAR(512) NOT NULL COMMENT 'INSERT ... ON DUPLICATE KEY UPDATE 의 충돌 키',
    platform     ENUM('WEB','ANDROID','IOS') NOT NULL COMMENT 'v1 = ANDROID',
    is_valid     BOOLEAN      NOT NULL DEFAULT TRUE COMMENT 'FCM UNREGISTERED 응답 시 FALSE (REQ-NTF-004)',
    last_used_at DATETIME     NULL     COMMENT '앱이 마지막으로 토큰을 보고한 시각',
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY UK_DEVICE_FCM (fcm_token),
    CONSTRAINT FK_DEVICE_USER FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'FCM 푸시 기기';

-- ---------------------------------------------------------------------
-- 6. schedule_repeat — 반복 일정 마스터(규칙)
--    "이후 전체 삭제" = 행 삭제가 아니라 is_active = FALSE
-- ---------------------------------------------------------------------
CREATE TABLE schedule_repeat (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    user_id      BIGINT        NOT NULL,
    title        VARCHAR(100)  NOT NULL COMMENT '일정 이름 (예: 출근)',
    days_of_week VARCHAR(30)   NOT NULL COMMENT '반복 요일 CSV (예: MON,TUE,WED,THU,FRI)',
    arrive_time  TIME          NOT NULL COMMENT '도착 목표 시각',
    until_date   DATE          NULL     COMMENT '반복 종료일. NULL = 무기한',
    origin_name  VARCHAR(100)  NOT NULL COMMENT '출발지 스냅샷 (schedule 과 동일 구조)',
    origin_lat   DECIMAL(10,7) NOT NULL,
    origin_lng   DECIMAL(10,7) NOT NULL,
    dest_name    VARCHAR(100)  NOT NULL COMMENT '목적지 스냅샷',
    dest_lat     DECIMAL(10,7) NOT NULL,
    dest_lng     DECIMAL(10,7) NOT NULL,
    importance   ENUM('NORMAL','HIGH','CRITICAL') NOT NULL DEFAULT 'NORMAL' COMMENT '생성될 인스턴스의 기본 중요도',
    is_active    BOOLEAN       NOT NULL DEFAULT TRUE COMMENT 'FALSE = 신규 인스턴스 생성 중단(기존 인스턴스는 유지)',
    created_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT FK_SREPEAT_USER FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '반복 일정 규칙 (F-01 / REQ-SCH-005)';

-- ---------------------------------------------------------------------
-- 7. schedule — 일정 (인스턴스). 추천의 출발점
--    삭제는 soft(status=CANCELED). 물리 삭제는 탈퇴 CASCADE 뿐
-- ---------------------------------------------------------------------
CREATE TABLE schedule (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    user_id            BIGINT        NOT NULL,
    repeat_id          BIGINT        NULL     COMMENT '반복 생성분만 값 존재. 단일 일정은 NULL',
    title              VARCHAR(100)  NOT NULL,
    origin_name        VARCHAR(100)  NOT NULL COMMENT '출발지 스냅샷 — place 를 FK 참조하지 않는다',
    origin_lat         DECIMAL(10,7) NOT NULL,
    origin_lng         DECIMAL(10,7) NOT NULL,
    dest_name          VARCHAR(100)  NOT NULL COMMENT '목적지 스냅샷',
    dest_lat           DECIMAL(10,7) NOT NULL,
    dest_lng           DECIMAL(10,7) NOT NULL,
    arrive_by          DATETIME      NOT NULL COMMENT '반드시 도착해야 하는 시각 — 출발시각 역산의 기준점',
    importance         ENUM('NORMAL','HIGH','CRITICAL') NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL=p50 / HIGH=p80 / CRITICAL=p95',
    consider_last_trip BOOLEAN       NOT NULL DEFAULT FALSE COMMENT '막차 위험 계산 대상 여부 (F-12)',
    is_customized      BOOLEAN       NOT NULL DEFAULT FALSE COMMENT 'TRUE = 반복 전체 수정이 이 행을 건너뜀',
    status             ENUM('UPCOMING','DONE','CANCELED') NOT NULL DEFAULT 'UPCOMING' COMMENT '재계산·알림 대상은 UPCOMING 만',
    created_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- repeat_id 가 NULL 인 단일 일정끼리는 제약을 받지 않는다(MySQL NULL 중복 허용) — 의도된 동작
    UNIQUE KEY UK_SCH_REPEAT_ARRIVE (repeat_id, arrive_by) COMMENT '반복 인스턴스 중복 생성 방지',
    KEY IX_SCH_USER_STATUS_ARRIVE (user_id, status, arrive_by) COMMENT '홈 화면·브리핑·기간 조회',
    CONSTRAINT FK_SCH_USER   FOREIGN KEY (user_id)   REFERENCES `user` (id)          ON DELETE CASCADE,
    CONSTRAINT FK_SCH_REPEAT FOREIGN KEY (repeat_id) REFERENCES schedule_repeat (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '일정 인스턴스 (F-01, F-03, F-05)';

-- ---------------------------------------------------------------------
-- 8. route_request — 외부 길찾기 호출 원본 (호출 1회당 1행)
--    raw_response 는 어떤 사용자 응답에도 노출 금지
-- ---------------------------------------------------------------------
CREATE TABLE route_request (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    schedule_id  BIGINT      NOT NULL,
    provider     ENUM('ODSAY','KAKAO') NOT NULL DEFAULT 'KAKAO',
    raw_response JSON        NULL COMMENT '외부 응답 원본 전체 — 재현성 확보용. 응답/로그 노출 금지',
    requested_at DATETIME    NOT NULL COMMENT '호출 시각',
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT FK_RREQ_SCHEDULE FOREIGN KEY (schedule_id) REFERENCES schedule (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '외부 길찾기 API 호출 원본';

-- ---------------------------------------------------------------------
-- 9. route — 후보 경로
--    [서비스 계층 보장 1] schedule_id 는 route_request.schedule_id 와 항상 같아야 한다
--                        (성능 목적 비정규화. DB로 강제 불가)
--    [서비스 계층 보장 2] 한 schedule 에 is_selected=TRUE 는 최대 1건
--                        (CLAUDE.md 규칙 6. PUT .../selection 트랜잭션에서 나머지 FALSE)
--    ※ 팀 합의 시 아래로 DB 강제 가능(별도 마이그레이션 안건):
--        selected_schedule_id BIGINT GENERATED ALWAYS AS (IF(is_selected, schedule_id, NULL)) VIRTUAL,
--        UNIQUE KEY UK_ROUTE_SELECTED (selected_schedule_id)
-- ---------------------------------------------------------------------
CREATE TABLE route (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    schedule_id       BIGINT       NOT NULL,
    route_request_id  BIGINT       NOT NULL COMMENT '이 후보를 만든 호출',
    provider_path_id  VARCHAR(100) NULL     COMMENT '외부 API 가 준 경로 식별자',
    base_duration_min INT          NOT NULL COMMENT '외부 API 기본 예상시간(분) — 뚜비 보정 전',
    is_selected       BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '사용자 주 경로. 재계산·알림 대상',
    rank_no           INT          NULL     COMMENT '최신 순위 캐시. 과거 순위의 진실은 recommendation',
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY IX_ROUTE_SCH_SELECTED (schedule_id, is_selected) COMMENT '일정별 후보 조회 + F-05 역조회 조인',
    CONSTRAINT FK_ROUTE_SCHEDULE FOREIGN KEY (schedule_id)      REFERENCES schedule (id)      ON DELETE CASCADE,
    CONSTRAINT FK_ROUTE_RREQ     FOREIGN KEY (route_request_id) REFERENCES route_request (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '후보 경로';

-- ---------------------------------------------------------------------
-- 10. route_segment — 경로의 이동 구간
--     IX_RSEG_SEGMENT 하나가 F-05(영향 일정 역조회)의 전부다:
--       segment_id → route_segment → route(is_selected=TRUE) → schedule(status=UPCOMING)
-- ---------------------------------------------------------------------
CREATE TABLE route_segment (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    route_id           BIGINT       NOT NULL,
    seq                INT          NOT NULL COMMENT '구간 순서 (1부터)',
    mode               ENUM('WALK','BUS','SUBWAY') NOT NULL,
    segment_id         VARCHAR(50)  NULL COMMENT '파이프라인 공용 구간 ID = Redis 조회 키. WALK 는 NULL. 채번 규칙은 docs/contracts 계약이 진실 — 코드 하드코딩 금지',
    provider_route_key VARCHAR(100) NULL COMMENT '외부 API 노선·역 식별자 — segment_id 매핑 근거',
    from_name          VARCHAR(100) NOT NULL,
    to_name            VARCHAR(100) NOT NULL,
    base_duration_min  INT          NOT NULL COMMENT '이 구간의 기본 소요시간(분)',
    geometry           JSON         NULL COMMENT '폴리라인 좌표 배열 — 지도 표시용',
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY UK_RSEG_ROUTE_SEQ (route_id, seq),
    KEY IX_RSEG_SEGMENT (segment_id) COMMENT 'F-05 영향 일정 역조회의 핵심 인덱스',
    CONSTRAINT FK_RSEG_ROUTE FOREIGN KEY (route_id) REFERENCES route (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '경로 구간 분해';

-- ---------------------------------------------------------------------
-- 11. recommendation — 추천 결과 스냅샷 (불변 INSERT, 절대 UPDATE 금지)
-- ---------------------------------------------------------------------
CREATE TABLE recommendation (
    id                      BIGINT      NOT NULL AUTO_INCREMENT,
    schedule_id             BIGINT      NOT NULL,
    route_id                BIGINT      NOT NULL COMMENT '당시 1순위 후보',
    depart_at               DATETIME    NOT NULL COMMENT '권장 출발 시각 — 서비스의 최종 답',
    eta                     DATETIME    NOT NULL COMMENT '예상 도착 시각',
    duration_min            INT         NOT NULL COMMENT '예상 이동시간(분)',
    buffer_min              INT         NOT NULL COMMENT '중요도에 따라 더한 안전 여유(분)',
    percentile_used         TINYINT     NOT NULL COMMENT '50 / 80 / 95',
    confidence              ENUM('HIGH','MID','LOW') NOT NULL COMMENT '실시간 대신 baseline 사용 시 LOW (REQ-ROU-005)',
    reasons                 JSON        NULL COMMENT '[{factor, min}, ...] + 2·3순위 후보 비교 요약',
    segment_status_snapshot JSON        NULL COMMENT '계산에 사용한 구간별 상태 원본 (재현성)',
    data_checked_at         DATETIME    NOT NULL COMMENT '사용한 교통 데이터의 확인 시각 — "18:01 기준" 표시용',
    policy_version          VARCHAR(10) NOT NULL COMMENT '추천 정책 버전 (예: v1.2)',
    trigger_type            ENUM('INITIAL','REFRESH','EVENT') NOT NULL COMMENT '최초 조회 / 사용자 새로고침 / 교통 변화 감지',
    created_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY IX_REC_SCH_CREATED (schedule_id, created_at) COMMENT 'GET .../recommendations/latest',
    CONSTRAINT FK_REC_SCHEDULE FOREIGN KEY (schedule_id) REFERENCES schedule (id) ON DELETE CASCADE,
    CONSTRAINT FK_REC_ROUTE    FOREIGN KEY (route_id)    REFERENCES route (id)    ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '추천 결과 스냅샷(불변)';

-- ---------------------------------------------------------------------
-- 12. notification — 알림 이력 + 인앱 알림함
--     발송 억제(알림 끔·DND·중복)여도 행은 반드시 생성하고 status=SUPPRESSED
-- ---------------------------------------------------------------------
CREATE TABLE notification (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    schedule_id       BIGINT       NULL COMMENT '일정 삭제 시 NULL 로 남겨 이력 유지',
    recommendation_id BIGINT       NULL COMMENT '이 알림의 근거가 된 추천 스냅샷',
    type              ENUM('MORNING_BRIEF','DEPART_SOON','EARLY_DEPART','ROUTE_CHANGE',
                           'ON_TIME_UNLIKELY','LAST_TRIP','SCHEDULE_CONFLICT','DATA_STALE') NOT NULL,
    title             VARCHAR(100) NOT NULL,
    body              VARCHAR(500) NOT NULL,
    suppress_key      VARCHAR(64)  NULL COMMENT '일정·이유·행동 조합 해시 — 재발송 방지 (REQ-NTF-002)',
    status            ENUM('SENT','FAILED','SUPPRESSED') NOT NULL,
    sent_at           DATETIME     NULL,
    read_at           DATETIME     NULL COMMENT 'NULL 개수 = 안읽음 뱃지',
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY IX_NTF_USER_CREATED (user_id, created_at) COMMENT '알림함 목록',
    KEY IX_NTF_SUPPRESS (user_id, suppress_key, created_at) COMMENT '재발송 판단',
    CONSTRAINT FK_NTF_USER     FOREIGN KEY (user_id)           REFERENCES `user` (id)         ON DELETE CASCADE,
    CONSTRAINT FK_NTF_SCHEDULE FOREIGN KEY (schedule_id)       REFERENCES schedule (id)       ON DELETE SET NULL,
    CONSTRAINT FK_NTF_REC      FOREIGN KEY (recommendation_id) REFERENCES recommendation (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '알림 발송·억제 이력 + 알림함';

-- ---------------------------------------------------------------------
-- 13. report — 이동 결과 리포트 (기간 집계, P3 — v1은 스키마만)
-- ---------------------------------------------------------------------
CREATE TABLE report (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    period_start    DATE        NOT NULL,
    period_end      DATE        NOT NULL,
    total_schedules INT         NOT NULL DEFAULT 0,
    on_time_est     INT         NOT NULL DEFAULT 0 COMMENT '정시 도착 추정 건수',
    delayed_est     INT         NOT NULL DEFAULT 0 COMMENT '지연 추정 건수',
    computed_at     DATETIME    NOT NULL COMMENT '집계 시각',
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY UK_REPORT_PERIOD (user_id, period_start, period_end),
    CONSTRAINT FK_REPORT_USER FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '기간 집계 리포트 (P3)';

-- ---------------------------------------------------------------------
-- 14. report_detail — 일정별 이동 결과
--     미동의자는 proxy_* 만, 동의자만 actual_arrival (REQ-HIS-001)
-- ---------------------------------------------------------------------
CREATE TABLE report_detail (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    report_id          BIGINT       NOT NULL,
    schedule_id        BIGINT       NOT NULL,
    planned_depart     DATETIME     NULL COMMENT '당시 권장 출발 시각',
    proxy_duration_min INT          NULL COMMENT '실제 구간 소요 합산(프록시 추정, 분)',
    proxy_arrival      DATETIME     NULL,
    actual_arrival     DATETIME     NULL COMMENT '지오펜스 실측. NULL = 미동의 사용자',
    result             ENUM('ON_TIME','TIGHT','LATE_RISK') NULL,
    main_cause         VARCHAR(100) NULL COMMENT '주요 원인 (예: 2호선 지연)',
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT FK_RDETAIL_REPORT   FOREIGN KEY (report_id)   REFERENCES report (id)   ON DELETE CASCADE,
    CONSTRAINT FK_RDETAIL_SCHEDULE FOREIGN KEY (schedule_id) REFERENCES schedule (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '일정별 이동 결과 (P3)';

-- =====================================================================
-- 파이프라인 서빙 테이블 (15~17)
--   소유: 분산 담당. 백엔드는 스키마 초안만 제공하고 읽기 전용으로 쓴다.
--   배치 적재 주체는 Spark. 스키마 수정 권한은 분산 담당에게 있다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 15. baseline_serving — 평소 이동시간 (Spark 배치 산출물)
--     ★ p50/p80/p95 단위는 **초**. route/recommendation 의 분과 혼동 금지
-- ---------------------------------------------------------------------
CREATE TABLE baseline_serving (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    segment_id     VARCHAR(50) NOT NULL COMMENT 'route_segment·Redis 와 동일 체계',
    dow            TINYINT     NOT NULL COMMENT '요일 1~7 (월=1)',
    hour_slot      TINYINT     NOT NULL COMMENT '시간대 0~23',
    weather_bucket ENUM('DRY','RAIN','SNOW') NOT NULL,
    p50            INT         NOT NULL COMMENT '단위: 초',
    p80            INT         NOT NULL COMMENT '단위: 초',
    p95            INT         NOT NULL COMMENT '단위: 초',
    sample_count   INT         NOT NULL DEFAULT 0 COMMENT '표본 수 — 적으면 신뢰도 낮음 표시',
    data_version   VARCHAR(20) NOT NULL COMMENT '배치 버전. 활성 버전은 pipeline_config 에서 조회',
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY UK_BASELINE_KEY (segment_id, dow, hour_slot, weather_bucket, data_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '평소 이동시간 baseline (소유: 분산 담당)';

-- ---------------------------------------------------------------------
-- 16. detection_log — 이상 감지 선행 검증 (REQ-PIP-003, 발표 근거)
-- ---------------------------------------------------------------------
CREATE TABLE detection_log (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    segment_id         VARCHAR(50) NOT NULL,
    detected_at        DATETIME    NOT NULL COMMENT '뚜비 감지 시각',
    official_notice_at DATETIME    NULL     COMMENT '공식 공지 시각. 없으면 NULL',
    lead_min           INT         NULL     COMMENT '선행 분 = 공지 − 감지',
    evidence           JSON        NULL     COMMENT '감지 근거(당시 수치)',
    verdict            ENUM('CONFIRMED','FALSE_POSITIVE','NO_NOTICE_SUSTAINED','NO_NOTICE_TRANSIENT') NULL,
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY IX_DETECT_SEGMENT (segment_id, detected_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '이상 감지 검증 로그 (소유: 분산 담당)';

-- ---------------------------------------------------------------------
-- 17. pipeline_config — 파이프라인 설정 포인터 (Key-Value)
--     ※ 공통 규칙(BIGINT AUTO_INCREMENT PK)의 **유일한 예외**.
--       ERD_master 정의를 그대로 따른다(소유: 분산 담당).
--     대표 용도: baseline_active_version = 백엔드가 읽어야 할 data_version
-- ---------------------------------------------------------------------
CREATE TABLE pipeline_config (
    config_key   VARCHAR(50)  NOT NULL COMMENT '설정 이름 (예: baseline_active_version)',
    config_value VARCHAR(100) NOT NULL COMMENT '설정 값 (예: 2026-08-29)',
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '파이프라인 설정 포인터 (소유: 분산 담당)';
