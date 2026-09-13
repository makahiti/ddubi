package com.ssafy.ddubi.domain.route.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 파일 공급원은 DB 쓰기 없이 작동하고 손상·버전 혼합을 성공으로 숨기지 않아야 한다. */
class ResourceSubwayMasterDataProviderTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unsetResourceKeepsExistingDatabaseProvider() throws Exception {
        var jdbc=mock(JdbcSeoulTransitMasterDataProvider.class);
        when(jdbc.activeVersion()).thenReturn(Optional.of("db-v1"));
        var provider=new ResourceSubwayMasterDataProvider(jdbc,mapper,new DefaultResourceLoader(),"");
        assertThat(provider.activeVersion()).contains("db-v1");
        verify(jdbc).activeVersion();
    }

    @Test
    void packagedMasterMapsRenamedStationAndKeepsMissingLiveIdsExplicit() throws Exception {
        var jdbc=mock(JdbcSeoulTransitMasterDataProvider.class);
        var provider=new ResourceSubwayMasterDataProvider(jdbc,mapper,new DefaultResourceLoader(),
                "classpath:transit/metropolitan-subway-master.json.gz");
        String version=provider.activeVersion().orElseThrow();
        var aliases=provider.loadAliases(version);
        var matcher=new SubwaySegmentMatcher(provider.loadSubwayLines(version),new TransitNameNormalizer(),
                aliases.subwayLineAliasIds(),aliases.subwayStationAliasIds());
        var result=matcher.match(new SubwayMappingRequest(List.of("인천2호선"),"아시아드경기장","가정",
                List.of("아시아드경기장","서해구청","가정")));
        assertThat(result.status()).isEqualTo(SubwayMappingStatus.EXACT);
        assertThat(result.segments()).extracting(MappedSubwaySegment::toStationId)
                .containsExactly("KRIC.S2802.3210","KRIC.S2802.3211");
        assertThat(result.segments()).allMatch(e -> e.realtimeSubwayId()==null && e.toRealtimeStationId()==null);
        assertThatThrownBy(() -> provider.loadSubwayLines("another-version")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
        // 공식 화면의 북부/남부 운행 카드를 임의로 연결하지 않는다.
        assertThat(matcher.match(new SubwayMappingRequest(List.of("GTX-A"),"서울역","수서",
                List.of("서울역","수서"))).status()).isNotEqualTo(SubwayMappingStatus.EXACT);
    }

    @Test
    void rejectsBrokenSequencesAndDanglingAliasInsteadOfDroppingRows() throws Exception {
        var broken=new SubwayLineMaster("TEST",null,"검증선","F","main",List.of(
                new SubwayStationMaster("A",null,"가",1),new SubwayStationMaster("B",null,"나",3)));
        assertThatThrownBy(() -> load(new ResourceSubwayMasterDataProvider.Snapshot(1,"test",List.of(broken),TransitAliasSnapshot.empty())))
                .isInstanceOf(IllegalArgumentException.class);
        var valid=new SubwayLineMaster("TEST",null,"검증선","F","main",List.of(
                new SubwayStationMaster("A",null,"가",1),new SubwayStationMaster("B",null,"나",2)));
        var aliases=new TransitAliasSnapshot(Map.of(),Map.of(),Map.of(),Map.of("없는역", Set.of("MISSING")));
        assertThatThrownBy(() -> load(new ResourceSubwayMasterDataProvider.Snapshot(1,"test",List.of(valid),aliases)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCorruptOrRemoteArtifacts() throws Exception {
        var file=temp.resolve("corrupt.gz");Files.writeString(file,"not gzip");
        assertThatThrownBy(() -> new ResourceSubwayMasterDataProvider(mock(JdbcSeoulTransitMasterDataProvider.class),
                mapper,new DefaultResourceLoader(),file.toUri().toString())).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> new ResourceSubwayMasterDataProvider(mock(JdbcSeoulTransitMasterDataProvider.class),
                mapper,new DefaultResourceLoader(),"https://example.org/master.gz")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void internalDownloadPreservesExactGzipAndSha() throws Exception {
        var provider=new ResourceSubwayMasterDataProvider(mock(JdbcSeoulTransitMasterDataProvider.class),mapper,
                new DefaultResourceLoader(),"classpath:transit/metropolitan-subway-master.json.gz");
        byte[] expected=provider.artifact().orElseThrow();
        var response=new com.ssafy.ddubi.domain.pipeline.controller.InternalSubwayMasterController(provider).download();
        assertThat(response.getBody()).containsExactly(expected);
        assertThat(response.getHeaders().getFirst("X-Content-SHA256")).isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(expected)));
        expected[0]=0;
        assertThat(provider.artifact().orElseThrow()[0]).isNotZero();
    }

    private ResourceSubwayMasterDataProvider load(ResourceSubwayMasterDataProvider.Snapshot data) throws Exception {
        var file=temp.resolve("master.gz");
        try(var gzip=new GZIPOutputStream(Files.newOutputStream(file))){mapper.writeValue(gzip,data);}
        return new ResourceSubwayMasterDataProvider(mock(JdbcSeoulTransitMasterDataProvider.class),mapper,
                new DefaultResourceLoader(),file.toUri().toString());
    }
}
