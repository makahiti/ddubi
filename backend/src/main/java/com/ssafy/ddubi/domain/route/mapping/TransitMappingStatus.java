package com.ssafy.ddubi.domain.route.mapping;

/**
 * 버스와 지하철에 공통으로 공개하는 공식 마스터 매핑 상태다.
 *
 * <p>EXACT만 구간별 시간 연산에 사용할 수 있다. PARTIAL·AMBIGUOUS·UNMAPPED는
 * 실패 원인을 보존하되 임의의 공식 ID를 선택하지 않는다. WALK는 서울 교통 마스터의
 * 매핑 대상이 아니므로 NOT_APPLICABLE로 구분한다.</p>
 */
public enum TransitMappingStatus {
    EXACT,
    PARTIAL,
    AMBIGUOUS,
    UNMAPPED,
    NOT_APPLICABLE
}
