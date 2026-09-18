package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.domain.route.service.SegmentResolveRequest;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

/** 파일 마스터의 캐시 저장을 실제 MySQL FK·동시 트랜잭션으로 검증한다. 운영 데이터는 쓰지 않는다. */
class ResourceMappingCacheIntegrationTest extends IntegrationTestSupport {
    @DynamicPropertySource static void resource(DynamicPropertyRegistry p) {
        p.add("ddubi.transit-mapping.subway-master-resource",
                () -> "classpath:transit/metropolitan-subway-master.json.gz");
    }
    @Autowired JdbcProviderSegmentMappingStore store;
    @Autowired ResourceSubwayMasterDataProvider provider;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.ssafy.ddubi.domain.route.service.MasterDataTransitMappingResolver resolver;

    @org.junit.jupiter.api.BeforeEach
    @org.junit.jupiter.api.AfterEach void clearFixtureVersion() {
        // 공통 cleaner는 마스터를 보존하므로 이 테스트가 등록한 파일 버전만 격리 DB에서 정리한다.
        jdbc.update("DELETE FROM seoul_transit_master_version WHERE data_version=?",provider.activeVersion().orElseThrow());
    }

    private void save(String signature) {
        String version = provider.activeVersion().orElseThrow();
        var request = new SegmentResolveRequest(TransportMode.SUBWAY,"fixture","가","나",List.of(),List.of(),List.of());
        store.save("KAKAO",signature,version,"fixture-policy",request,
                TransitMappingResult.manuallyRejected(version,"fixture-policy"));
    }

    @Test void concurrentCacheWritesRegisterOneStagingVersionWithoutActivatingIt() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            var calls = java.util.stream.IntStream.range(0,8)
                    .<java.util.concurrent.Callable<Void>>mapToObj(i -> () -> { save("fixture-"+i); return null; }).toList();
            for (var future : executor.invokeAll(calls)) future.get();
        } finally { executor.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_segment_mapping",Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seoul_transit_master_version",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM seoul_transit_master_version",String.class)).isEqualTo("STAGING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_config WHERE config_key='seoul_transit_master_active_version'",Integer.class)).isZero();
        jdbc.update("UPDATE provider_segment_mapping SET review_status='REJECTED' WHERE provider_signature='fixture-0'");
        save("fixture-0");
        assertThat(store.find("KAKAO",TransportMode.SUBWAY,"fixture-0",provider.activeVersion().orElseThrow(),"fixture-policy").status())
                .isEqualTo(ProviderSegmentMappingLookup.Status.REJECTED);
    }

    @Test void exactResourceMappingIsPersistedAndReadBack() {
        var request = new SegmentResolveRequest(TransportMode.SUBWAY,"인천2호선","아시아드경기장","가정",
                List.of("아시아드경기장","서해구청","가정"),
                List.of(new com.ssafy.ddubi.domain.route.model.VehicleInfo("인천2호선","SUBWAY")),List.of());
        var result = resolver.resolve(request);
        assertThat(result.status()).isEqualTo(TransitMappingStatus.EXACT);
        String signature = jdbc.queryForObject("SELECT provider_signature FROM provider_segment_mapping WHERE mapping_status='EXACT'",String.class);
        var cached = store.find("KAKAO",TransportMode.SUBWAY,signature,result.masterDataVersion(),result.mappingPolicyVersion());
        assertThat(cached.status()).isEqualTo(ProviderSegmentMappingLookup.Status.EXACT);
        assertThat(cached.exactResult()).isEqualTo(result);
    }

    @Test void sameVersionWithDifferentSourceFailsWithoutOverwritingExistingMaster() {
        String version=provider.activeVersion().orElseThrow();
        jdbc.update("INSERT INTO seoul_transit_master_version(data_version,status,source_name) VALUES (?,'ACTIVE','another-source')",version);
        assertThatThrownBy(() -> save("collision")).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_segment_mapping",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT source_name FROM seoul_transit_master_version",String.class)).isEqualTo("another-source");
        assertThat(jdbc.queryForObject("SELECT status FROM seoul_transit_master_version",String.class)).isEqualTo("ACTIVE");
    }
}
