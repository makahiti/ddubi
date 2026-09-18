package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.model.TransportMode;

/**
 * 서울 공식 마스터에서 확인된 인접 정류장·역 한 구간이다.
 *
 * <p>이 객체의 정적 ID들은 canonical-adjacent-v2 구성요소다. 서울 ID는 보존하고 다른 버스 출처는 namespace를 포함한다. {@code segmentId}는
 * {@link CanonicalSegmentIdFactory}가 공식 노선과 실제 인접 노드 쌍으로만 만든다.
 * {@code routeId/fromNodeId/toNodeId}는 변하지 않는 canonical topology이고,
 * 이름이 비슷한지를 나타내는 값이 아니다. {@code realtime*} 필드는 현재 실시간 API를
 * 조회하기 위한 대응값이므로 없을 수 있고 canonical ID 생성에는 사용하지 않는다.</p>
 *
 * @param sequence provider step 안 인접 edge 순서(1부터 시작)
 * @param segmentId Redis·Kafka·baseline에서 공통 사용하는 canonical 인접구간 ID
 * @param routeId 정적 공식 노선/호선 ID
 * @param realtimeRouteId 실시간 API 노선/호선 ID, 대응 미확인 시 null
 * @param fromNodeId 정적 공식 출발 정류장/역 ID
 * @param toNodeId 정적 공식 도착 정류장/역 ID
 * @param fromRealtimeNodeId 실시간 API 출발 노드 ID, 대응 미확인 시 null
 * @param toRealtimeNodeId 실시간 API 도착 노드 ID, 대응 미확인 시 null
 */
public record MappedTransitSegment(
        int sequence,
        String segmentId,
        TransportMode mode,
        String routeId,
        String realtimeRouteId,
        String routeName,
        String direction,
        String fromNodeId,
        String toNodeId,
        String fromRealtimeNodeId,
        String toRealtimeNodeId,
        int fromOrder,
        int toOrder
) {
    /** 실시간 API 호출에 필요한 provider crosswalk가 두 끝점까지 모두 준비됐는지 나타낸다. */
    public boolean realtimeLookupReady() {
        return realtimeRouteId != null && !realtimeRouteId.isBlank()
                && fromRealtimeNodeId != null && !fromRealtimeNodeId.isBlank()
                && toRealtimeNodeId != null && !toRealtimeNodeId.isBlank();
    }
}
