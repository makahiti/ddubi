package com.ssafy.ddubi.domain.route.mapping;

/**
 * 공식 지하철 노선에서 실제로 서로 이웃한 두 역 사이의 구간이다.
 * 카카오 승차→하차 SUBWAY step 하나는 이 객체 여러 개로 전개된다.
 * canonical segment_id 문자열은 별도 Factory가 정적 공식 ID 조합으로 생성한다.
 *
 * <p>{@code subwayId/fromStationId/toStationId}는 정적 topology의 진실이고,
 * {@code realtimeSubwayId/fromRealtimeStationId/toRealtimeStationId}는 실시간 API용
 * 선택 crosswalk다. 실시간 값이 null이어도 정적 topology EXACT는 유지되지만 실시간 보정에는
 * 사용할 수 없다.</p>
 */
public record MappedSubwaySegment(
        int sequence,
        String subwayId,
        String realtimeSubwayId,
        String lineName,
        String direction,
        String fromStationId,
        String toStationId,
        String fromRealtimeStationId,
        String toRealtimeStationId,
        int fromOrder,
        int toOrder
) {
}
