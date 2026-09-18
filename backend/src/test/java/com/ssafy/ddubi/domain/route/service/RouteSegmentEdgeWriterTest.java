package com.ssafy.ddubi.domain.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static com.ssafy.ddubi.domain.route.mapping.MetropolitanBusFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 쓰기 전 경로·재매핑 검증과 선택 대안의 전체 edge 보존을 확인한다. 실제 rollback은 MySQL 테스트에 둔다. */
class RouteSegmentEdgeWriterTest {
    final ObjectMapper json=new ObjectMapper();
    MasterDataTransitMappingResolver resolver() {
        var provider=mock(SeoulTransitMasterDataProvider.class);when(provider.activeVersion()).thenReturn(Optional.empty());
        return new MasterDataTransitMappingResolver(provider,null,new ProviderSegmentSignatureFactory(),new CanonicalSegmentIdFactory(),
                new MetropolitanBusCatalog(master(List.of(regular()))),null,Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"),ZoneId.of("Asia/Seoul")));
    }
    RouteSegment segment(MasterDataTransitMappingResolver resolver) {
        var names=List.of("출발","중간","도착");var vehicles=List.of(new VehicleInfo("100","BUS"));
        var mapping=resolver.resolve(new SegmentResolveRequest(TransportMode.BUS,"100","출발","도착",names,vehicles,geometry()));
        return new RouteSegment(1,TransportMode.BUS,null,"100",null,"출발","도착",400,100,names,vehicles,geometry(),mapping);
    }
    @Test void writesEveryAdjacentEdgeAndKeepsMultiEdgeParentIdEmpty() throws Exception {
        var resolver=resolver();var segment=segment(resolver);var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(20L))).thenReturn(List.of(Map.of("route_id",10L,"seq",1,"mode","BUS", "provider_route_key","100","from_name","출발","to_name","도착","geometry",json.writeValueAsString(geometry()))));
        var writer=new RouteSegmentEdgeWriter(jdbc,resolver,json);
        assertThat(writer.replace(10,20,segment,0,0)).containsExactly("BUS:R1:A:B","BUS:R1:B:C");
        verify(jdbc).batchUpdate(contains("INSERT INTO route_segment_edge"),org.mockito.ArgumentMatchers.<List<Object[]>>argThat(rows->rows.size()==2
                && rows.get(0)[2].equals("BUS:R1:A:B") && rows.get(1)[2].equals("BUS:R1:B:C")));
        verify(jdbc).update("UPDATE route_segment SET segment_id=? WHERE id=?",null,20L);
    }
    @Test void wrongParentNeverDeletesPreviousEdges() throws Exception {
        var resolver=resolver();var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(20L))).thenReturn(List.of(Map.of("route_id",99L,"seq",1,"mode","BUS","from_name","출발","to_name","도착")));
        assertThatThrownBy(()->new RouteSegmentEdgeWriter(jdbc,resolver,json).replace(10,20,segment(resolver),0,0)).isInstanceOf(IllegalArgumentException.class);
        verify(jdbc,never()).update(contains("DELETE"),any(Object[].class));
    }
}
