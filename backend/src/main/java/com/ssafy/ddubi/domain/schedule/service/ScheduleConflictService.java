package com.ssafy.ddubi.domain.schedule.service;

import com.ssafy.ddubi.domain.schedule.dto.response.ScheduleConflictResponse;
import com.ssafy.ddubi.domain.schedule.dto.response.ScheduleConflictResponse.Conflict;
import com.ssafy.ddubi.domain.schedule.entity.LocationSnapshot;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SCH-006 연속 일정 충돌 — 계산형 조회 (REQ-RSK-001). 저장 테이블 없음.
 *
 * 앞 일정 도착 시각부터 다음 일정 도착 시각까지가 가용 시간이고, 앞 목적지 → 다음
 * 출발지 이동 시간이 필요 시간이다. 부족(shortage > 0)한 쌍만 돌려준다.
 *
 * <h3>requiredMin 이 추정치인 이유</h3>
 * 선택 경로(route)는 Phase 3 산출물이라 아직 없다. 그때까지는 좌표 직선거리에
 * 대중교통 체감 속도를 적용해 추정한다. 추정이 정확하지 않아도 "물리적으로 불가능한
 * 연속 일정"을 등록 직후 경고하는 목적은 달성된다. Phase 3 에서 선택 경로의 실제
 * 소요시간으로 대체한다.
 */
@Service
public class ScheduleConflictService {

    /** 대중교통 문전(door-to-door) 체감 속도. 직선거리 기준이라 보수적으로 잡았다. */
    private static final double TRANSIT_SPEED_KMH = 18.0;
    /** 환승 대기·건물 진출입 고정 비용(분). */
    private static final long FIXED_OVERHEAD_MIN = 10;
    /** 이 이상 부족하면 CRITICAL. */
    private static final long CRITICAL_SHORTAGE_MIN = 10;
    /** 같은 건물 취급 — 이 미만이면 이동 시간 0 으로 본다(미터). */
    private static final double SAME_SPOT_METERS = 100;

    private final ScheduleRepository scheduleRepository;

    public ScheduleConflictService(ScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    @Transactional(readOnly = true)
    public ScheduleConflictResponse findConflicts(Long userId, LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "from 은 to 보다 앞선 시각이어야 합니다.");
        }
        // 취소·완료 일정은 충돌의 당사자가 아니다 — UPCOMING 만 본다.
        List<Schedule> schedules = scheduleRepository
                .findAllByUserIdAndStatusAndArriveByBetweenOrderByArriveBy(
                        userId, ScheduleStatus.UPCOMING, from, to);

        List<Conflict> conflicts = new ArrayList<>();
        for (int i = 0; i + 1 < schedules.size(); i++) {
            Schedule prev = schedules.get(i);
            Schedule next = schedules.get(i + 1);

            long availableMin = Duration.between(prev.getArriveBy(), next.getArriveBy()).toMinutes();
            long requiredMin = estimateTravelMin(prev.getDestination(), next.getOrigin());
            long shortageMin = requiredMin - availableMin;
            if (shortageMin <= 0) {
                continue;
            }
            conflicts.add(new Conflict(prev.getId(), next.getId(),
                    availableMin, requiredMin, shortageMin,
                    shortageMin > CRITICAL_SHORTAGE_MIN ? "CRITICAL" : "WARNING"));
        }
        return new ScheduleConflictResponse(conflicts);
    }

    /** Phase 3 전 임시 추정. 하버사인 직선거리 → 체감 속도 + 고정 비용. */
    private long estimateTravelMin(LocationSnapshot fromLoc, LocationSnapshot toLoc) {
        double meters = haversineMeters(
                fromLoc.getLat().doubleValue(), fromLoc.getLng().doubleValue(),
                toLoc.getLat().doubleValue(), toLoc.getLng().doubleValue());
        if (meters < SAME_SPOT_METERS) {
            return 0;
        }
        double travelMin = meters / 1000.0 / TRANSIT_SPEED_KMH * 60.0;
        return Math.round(travelMin) + FIXED_OVERHEAD_MIN;
    }

    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthRadius = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
