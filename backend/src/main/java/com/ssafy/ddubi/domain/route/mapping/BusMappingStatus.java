package com.ssafy.ddubi.domain.route.mapping;

/**
 * 카카오 BUS step을 공식 노선·정류장에 연결한 결과 상태다.
 *
 * <ul>
 *     <li>{@link #EXACT}: 공식 노선, 승·하차 정류장, 진행 순서가 하나로 확정됐다.</li>
 *     <li>{@link #PARTIAL}: 노선 후보는 찾았지만 정류장·순서·방향 증거가 부족하다.</li>
 *     <li>{@link #AMBIGUOUS}: 같은 수준의 후보가 둘 이상이므로 자동 선택하면 안 된다.</li>
 *     <li>{@link #UNMAPPED}: 공식 마스터에서 노선 후보 자체를 찾지 못했다.</li>
 * </ul>
 * EXACT가 아닌 결과에는 연산용 인접 구간을 발급하지 않는다.
 */
public enum BusMappingStatus {
    EXACT,
    PARTIAL,
    AMBIGUOUS,
    UNMAPPED
}
