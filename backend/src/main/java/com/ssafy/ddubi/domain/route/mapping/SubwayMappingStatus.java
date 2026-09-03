package com.ssafy.ddubi.domain.route.mapping;

/**
 * 카카오 SUBWAY step을 공식 지하철 노선·역에 연결한 결과 상태다.
 *
 * <ul>
 *     <li>{@link #EXACT}: 호선, 승·하차역, 진행 순서와 방향이 하나로 확정됐다.</li>
 *     <li>{@link #PARTIAL}: 호선은 찾았지만 역 또는 방향 증거가 충분하지 않다.</li>
 *     <li>{@link #AMBIGUOUS}: 같은 수준의 공식 후보가 둘 이상이라 자동 선택할 수 없다.</li>
 *     <li>{@link #UNMAPPED}: 공식 마스터에서 호선 후보를 찾지 못했다.</li>
 * </ul>
 * EXACT가 아닌 결과에는 연산용 인접 역 구간을 발급하지 않는다.
 */
public enum SubwayMappingStatus {
    EXACT,
    PARTIAL,
    AMBIGUOUS,
    UNMAPPED
}
