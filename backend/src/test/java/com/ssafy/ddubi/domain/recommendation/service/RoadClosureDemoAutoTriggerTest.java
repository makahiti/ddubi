package com.ssafy.ddubi.domain.recommendation.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 시연 일정은 상세 화면을 열지 않아도 서버가 한 번 통제를 건다. 같은 일정을 되풀이해 다시 계산하지 않는다(2026-09-25). */
class RoadClosureDemoAutoTriggerTest {
    private final Instant now = Instant.parse("2026-09-25T14:00:00Z");
    private final RoadClosureDemoSupport demo = mock(RoadClosureDemoSupport.class);
    private final RoadClosureDemoRunner runner = mock(RoadClosureDemoRunner.class);
    private final RoadClosureDemoAutoTrigger trigger =
            new RoadClosureDemoAutoTrigger(mock(JdbcTemplate.class), demo, runner, 10);
    private final List<RoadClosureDemoAutoTrigger.Candidate> one =
            List.of(new RoadClosureDemoAutoTrigger.Candidate(65, 1, 1));

    @Test
    void runsOnceAndNeverRecalculatesTheSameSchedule() {
        when(demo.event(65)).thenReturn(Optional.empty());
        when(runner.run(65, 1, now)).thenReturn(Map.of("state", "ALTERNATIVE_READY"));

        trigger.run(one, now);
        trigger.run(one, now.plusSeconds(5));

        verify(runner, times(1)).run(anyLong(), anyLong(), any());
    }

    /** 앱이 먼저 걸었거나 서버가 재시작된 뒤: 같은 입력 버전의 통제가 있으면 다시 계산하지 않는다. */
    @Test
    void skipsWhenTheSameRevisionIsAlreadyControlled() throws Exception {
        when(demo.event(65))
                .thenReturn(Optional.of(new ObjectMapper().readTree("{\"inputRevision\":1}")));

        trigger.run(one, now);

        verify(runner, never()).run(anyLong(), anyLong(), any());
    }

    /** 첫 계산이 덜 끝났거나 버스 위주가 아니면 몇 번만 다시 해 보고 평소 일정처럼 둔다. */
    @Test
    void retriesALimitedNumberOfTimes() {
        when(demo.event(65)).thenReturn(Optional.empty());
        when(runner.run(anyLong(), anyLong(), any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_STATE, "버스 중심 경로가 선택되지 않았습니다."));

        for (int i = 0; i < RoadClosureDemoAutoTrigger.MAX_ATTEMPTS + 3; i++)
            trigger.run(one, now.plusSeconds(5L * i));

        verify(runner, times(RoadClosureDemoAutoTrigger.MAX_ATTEMPTS))
                .run(anyLong(), anyLong(), any());
    }
}
