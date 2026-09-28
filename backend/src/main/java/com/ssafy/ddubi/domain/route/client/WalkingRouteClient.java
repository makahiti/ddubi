package com.ssafy.ddubi.domain.route.client;

import com.ssafy.ddubi.domain.route.model.Coordinate;
import java.time.Instant;

/**
 * 카카오맵 도보 조회 port. origin/destination은 WGS84 경도·위도 순서다.
 * routeMode는 공식 API의 BROAD_FIRST/SHORTEST/ACCESSIBLE 중 하나다.
 * 실패 seconds=null은 0초가 아니다. 어댑터는 URL·키·raw 응답을 반환하지 않는다.
 */
public interface WalkingRouteClient {
    record Request(Coordinate origin, Coordinate destination, String routeMode, String policyVersion) { }
    /**
     * 도보 조회 결과. {@code path}는 지도에 선을 그릴 좌표열이며, 없거나 검증에 실패하면 빈 목록이다.
     *
     * <p>좌표는 표시 전용이다. 시간은 {@code seconds}만 쓰고 좌표로 다시 계산하지 않는다.
     */
    record Evidence(
            Long seconds,
            Instant validUntil,
            String source,
            String reason,
            java.util.List<Coordinate> path) {
        public Evidence {
            path = path == null ? java.util.List.of() : java.util.List.copyOf(path);
        }

        /** 좌표 없이 시간만 있는 결과. 기존 호출부와 fixture가 그대로 쓴다. */
        public Evidence(Long seconds, Instant validUntil, String source, String reason) {
            this(seconds, validUntil, source, reason, java.util.List.of());
        }
    }
    Evidence route(Request request);
}
