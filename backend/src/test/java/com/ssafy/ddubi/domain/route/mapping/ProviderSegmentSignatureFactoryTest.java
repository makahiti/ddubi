package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.model.*;
import com.ssafy.ddubi.domain.route.service.SegmentResolveRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 공용 캐시의 지하철 서명만 검증한다. BUS 전체 좌표·검토일 서명은 Resolver 테스트에서 검증한다. */
class ProviderSegmentSignatureFactoryTest {
    final ProviderSegmentSignatureFactory factory=new ProviderSegmentSignatureFactory();
    final TransitNameNormalizer normalizer=new TransitNameNormalizer();
    SegmentResolveRequest request(String line,List<String> stops) {
        return new SegmentResolveRequest(TransportMode.SUBWAY,line,stops.get(0),stops.get(stops.size()-1),stops,
                List.of(new VehicleInfo(line,"SUBWAY")),List.of());
    }
    @Test void equivalentDecorationsShareSignatureButReverseOrDifferentInteriorDoesNot() {
        var a=request("수도권 02호선",List.of("강남역","역삼역","선릉역"));
        var b=request("2호선",List.of("강남","역삼","선릉"));
        var reverse=request("2호선",List.of("선릉","역삼","강남"));
        var other=request("2호선",List.of("강남","다른역","선릉"));
        assertThat(factory.create(a,normalizer)).isEqualTo(factory.create(b,normalizer)).hasSize(64)
                .isNotEqualTo(factory.create(reverse,normalizer)).isNotEqualTo(factory.create(other,normalizer));
    }
    @Test void busCannotAccidentallyUseSubwayNameOnlyCache() {
        var bus=new SegmentResolveRequest(TransportMode.BUS,"100","출발","도착",List.of("출발","도착"),
                List.of(new VehicleInfo("100","BUS")),List.of());
        assertThatThrownBy(()->factory.create(bus,normalizer)).isInstanceOf(IllegalArgumentException.class);
    }
}
