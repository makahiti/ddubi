package com.ssafy.ddubi.domain.route.repository;

import com.ssafy.ddubi.domain.route.entity.SavedRoute;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 후보 경로 조회. 조건이 전부 {@code schedule_id} 로 시작해 {@code IX_ROUTE_SCH_SELECTED (schedule_id,
 * is_selected)} 를 그대로 탄다.
 *
 * <h3>구간까지 볼 거면 fetch join 을 쓴다</h3>
 *
 * {@code segments} 가 LAZY 라, 목록을 돌며 구간을 건드리면 후보 수만큼 쿼리가 더 나간다. 조회 API 는 응답을 만들면서 구간을 반드시 보므로 한 번에
 * 가져온다.
 */
public interface SavedRouteRepository extends JpaRepository<SavedRoute, Long> {

    /**
     * 옛 후보를 지우기 전에 기다리는 시간.
     *
     * <p>재계산이 끝나는 순간 앱은 그 직전에 받은 목록을 화면에 들고 있을 수 있다. 바로 지우면 사용자가 누른 경로가 404 로 사라진다(2026-09-18 실기기에서
     * 확인). 새 검색분은 즉시 목록에 나오고, 옛 검색분은 이 시간이 지난 뒤의 다음 정리에서 지워진다.
     */
    Duration STALE_CANDIDATE_GRACE = Duration.ofMinutes(10);

    List<SavedRoute> findAllByScheduleIdOrderByRankNoAsc(Long scheduleId);

    Optional<SavedRoute> findByScheduleIdAndSelectedIsTrue(Long scheduleId);

    /** 이동 변수 조회가 선택 경로의 구간을 한 번에 읽고, 같은 트랜잭션에서 각 canonical edge를 조회할 때 사용한다. */
    @Query(
            """
            select distinct r from SavedRoute r
            left join fetch r.segments s
            where r.schedule.id = :scheduleId and r.selected = true
            """)
    Optional<SavedRoute> findSelectedWithSegments(@Param("scheduleId") Long scheduleId);

    /**
     * 화면에 보여 줄 후보 목록. <b>마지막 검색분</b>과 지금 선택된 경로만 돌려준다.
     *
     * <h3>왜 전부 주지 않나</h3>
     *
     * 재계산할 때마다 후보가 새로 저장되고 예전 것은 남는다. 조건 없이 읽으면 검색 횟수만큼 후보가 불어나 순위가 1~20 을 반복하고 응답도 그만큼 커진다. 선택된
     * 경로는 예전 검색분일 수 있어 예외로 함께 돌려준다 — 사용자가 고른 경로가 목록에서 사라지면 안 된다.
     *
     * <p>rank_no 는 nullable 이라 마지막에 둔다 — 순위 없는 후보가 목록 맨 앞에 오면 안 된다.
     */
    @Query(
            """
            select distinct r from SavedRoute r
            left join fetch r.segments
            where r.schedule.id = :scheduleId
              and (r.selected = true
                   or r.routeRequest.id = (select max(o.routeRequest.id) from SavedRoute o
                                           where o.schedule.id = :scheduleId))
            order by case when r.rankNo is null then 1 else 0 end, r.rankNo asc, r.id asc
            """)
    List<SavedRoute> findAllWithSegmentsByScheduleId(@Param("scheduleId") Long scheduleId);

    /**
     * 더 쓰이지 않는 옛 검색분 후보. 지난 추천이 가리키는 경로와 선택된 경로는 제외한다.
     *
     * <p>지난 추천이 가리키는 후보까지 지우면 "그때 무엇을 추천했는지" 기록이 깨진다. 구간과 구간 간선은 경로에 매달려 있어 함께 지워진다(cascade).
     *
     * <p>시각 비교는 {@code route_request.requested_at} 으로 한다. 이 값은 저장하는 쪽이 KST 로 직접 넣는 값이라 호출자의 {@code
     * before} 와 같은 시계다. 엔티티 생성 시각({@code createdAt})은 JVM 기본 시간대로 채워져 서버 설정에 따라 9시간이 어긋날 수 있어 쓰지
     * 않는다.
     *
     * @param before 이 시각(KST)보다 먼저 요청된 검색분의 후보만 고른다. 보통 {@code now - STALE_CANDIDATE_GRACE}.
     */
    @Query(
            """
            select r from SavedRoute r
            where r.schedule.id = :scheduleId
              and r.routeRequest.id < :keepRequestId
              and r.selected = false
              and r.routeRequest.requestedAt < :before
              and not exists (select 1 from Recommendation c where c.route = r)
            """)
    List<SavedRoute> findStaleCandidates(
            @Param("scheduleId") Long scheduleId,
            @Param("keepRequestId") Long keepRequestId,
            @Param("before") LocalDateTime before);

    @Query("select r from SavedRoute r left join fetch r.segments where r.id = :routeId")
    Optional<SavedRoute> findWithSegmentsById(@Param("routeId") Long routeId);
}
