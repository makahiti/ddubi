package com.ssafy.ddubi.domain.route.mapping;

/**
 * 공식 노선 안에서 순서가 확정된 버스 정류장이다.
 *
 * @param stopId 서울시 등 공식 제공자가 발급한 정류소 ID
 * @param stopName 공식 정류소명. 카카오 이름과 직접 비교하지 않고 정규화 후 비교한다.
 * @param sequence 해당 노선 안에서의 운행 순번
 * @param longitude 정류소 경도. 없으면 좌표 방향 검증을 생략한다.
 * @param latitude 정류소 위도. 없으면 좌표 방향 검증을 생략한다.
 */
public record BusStopMaster(
        String stopId,
        String stopName,
        int sequence,
        Double longitude,
        Double latitude
) {
}
