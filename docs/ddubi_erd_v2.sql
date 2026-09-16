-- =====================================================================
-- 뚜비(DDUBI) 서비스 DB 스키마 — ERD_master (구 ERD v2)
-- =====================================================================
-- 원천: Notion「ERD_master」(mermaid) + 「ERD 설명서」 + backend/BACKEND_CONVENTION.md
-- 대상: MySQL 8.0+ / InnoDB / utf8mb4
-- 소유: 백엔드(서비스) — 단, 16번 detection_log 는 분산 담당 소유(15~17번 주석 참조)
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
    provider         ENUM('LOCAL','KAKAO','GOOGLE','NAVER') NOT NULL COMMENT '로그인 수단 4종. SSAFY·APPLE 은 팀 합의로 제외',
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
    start_date   DATE          NOT NULL COMMENT '반복 시작일 — 이 날짜부터 인스턴스를 만든다 (REPEAT-001 startDate)',
    until_date   DATE          NULL     COMMENT '반복 종료일. NULL = 무기한 (REPEAT-001 endDate)',
    origin_name  VARCHAR(100)  NOT NULL COMMENT '출발지 스냅샷 (schedule 과 동일 구조)',
    origin_lat   DECIMAL(10,7) NOT NULL,
    origin_lng   DECIMAL(10,7) NOT NULL,
    dest_name    VARCHAR(100)  NOT NULL COMMENT '목적지 스냅샷',
    dest_lat     DECIMAL(10,7) NOT NULL,
    dest_lng     DECIMAL(10,7) NOT NULL,
    importance   ENUM('NORMAL','HIGH','CRITICAL') NOT NULL DEFAULT 'NORMAL' COMMENT '생성될 인스턴스의 기본 중요도',
    consider_last_trip BOOLEAN NOT NULL DEFAULT FALSE COMMENT '생성될 인스턴스의 막차 고려 기본값 — 없으면 연장 잡이 값을 정할 수 없다',
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
--                        (BACKEND_CONVENTION.md 규칙 6. PUT .../selection 트랜잭션에서 나머지 FALSE)
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
--     provider step 하나가 여러 canonical edge로 전개되므로 실제 F-05 역조회는
--       route_segment_edge.segment_id → route_segment → route → schedule 경로를 사용한다.
-- ---------------------------------------------------------------------
CREATE TABLE route_segment (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    route_id           BIGINT       NOT NULL,
    seq                INT          NOT NULL COMMENT '구간 순서 (1부터)',
    mode               ENUM('WALK','BUS','SUBWAY') NOT NULL,
    segment_id         VARCHAR(180) NULL COMMENT '레거시 단일-edge 호환 열. 신규 저장은 route_segment_edge를 단일 진실로 사용',
    provider_route_key VARCHAR(100) NULL COMMENT '외부 API 노선·역 식별자 — segment_id 매핑 근거',
    from_name          VARCHAR(100) NOT NULL,
    to_name            VARCHAR(100) NOT NULL,
    base_duration_min  INT          NOT NULL COMMENT '이 구간의 기본 소요시간(분)',
    geometry           JSON         NULL COMMENT '폴리라인 좌표 배열 — 지도 표시용',
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY UK_RSEG_ROUTE_SEQ (route_id, seq),
    KEY IX_RSEG_SEGMENT (segment_id) COMMENT '레거시 단일-edge 역조회 인덱스',
    CONSTRAINT FK_RSEG_ROUTE FOREIGN KEY (route_id) REFERENCES route (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '경로 구간 분해';

-- provider step의 시간·geometry를 임의 분할하지 않고, 매핑된 모든 공식 인접 edge를 1:N으로 보존한다.
CREATE TABLE route_segment_edge (
    route_segment_id BIGINT       NOT NULL,
    edge_seq         INT          NOT NULL COMMENT 'provider step 안 canonical edge 순서 (1부터)',
    segment_id       VARCHAR(180) NOT NULL COMMENT 'Redis·baseline_serving과 같은 공용 인접구간 ID',
    mapping_version  VARCHAR(50)  NOT NULL COMMENT '사용한 서울 교통 마스터 버전',
    policy_version   VARCHAR(30)  NOT NULL COMMENT '매핑·segment ID 정책 버전',
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (route_segment_id, edge_seq),
    KEY IX_RSEG_EDGE_SEGMENT (segment_id, route_segment_id)
        COMMENT 'F-05: 영향 segment에서 선택 경로·일정 역조회',
    CONSTRAINT FK_RSEG_EDGE_SEGMENT FOREIGN KEY (route_segment_id)
        REFERENCES route_segment (id) ON DELETE CASCADE,
    CONSTRAINT CK_RSEG_EDGE_SEQ CHECK (edge_seq >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'provider 이동구간의 canonical 인접구간 목록';

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
    suppress_reason   ENUM('DUPLICATE','ALARM_OFF','NO_VALID_DEVICE') NULL
                      COMMENT '보내지 않은 이유. SENT/FAILED 와 도입(V6) 이전 행은 NULL',
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
    UNIQUE KEY UK_RDETAIL_SCHEDULE (schedule_id) COMMENT '일정 하나의 이동 결과는 하나. 재보고는 갱신',
    CONSTRAINT FK_RDETAIL_REPORT   FOREIGN KEY (report_id)   REFERENCES report (id)   ON DELETE CASCADE,
    CONSTRAINT FK_RDETAIL_SCHEDULE FOREIGN KEY (schedule_id) REFERENCES schedule (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '일정별 이동 결과 (P3)';

-- =====================================================================
-- 파이프라인 서빙 테이블 (15~17) — 소유가 표마다 다르다
--   15 baseline_serving · 17 pipeline_config: **백엔드(서비스) 소유.**
--     Spark 는 MySQL 에 직접 쓰지 않고 internal API(방법 2, docs/contracts/
--     internal-api.yaml)로 업로드하며, 검증·적재·버전 전환은 백엔드가 한다.
--     (2026-09-01 인프라 합의. 이전에는 분산 담당 소유였다)
--   16 detection_log: 분산 담당 소유. 감지 주체가 Spark 라 그쪽이 직접 쓴다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 15. baseline_serving — 평소 이동시간 (Spark 배치 산출물)
--     ★ p50/p80/p95 단위는 **초**. route/recommendation 의 분과 혼동 금지
-- ---------------------------------------------------------------------
CREATE TABLE baseline_serving (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    segment_id     VARCHAR(180) NOT NULL COMMENT 'route_segment·Redis 와 동일 체계',
    dow            TINYINT     NOT NULL COMMENT '요일 1~7. 일=1, 토=7 (Spark dayofweek 기준). Java LocalDate 는 월=1 이므로 변환 필요',
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
    UNIQUE KEY UK_BASELINE_KEY (segment_id, dow, hour_slot, weather_bucket, data_version),
    KEY IX_BASELINE_VERSION (data_version) COMMENT '버전 단위 집계·삭제·정리용. UK 에서는 data_version 이 마지막이라 단독 조회에 못 쓴다'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '평소 이동시간 baseline (소유: 백엔드 — Spark 가 internal API 로 업로드)';

-- ---------------------------------------------------------------------
-- 16. detection_log — 이상 감지 선행 검증 (REQ-PIP-003, 발표 근거)
-- ---------------------------------------------------------------------
CREATE TABLE detection_log (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    segment_id         VARCHAR(180) NOT NULL,
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
--     ※ 공통 규칙(BIGINT AUTO_INCREMENT PK)의 **유일한 예외**. ERD_master 정의를 따른다.
--     소유: 백엔드. internal API 의 activate 가 아래 두 키를 트랜잭션으로 갱신한다.
--       baseline_active_version    = 서빙 중인 data_version (예: 2026-09-01)
--       baseline_last_activated_at = 마지막 활성화 시각 — 이 값이 오래되면
--         confidence 강등·DATA_STALE 알림의 근거가 된다 (internal-api-review 3-2)
-- ---------------------------------------------------------------------
CREATE TABLE pipeline_config (
    config_key   VARCHAR(50)  NOT NULL COMMENT '설정 이름 (예: baseline_active_version)',
    config_value VARCHAR(100) NOT NULL COMMENT '설정 값 (예: 2026-08-29)',
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '파이프라인 설정 포인터 (소유: 백엔드 — internal API 활성화 시점에 갱신)';

-- =====================================================================
-- 서울 공식 대중교통 마스터 (18~24)
--   카카오 이름 기반 경로를 공식 노선·정류장·역 ID로 매핑하기 위한 읽기 전용 기준정보다.
--   운행 이력은 저장하지 않으며, 새 버전을 전부 적재한 뒤
--   pipeline_config.seoul_transit_master_active_version 포인터만 교체한다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 18. seoul_transit_master_version — 마스터 적재·활성화 이력
-- ---------------------------------------------------------------------
CREATE TABLE seoul_transit_master_version (
    data_version VARCHAR(50) NOT NULL COMMENT '공식 파일 기준일 또는 적재 버전',
    status       ENUM('STAGING','ACTIVE','RETIRED') NOT NULL DEFAULT 'STAGING',
    source_name  VARCHAR(200) NOT NULL COMMENT '서울시 원본 데이터셋 또는 파일명',
    loaded_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    activated_at DATETIME(6) NULL,
    PRIMARY KEY (data_version),
    KEY IX_SEOUL_MASTER_STATUS (status, activated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '서울 교통 마스터 버전';

-- ---------------------------------------------------------------------
-- 19~21. 버스 노선·정류장·노선별 운행 순서
-- ---------------------------------------------------------------------
CREATE TABLE seoul_bus_route (
    data_version VARCHAR(50)  NOT NULL,
    route_id     VARCHAR(50)  NOT NULL COMMENT '서울시 공식 버스 노선 ID',
    route_name   VARCHAR(100) NOT NULL COMMENT '공식 노선명',
    PRIMARY KEY (data_version, route_id),
    KEY IX_SEOUL_BUS_ROUTE_NAME (data_version, route_name),
    CONSTRAINT FK_SEOUL_BUS_ROUTE_VERSION FOREIGN KEY (data_version)
        REFERENCES seoul_transit_master_version (data_version) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '서울 버스 노선 마스터';

CREATE TABLE seoul_bus_stop (
    data_version VARCHAR(50)  NOT NULL,
    stop_id      VARCHAR(50)  NOT NULL COMMENT '서울시 NODE_ID 등 공식 정류장 ID',
    ars_id       VARCHAR(20)  NULL COMMENT '시민 안내용 ARS 정류장 번호',
    stop_name    VARCHAR(150) NOT NULL COMMENT '공식 정류장명',
    longitude    DECIMAL(10,7) NULL COMMENT 'WGS84 경도',
    latitude     DECIMAL(10,7) NULL COMMENT 'WGS84 위도',
    PRIMARY KEY (data_version, stop_id),
    KEY IX_SEOUL_BUS_STOP_NAME (data_version, stop_name),
    KEY IX_SEOUL_BUS_STOP_ARS (data_version, ars_id),
    CONSTRAINT FK_SEOUL_BUS_STOP_VERSION FOREIGN KEY (data_version)
        REFERENCES seoul_transit_master_version (data_version) ON DELETE CASCADE,
    CONSTRAINT CK_SEOUL_BUS_STOP_LONGITUDE CHECK (longitude IS NULL OR longitude BETWEEN 124 AND 132),
    CONSTRAINT CK_SEOUL_BUS_STOP_LATITUDE CHECK (latitude IS NULL OR latitude BETWEEN 33 AND 39)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '서울 버스 정류장 마스터';

CREATE TABLE seoul_bus_route_stop (
    data_version VARCHAR(50) NOT NULL,
    route_id     VARCHAR(50) NOT NULL,
    stop_id      VARCHAR(50) NOT NULL,
    stop_sequence INT        NOT NULL COMMENT '노선 안 운행 순번. 같은 정류장 재방문은 다른 순번으로 보존',
    PRIMARY KEY (data_version, route_id, stop_sequence),
    KEY IX_SEOUL_BUS_ROUTE_STOP (data_version, route_id, stop_id),
    KEY IX_SEOUL_BUS_STOP_ROUTE (data_version, stop_id, route_id),
    CONSTRAINT FK_SEOUL_BUS_RS_ROUTE FOREIGN KEY (data_version, route_id)
        REFERENCES seoul_bus_route (data_version, route_id) ON DELETE CASCADE,
    CONSTRAINT FK_SEOUL_BUS_RS_STOP FOREIGN KEY (data_version, stop_id)
        REFERENCES seoul_bus_stop (data_version, stop_id) ON DELETE CASCADE,
    CONSTRAINT CK_SEOUL_BUS_STOP_SEQUENCE CHECK (stop_sequence >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '서울 버스 노선별 정류장 순서';

-- ---------------------------------------------------------------------
-- 22~24. 지하철 호선·역·방향별 운행 순서
-- ---------------------------------------------------------------------
CREATE TABLE seoul_subway_line (
    data_version VARCHAR(50)  NOT NULL,
    subway_id    VARCHAR(50)  NOT NULL COMMENT '정적 마스터 호선 코드',
    realtime_subway_id VARCHAR(50) NULL COMMENT '서울 실시간 API subwayId. 대응 미확인 시 NULL',
    direction    VARCHAR(30)  NOT NULL COMMENT 'UP/DOWN/INNER/OUTER 등 공식 운행 방향',
    service_pattern_id VARCHAR(64) NOT NULL COMMENT '본선·지선·단축운행을 구분하는 역 순서 해시',
    line_name    VARCHAR(100) NOT NULL COMMENT '공식 호선명',
    PRIMARY KEY (data_version, subway_id, direction, service_pattern_id),
    KEY IX_SEOUL_SUBWAY_LINE_NAME (data_version, line_name),
    KEY IX_SEOUL_SUBWAY_REALTIME_LINE (data_version, realtime_subway_id),
    CONSTRAINT FK_SEOUL_SUBWAY_LINE_VERSION FOREIGN KEY (data_version)
        REFERENCES seoul_transit_master_version (data_version) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '서울 지하철 호선·방향 마스터';

CREATE TABLE seoul_subway_station (
    data_version VARCHAR(50)  NOT NULL,
    station_id   VARCHAR(50)  NOT NULL COMMENT '정적 역 마스터의 STATION_CD',
    external_code VARCHAR(30) NULL COMMENT '승객 안내용 FR_CODE',
    realtime_station_id VARCHAR(50) NULL COMMENT '서울 실시간 API statnId. 대응 미확인 시 NULL',
    station_name VARCHAR(150) NOT NULL COMMENT '공식 역명',
    longitude    DECIMAL(10,7) NULL COMMENT 'WGS84 경도',
    latitude     DECIMAL(10,7) NULL COMMENT 'WGS84 위도',
    PRIMARY KEY (data_version, station_id),
    KEY IX_SEOUL_SUBWAY_STATION_NAME (data_version, station_name),
    UNIQUE KEY UK_SEOUL_SUBWAY_REALTIME_STATION (data_version, realtime_station_id),
    CONSTRAINT FK_SEOUL_SUBWAY_STATION_VERSION FOREIGN KEY (data_version)
        REFERENCES seoul_transit_master_version (data_version) ON DELETE CASCADE,
    CONSTRAINT CK_SEOUL_SUBWAY_LONGITUDE CHECK (longitude IS NULL OR longitude BETWEEN 124 AND 132),
    CONSTRAINT CK_SEOUL_SUBWAY_LATITUDE CHECK (latitude IS NULL OR latitude BETWEEN 33 AND 39)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '서울 지하철 역 마스터';

CREATE TABLE seoul_subway_line_station (
    data_version   VARCHAR(50) NOT NULL,
    subway_id      VARCHAR(50) NOT NULL,
    direction      VARCHAR(30) NOT NULL,
    service_pattern_id VARCHAR(64) NOT NULL,
    station_id     VARCHAR(50) NOT NULL,
    station_sequence INT       NOT NULL COMMENT '해당 방향의 실제 운행 순번',
    PRIMARY KEY (data_version, subway_id, direction, service_pattern_id, station_sequence),
    KEY IX_SEOUL_SUBWAY_LINE_STATION (data_version, subway_id, direction, service_pattern_id, station_id),
    KEY IX_SEOUL_SUBWAY_STATION_LINE (data_version, station_id, subway_id),
    CONSTRAINT FK_SEOUL_SUBWAY_LS_LINE FOREIGN KEY
        (data_version, subway_id, direction, service_pattern_id)
        REFERENCES seoul_subway_line
        (data_version, subway_id, direction, service_pattern_id) ON DELETE CASCADE,
    CONSTRAINT FK_SEOUL_SUBWAY_LS_STATION FOREIGN KEY (data_version, station_id)
        REFERENCES seoul_subway_station (data_version, station_id) ON DELETE CASCADE,
    CONSTRAINT CK_SEOUL_SUBWAY_SEQUENCE CHECK (station_sequence >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '서울 지하철 방향별 역 순서';

-- ---------------------------------------------------------------------
-- 25. transit_alias — 제공자 표기와 공식 마스터 표기의 검증된 대응
-- ---------------------------------------------------------------------
CREATE TABLE transit_alias (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    data_version    VARCHAR(50)  NOT NULL,
    provider        VARCHAR(30)  NOT NULL COMMENT 'KAKAO, SEOUL 등 외부 표기 제공자',
    mode            ENUM('BUS','SUBWAY') NOT NULL,
    alias_type      ENUM('ROUTE','STOP','LINE','STATION') NOT NULL,
    alias_name      VARCHAR(150) NOT NULL COMMENT '외부 제공자 원문 표기',
    normalized_name VARCHAR(150) NOT NULL COMMENT '기본 정규화 규칙을 적용한 조회 키',
    canonical_id    VARCHAR(50)  NOT NULL COMMENT '공식 노선·정류장·호선·역 ID',
    canonical_name  VARCHAR(150) NOT NULL COMMENT 'canonical_id에서 역조회한 공식 표기. 표시·감사용이며 Matcher는 ID를 사용',
    verified        BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '사람 또는 검증 배치가 확인한 별칭만 자동 매칭에 사용',
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY UK_TRANSIT_ALIAS
        (data_version, provider, mode, alias_type, normalized_name, canonical_id),
    KEY IX_TRANSIT_ALIAS_CANONICAL (data_version, mode, canonical_id),
    CONSTRAINT FK_TRANSIT_ALIAS_VERSION FOREIGN KEY (data_version)
        REFERENCES seoul_transit_master_version (data_version) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '외부 교통 명칭 별칭';

-- ---------------------------------------------------------------------
-- 26. provider_segment_mapping — 실제 등장한 외부 구간의 매핑 결과 캐시
-- ---------------------------------------------------------------------
CREATE TABLE provider_segment_mapping (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    provider            VARCHAR(30)  NOT NULL,
    mode                ENUM('BUS','SUBWAY') NOT NULL,
    provider_signature  CHAR(64)     NOT NULL COMMENT '교통수단·노선·승하차·통과순서·BUS endpoint 좌표의 SHA-256',
    mapping_version     VARCHAR(50)  NOT NULL COMMENT '판정에 사용한 활성 서울 마스터 버전',
    policy_version      VARCHAR(30)  NOT NULL COMMENT '정규화·판정·segment_id 알고리즘 버전',
    mapping_status      ENUM('EXACT','PARTIAL','AMBIGUOUS','UNMAPPED') NOT NULL,
    canonical_route_id  VARCHAR(50)  NULL,
    from_node_id        VARCHAR(50)  NULL,
    to_node_id          VARCHAR(50)  NULL,
    evidence_json       JSON         NOT NULL COMMENT '재검증용 이름·순서와 BUS 첫/끝 좌표. 전체 polyline은 저장하지 않음',
    result_json         JSON         NOT NULL COMMENT '인접 구간별 segment_id를 포함한 전체 판정 결과',
    resolved_at         DATETIME(6)  NULL COMMENT 'EXACT 자동 판정 시각. 수동 검증을 의미하지 않음',
    review_status       ENUM('UNREVIEWED','APPROVED','REJECTED') NOT NULL DEFAULT 'UNREVIEWED'
                                     COMMENT '수동 확인 결과. 자동 계산이 이 값을 덮어쓰지 않음',
    review_note         VARCHAR(500) NULL,
    reviewed_at         DATETIME(6)  NULL,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY UK_PROVIDER_SEGMENT_SIGNATURE
        (provider, mode, provider_signature, mapping_version, policy_version),
    KEY IX_PROVIDER_SEGMENT_STATUS (mapping_version, mapping_status, active),
    KEY IX_PROVIDER_SEGMENT_REVIEW (mapping_version, review_status, mapping_status),
    CONSTRAINT FK_PROVIDER_SEGMENT_VERSION FOREIGN KEY (mapping_version)
        REFERENCES seoul_transit_master_version (data_version) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '외부 경로 구간 매핑 캐시';

-- 수도권 버스 artifact는 기존 서울·지하철 버전과 독립적으로 보관한다.
-- Spring 관리 적재만 쓰며 수집기/Spark는 이 테이블에 직접 쓰지 않는다.
CREATE TABLE metropolitan_bus_master (
    data_version VARCHAR(50) NOT NULL,
    artifact_sha256 CHAR(64) NOT NULL,
    artifact_gzip LONGBLOB NOT NULL,
    route_count INT NOT NULL,
    stop_count INT NOT NULL,
    loaded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (data_version),
    CONSTRAINT CK_METRO_ROUTE_COUNT CHECK (route_count > 0),
    CONSTRAINT CK_METRO_STOP_COUNT CHECK (stop_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- singleton 행을 FOR UPDATE로 잠가 동시 적재의 활성 전환을 직렬화한다.
CREATE TABLE metropolitan_bus_master_active (
    singleton_id TINYINT NOT NULL,
    data_version VARCHAR(50) NULL,
    activated_at DATETIME(6) NULL,
    PRIMARY KEY (singleton_id),
    CONSTRAINT CK_METRO_SINGLETON CHECK (singleton_id = 1),
    CONSTRAINT FK_METRO_ACTIVE_VERSION FOREIGN KEY (data_version)
        REFERENCES metropolitan_bus_master(data_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO metropolitan_bus_master_active(singleton_id) VALUES (1);

CREATE TABLE metropolitan_bus_mapping_cache (
    data_version VARCHAR(50) NOT NULL,
    policy_version VARCHAR(50) NOT NULL,
    provider_signature CHAR(64) NOT NULL,
    result_json JSON NOT NULL,
    review_status ENUM('UNREVIEWED','APPROVED','REJECTED') NOT NULL DEFAULT 'UNREVIEWED',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(data_version,policy_version,provider_signature),
    CONSTRAINT FK_METRO_CACHE_VERSION FOREIGN KEY(data_version)
        REFERENCES metropolitan_bus_master(data_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
