package com.ssafy.ddubi.domain.recommendation.service;

/**
 * 예측 모델이 요구하는 서울 제공자 ID 묶음. {@code route/mapping} 의
 * {@code MappedBusSegment} 에서 그대로 온다 — 카카오 ID 가 아니다.
 */
public record BusProviderKey(
        String providerRouteId,
        String providerFromStopId,
        String providerToStopId,
        int fromStopSequence
) {
}
