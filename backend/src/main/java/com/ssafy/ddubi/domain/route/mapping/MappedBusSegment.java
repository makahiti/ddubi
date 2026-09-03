package com.ssafy.ddubi.domain.route.mapping;

/**
 * 공식 노선에서 실제로 서로 이웃한 정류장 한 구간이다.
 * 카카오의 승차→하차 BUS step 하나는 이 객체 여러 개로 전개된다.
 * canonical segment_id 문자열은 별도 Factory가 이 공식 ID 조합으로 생성한다.
 */
public record MappedBusSegment(
        int sequence,
        String routeId,
        String fromStopId,
        String toStopId,
        int fromOrder,
        int toOrder
) {
}
