package com.ssafy.ddubi.domain.route.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.service.MasterDataTransitMappingResolver;
import com.ssafy.ddubi.domain.route.service.SegmentResolveRequest;
import com.ssafy.ddubi.domain.route.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.zip.GZIPOutputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 적재 오류·재적재 장애·기존 지하철 경계·대안 선택 실패를 서비스 경계에서 검증한다. */
class MetropolitanBusServiceTest {
    @TempDir Path temp;
    final MetropolitanBusMatcherTest fixture=new MetropolitanBusMatcherTest();
    byte[] gzip(MetropolitanBusMaster master)throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var out=new GZIPOutputStream(bytes)){new ObjectMapper().writeValue(out,master);}
        return bytes.toByteArray();
    }
    MasterDataTransitMappingResolver resolver(MetropolitanBusCatalog catalog,MetropolitanBusMappingCache cache) {
        var provider=mock(SeoulTransitMasterDataProvider.class);
        when(provider.activeVersion()).thenReturn(Optional.empty());
        return new MasterDataTransitMappingResolver(provider,null,new ProviderSegmentSignatureFactory(),
                new CanonicalSegmentIdFactory(),catalog,cache);
    }
    @Test void alteredArtifactNeverTouchesDatabase() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);var service=new MetropolitanBusMasterService(jdbc);
        Path file=temp.resolve("master.gz");Files.write(file,gzip(fixture.master(List.of(fixture.regular()))));
        assertThatThrownBy(()->service.importAndActivate(file,"0".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void duplicateSequenceArtifactNeverActivates() throws Exception {
        var route=fixture.route("1","100",List.of(fixture.stop("a","출발",1,fixture.a),fixture.stop("b","도착",1,fixture.b)));
        byte[] bytes=gzip(fixture.master(List.of(route)));Path file=temp.resolve("master.gz");Files.write(file,bytes);
        JdbcTemplate jdbc=mock(JdbcTemplate.class);var service=new MetropolitanBusMasterService(jdbc);
        assertThatThrownBy(()->service.importAndActivate(file,MetropolitanBusMaster.sha(bytes))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void failedReloadKeepsLastGoodSnapshot() throws Exception {
        var service=mock(MetropolitanBusMasterService.class);
        when(service.activeVersion()).thenReturn(Optional.of("TEST"));
        when(service.load("TEST")).thenReturn(fixture.master(List.of(fixture.regular())));
        var catalog=new MetropolitanBusCatalog(service);var before=catalog.snapshot();
        when(service.activeVersion()).thenReturn(Optional.of("BROKEN"));
        when(service.load("BROKEN")).thenThrow(new IOException("test failure"));
        assertThatThrownBy(catalog::reload).isInstanceOf(IOException.class);
        assertThat(catalog.snapshot()).isSameAs(before);
    }
    @Test void metropolitanAlternativesRoundTripAndNeverBecomeInventedCanonicalIds() throws Exception {
        var catalog=mock(MetropolitanBusCatalog.class);
        when(catalog.snapshot()).thenReturn(new MetropolitanBusCatalog.Snapshot("TEST",new MetropolitanBusMatcher(fixture.master(List.of(fixture.regular())))));
        var resolver=resolver(catalog,null);
        var req=new SegmentResolveRequest(TransportMode.BUS,"100","출발정류소","도착정류소",List.of("출발정류소","중간정류소","도착정류소"),
                List.of(new VehicleInfo("100","BUS")),List.of(fixture.a,fixture.b,fixture.c));
        var result=resolver.resolve(req);
        assertThat(result.status()).isEqualTo(TransitMappingStatus.EXACT);
        assertThat(result.segments()).allSatisfy(e -> {
            assertThat(e.segmentId()).startsWith("BUS:GBIS.");
            assertThat(e.realtimeLookupReady()).isFalse();
        });
        assertThat(result.busAlternatives().get(0).geometryStatus()).isEqualTo("VERIFIED");
        var json=new ObjectMapper();assertThat(json.readValue(json.writeValueAsBytes(result),TransitMappingResult.class)).isEqualTo(result);
        var walk=new SegmentResolveRequest(TransportMode.WALK,null,null,null,List.of(),List.of(),List.of());
        assertThat(resolver.resolve(walk).status()).isEqualTo(TransitMappingStatus.NOT_APPLICABLE);
    }
    @Test void manualRejectionPreventsMetropolitanRematching() throws Exception {
        var catalog=mock(MetropolitanBusCatalog.class);var cache=mock(MetropolitanBusMappingCache.class);
        when(catalog.snapshot()).thenReturn(new MetropolitanBusCatalog.Snapshot("TEST",new MetropolitanBusMatcher(fixture.master(List.of(fixture.regular())))));
        when(cache.find(anyString(),anyString(),anyString())).thenReturn(ProviderSegmentMappingLookup.rejected());
        var resolver=resolver(catalog,cache);
        var req=new SegmentResolveRequest(TransportMode.BUS,"100","출발정류소","도착정류소",List.of("출발정류소","중간정류소","도착정류소"),List.of(new VehicleInfo("100","BUS")),List.of(fixture.a,fixture.b,fixture.c));
        assertThat(resolver.resolve(req).failureReason()).isEqualTo("MANUALLY_REJECTED");
        verify(cache,never()).save(anyString(),anyString(),anyString(),any());
    }
}
