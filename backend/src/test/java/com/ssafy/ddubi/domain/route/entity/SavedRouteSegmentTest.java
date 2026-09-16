package com.ssafy.ddubi.domain.route.entity;

import com.ssafy.ddubi.domain.route.model.TransportMode;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SavedRouteSegmentTest {
    @Test void preservesAllAlternativeVehicleNamesBeyondOldLimit() throws Exception {
        String labels = "서울버스360,서울버스361,".repeat(30);
        var segment = SavedRouteSegment.builder().seq(1).mode(TransportMode.BUS)
                .providerRouteKey(labels).fromName("A").toName("B").baseDurationMin(5).build();
        assertThat(segment.getProviderRouteKey()).isEqualTo(labels).hasSizeGreaterThan(100);
        assertThat(SavedRouteSegment.class.getDeclaredField("providerRouteKey").getAnnotation(Column.class).columnDefinition()).isEqualTo("TEXT");
    }
}
