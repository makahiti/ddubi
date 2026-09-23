package com.ssafy.ddubi.domain.route.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.support.IsolatedMySql;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * 실제 MySQL·Spring 트랜잭션 프록시에서 두 번째 edge INSERT 실패가 첫 INSERT/DELETE까지 롤백되는지 확인한다. 서비스가 담당하지 않는 부모
 * route는 최소 행만 만들고, route_segment/edge는 저장소 V1 DDL을 그대로 사용한다. 격리 DB 는 IsolatedMySql 이 준다(CI 는
 * services 의 MySQL 에 새 데이터베이스, 로컬은 Testcontainers). 둘 다 없으면 실패한다.
 */
class RouteSegmentEdgeDatabaseTest {

    @Test
    void repeatedReplaceIsIdempotentAndBatchFailureRestoresPreviousEdges() throws Exception {
        try (var mysql = IsolatedMySql.create()) {
            var ds = mysql.dataSource();
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE route (id BIGINT PRIMARY KEY)");
            String ddl;
            try (var stream = new ClassPathResource("db/migration/V1__init.sql").getInputStream()) {
                ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (String table : new String[] {"route_segment", "route_segment_edge"}) {
                int start = ddl.indexOf("CREATE TABLE " + table + " (");
                jdbc.execute(ddl.substring(start, ddl.indexOf(';', start) + 1));
            }
            var fixture = new RouteSegmentEdgeWriterTest();
            var resolver = fixture.resolver();
            var segment = fixture.segment(resolver);
            var json = new ObjectMapper();
            jdbc.update("INSERT INTO route(id) VALUES(10)");
            jdbc.update(
                    "INSERT INTO route_segment(id,route_id,seq,mode,provider_route_key,from_name,to_name,base_duration_min,geometry) VALUES(20,10,1,'BUS','100','출발','도착',2,?)",
                    json.writeValueAsString(segment.geometry()));
            var interceptor = new TransactionInterceptor();
            interceptor.setTransactionManager(new DataSourceTransactionManager(ds));
            interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
            var proxy = new ProxyFactory(new RouteSegmentEdgeWriter(jdbc, resolver, json));
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(interceptor);
            var writer = (RouteSegmentEdgeWriter) proxy.getProxy();
            writer.replace(10, 20, segment, 0, 0);
            writer.replace(10, 20, segment, 0, 0);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT segment_id FROM route_segment_edge ORDER BY edge_seq",
                                    String.class))
                    .containsExactly("BUS:R1:A:B", "BUS:R1:B:C");
            // 기존 행에 구별 가능한 값을 둬 '실패 후 새 값이 일부 남는' 부분 성공까지 검출한다.
            jdbc.update("UPDATE route_segment_edge SET mapping_version='BEFORE'");
            // GitLab Testcontainers의 일반 MySQL 계정은 binary log 환경에서 CREATE TRIGGER에 SUPER 권한이 필요하다.
            // 기존 행은 통과하고 새 두 번째 edge만 거부하는 테스트 전용 CHECK로 같은 롤백 경계를 검증한다.
            jdbc.execute(
                    "ALTER TABLE route_segment_edge ADD CONSTRAINT CK_TEST_FAIL_SECOND_EDGE "
                            + "CHECK (mapping_version='BEFORE' OR edge_seq<>2)");
            assertThatThrownBy(() -> writer.replace(10, 20, segment, 0, 0))
                    .isInstanceOf(Exception.class);
            assertThat(
                            jdbc.queryForList(
                                    "SELECT mapping_version FROM route_segment_edge ORDER BY edge_seq",
                                    String.class))
                    .containsExactly("BEFORE", "BEFORE");
        }
    }
}
