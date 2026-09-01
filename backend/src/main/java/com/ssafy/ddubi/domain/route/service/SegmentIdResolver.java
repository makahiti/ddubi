package com.ssafy.ddubi.domain.route.service;

import java.util.Optional;

/**
 * 외부 API의 노선/정류장 표현을 파이프라인 공용 segment_id로 바꾸는 경계.
 * 채번 규칙은 구현체와 docs/contracts에서 관리하며 카카오 변환기에 하드코딩하지 않는다.
 */
@FunctionalInterface
public interface SegmentIdResolver {

    Optional<String> resolve(SegmentResolveRequest request);
}
