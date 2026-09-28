package com.ssafy.ddubi.domain.schedule.repository;

import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    /** 수정의 버전 검사와 inputRevision 증가를 직렬화한다. 조회 API에는 이 lock을 쓰지 않는다. */
    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Schedule s where s.id = :id")
    Optional<Schedule> findForUpdate(@Param("id") Long id);

    Page<Schedule> findAllByUserIdAndArriveByBetween(
            Long userId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<Schedule> findAllByUserIdAndStatusAndArriveByBetween(
            Long userId,
            ScheduleStatus status,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable);

    /** SCH-006 충돌 계산 — 페이징 없이 시각순 전건. 충돌은 아직 안 지난 UPCOMING 끼리만 따진다. */
    List<Schedule> findAllByUserIdAndStatusAndArriveByBetweenOrderByArriveBy(
            Long userId, ScheduleStatus status, LocalDateTime from, LocalDateTime to);

    /**
     * HOME-001 오늘 일정 — 지정한 상태 하나만 빼고 전건을 시각순으로 준다.
     *
     * <p>홈은 "오늘 하루" 를 보여주는 화면이라 이미 끝난(DONE) 일정도 목록에 남아야 흐름이 보인다. 반대로 취소(CANCELED)는 일어나지 않은 일이라 뺀다.
     * 그래서 상태로 좁히는 대신 상태 하나를 제외하는 형태로 조회한다. 목록 API(SCH-001)가 status 미지정일 때 돌려주는 집합에서 취소만 뺀 것과 같은
     * 범위라, 홈과 목록이 같은 날짜에 서로 다른 일정을 보여주지 않는다.
     */
    List<Schedule> findAllByUserIdAndStatusNotAndArriveByBetweenOrderByArriveBy(
            Long userId, ScheduleStatus excluded, LocalDateTime from, LocalDateTime to);

    /** HOME-001 다음 일정. */
    Optional<Schedule> findFirstByUserIdAndStatusAndArriveByGreaterThanEqualOrderByArriveBy(
            Long userId, ScheduleStatus status, LocalDateTime at);

    /**
     * 자동 마감 대상 — 도착 예정 시각이 지났는데 아직 UPCOMING 인 일정.
     *
     * <p>한 바퀴에 처리할 개수를 {@code Pageable} 로 묶는다. 오래 방치된 서버에서 수만 건이 한 트랜잭션에 들어오면 그 한 번이 DB 를 오래 잡는다.
     * 남은 건은 다음 바퀴가 가져간다.
     */
    List<Schedule> findAllByStatusAndArriveByLessThanOrderByArriveBy(
            ScheduleStatus status, LocalDateTime before, Pageable pageable);

    /** 반복 인스턴스 중복 생성 방지용 — 이미 있는 도착 시각들. */
    @Query("select s.arriveBy from Schedule s where s.repeat.id = :repeatId")
    List<LocalDateTime> findArriveBysByRepeatId(@Param("repeatId") Long repeatId);

    /**
     * REPEAT-004 재생성 대상 삭제 — 미래·미시작·개별수정 안 된 인스턴스만. customized=true 는 사용자가 손댄 것이라 남긴다(도메인 규칙 5).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            delete from Schedule s
            where s.repeat.id = :repeatId
              and s.status = com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus.UPCOMING
              and s.customized = false
              and s.arriveBy > :after
            """)
    int deleteRegenerableInstances(
            @Param("repeatId") Long repeatId, @Param("after") LocalDateTime after);

    /**
     * REPEAT-005 "이후 일정 전체 취소" — 대상 조건은 재생성 삭제와 똑같고, 지우는 대신 CANCELED 로 바꾼다.
     *
     * <p>물리 삭제하지 않는 이유는 규칙 11 이다 — 삭제는 soft(CANCELED), 물리 삭제는 탈퇴 CASCADE 뿐이다. 알림·추천이 이미 참조한 일정을 지우면
     * 그쪽이 고아가 된다.
     *
     * <p>{@code journeyState} 와 {@code inputRevision} 도 함께 바꾼다. 벌크 갱신은 엔티티의 {@code cancel()} 을 타지
     * 않으므로, 여기서 같이 맞추지 않으면 status 만 CANCELED 인데 journey_state 는 NOT_STARTED 인 행이 남는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update Schedule s
               set s.status = com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus.CANCELED,
                   s.journeyState = 'CANCELED',
                   s.inputRevision = s.inputRevision + 1
             where s.repeat.id = :repeatId
               and s.status = com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus.UPCOMING
               and s.customized = false
               and s.arriveBy > :after
            """)
    int cancelRegenerableInstances(
            @Param("repeatId") Long repeatId, @Param("after") LocalDateTime after);
}
