package com.ssafy.ddubi.domain.route.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubwaySegmentMatcherTest {

    @Test
    void keepsStaticCanonicalIdsStableAndCarriesRealtimeCrosswalkSeparately() {
        SubwaySegmentMatcher matcher = new SubwaySegmentMatcher(List.of(new SubwayLineMaster(
                "2", "1002", "2호선", "INNER", "MAIN", List.of(
                new SubwayStationMaster("0222", "1002000222", "강남", 1),
                new SubwayStationMaster("0223", "1002000223", "역삼", 2)))));

        SubwayMappingResult result = matcher.match(request(
                "2호선", "강남역", "역삼역", "강남역", "역삼역"));

        assertThat(result.status()).isEqualTo(SubwayMappingStatus.EXACT);
        assertThat(result.officialSubwayId()).isEqualTo("2");
        assertThat(result.fromStationId()).isEqualTo("0222");
        assertThat(result.segments().get(0).realtimeSubwayId()).isEqualTo("1002");
        assertThat(result.segments().get(0).fromRealtimeStationId()).isEqualTo("1002000222");
    }

    @Test
    void mapsUniqueDirectionAndExpandsEveryAdjacentStationEdge() {
        SubwaySegmentMatcher matcher = new SubwaySegmentMatcher(List.of(
                line("2", "2호선", "INNER",
                        station("222", "강남", 1),
                        station("220", "선릉", 2),
                        station("216", "잠실", 3)),
                line("2", "2호선", "OUTER",
                        station("216", "잠실", 1),
                        station("220", "선릉", 2),
                        station("222", "강남", 3))));

        SubwayMappingResult result = matcher.match(new SubwayMappingRequest(
                List.of("수도권 02호선"), "강남역", "잠실역",
                List.of("강남역", "선릉역", "잠실역")));

        assertThat(result.status()).isEqualTo(SubwayMappingStatus.EXACT);
        assertThat(result.direction()).isEqualTo("INNER");
        assertThat(result.segments()).extracting(MappedSubwaySegment::fromStationId)
                .containsExactly("222", "220");
        assertThat(result.segments()).extracting(MappedSubwaySegment::toStationId)
                .containsExactly("220", "216");
    }

    @Test
    void neverSelectsOneDirectionWhenTopologyPathsRemainTied() {
        SubwaySegmentMatcher matcher = new SubwaySegmentMatcher(List.of(
                line("2", "2호선", "INNER",
                        station("I1", "출발", 1), station("I2", "도착", 2)),
                line("2", "2호선", "OUTER",
                        station("O1", "출발", 1), station("O2", "도착", 2))));

        SubwayMappingResult result = matcher.match(
                request("2호선", "출발", "도착", "출발", "도착"));

        assertThat(result.status()).isEqualTo(SubwayMappingStatus.AMBIGUOUS);
        assertThat(result.failureReason()).isEqualTo("MULTIPLE_TOPOLOGY_PATHS");
        assertThat(result.officialSubwayId()).isNull();
    }

    @Test
    void treatsIdenticalSubpathsOfDifferentServicePatternsAsOneCandidate() {
        SubwaySegmentMatcher matcher = new SubwaySegmentMatcher(List.of(
                pattern("5", "5호선", "DOWN", "MAIN",
                        station("S1", "출발", 1), station("S2", "중간", 2),
                        station("S3", "도착", 3)),
                pattern("5", "5호선", "DOWN", "SHORT",
                        station("S1", "출발", 1), station("S2", "중간", 2),
                        station("S3", "도착", 3), station("S4", "회차", 4))));

        SubwayMappingResult result = matcher.match(
                request("5호선", "출발", "도착", "출발", "중간", "도착"));

        assertThat(result.status()).isEqualTo(SubwayMappingStatus.EXACT);
        assertThat(result.segments()).hasSize(2);
    }

    @Test
    void keepsBranchPatternsAmbiguousWhenIntermediatePathIsUnknown() {
        SubwaySegmentMatcher matcher = new SubwaySegmentMatcher(List.of(
                pattern("5", "5호선", "DOWN", "A",
                        station("S1", "출발", 1), station("A2", "분기A", 2),
                        station("S3", "도착", 3)),
                pattern("5", "5호선", "DOWN", "B",
                        station("S1", "출발", 1), station("B2", "분기B", 2),
                        station("S3", "도착", 3))));

        SubwayMappingResult result = matcher.match(
                request("5호선", "출발", "도착", "출발", "도착"));

        assertThat(result.status()).isEqualTo(SubwayMappingStatus.AMBIGUOUS);
        assertThat(result.segments()).isEmpty();
    }

    @Test
    void usesAliasCanonicalIdAndNeverFallsBackToAConflictingOfficialName() {
        SubwaySegmentMatcher matcher = new SubwaySegmentMatcher(
                List.of(
                        line("A", "경의중앙선", "UP",
                                station("A1", "서울", 1), station("A2", "도착", 2)),
                        line("B", "경의중앙", "UP",
                                station("B1", "서울역", 1), station("B2", "도착", 2))),
                new TransitNameNormalizer(),
                Map.of("경의중앙선", Set.of("B")),
                Map.of("서울", Set.of("B1")));

        SubwayMappingResult result = matcher.match(
                request("경의중앙선", "서울", "도착", "서울", "도착"));

        assertThat(result.status()).isEqualTo(SubwayMappingStatus.EXACT);
        assertThat(result.officialSubwayId()).isEqualTo("B");
        assertThat(result.fromStationId()).isEqualTo("B1");
    }

    @Test
    void scopesMultiTargetStationAliasToTheSelectedLineTopology() {
        SubwaySegmentMatcher matcher = new SubwaySegmentMatcher(
                List.of(
                        line("A", "A선", "UP",
                                station("A1", "공식A", 1), station("A2", "도착A", 2)),
                        line("B", "B선", "UP",
                                station("B1", "공식B", 1), station("B2", "도착B", 2))),
                new TransitNameNormalizer(), Map.of(),
                Map.of("카카오환승역", Set.of("A1", "B1")));

        SubwayMappingResult lineA = matcher.match(
                request("A선", "카카오환승역", "도착A", "카카오환승역", "도착A"));
        SubwayMappingResult lineB = matcher.match(
                request("B선", "카카오환승역", "도착B", "카카오환승역", "도착B"));

        assertThat(lineA.status()).isEqualTo(SubwayMappingStatus.EXACT);
        assertThat(lineA.fromStationId()).isEqualTo("A1");
        assertThat(lineB.status()).isEqualTo(SubwayMappingStatus.EXACT);
        assertThat(lineB.fromStationId()).isEqualTo("B1");
    }

    @Test
    void doesNotCollapseDifferentStaticLineIdsWithSameNameAndPath() {
        SubwaySegmentMatcher matcher = new SubwaySegmentMatcher(List.of(
                line("A", "공통선", "UP",
                        station("S1", "출발", 1), station("S2", "도착", 2)),
                line("B", "공통선", "UP",
                        station("S1", "출발", 1), station("S2", "도착", 2))));

        SubwayMappingResult result = matcher.match(
                request("공통선", "출발", "도착", "출발", "도착"));

        assertThat(result.status()).isEqualTo(SubwayMappingStatus.AMBIGUOUS);
        assertThat(result.failureReason()).isEqualTo("LINE_NAME_AMBIGUOUS");
    }

    @Test
    void returnsPartialWhenStationOrderOrPassingEvidenceConflicts() {
        SubwaySegmentMatcher matcher = new SubwaySegmentMatcher(List.of(line(
                "2", "2호선", "UP",
                station("S1", "출발", 1), station("S2", "중간A", 2),
                station("S3", "중간B", 3), station("S4", "도착", 4))));

        SubwayMappingResult wrongPassing = matcher.match(request(
                "2호선", "출발", "도착", "출발", "중간B", "중간A", "도착"));
        SubwayMappingResult reverse = matcher.match(
                request("2호선", "도착", "출발", "도착", "출발"));

        assertThat(wrongPassing.status()).isEqualTo(SubwayMappingStatus.PARTIAL);
        assertThat(wrongPassing.failureReason()).isEqualTo("PASSING_STATIONS_NOT_CONFIRMED");
        assertThat(reverse.status()).isEqualTo(SubwayMappingStatus.PARTIAL);
        assertThat(reverse.failureReason()).isEqualTo("STATION_OR_FORWARD_ORDER_NOT_FOUND");
    }

    private SubwayMappingRequest request(
            String line,
            String from,
            String to,
            String... stations
    ) {
        return new SubwayMappingRequest(List.of(line), from, to, List.of(stations));
    }

    private SubwayStationMaster station(String id, String name, int sequence) {
        return new SubwayStationMaster(id, name, sequence);
    }

    private SubwayLineMaster line(
            String id,
            String name,
            String direction,
            SubwayStationMaster... stations
    ) {
        return new SubwayLineMaster(id, name, direction, List.of(stations));
    }

    private SubwayLineMaster pattern(
            String id,
            String name,
            String direction,
            String pattern,
            SubwayStationMaster... stations
    ) {
        return new SubwayLineMaster(id, name, direction, pattern, List.of(stations));
    }
}
