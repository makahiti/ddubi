package com.ssafy.ddubi.domain.route.mapping;

/**
 * canonical topology 매핑과 별개로 실시간 API crosswalk가 얼마나 준비됐는지 나타낸다.
 * NONE/PARTIAL이면 Backend는 해당 edge에 실시간값이 있다고 가정하지 않고 baseline으로
 * fallback하며 confidence를 낮춰야 한다.
 */
public enum RealtimeMappingCoverage {
    NOT_APPLICABLE,
    NONE,
    PARTIAL,
    FULL
}
