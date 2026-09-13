package com.ssafy.ddubi.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 테스트 간 데이터 격리.
 *
 * <p>@Transactional 롤백을 쓰지 않는 이유: 이 프로젝트가 검증하려는 것 중에
 * <b>DB 가 커밋 시점에 수행하는 동작</b>(ON DELETE CASCADE)이 있다. 롤백 방식이면
 * 트랜잭션이 끝나지 않아 CASCADE 결과를 확인할 수 없다. 그래서 실제로 커밋하고 지운다.
 *
 * <p>FK 때문에 순서가 중요해 외래키 검사를 잠시 끈다.
 * flyway_schema_history 는 지우지 않는다 — 지우면 다음 컨텍스트에서 마이그레이션이 다시 돈다.
 *
 * <h3>목록을 손으로 관리하되, 빠뜨리면 바로 실패한다</h3>
 * 자동으로 전부 지우지 않는 이유는 <b>일부러 남기는 테이블</b>이 있기 때문이다(아래 {@link #KEPT}).
 * 그런데 손 목록은 새 테이블이 생겼을 때 조용히 샌다. 실제로 {@code route_segment_edge} 가
 * 빠져 있었고, 첫 테스트만 통과하고 나머지가 <b>PK 중복</b>으로 죽었다 — TRUNCATE 가
 * AUTO_INCREMENT 를 1로 되돌리는데 자식 행은 남아 있어서 (1,1) 이 두 번 들어갔다.
 *
 * <p>그래서 실제 스키마와 대조해, 양쪽 어디에도 없는 테이블이 있으면 <b>즉시 실패</b>한다.
 * 진단이 "왜 두 번째 테스트부터 깨지지" 가 아니라 "이 테이블을 어디에 넣을지 정해라" 가 된다.
 */
@Component
public class DatabaseCleaner {

    /** 자식 → 부모 순서. FK 검사를 끄더라도 의도를 남기기 위해 순서를 유지한다. */
    private static final List<String> TABLES = List.of(
            "schedule_evaluation", "report_detail", "report",
            "notification",
            "recommendation",
            "route_segment_edge", "route_segment", "route", "route_request",
            "schedule", "schedule_repeat",
            "device_token", "place", "user_transport_pref", "user_auth", "user",
            "baseline_serving", "detection_log", "pipeline_config");

    /**
     * 일부러 지우지 않는 테이블.
     *
     * <ul>
     *   <li>{@code flyway_schema_history} — 지우면 다음 컨텍스트에서 마이그레이션이 다시 돈다</li>
     *   <li>{@code seoul_*}, {@code transit_alias} — 서울 교통 정적 마스터. 한 번 적재하면
     *       테스트 전체가 공유한다. 매번 지우면 적재 비용이 테스트 수만큼 든다</li>
     *   <li>{@code provider_segment_mapping} — 위 마스터로 만든 매핑 캐시라 같이 남긴다</li>
     *   <li>{@code metropolitan_bus_*} — 수도권 버스 마스터·캐시(2026-09-10 합류). 같은 이유로 남긴다</li>
     * </ul>
     */
    private static final Set<String> KEPT = Set.of(
            "flyway_schema_history",
            "provider_segment_mapping",
            "transit_alias",
            "seoul_bus_route", "seoul_bus_route_stop", "seoul_bus_stop",
            "seoul_subway_line", "seoul_subway_line_station", "seoul_subway_station",
            "seoul_transit_master_version",
            "metropolitan_bus_master", "metropolitan_bus_master_active",
            "metropolitan_bus_mapping_cache");

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void truncateAll() {
        failIfAnyTableIsUnaccountedFor();

        em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
        TABLES.forEach(table ->
                em.createNativeQuery("TRUNCATE TABLE `" + table + "`").executeUpdate());
        em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
    }

    /**
     * 스키마에 있는데 지우지도 남기지도 않기로 한 테이블이 있으면 실패한다.
     * 새 테이블을 만든 사람이 <b>이 파일을 고치도록</b> 강제하는 것이 목적이다.
     */
    @SuppressWarnings("unchecked")
    private void failIfAnyTableIsUnaccountedFor() {
        List<String> actual = em.createNativeQuery("""
                select table_name from information_schema.tables
                 where table_schema = database() and table_type = 'BASE TABLE'
                """).getResultList();

        Set<String> known = new LinkedHashSet<>(TABLES);
        known.addAll(KEPT);

        String unaccounted = actual.stream()
                .filter(table -> !known.contains(table.toLowerCase()))
                .sorted()
                .collect(Collectors.joining(", "));

        if (!unaccounted.isEmpty()) {
            throw new IllegalStateException("""
                    DatabaseCleaner 가 모르는 테이블이 있습니다: %s

                    테스트마다 비워야 하면 TABLES 에(자식이 먼저 오도록), 공유해야 하면 KEPT 에
                    넣어 주세요. 그냥 두면 테스트 사이에 데이터가 남아 두 번째 테스트부터
                    PK 중복이나 개수 불일치로 깨지고, 원인이 한참 뒤에 드러납니다.
                    """.formatted(unaccounted));
        }
    }
}
