package com.ssafy.ddubi.domain.report.service;

import com.ssafy.ddubi.domain.report.entity.Report;
import com.ssafy.ddubi.domain.report.repository.ReportRepository;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.global.util.BusinessTime;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * 일정이 속한 주(週)의 리포트를 가져오거나 만든다.
 *
 * <h3>동시에 같은 주를 만들려 하면</h3>
 * {@code report} 의 UNIQUE(user_id, period_start, period_end) 가 막고 한쪽은 실패한다.
 * 그 요청은 409 로 떨어지고 클라이언트가 다시 보내면 상대가 만든 행을 읽어 성공한다.
 * <b>중복 행이 생기지 않는다는 것이 중요하고</b>, 재시도 한 번은 감수할 만하다.
 *
 * <p>한때 이 생성만 {@code REQUIRES_NEW} 로 떼어내 충돌을 안에서 삼키려 했는데 두 가지가 틀어졌다.
 * ① MySQL 기본 격리 수준이 REPEATABLE READ 라, 이미 스냅샷을 뜬 바깥 트랜잭션에는 방금 커밋한
 * 리포트가 <b>보이지 않는다</b>. ② 뒤 단계에서 예외가 나면 바깥은 롤백되는데 리포트 행은 이미
 * 커밋돼 빈 행이 남는다. 막으려던 문제보다 부작용이 컸다.
 */
@Component
public class WeeklyReportProvider {

    private final ReportRepository reportRepository;

    public WeeklyReportProvider(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    /** 주는 월요일에 시작해 일요일에 끝난다(ISO-8601, 한국 달력 표기와 동일). */
    public static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * @param date 귀속 기준일. <b>보고 시각이 아니라 일정의 도착 목표일</b>이다.
     *             자정 직전 일정을 새벽에 보고해도 원래 주에 들어가야 한다
     */
    public Report findOrCreate(User user, LocalDate date) {
        LocalDate start = weekStart(date);
        LocalDate end = start.plusDays(6);

        return reportRepository.findByUserIdAndPeriodStartAndPeriodEnd(user.getId(), start, end)
                .orElseGet(() -> reportRepository.save(Report.builder()
                        .user(user)
                        .periodStart(start)
                        .periodEnd(end)
                        .computedAt(BusinessTime.now())
                        .build()));
    }
}
