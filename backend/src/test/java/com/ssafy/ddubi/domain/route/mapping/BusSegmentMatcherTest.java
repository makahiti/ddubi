package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.model.Coordinate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BusSegmentMatcherTest {

    @Test
    void mapsOnlyUniqueRouteWithVerifiedEndpointsAndExpandsAdjacentEdges() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(route(
                "R-422", "422",
                stop("S17", "성남역", 17, 127.1180, 37.3940),
                stop("S18", "중간A", 18, 127.1200, 37.3960),
                stop("S19", "중간B", 19, 127.1220, 37.3980),
                stop("S20", "성남시청", 20, 127.1240, 37.4000))));

        BusMappingResult result = matcher.match(request(
                List.of("422번"), "성남역", "성남시청",
                List.of("성남역", "중간A", "중간B", "성남시청"),
                path(127.1180, 37.3940, 127.1240, 37.4000)));

        assertThat(result.status()).isEqualTo(BusMappingStatus.EXACT);
        assertThat(result.direction()).isEqualTo("SEQUENCE_FORWARD");
        assertThat(result.segments()).extracting(MappedBusSegment::fromStopId)
                .containsExactly("S17", "S18", "S19");
        assertThat(result.segments()).extracting(MappedBusSegment::toStopId)
                .containsExactly("S18", "S19", "S20");
    }

    @Test
    void doesNotCallNameAndOrderOnlyEvidenceExactWhenGeometryIsMissing() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(route(
                "R1", "100",
                stop("A", "출발", 1, 127.0, 37.0),
                stop("B", "도착", 2, 127.01, 37.01))));

        BusMappingResult result = matcher.match(request(
                List.of("100"), "출발", "도착", List.of("출발", "도착"), List.of()));

        assertThat(result.status()).isEqualTo(BusMappingStatus.PARTIAL);
        assertThat(result.failureReason()).isEqualTo("GEOMETRY_NOT_AVAILABLE");
        assertThat(result.segments()).isEmpty();
    }

    @Test
    void rejectsFarParallelGeometryThatOnlyHasTheSameDirection() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(route(
                "R1", "100",
                stop("A", "출발", 1, 127.0, 37.0),
                stop("B", "도착", 2, 127.01, 37.01))));

        BusMappingResult result = matcher.match(request(
                List.of("100"), "출발", "도착", List.of("출발", "도착"),
                path(128.0, 38.0, 128.01, 38.01)));

        assertThat(result.status()).isEqualTo(BusMappingStatus.PARTIAL);
        assertThat(result.failureReason()).isEqualTo("GEOMETRY_ENDPOINT_TOO_FAR");
    }

    @Test
    void rejectsReversedGeometryInsteadOfUsingRelativeDirectionOnly() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(route(
                "R1", "100",
                stop("A", "출발", 1, 127.0, 37.0),
                stop("B", "도착", 2, 127.01, 37.01))));

        BusMappingResult result = matcher.match(request(
                List.of("100"), "출발", "도착", List.of("출발", "도착"),
                path(127.01, 37.01, 127.0, 37.0)));

        assertThat(result.status()).isEqualTo(BusMappingStatus.PARTIAL);
        assertThat(result.failureReason()).isEqualTo("GEOMETRY_ENDPOINT_TOO_FAR");
    }

    @Test
    void rejectsReversedGeometryWhenBothCloseStopsAreInsideEndpointRadius() {
        // 약 80m 떨어진 정류장은 역방향 양 끝도 150m 반경 안이므로 거리 gate만으로는 부족하다.
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(route(
                "R1", "100",
                stop("A", "출발", 1, 127.0, 37.0),
                stop("B", "도착", 2, 127.0009, 37.0))));

        BusMappingResult result = matcher.match(request(
                List.of("100"), "출발", "도착", List.of("출발", "도착"),
                path(127.0009, 37.0, 127.0, 37.0)));

        assertThat(result.status()).isEqualTo(BusMappingStatus.PARTIAL);
        assertThat(result.failureReason()).isEqualTo("GEOMETRY_DIRECTION_NOT_CONFIRMED");
    }

    @Test
    void rejectsZeroLengthGeometryThatCannotProveDirection() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(route(
                "R1", "100",
                stop("A", "출발", 1, 127.0, 37.0),
                stop("B", "도착", 2, 127.0009, 37.0))));

        BusMappingResult result = matcher.match(request(
                List.of("100"), "출발", "도착", List.of("출발", "도착"),
                path(127.00045, 37.0, 127.00045, 37.0)));

        assertThat(result.status()).isEqualTo(BusMappingStatus.PARTIAL);
        assertThat(result.failureReason()).isEqualTo("GEOMETRY_DIRECTION_NOT_CONFIRMED");
    }

    @Test
    void keepsMultipleProviderVehiclesAmbiguousRatherThanSelectingOne() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(
                route("R51", "51", stop("A", "출발", 1, 127.0, 37.0),
                        stop("B", "도착", 2, 127.01, 37.01)),
                route("R119", "119", stop("C", "출발", 1, 127.0, 37.0),
                        stop("D", "도착", 2, 127.01, 37.01))));

        BusMappingResult result = matcher.match(request(
                List.of("51", "119"), "출발", "도착", List.of("출발", "도착"),
                path(127.0, 37.0, 127.01, 37.01)));

        assertThat(result.status()).isEqualTo(BusMappingStatus.AMBIGUOUS);
        assertThat(result.failureReason()).isEqualTo("MULTIPLE_PROVIDER_VEHICLES");
        assertThat(result.officialRouteId()).isNull();
    }

    @Test
    void reportsIncompleteMasterCoverageForProviderVehicleAlternatives() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(route(
                "R51", "51", stop("A", "출발", 1, 127.0, 37.0),
                stop("B", "도착", 2, 127.01, 37.01))));

        BusMappingResult result = matcher.match(request(
                List.of("51", "119"), "출발", "도착", List.of("출발", "도착"),
                path(127.0, 37.0, 127.01, 37.01)));

        assertThat(result.status()).isEqualTo(BusMappingStatus.PARTIAL);
        assertThat(result.failureReason()).isEqualTo("PROVIDER_VEHICLE_COVERAGE_INCOMPLETE");
    }

    @Test
    void resolvesVerifiedAliasesByCanonicalIdWithoutRewritingOfficialNames() {
        TransitNameNormalizer normalizer = new TransitNameNormalizer();
        BusSegmentMatcher matcher = new BusSegmentMatcher(
                List.of(
                        route("R-PLAIN", "9003", stop("P1", "서울역", 1, 127.0, 37.0),
                                stop("P2", "강남", 2, 127.01, 37.01)),
                        route("R-SEONGNAM", "9003성남", stop("S1", "서울", 1, 127.0, 37.0),
                                stop("S2", "강남", 2, 127.01, 37.01))),
                normalizer,
                Map.of("9003", Set.of("R-SEONGNAM")),
                Map.of("서울역", Set.of("S1")),
                150.0);

        BusMappingResult result = matcher.match(request(
                List.of("9003"), "서울역", "강남", List.of("서울역", "강남"),
                path(127.0, 37.0, 127.01, 37.01)));

        assertThat(result.status()).isEqualTo(BusMappingStatus.EXACT);
        assertThat(result.officialRouteId()).isEqualTo("R-SEONGNAM");
    }

    @Test
    void doesNotFallBackToNameWhenAliasCanonicalIdIsMissing() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(
                List.of(route("R1", "100", stop("A", "출발", 1, 127.0, 37.0),
                        stop("B", "도착", 2, 127.01, 37.01))),
                new TransitNameNormalizer(), Map.of("100", Set.of("MISSING")), Map.of(), 150.0);

        BusMappingResult result = matcher.match(request(
                List.of("100"), "출발", "도착", List.of("출발", "도착"),
                path(127.0, 37.0, 127.01, 37.01)));

        assertThat(result.status()).isEqualTo(BusMappingStatus.UNMAPPED);
    }

    @Test
    void scopesMultiTargetStopAliasToTheSelectedBusRoute() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(
                List.of(
                        route("R100", "100",
                                stop("A1", "공식A", 1, 127.0, 37.0),
                                stop("A2", "도착A", 2, 127.001, 37.0)),
                        route("R200", "200",
                                stop("B1", "공식B", 1, 127.1, 37.1),
                                stop("B2", "도착B", 2, 127.101, 37.1))),
                new TransitNameNormalizer(), Map.of(),
                Map.of("카카오중앙", Set.of("A1", "B1")), 150.0);

        BusMappingResult route100 = matcher.match(request(
                List.of("100"), "카카오중앙", "도착A", List.of("카카오중앙", "도착A"),
                path(127.0, 37.0, 127.001, 37.0)));
        BusMappingResult route200 = matcher.match(request(
                List.of("200"), "카카오중앙", "도착B", List.of("카카오중앙", "도착B"),
                path(127.1, 37.1, 127.101, 37.1)));

        assertThat(route100.status()).isEqualTo(BusMappingStatus.EXACT);
        assertThat(route100.fromStopId()).isEqualTo("A1");
        assertThat(route200.status()).isEqualTo(BusMappingStatus.EXACT);
        assertThat(route200.fromStopId()).isEqualTo("B1");
    }

    @Test
    void keepsDifferentBranchesOfSameRouteIdAmbiguous() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(
                route("R1", "100", stop("A", "출발", 1, 127.0, 37.0),
                        stop("X", "분기X", 2, 127.005, 37.005),
                        stop("D", "도착", 3, 127.01, 37.01)),
                route("R1", "100", stop("A", "출발", 1, 127.0, 37.0),
                        stop("Y", "분기Y", 2, 127.006, 37.006),
                        stop("D", "도착", 3, 127.01, 37.01))));

        BusMappingResult result = matcher.match(request(
                List.of("100"), "출발", "도착", List.of("출발", "도착"),
                path(127.0, 37.0, 127.01, 37.01)));

        assertThat(result.status()).isEqualTo(BusMappingStatus.AMBIGUOUS);
        assertThat(result.failureReason()).isEqualTo("MULTIPLE_VERIFIED_PATHS");
    }

    @Test
    void rejectsIntermediateStopsInTheWrongOrder() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(route(
                "R1", "100",
                stop("A", "출발", 1, 127.0, 37.0),
                stop("B", "중간A", 2, 127.003, 37.003),
                stop("C", "중간B", 3, 127.006, 37.006),
                stop("D", "도착", 4, 127.01, 37.01))));

        BusMappingResult result = matcher.match(request(
                List.of("100"), "출발", "도착",
                List.of("출발", "중간B", "중간A", "도착"),
                path(127.0, 37.0, 127.01, 37.01)));

        assertThat(result.status()).isEqualTo(BusMappingStatus.PARTIAL);
        assertThat(result.failureReason()).isEqualTo("PASSING_STOPS_NOT_CONFIRMED");
    }

    private BusMappingRequest request(
            List<String> routes,
            String from,
            String to,
            List<String> stops,
            List<Coordinate> geometry
    ) {
        return new BusMappingRequest(routes, from, to, stops, geometry);
    }

    private BusRouteMaster route(String id, String name, BusStopMaster... stops) {
        return new BusRouteMaster(id, name, List.of(stops));
    }

    private BusStopMaster stop(
            String id,
            String name,
            int sequence,
            double longitude,
            double latitude
    ) {
        return new BusStopMaster(id, name, sequence, longitude, latitude);
    }

    private List<Coordinate> path(
            double startLongitude,
            double startLatitude,
            double endLongitude,
            double endLatitude
    ) {
        return List.of(
                new Coordinate(startLongitude, startLatitude),
                new Coordinate(endLongitude, endLatitude));
    }
}
