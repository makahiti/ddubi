package com.ssafy.ddubi.domain.route.mapping;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

/** 환승역 ID는 호선별 보존하며 이전 역 단위 crosswalk도 계속 읽는다. */
@Testcontainers(disabledWithoutDocker = true)
class SubwayLineScopedRealtimeDatabaseTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test void lineOverrideAndLegacyFallbackCoexist() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__init.sql"),
                new ClassPathResource("db/migration/V7__subway_line_scoped_realtime_station.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO seoul_transit_master_version(data_version,status,source_name) VALUES('TEST','STAGING','test')");
        jdbc.update("INSERT INTO seoul_subway_station(data_version,station_id,realtime_station_id,station_name) VALUES('TEST','S','legacy','Station')");
        for (String line : new String[]{"1", "2", "3"}) {
            jdbc.update("INSERT INTO seoul_subway_line(data_version,subway_id,realtime_subway_id,direction,service_pattern_id,line_name) VALUES('TEST',?,?,'FORWARD','main',?)", line,line,line);
            jdbc.update("INSERT INTO seoul_subway_line_station(data_version,subway_id,direction,service_pattern_id,station_id,station_sequence,realtime_station_id) VALUES('TEST',?,'FORWARD','main','S',1,?)",line,line.equals("3")?null:"realtime-"+line);
        }
        var lines = new JdbcSeoulTransitMasterDataProvider(jdbc).loadSubwayLines("TEST");
        assertThat(lines).hasSize(3);
        assertThat(lines).allSatisfy(line -> assertThat(line.stations().get(0).stationId()).isEqualTo("S"));
        assertThat(lines.stream().map(line -> line.stations().get(0).realtimeStationId())).containsExactly("realtime-1","realtime-2","legacy");
    }
}
