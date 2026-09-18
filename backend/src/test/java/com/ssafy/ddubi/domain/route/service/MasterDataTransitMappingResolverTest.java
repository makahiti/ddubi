package com.ssafy.ddubi.domain.route.service;

import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.ssafy.ddubi.domain.route.mapping.MetropolitanBusFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 운영 Resolver의 기본 BUS 분기, 독립 버전 캐시와 기존 지하철 경계를 검증한다. */
class MasterDataTransitMappingResolverTest {
    final Clock date=Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"),ZoneId.of("Asia/Seoul"));
    SegmentResolveRequest bus(List<String> vehicles,List<Coordinate> points) {
        return new SegmentResolveRequest(TransportMode.BUS,"100","출발","도착",List.of("출발","중간","도착"),
                vehicles.stream().map(n->new VehicleInfo(n,"BUS")).toList(),points);
    }
    SegmentResolveRequest subway(String name) {
        return new SegmentResolveRequest(TransportMode.SUBWAY,name,"강남역","역삼역",List.of("강남역","역삼역"),
                List.of(new VehicleInfo(name,"SUBWAY")),List.of());
    }
    SeoulTransitMasterDataProvider provider() {
        var provider=mock(SeoulTransitMasterDataProvider.class);
        when(provider.activeVersion()).thenReturn(Optional.of("SUB1"));
        when(provider.loadAliases(anyString())).thenReturn(TransitAliasSnapshot.empty());
        when(provider.loadSubwayLines(anyString())).thenReturn(List.of(new SubwayLineMaster("2",null,"2호선","UP","MAIN",
                List.of(new SubwayStationMaster("0222",null,"강남",1),new SubwayStationMaster("0223",null,"역삼",2)))));
        return provider;
    }
    MasterDataTransitMappingResolver resolver(SeoulTransitMasterDataProvider provider,ProviderSegmentMappingStore subwayCache,
            MetropolitanBusCatalog catalog,MetropolitanBusMappingCache cache,Clock clock) {
        return new MasterDataTransitMappingResolver(provider,subwayCache,new ProviderSegmentSignatureFactory(),
                new CanonicalSegmentIdFactory(),catalog,cache,clock);
    }
    MetropolitanBusCatalog catalog() {return new MetropolitanBusCatalog(master(List.of(regular())));}
    @Test void busUsesMetropolitanByDefaultAndPreservesCanonicalEdges() {
        var oldCache=mock(ProviderSegmentMappingStore.class);
        var result=resolver(provider(),oldCache,catalog(),null,date).resolve(bus(List.of("100"),geometry()));
        assertThat(result.status()).isEqualTo(TransitMappingStatus.EXACT);
        assertThat(result.masterDataVersion()).isEqualTo("TEST");
        assertThat(result.busAlternatives()).hasSize(1);
        assertThat(result.segments()).extracting(MappedTransitSegment::segmentId).containsExactly("BUS:R1:A:B","BUS:R1:B:C");
        verifyNoInteractions(oldCache);
    }
    @Test void missingMetropolitanMasterCannotFallBackAndSubwayStillWorks() {
        var resolver=resolver(provider(),null,mock(MetropolitanBusCatalog.class),null,date);
        var result=resolver.resolve(bus(List.of("100"),geometry()));
        assertThat(result.failureReason()).isEqualTo("MASTER_DATA_NOT_CONFIGURED");
        assertThat(result.segments()).isEmpty();
        assertThat(resolver.resolve(subway("2호선")).status()).isEqualTo(TransitMappingStatus.EXACT);
        assertThat(resolver.resolve(new SegmentResolveRequest(TransportMode.WALK,null,null,null,List.of(),List.of(),List.of())).status()).isEqualTo(TransitMappingStatus.NOT_APPLICABLE);
    }
    @Test void missingSubwayMasterDoesNotDisableBus() {
        var provider=provider();when(provider.activeVersion()).thenReturn(Optional.empty());
        assertThat(resolver(provider,null,catalog(),null,date).resolve(bus(List.of("100"),geometry())).status()).isEqualTo(TransitMappingStatus.EXACT);
    }
    @Test void cacheHitAvoidsRewriteAndFullGeometryAndDateChangeKeys() {
        var cache=mock(MetropolitanBusMappingCache.class);
        when(cache.find(anyString(),anyString(),anyString())).thenReturn(ProviderSegmentMappingLookup.miss());
        var resolver=resolver(provider(),null,catalog(),cache,date);
        var result=resolver.resolve(bus(List.of("100"),geometry()));
        var signature=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cache).save(signature.capture(),eq("TEST"),eq("metro-id-v2-2026-09-10"),eq(result));
        var original=signature.getValue();
        when(cache.find(original,"TEST","metro-id-v2-2026-09-10")).thenReturn(ProviderSegmentMappingLookup.exact(result));
        assertThat(resolver.resolve(bus(List.of("100"),geometry()))).isEqualTo(result);
        verify(cache,times(1)).save(anyString(),anyString(),anyString(),any());
        resolver.resolve(bus(List.of("100"),List.of(geometry().get(0),new Coordinate(128,38),geometry().get(2))));
        verify(cache,times(2)).save(signature.capture(),anyString(),anyString(),any());
        assertThat(signature.getValue()).isNotEqualTo(original);
        resolver(provider(),null,catalog(),cache,Clock.offset(date,Duration.ofDays(1))).resolve(bus(List.of("100"),geometry()));
        verify(cache).find(anyString(),eq("TEST"),eq("metro-id-v2-2026-09-11"));
    }
    @Test void multipleVerifiedVehiclesNeverConcatenateCanonicalEdges() {
        var second=new MetropolitanBusMaster.Route("SEOUL_BUS","R2","200","서울",regular().stops(),Map.of());
        var resolver=resolver(provider(),null,new MetropolitanBusCatalog(master(List.of(regular(),second))),null,date);
        var result=resolver.resolve(bus(List.of("100","200"),geometry()));
        assertThat(result.status()).isEqualTo(TransitMappingStatus.PARTIAL);
        assertThat(result.busAlternatives()).hasSize(2);assertThat(result.segments()).isEmpty();
    }
    @Test void subwayRejectAndAliasRefreshRetainTheirOwnCachePolicy() {
        var provider=provider();var cache=mock(ProviderSegmentMappingStore.class);
        when(cache.find(anyString(),any(),anyString(),anyString(),anyString())).thenReturn(ProviderSegmentMappingLookup.rejected());
        var resolver=resolver(provider,cache,catalog(),null,date);
        assertThat(resolver.resolve(subway("2호선")).failureReason()).isEqualTo("MANUALLY_REJECTED");
        verify(cache,never()).save(anyString(),anyString(),anyString(),anyString(),any(),any());
        when(cache.find(anyString(),any(),anyString(),anyString(),anyString())).thenReturn(ProviderSegmentMappingLookup.miss());
        var before=resolver.resolve(subway("카카오이호선"));
        when(provider.loadAliases("SUB1")).thenReturn(new TransitAliasSnapshot(Map.of(),Map.of(),Map.of("카카오이호선",Set.of("2")),Map.of()));
        resolver.reloadWhenActiveVersionChanges();var after=resolver.resolve(subway("카카오이호선"));
        assertThat(before.status()).isEqualTo(TransitMappingStatus.UNMAPPED);
        assertThat(after.status()).isEqualTo(TransitMappingStatus.EXACT);
        assertThat(after.mappingPolicyVersion()).isNotEqualTo(before.mappingPolicyVersion());
    }
    @Test void subwayStaticIdsRemainSeparateFromMissingRealtimeCrosswalk() {
        var result=resolver(provider(),null,catalog(),null,date).resolve(subway("2호선"));
        assertThat(result.status()).isEqualTo(TransitMappingStatus.EXACT);
        assertThat(result.segments()).extracting(MappedTransitSegment::segmentId).containsExactly("SUB:2:0222:0223");
        assertThat(result.realtimeCoverage()).isEqualTo(RealtimeMappingCoverage.NONE);
    }
}
