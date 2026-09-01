-- 이 파일은 이미 실행된 V1을 직접 고치지 않고 DB 구조를 다음 버전으로 올리는 Flyway 마이그레이션이다.
-- 기존 ODSAY 데이터와의 호환성을 유지하면서 신규 경로 요청의 기본 제공자를 카카오로 전환한다.
ALTER TABLE route_request
    MODIFY provider ENUM('ODSAY', 'KAKAO') NOT NULL DEFAULT 'KAKAO';
