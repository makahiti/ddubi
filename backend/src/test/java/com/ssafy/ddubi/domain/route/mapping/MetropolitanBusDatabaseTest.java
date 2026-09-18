package com.ssafy.ddubi.domain.route.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static org.assertj.core.api.Assertions.*;

/** 실제 MySQL 8에서 새 스키마·버전 충돌·롤백·캐시 FK를 확인한다.
 * Docker가 없는 환경에서는 명시적으로 skip되며 mock 테스트를 DB 검증으로 보고하지 않는다. */
@Testcontainers(disabledWithoutDocker=true)
class MetropolitanBusDatabaseTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0");
    @TempDir Path temp;
    @Test void activationIsAtomicImmutableAndSeparateFromSeoulCache() throws Exception {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V4__add_metropolitan_bus_master.sql")).execute(ds);
        var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var service=new MetropolitanBusMasterService(jdbc);var fixture=new MetropolitanBusMatcherTest();
        var master=fixture.master(List.of(fixture.regular()));Path file=temp.resolve("master.gz");
        var out=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(out)){new ObjectMapper().writeValue(gzip,master);}
        byte[] bytes=out.toByteArray();Files.write(file,bytes);String sha=MetropolitanBusMaster.sha(bytes);
        tx.executeWithoutResult(s->{try{service.importAndActivate(file,sha);}catch(IOException e){throw new UncheckedIOException(e);}});
        assertThat(service.activeVersion()).contains("TEST");assertThat(service.load("TEST")).isEqualTo(master);
        tx.executeWithoutResult(s->{try{service.importAndActivate(file,sha);}catch(IOException e){throw new UncheckedIOException(e);}});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM metropolitan_bus_master",Integer.class)).isEqualTo(1);
        // 실패한 다음 버전이 ACTIVE로 남지 않는지 실제 트랜잭션 롤백을 확인한다.
        assertThatThrownBy(()->tx.executeWithoutResult(s->{
            jdbc.update("UPDATE metropolitan_bus_master_active SET data_version=NULL WHERE singleton_id=1");
            throw new IllegalStateException("후속 적재 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(service.activeVersion()).contains("TEST");
        var cache=new MetropolitanBusMappingCache(jdbc,new ObjectMapper());
        var result=TransitMappingResult.masterDataNotConfigured();
        cache.save("a".repeat(64),"TEST","P1",result);
        jdbc.update("UPDATE metropolitan_bus_mapping_cache SET review_status='REJECTED'");
        cache.save("a".repeat(64),"TEST","P1",result);
        assertThat(cache.find("a".repeat(64),"TEST","P1").status()).isEqualTo(ProviderSegmentMappingLookup.Status.REJECTED);
        assertThat(cache.find("a".repeat(64),"TEST","P2").status()).isEqualTo(ProviderSegmentMappingLookup.Status.MISS);
    }
}
