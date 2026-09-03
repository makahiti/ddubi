package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.domain.route.model.VehicleInfo;
import com.ssafy.ddubi.domain.route.model.Coordinate;
import com.ssafy.ddubi.domain.route.service.SegmentResolveRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderSegmentSignatureFactoryTest {

    private final ProviderSegmentSignatureFactory factory = new ProviderSegmentSignatureFactory();
    private final TransitNameNormalizer normalizer = new TransitNameNormalizer();

    @Test
    void producesSameSignatureForEquivalentDecorationsButNotReversedPath() {
        SegmentResolveRequest decorated = request("3412번", List.of("잠실역(승차)", "삼성역", "강남역"));
        SegmentResolveRequest normalized = request("3412", List.of("잠실역", "삼성역", "강남역"));
        SegmentResolveRequest reversed = request("3412", List.of("강남역", "삼성역", "잠실역"));

        assertThat(factory.create(decorated, normalizer))
                .isEqualTo(factory.create(normalized, normalizer));
        assertThat(factory.create(normalized, normalizer))
                .isNotEqualTo(factory.create(reversed, normalizer));
        assertThat(factory.create(normalized, normalizer)).hasSize(64);
    }

    @Test
    void includesBusEndpointGeometryThatChangesExactJudgement() {
        SegmentResolveRequest near = requestWithGeometry(List.of(
                new Coordinate(127.0, 37.0), new Coordinate(127.01, 37.01)));
        SegmentResolveRequest reversed = requestWithGeometry(List.of(
                new Coordinate(127.01, 37.01), new Coordinate(127.0, 37.0)));
        SegmentResolveRequest far = requestWithGeometry(List.of(
                new Coordinate(128.0, 38.0), new Coordinate(128.01, 38.01)));

        assertThat(factory.create(near, normalizer))
                .isNotEqualTo(factory.create(reversed, normalizer));
        assertThat(factory.create(near, normalizer))
                .isNotEqualTo(factory.create(far, normalizer));
    }

    private SegmentResolveRequest request(String route, List<String> stops) {
        return new SegmentResolveRequest(
                TransportMode.BUS, route, stops.get(0), stops.get(stops.size() - 1), stops,
                List.of(new VehicleInfo(route, "BUS")), List.of());
    }

    private SegmentResolveRequest requestWithGeometry(List<Coordinate> geometry) {
        return new SegmentResolveRequest(
                TransportMode.BUS, "100", "출발", "도착", List.of("출발", "도착"),
                List.of(new VehicleInfo("100", "BUS")), geometry);
    }
}
