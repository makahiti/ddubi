package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.service.SegmentResolveRequest;
import com.ssafy.ddubi.domain.route.model.TransportMode;

/**
 * 실제 등장한 외부 구간의 판정 결과와 수동 차단 결정을 마스터 버전별로 조회하는 저장 포트다.
 * REJECTED를 MISS와 구분해야 사람이 확인한 오판을 자동 Matcher가 다시 서빙하지 않는다.
 */
public interface ProviderSegmentMappingStore {

    ProviderSegmentMappingLookup find(
            String provider,
            TransportMode mode,
            String signature,
            String mappingVersion,
            String policyVersion);

    void save(
            String provider,
            String signature,
            String mappingVersion,
            String policyVersion,
            SegmentResolveRequest request,
            TransitMappingResult result
    );
}
