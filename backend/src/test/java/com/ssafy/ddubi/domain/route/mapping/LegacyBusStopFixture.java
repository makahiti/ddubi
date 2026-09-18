package com.ssafy.ddubi.domain.route.mapping;

/**
 * 과거 corpus JSON을 읽기 위한 테스트 전용 입력 형식이다. 운영 매핑에는 사용하지 않는다.
 * 공식 노선 안에서 순서가 확정된 버스 정류장이다.
 *
 * @param stopId 서울시 등 공식 제공자가 발급한 정류소 ID
 * @param stopName 공식 정류소명. 카카오 이름과 직접 비교하지 않고 정규화 후 비교한다.
 * @param sequence 해당 노선 안에서의 운행 순번
 * @param longitude 정류소 경도. 없으면 공간 검증을 통과할 수 없다.
 * @param latitude 정류소 위도. 없으면 공간 검증을 통과할 수 없다.
 */
public record LegacyBusStopFixture(
        String stopId,
        String stopName,
        int sequence,
        Double longitude,
        Double latitude
) {
}
