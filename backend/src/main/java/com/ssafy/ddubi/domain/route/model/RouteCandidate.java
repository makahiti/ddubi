package com.ssafy.ddubi.domain.route.model;

import java.util.List;

/**
 * 카카오가 반환한 후보 순서를 providerCandidateIndex로 그대로 보존한다.
 * 전체 시간/거리는 step 합이 아니라 카카오 route.properties 값을 기준으로 한다.
 */
public record RouteCandidate(
        // 카카오 routes 배열의 순번(0부터). 우리 서비스가 새로 매긴 DB ID가 아니다.
        int providerCandidateIndex,
        RouteType type,
        // 카카오가 계산한 후보 전체 거리(m)와 시간(초)
        long totalDistanceMeters,
        long totalDurationSeconds,
        int transfers,
        FareRange fare,
        // 전체값에는 있지만 step으로 표현되지 않은 거리와 시간
        long unassignedDistanceMeters,
        long unassignedDurationSeconds,
        // 실제 이동 순서대로 정렬된 버스·지하철·도보 구간
        List<RouteSegment> segments
) {
    public RouteCandidate {
        // 외부에서 받은 가변 List가 나중에 바뀌어 후보 내용이 변하지 않도록 복사한다.
        segments = List.copyOf(segments);
    }
}
