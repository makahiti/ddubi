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
    record Evidence(Long seconds, Instant validUntil, String source, String reason) { }
    Evidence route(Request request);
}
