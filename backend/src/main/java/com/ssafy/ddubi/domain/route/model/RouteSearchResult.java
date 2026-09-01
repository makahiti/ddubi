package com.ssafy.ddubi.domain.route.model;

import java.time.Instant;
import java.util.List;

/** 외부 길찾기 결과를 카카오 형식에서 분리한 우리 서비스의 내부 결과. */
public record RouteSearchResult(
        // 현재는 KAKAO. 추후 제공자가 늘어날 수 있어 결과에 함께 기록한다.
        String provider,
        // 경로를 언제 조회했는지 알려 주는 시각. 교통 정보의 신선도 판단에 사용한다.
        Instant fetchedAt,
        // 필요하면 사용자를 카카오맵 결과 화면으로 보낼 수 있는 링크
        String landingUrl,
        List<RouteCandidate> candidates
) {
    public RouteSearchResult {
        // 결과 생성 후 후보 목록이 변경되지 않게 불변 복사한다.
        candidates = List.copyOf(candidates);
    }
}
