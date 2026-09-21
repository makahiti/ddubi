package com.ssafy.ddubi.domain.route.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.client.dto.KakaoWalkingResponse;
import org.junit.jupiter.api.Test;

/** 공식 응답 구조의 횡단보도 안내와 과거 38m 손계산 사례를 검증한다. */
class WalkingSignalAllowanceTest {
    private KakaoWalkingResponse.Route route(String guidance, long distance) throws Exception {
        return new ObjectMapper()
                .readValue(
                        """
                {"properties":{"totalTime":452,"totalDistance":600},
                 "legs":[{"steps":[{"properties":{"guidance":"%s","distance":%d}}]}]}
                """
                                .formatted(guidance, distance),
                        KakaoWalkingResponse.Route.class);
    }

    @Test
    void documented38MeterExampleAdds170Seconds() throws Exception {
        assertThat(WalkingSignalAllowance.seconds(route("횡단보도 이용", 38))).isEqualTo(170);
    }

    @Test
    void unverifiedSignalAndOutOfRangeLengthDoNotInventDelay() throws Exception {
        for (String text : java.util.List.of("직진", "무신호 횡단보도", "신호등이 없는 횡단보도"))
            assertThat(WalkingSignalAllowance.seconds(route(text, 38))).isZero();
        assertThat(WalkingSignalAllowance.seconds(route("신호 횡단보도", 0))).isZero();
        assertThat(WalkingSignalAllowance.seconds(route("신호 횡단보도", 101))).isZero();
    }
}
