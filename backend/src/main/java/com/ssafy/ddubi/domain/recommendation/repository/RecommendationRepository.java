package com.ssafy.ddubi.domain.recommendation.repository;

import com.ssafy.ddubi.domain.recommendation.entity.Recommendation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 추천은 불변 INSERT 라 같은 일정에 행이 쌓인다. 화면이 원하는 것은 늘 <b>가장 최근 것</b>이다.
 * {@code IX_REC_SCH_CREATED (schedule_id, created_at)} 가 그 조회를 위해 있다.
 */
public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    Optional<Recommendation> findFirstByScheduleIdOrderByCreatedAtDesc(Long scheduleId);
}
