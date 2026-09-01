package com.ssafy.ddubi.domain.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.client.dto.KakaoPublicTransitResponse;
import com.ssafy.ddubi.domain.route.model.RouteCandidate;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoRouteMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsActualResponseShapeIntoCandidatesAndSeparatedSegments() throws Exception {
        // Resolver가 WALK를 제외한 대중교통 구간에서만 호출되는지도 함께 센다.
        AtomicInteger resolverCalls = new AtomicInteger();
        KakaoRouteMapper mapper = new KakaoRouteMapper(request -> {
            resolverCalls.incrementAndGet();
            return Optional.of("TEST:" + request.mode() + ":" + request.fromName() + ":" + request.toName());
        });
        Instant fetchedAt = Instant.parse("2026-08-31T00:00:00Z");

        // 사용자가 받은 실제 JSON 구조에서 뽑은 fixture를 우리 모델로 변환한다.
        RouteSearchResult result = mapper.map(readFixture(), fetchedAt);

        assertThat(result.provider()).isEqualTo("KAKAO");
        assertThat(result.fetchedAt()).isEqualTo(fetchedAt);
        assertThat(result.candidates()).hasSize(2);

        RouteCandidate bus = result.candidates().get(0);
        assertThat(bus.providerCandidateIndex()).isZero();
        assertThat(bus.fare().minimumWon()).isEqualTo(1550);
        assertThat(bus.fare().maximumWon()).isEqualTo(1650);
        assertThat(bus.totalDurationSeconds()).isEqualTo(1581);
        assertThat(bus.unassignedDurationSeconds()).isEqualTo(387);

        RouteCandidate mixed = result.candidates().get(1);
        assertThat(mixed.fare().minimumWon()).isEqualTo(1650);
        assertThat(mixed.fare().maximumWon()).isEqualTo(1650);
        assertThat(mixed.segments()).extracting(segment -> segment.mode())
                .containsExactly(TransportMode.SUBWAY, TransportMode.WALK, TransportMode.BUS);
        assertThat(mixed.segments().get(0).providerRouteKey()).isEqualTo("경강선");
        assertThat(mixed.segments().get(1).segmentId()).isNull();
        assertThat(mixed.segments().get(1).vehicles()).isEmpty();
        assertThat(mixed.segments().get(2).geometry()).hasSize(2);
        assertThat(mixed.unassignedDurationSeconds()).isEqualTo(637);
        assertThat(mixed.unassignedDistanceMeters()).isEqualTo(616);
        assertThat(resolverCalls).hasValue(4);
    }

    @Test
    void mapsNoResultsAndEmptyOkResponseToRouteNotAvailable() {
        // 카카오가 명시적으로 경로 없음이라고 주거나 OK인데 배열이 비어도 같은 업무 오류를 사용한다.
        KakaoRouteMapper mapper = new KakaoRouteMapper(request -> Optional.empty());

        assertThatThrownBy(() -> mapper.map(
                new KakaoPublicTransitResponse("NO_RESULTS", null, null), Instant.now()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.ROUTE_NOT_AVAILABLE));

        assertThatThrownBy(() -> mapper.map(
                new KakaoPublicTransitResponse("OK", null, List.of()), Instant.now()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.ROUTE_NOT_AVAILABLE));
    }

    private KakaoPublicTransitResponse readFixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/fixtures/kakao-public-transit-response.json")) {
            return objectMapper.readValue(input, KakaoPublicTransitResponse.class);
        }
    }
}
