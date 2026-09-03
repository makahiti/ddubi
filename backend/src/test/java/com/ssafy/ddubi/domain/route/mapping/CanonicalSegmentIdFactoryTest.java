package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.model.TransportMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalSegmentIdFactoryTest {

    private final CanonicalSegmentIdFactory factory = new CanonicalSegmentIdFactory();

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
        assertThatThrownBy(() -> factory.create(
                TransportMode.BUS, "R".repeat(51), "A", "B"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesDirectionInTheCanonicalKey() {
        assertThat(factory.create(TransportMode.BUS, "R1", "A", "B"))
                .isNotEqualTo(factory.create(TransportMode.BUS, "R1", "B", "A"));
    }
}
