package com.ssafy.ddubi.domain.route.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.ddubi.domain.route.model.TransportMode;
import org.junit.jupiter.api.Test;

class CanonicalSegmentIdFactoryTest {

    private final CanonicalSegmentIdFactory factory = new CanonicalSegmentIdFactory();

    @Test
    void sameNumericIdsInDifferentSourcesNeverCollideAndSeoulStaysCompatible() {
        assertThat(factory.createBus("SEOUL_BUS", "1", "10", "11")).isEqualTo("BUS:1:10:11");
        assertThat(factory.createBus("GBIS", "1", "10", "11"))
                .isEqualTo("BUS:GBIS.1:GBIS.10:GBIS.11")
                .isNotEqualTo(factory.createBus("INCHEON_PUBLIC_CSV", "1", "10", "11"));
        assertThat(factory.createBus("BUSAN_GTFS", "R1", "S1", "S2"))
                .isEqualTo("BUS:BUSAN_GTFS.R1:BUSAN_GTFS.S1:BUSAN_GTFS.S2");
        assertThatThrownBy(() -> factory.createBus("GBIS", "1".repeat(50), "10", "11"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 배포 대응표 모든 edge의 Java/Python ID 동일성을 검사한다. 선행 0과 출처별 숫자 충돌을 포함한다. */
    @Test
    void generatedCrosswalkMatchesJavaForEveryEdge() throws Exception {
        var file =
                java.nio.file.Path.of("transit-master/bus/metropolitan-bus-v6.crosswalk.json.gz");
        try (var stream =
                new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(file))) {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(stream);
            int count = 0;
            for (var row : root.path("edges")) {
                assertThat(
                                factory.createBus(
                                        row.path("namespace").asText(),
                                        row.path("routeId").asText(),
                                        row.path("fromStopId").asText(),
                                        row.path("toStopId").asText()))
                        .isEqualTo(row.path("segmentId").asText());
                count++;
            }
            assertThat(count).isEqualTo(root.path("edgeCount").asInt()).isGreaterThan(100000);
        }
    }

    @Test
    void encodesOnlyOfficialTransitAdjacencyWithoutNames() {
        assertThat(factory.create(TransportMode.BUS, "100100609", "123456", "123789"))
                .isEqualTo("BUS:100100609:123456:123789");
        assertThat(factory.create(TransportMode.SUBWAY, "1002", "1002000222", "1002000223"))
                .isEqualTo("SUB:1002:1002000222:1002000223");
        assertThatThrownBy(() -> factory.create(TransportMode.WALK, "W", "A", "B"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsComponentsThatWouldMakeAmbiguousOrUnsafeKeys() {
        assertThatThrownBy(() -> factory.create(TransportMode.BUS, "", "A", "B"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create(TransportMode.BUS, "R:1", "A", "B"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create(TransportMode.BUS, " R1", "A", "B"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create(TransportMode.BUS, "R1\n", "A", "B"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create(TransportMode.BUS, "R".repeat(51), "A", "B"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesDirectionInTheCanonicalKey() {
        assertThat(factory.create(TransportMode.BUS, "R1", "A", "B"))
                .isNotEqualTo(factory.create(TransportMode.BUS, "R1", "B", "A"));
    }
}
