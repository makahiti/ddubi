package com.ssafy.ddubi.domain.route.client;

import com.ssafy.ddubi.domain.route.client.dto.KakaoWalkingResponse;

/**
 * 카카오 도보 안내에서 횡단보도 이용 구간에 계획 여유(초)를 더한다. docs/contracts/travel-time-calculation.md의 사용자 경험식 B(L)를
 * 사용한다. 실제 적색 잔여시간이나 전체 경로 P90이 아니다. 횡단보도 안내/길이가 없으면 가산하지 않는다. 실제 신호 설비를 확정하는 값은 아니다. 복수 신호는 각
 * step의 계획 여유를 합한다. 환승 도보에는 이 함수를 호출하지 않는다.
 */
public final class WalkingSignalAllowance {
    private WalkingSignalAllowance() {}

    /** B(L)=10*ceil((116.91249+1.259*L)/10), 분석 범위 0<L<=100m에 한정한다. */
    public static long seconds(KakaoWalkingResponse.Route route) {
        if (route == null || route.legs() == null) return 0;
        long allowance = 0;
        for (var leg : route.legs()) {
            if (leg == null || leg.steps() == null) continue;
            for (var step : leg.steps()) {
                if (step == null || step.properties() == null) continue;
                var p = step.properties();
                String text = p.guidance();
                // 실제 guidance '횡단보도 이용'을 정책 적용 대상으로 삼는다. 명시된 무신호 안내는 제외한다.
                if (text == null
                        || !text.contains("횡단보도")
                        || text.contains("무신호")
                        || text.contains("신호등 없는")
                        || text.contains("신호등이 없는")
                        || text.contains("신호 없는")
                        || p.distance() == null
                        || p.distance() <= 0
                        || p.distance() > 100) continue;
                allowance += 10L * (long) Math.ceil((116.91249 + 1.259 * p.distance()) / 10.0);
            }
        }
        return allowance;
    }
}
