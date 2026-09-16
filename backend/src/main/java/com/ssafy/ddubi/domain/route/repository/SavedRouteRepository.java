package com.ssafy.ddubi.domain.route.repository;

import com.ssafy.ddubi.domain.route.entity.SavedRoute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 후보 경로 조회. 조건이 전부 {@code schedule_id} 로 시작해
 * {@code IX_ROUTE_SCH_SELECTED (schedule_id, is_selected)} 를 그대로 탄다.
 *
 * <h3>구간까지 볼 거면 fetch join 을 쓴다</h3>
 * {@code segments} 가 LAZY 라, 목록을 돌며 구간을 건드리면 후보 수만큼 쿼리가 더 나간다.
 * 조회 API 는 응답을 만들면서 구간을 반드시 보므로 한 번에 가져온다.
 */
public interface SavedRouteRepository extends JpaRepository<SavedRoute, Long> {

    List<SavedRoute> findAllByScheduleIdOrderByRankNoAsc(Long scheduleId);

    Optional<SavedRoute> findByScheduleIdAndSelectedIsTrue(Long scheduleId);

    /** rank_no 는 nullable 이라 마지막에 둔다 — 순위 없는 후보가 목록 맨 앞에 오면 안 된다. */
    @Query("""
            select distinct r from SavedRoute r
            left join fetch r.segments
            where r.schedule.id = :scheduleId
            order by case when r.rankNo is null then 1 else 0 end, r.rankNo asc, r.id asc
            """)
    List<SavedRoute> findAllWithSegmentsByScheduleId(@Param("scheduleId") Long scheduleId);

    @Query("select r from SavedRoute r left join fetch r.segments where r.id = :routeId")
    Optional<SavedRoute> findWithSegmentsById(@Param("routeId") Long routeId);
}
