package com.ssafy.ddubi.domain.route.repository;

import com.ssafy.ddubi.domain.route.entity.SavedRoute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 후보 경로 조회. 조건이 전부 {@code schedule_id} 로 시작해
 * {@code IX_ROUTE_SCH_SELECTED (schedule_id, is_selected)} 를 그대로 탄다.
 */
public interface SavedRouteRepository extends JpaRepository<SavedRoute, Long> {

    List<SavedRoute> findAllByScheduleIdOrderByRankNoAsc(Long scheduleId);

    Optional<SavedRoute> findByScheduleIdAndSelectedIsTrue(Long scheduleId);
}
