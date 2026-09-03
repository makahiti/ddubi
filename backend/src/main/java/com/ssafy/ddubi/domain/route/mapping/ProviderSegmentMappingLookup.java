package com.ssafy.ddubi.domain.route.mapping;

import java.util.Objects;

/**
 * 외부 구간 매핑 캐시 조회 결과를 단순 hit/miss보다 구체적으로 표현한다.
 *
 * <p>{@link Status#REJECTED}는 사람이 해당 signature의 자동 판정을 오판으로 확인했다는
 * 차단 신호다. 이를 일반 MISS로 취급하면 Resolver가 같은 알고리즘을 다시 실행해 동일한
 * 잘못된 EXACT를 사용자에게 반환하므로, 호출자는 재계산하지 않고 비매핑 결과를 반환해야 한다.</p>
 */
public record ProviderSegmentMappingLookup(
        Status status,
        TransitMappingResult exactResult
) {
    public ProviderSegmentMappingLookup {
        Objects.requireNonNull(status, "캐시 조회 상태는 비어 있을 수 없습니다.");
        if ((status == Status.EXACT) != (exactResult != null)) {
            throw new IllegalArgumentException("EXACT 조회만 캐시 결과를 포함해야 합니다.");
        }
    }

    public static ProviderSegmentMappingLookup miss() {
        return new ProviderSegmentMappingLookup(Status.MISS, null);
    }

    public static ProviderSegmentMappingLookup exact(TransitMappingResult result) {
        return new ProviderSegmentMappingLookup(Status.EXACT, Objects.requireNonNull(result));
    }

    public static ProviderSegmentMappingLookup rejected() {
        return new ProviderSegmentMappingLookup(Status.REJECTED, null);
    }

    public enum Status {
        MISS,
        EXACT,
        REJECTED
    }
}
