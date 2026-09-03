package com.ssafy.ddubi.domain.route.service;

import com.ssafy.ddubi.domain.route.mapping.TransitMappingResult;

/**
 * 카카오 경로의 이름·순서·좌표 증거를 서울 공식 마스터 ID로 변환하는 경계다.
 *
 * <p>마스터가 MySQL, 파일 또는 캐시 중 어디에 저장되는지는 구현체가 감춘다.
 * 결과에는 검증된 공식 인접 구간과 Backend 구현안의 canonical segment ID, 판정에 사용한
 * 마스터·정책 버전, 실시간 crosswalk coverage가 함께 들어간다.</p>
 */
@FunctionalInterface
public interface TransitMappingResolver {

    TransitMappingResult resolve(SegmentResolveRequest request);
}
