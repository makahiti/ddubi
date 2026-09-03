package com.ssafy.ddubi.domain.route.mapping;

/**
 * 공식 지하철 노선의 한 방향 안에서 순서가 확정된 역이다.
 *
 * @param stationId 정적 역 마스터의 STATION_CD
 * @param realtimeStationId 서울 실시간 API의 statnId. 대응표 미적재 시 null
 * @param stationName 공식 역명
 * @param sequence 해당 방향의 운행 순번
 */
public record SubwayStationMaster(
        String stationId,
        String realtimeStationId,
        String stationName,
        int sequence
) {
    /** 테스트·이미 canonical ID인 입력은 전달된 ID 자체가 실시간 조회 ID임을 명시한다. */
    public SubwayStationMaster(String stationId, String stationName, int sequence) {
        this(stationId, stationId, stationName, sequence);
    }

    public boolean hasRealtimeStationId() {
        return realtimeStationId != null && !realtimeStationId.isBlank();
    }

    /** 내부 canonical ID는 실시간 제공자 대응표가 바뀌어도 변하지 않는 정적 STATION_CD다. */
    public String canonicalNodeId() {
        return stationId;
    }
}
