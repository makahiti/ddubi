package com.ssafy.ddubi.domain.route.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.evaluation.JourneyEvidence;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteEvidenceAssembler;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator;
import com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService;
import com.ssafy.ddubi.domain.route.client.WalkingRouteClient;
import com.ssafy.ddubi.domain.route.client.dto.KakaoPublicTransitResponse;
import com.ssafy.ddubi.domain.route.dto.RouteSearchResponse;
import com.ssafy.ddubi.domain.route.mapping.TransitMappingResult;
import com.ssafy.ddubi.domain.route.model.Coordinate;
import com.ssafy.ddubi.domain.route.model.RouteCandidate;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class KakaoRouteMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsActualResponseShapeIntoCandidatesAndSeparatedSegments() throws Exception {
        // Resolver가 WALK를 제외한 대중교통 구간에서만 호출되는지도 함께 센다.
        KakaoRouteMapper mapper =
                new KakaoRouteMapper(request -> TransitMappingResult.masterDataNotConfigured());
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
        assertThat(mixed.segments())
                .extracting(segment -> segment.mode())
                .containsExactly(TransportMode.SUBWAY, TransportMode.WALK, TransportMode.BUS);
        assertThat(mixed.segments().get(0).providerRouteKey()).isEqualTo("경강선");
        assertThat(mixed.segments().get(1).segmentId()).isNull();
        assertThat(mixed.segments().get(1).mapping().status().name()).isEqualTo("NOT_APPLICABLE");
        assertThat(mixed.segments().get(0).mapping().failureReason())
                .isEqualTo("MASTER_DATA_NOT_CONFIGURED");
        assertThat(mixed.segments().get(1).vehicles()).isEmpty();
        assertThat(mixed.segments().get(2).geometry()).hasSize(2);
        assertThat(mixed.unassignedDurationSeconds()).isEqualTo(637);
        assertThat(mixed.unassignedDistanceMeters()).isEqualTo(616);

        // 아직 마스터 공급원이 없는 fixture에서는 대중교통 2개가 모두 UNMAPPED로 집계돼야 한다.
        RouteSearchResponse response = RouteSearchResponse.from(result);
        assertThat(response.candidates().get(1).mappingSummary().transitStepCount()).isEqualTo(2);
        assertThat(response.candidates().get(1).mappingSummary().unmappedCount()).isEqualTo(2);
        assertThat(response.candidates().get(1).mappingSummary().exactCount()).isZero();
    }

    @Test
    void unresolvedEndpointWalkAndWaitNeverBecomeShorterThanKakaoTotal() throws Exception {
        Instant fetchedAt = Instant.parse("2026-08-31T00:00:00Z");
        var mapped =
                new KakaoRouteMapper(request -> TransitMappingResult.masterDataNotConfigured())
                        .map(readFixture(), fetchedAt);
        var walking =
                new WalkingEnrichmentService(
                        request ->
                                new WalkingRouteClient.Evidence(
                                        null, null, "KAKAO_WALKING_API", "WALK_TIMEOUT"),
                        objectMapper,
                        true,
                        0,
                        "",
                        true);
        var prepared =
                walking.prepare(
                        mapped.candidates(),
                        new Coordinate(127.10, 37.39),
                        new Coordinate(127.13, 37.42),
                        fetchedAt);
        var assembler = new RouteEvidenceAssembler(null, objectMapper);
        var evaluator = new RouteTimelineEvaluator();

        for (int index = 0; index < mapped.candidates().size(); index++) {
            RouteCandidate provider = mapped.candidates().get(index);
            RouteCandidate safe = prepared.candidates().get(index);
            JourneyEvidence.Journey journey =
                    assembler.assemble(
                            safe,
                            new RouteEvidenceAssembler.Snapshot(java.util.Map.of(), List.of()),
                            fetchedAt);
            var result =
                    evaluator.evaluate(
                            journey, fetchedAt, fetchedAt, fetchedAt.plusSeconds(3600), List.of());

            // 실제 카카오 fixture의 미분해 387초/637초를 버리지 않는다. 도보 API가 실패해도
            // 계산 결과는 각각 1581초/1781초로 provider total과 정확히 일치한다.
            assertThat(safe.totalDurationSeconds()).isEqualTo(provider.totalDurationSeconds());
            assertThat(result.estimatedArrivalAt())
                    .isEqualTo(fetchedAt.plusSeconds(provider.totalDurationSeconds()));
            assertThat(result.steps())
                    .extracting(RouteTimelineEvaluator.Step::key)
                    .containsExactly("provider-total");
        }
    }

    @Test
    void mapsNoResultsAndEmptyOkResponseToRouteNotAvailable() {
        // 카카오가 명시적으로 경로 없음이라고 주거나 OK인데 배열이 비어도 같은 업무 오류를 사용한다.
        KakaoRouteMapper mapper =
                new KakaoRouteMapper(request -> TransitMappingResult.masterDataNotConfigured());

        assertThatThrownBy(
                        () ->
                                mapper.map(
                                        new KakaoPublicTransitResponse("NO_RESULTS", null, null),
                                        Instant.now()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.errorCode())
                                        .isEqualTo(ErrorCode.ROUTE_NOT_AVAILABLE));

        assertThatThrownBy(
                        () ->
                                mapper.map(
                                        new KakaoPublicTransitResponse("OK", null, List.of()),
                                        Instant.now()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.errorCode())
                                        .isEqualTo(ErrorCode.ROUTE_NOT_AVAILABLE));
    }

    private KakaoPublicTransitResponse readFixture() throws Exception {
        try (InputStream input =
                getClass().getResourceAsStream("/fixtures/kakao-public-transit-response.json")) {
            return objectMapper.readValue(input, KakaoPublicTransitResponse.class);
        }
    }
}
