package com.ssafy.ddubi.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 테스트 간 데이터 격리.
 *
 * <p>@Transactional 롤백을 쓰지 않는 이유: 이 프로젝트가 검증하려는 것 중에
 * <b>DB 가 커밋 시점에 수행하는 동작</b>(ON DELETE CASCADE)이 있다. 롤백 방식이면
 * 트랜잭션이 끝나지 않아 CASCADE 결과를 확인할 수 없다. 그래서 실제로 커밋하고 지운다.
 *
 * <p>FK 때문에 순서가 중요해 외래키 검사를 잠시 끈다.
 * flyway_schema_history 는 지우지 않는다 — 지우면 다음 컨텍스트에서 마이그레이션이 다시 돈다.
 */
@Component
public class DatabaseCleaner {

    /** 자식 → 부모 순서. FK 검사를 끄더라도 의도를 남기기 위해 순서를 유지한다. */
    private static final List<String> TABLES = List.of(
            "report_detail", "report",
            "notification",
            "recommendation",
            "route_segment", "route", "route_request",
            "schedule", "schedule_repeat",
            "device_token", "place", "user_transport_pref", "user_auth", "user",
            "baseline_serving", "detection_log", "pipeline_config");

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void truncateAll() {
        em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
        TABLES.forEach(table ->
                em.createNativeQuery("TRUNCATE TABLE `" + table + "`").executeUpdate());
        em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
    }
}
