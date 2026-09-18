package com.ssafy.ddubi.domain.schedule.service;

import com.ssafy.ddubi.domain.recommendation.service.ScheduleInputChanged;
import com.ssafy.ddubi.domain.schedule.entity.LocationSnapshot;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 일정 취소(SCH-005) 규칙을 DB 없이 고정한다.
 * 취소는 soft(status=CANCELED)이며 재평가 중단 이벤트를 낸다. 남의 일정·없는 일정·완료된 일정은 거절한다.
 */
class ScheduleCancelServiceTest {
    final ScheduleRepository schedules = mock(ScheduleRepository.class);
    final UserRepository users = mock(UserRepository.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final ScheduleService service = new ScheduleService(schedules, users, events);

    Schedule owned(long userId) {
        User user = User.builder().email("owner@ddubi.com").nickname("주인").build();
        ReflectionTestUtils.setField(user, "id", userId);
        LocationSnapshot origin = new LocationSnapshot("강남역", new BigDecimal("37.4979526"), new BigDecimal("127.0276241"));
        LocationSnapshot destination = new LocationSnapshot("광화문", new BigDecimal("37.5716229"), new BigDecimal("126.9767879"));
        return Schedule.builder().user(user).title("회의").origin(origin).destination(destination)
                .arriveBy(LocalDateTime.now().plusDays(1)).considerLastTrip(false).build();
    }

    @Test void ownerCancelIsSoftAndStopsEvaluationThroughEvent() {
        Schedule schedule = owned(1L);
        when(schedules.findForUpdate(10L)).thenReturn(Optional.of(schedule));

        service.cancel(1L, 10L);

        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.CANCELED);
        verify(schedules).flush();
        verify(events).publishEvent(new ScheduleInputChanged(10L));
        // 같은 요청이 다시 와도(재삭제) 실패하지 않는다 — 멱등.
        service.cancel(1L, 10L);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.CANCELED);
    }

    @Test void strangerCannotCancelAndNothingChanges() {
        Schedule schedule = owned(1L);
        when(schedules.findForUpdate(10L)).thenReturn(Optional.of(schedule));

        assertThatThrownBy(() -> service.cancel(2L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN_RESOURCE);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.UPCOMING);
        verify(events, never()).publishEvent(any());
        verify(schedules, never()).flush();
    }

    @Test void missingScheduleIsNotFoundAndDoneScheduleIsConflict() {
        when(schedules.findForUpdate(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancel(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND);

        Schedule done = owned(1L);
        ReflectionTestUtils.setField(done, "status", ScheduleStatus.DONE);
        when(schedules.findForUpdate(11L)).thenReturn(Optional.of(done));
        assertThatThrownBy(() -> service.cancel(1L, 11L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
        assertThat(done.getStatus()).isEqualTo(ScheduleStatus.DONE);
        verify(events, never()).publishEvent(any());
    }
}
