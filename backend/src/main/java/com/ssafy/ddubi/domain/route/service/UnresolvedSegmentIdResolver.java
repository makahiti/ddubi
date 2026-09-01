package com.ssafy.ddubi.domain.route.service;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 공통 segment_id 계약이 확정되기 전 사용하는 안전한 기본 구현.
 */
@Component
public class UnresolvedSegmentIdResolver implements SegmentIdResolver {

    @Override
    public Optional<String> resolve(SegmentResolveRequest request) {
        // 이름을 조합해 임의 ID를 만들면 동명 정류장·노선명 변경 때 잘못 연결되므로 일단 비워 둔다.
        return Optional.empty();
    }
}
