package com.ssafy.ddubi.domain.report.repository;

import com.ssafy.ddubi.domain.report.entity.ArrivalResult;
import com.ssafy.ddubi.domain.report.entity.ReportDetail;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReportDetailRepository extends JpaRepository<ReportDetail, Long> {

    /** 같은 일정을 두 번 보고하면 새 행이 아니라 이 행을 고친다. */
    Optional<ReportDetail> findByScheduleId(Long scheduleId);

    /** REPORT-002 상세 — 일정을 함께 끌어와 N+1 을 막는다. */
    @Query("""
            select d from ReportDetail d
              join fetch d.schedule
             where d.report.id = :reportId
             order by d.schedule.arriveBy
            """)
    List<ReportDetail> findAllByReportIdWithSchedule(@Param("reportId") Long reportId);

    /** 집계 재계산용. 리포트에 달린 결과 전부. */
    List<ReportDetail> findAllByReportId(Long reportId);

    /**
     * HISTORY-001 — 기간은 리포트 주간이 아니라 <b>일정 도착 목표 시각</b> 기준이다.
     * 사용자는 "지난주에 어디 다녔나"를 일정 날짜로 찾지 집계 주차로 찾지 않는다.
     */
    @Query("""
            select d from ReportDetail d
              join fetch d.schedule s
             where s.user.id = :userId
               and s.arriveBy between :from and :to
            """)
    Page<ReportDetail> findHistory(@Param("userId") Long userId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to,
                                   Pageable pageable);

    /**
     * HISTORY-001 요약 — <b>페이지가 아니라 조회 구간 전체</b>를 센다.
     *
     * 판정 값을 파라미터로 받는 이유: JPQL 에 enum 을 상수로 쓰려면 패키지 전체 경로를 적어야
     * 하는데, 클래스를 옮기면 컴파일이 아니라 실행 시점에 깨진다. 파라미터로 넘기면 컴파일러가 잡는다.
     */
    @Query("""
            select count(d)                                              as totalCount,
                   sum(case when d.result = :onTime then 1L else 0L end) as onTimeCount,
                   sum(case when d.result = :tight  then 1L else 0L end) as tightCount,
                   sum(case when d.result = :late   then 1L else 0L end) as lateCount,
                   avg(d.proxyDurationMin)                               as avgDurationMin
              from ReportDetail d
              join d.schedule s
             where s.user.id = :userId
               and s.arriveBy between :from and :to
            """)
    HistoryStats summarizeHistory(@Param("userId") Long userId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to,
                                  @Param("onTime") ArrivalResult onTime,
                                  @Param("tight") ArrivalResult tight,
                                  @Param("late") ArrivalResult late);
}
