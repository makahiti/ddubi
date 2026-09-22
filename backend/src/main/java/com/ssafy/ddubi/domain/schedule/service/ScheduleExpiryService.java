package com.ssafy.ddubi.domain.schedule.service;

import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도착 확인 없이 시각이 지난 일정을 닫는다.
 *
 * <h3>왜 필요한가</h3>
 *
 * 일정이 UPCOMING 에서 벗어나는 길은 사용자의 도착 보고({@code ArrivalService})와 취소뿐이었다. 그래서 도착을 누르지 않은 일정은 도착 예정 시각이
 * 며칠 지나도 계속 "예정" 으로 남고, 목록·홈에서 다가오는 일정 사이에 섞여 보인다. 평가 행에 붙은 오류 표시도 같이 박제된다.
 *
 * <h3>유예 시간을 두는 이유</h3>
 *
 * 늦게 도착한 사람이 뒤늦게 도착을 누르는 일은 실제로 생긴다. 도착 예정 시각이 지나자마자 닫으면 그 보고가 "지난 일정" 에 꽂힌다. 그래서 기본 6시간을 기다린다. 닫힌
 * 뒤에 도착을 보고해도 {@code ArrivalService} 는 CANCELED 만 막으므로 기록 자체는 여전히 남는다.
 *
 * <h3>시각을 인자로 받는다</h3>
 *
 * 실제 시계를 직접 읽지 않는다. 그래야 테스트가 시계에 기대지 않고, 자정 근처에 돌려도 흔들리지 않는다.
 */
@Service
public class ScheduleExpiryService {

    private final ScheduleRepository schedules;
    private final Duration grace;
    private final int batchSize;

    public ScheduleExpiryService(
            ScheduleRepository schedules,
            @Value("${ddubi.schedule.expiry-grace:6h}") Duration grace,
            @Value("${ddubi.schedule.expiry-batch-size:200}") int batchSize) {
        this.schedules = schedules;
        this.grace = grace;
        this.batchSize = batchSize;
    }

    /**
     * 유예 시간까지 지난 UPCOMING 일정을 DONE 으로 닫는다.
     *
     * <p>한 바퀴에 {@code expiry-batch-size} 건까지만 본다. 남은 건은 다음 바퀴가 가져가므로 한 번의 실행이 DB 를 오래 잡지 않는다.
     *
     * @return 실제로 닫은 건수
     */
    @Transactional
    public int expireOverdue(LocalDateTime now) {
        List<Schedule> overdue =
                schedules.findAllByStatusAndArriveByLessThanOrderByArriveBy(
                        ScheduleStatus.UPCOMING, now.minus(grace), PageRequest.of(0, batchSize));
        int closed = 0;
        for (Schedule schedule : overdue) {
            if (schedule.markExpired()) {
                closed++;
            }
        }
        return closed;
    }
}
