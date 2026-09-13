package com.ssafy.ddubi.domain.recommendation.service;

/**
 * provider step 을 서울 마스터의 인접 구간으로 전개한 edge 하나.
 * 예측·기준선·실시간 상태가 전부 이 단위로 키가 잡혀 있다.
 *
 * @param busKey 예측에 쓸 제공자 ID. 버스가 아니거나 매핑이 없으면 null
 */
public record EdgeEstimateInput(
        int edgeSeq,
        String segmentId,
        BusProviderKey busKey
) {
}
