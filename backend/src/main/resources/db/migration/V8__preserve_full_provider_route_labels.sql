-- 카카오 BUS step은 여러 대체 노선의 표시명을 합쳐 반환할 수 있다.
-- 이 값은 식별자가 아니라 감사용 원문이므로 자르거나 임의 노선 하나로 축약하지 않는다.
ALTER TABLE route_segment
    MODIFY COLUMN provider_route_key TEXT NULL
    COMMENT '외부 API 차량명 목록 전체 — 표시 및 매핑 감사 근거, canonical ID 아님';
