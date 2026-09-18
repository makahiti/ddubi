package com.ssafy.ddubi.domain.schedule.service;

import com.ssafy.ddubi.domain.schedule.dto.request.LocationRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleCreateRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleUpdateRequest;
import com.ssafy.ddubi.domain.schedule.dto.response.ScheduleResponse;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.common.PageResponse;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.util.BusinessTime;
import com.ssafy.ddubi.global.util.SeoulArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 일정 인스턴스 (SCH-001~005).
 *
 * 이 클래스가 지키는 도메인 규칙:
 *  - 규칙 1 소유권 / 규칙 2 장소 스냅샷 / 규칙 5 개별수정 표시 /
 *    규칙 11 soft 취소 / 규칙 12 updated_at 낙관적 잠금
 *
 * 등록·수정은 PENDING을 반환한다. DB 감시기가 변경된 입력 버전을 찾아 별도 평가하며
 * 최신 결과는 recommendations/latest에서 조회한다.
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    /** 일정 저장과 외부 경로 계산을 분리하기 위한 초기 상태. */
    static final String RECOMMENDATION_PENDING = "PENDING";

    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final org.springframework.context.ApplicationEventPublisher events;

    public ScheduleService(ScheduleRepository scheduleRepository, UserRepository userRepository, org.springframework.context.ApplicationEventPublisher events) {
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository; this.events=events;
    }

    // ------------------------------------------------------------------
    // SCH-001 목록
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<ScheduleResponse> list(Long userId, LocalDateTime from, LocalDateTime to,
                                               ScheduleStatus status, Pageable pageable) {
        requireRange(from, to);
        LocalDateTime now = LocalDateTime.now();
        var page = status == null
                ? scheduleRepository.findAllByUserIdAndArriveByBetween(userId, from, to, pageable)
                : scheduleRepository.findAllByUserIdAndStatusAndArriveByBetween(userId, status, from, to, pageable);
        return PageResponse.of(page, s -> ScheduleResponse.from(s, now));
    }

    // ------------------------------------------------------------------
    // SCH-002 등록
    // ------------------------------------------------------------------

    @Transactional
    public ScheduleResponse create(Long userId, ScheduleCreateRequest request) {
        requireInSeoul(request.origin());
        requireInSeoul(request.destination());
        if (!request.arriveBy().isAfter(LocalDateTime.now())) {
            // 과거 도착 목표는 출발 시각 역산이 불가능하다 — 만들어봐야 추천이 나올 수 없다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "도착 목표 시각은 미래여야 합니다.");
        }

        validateDeparture(request.plannedDepartAt(), request.arriveBy());
        Schedule schedule = scheduleRepository.save(Schedule.builder()
                .user(userRepository.getReferenceById(userId))
                .title(request.title())
                .origin(request.origin().toSnapshot())
                .destination(request.destination().toSnapshot())
                .arriveBy(BusinessTime.normalize(request.arriveBy()))
                .importance(request.importance())
                .considerLastTrip(Boolean.TRUE.equals(request.considerLastTrip()))
                .build());
        schedule.changePlannedDepartAt(BusinessTime.normalize(request.plannedDepartAt()));
        scheduleRepository.flush();
        events.publishEvent(new com.ssafy.ddubi.domain.recommendation.service.ScheduleInputChanged(schedule.getId()));
        return ScheduleResponse.of(schedule, LocalDateTime.now(), RECOMMENDATION_PENDING);
    }

    // ------------------------------------------------------------------
    // SCH-003 상세
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ScheduleResponse get(Long userId, Long scheduleId) {
        // 일정 원본 조회. 최신 추천·선택 경로는 recommendations/latest가 별도로 제공한다.
        return ScheduleResponse.from(findOwned(userId, scheduleId), LocalDateTime.now());
    }

    // ------------------------------------------------------------------
    // SCH-004 수정 (낙관적 잠금)
    // ------------------------------------------------------------------

    @Transactional
    public ScheduleResponse update(Long userId, Long scheduleId, ScheduleUpdateRequest request) {
        if (request.hasNoChanges()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "수정할 필드가 없습니다.");
        }
        Schedule schedule = findOwnedForUpdate(userId, scheduleId);
        schedule.requireEditable();

        // 규칙 12: 조회 시점의 updatedAt 과 다르면 그 사이 다른 기기가 수정한 것이다.
        // 마지막 저장이 조용히 이기게 두면 사용자는 자기 수정이 사라진 이유를 모른다.
        if (!schedule.getUpdatedAt().equals(request.getUpdatedAt())) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "일정이 다른 곳에서 수정되었습니다. 새로 고침 후 다시 시도해 주세요.");
        }

        if (request.isTitlePresent()) {
            requireNotNull(request.getTitle(), "title");
            schedule.changeTitle(request.getTitle());
        }
        if (request.isOriginPresent()) {
            requireNotNull(request.getOrigin(), "origin");
            requireInSeoul(request.getOrigin());
            schedule.changeOrigin(request.getOrigin().toSnapshot());
        }
        if (request.isDestinationPresent()) {
            requireNotNull(request.getDestination(), "destination");
            requireInSeoul(request.getDestination());
            schedule.changeDestination(request.getDestination().toSnapshot());
        }
        if (request.isArriveByPresent()) {
            requireNotNull(request.getArriveBy(), "arriveBy");
            if (!request.getArriveBy().isAfter(LocalDateTime.now())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "도착 목표 시각은 미래여야 합니다.");
            }
            schedule.changeArriveBy(BusinessTime.normalize(request.getArriveBy()));
        }
        if (request.isImportancePresent()) {
            requireNotNull(request.getImportance(), "importance");
            schedule.changeImportance(request.getImportance());
        }
        if (request.isConsiderLastTripPresent()) {
            requireNotNull(request.getConsiderLastTrip(), "considerLastTrip");
            schedule.changeConsiderLastTrip(request.getConsiderLastTrip());
        }

        // 규칙 5: 반복 인스턴스를 개별 수정하면 반복 전체 수정에서 제외된다.
        schedule.markCustomizedIfRepeatInstance();

        if (request.isPlannedDepartAtPresent()) {
            requireNotNull(request.getPlannedDepartAt(), "plannedDepartAt");
            schedule.changePlannedDepartAt(BusinessTime.normalize(request.getPlannedDepartAt()));
        }
        validateDeparture(schedule.getPlannedDepartAt(), schedule.getArriveBy());

        scheduleRepository.flush();
        events.publishEvent(new com.ssafy.ddubi.domain.recommendation.service.ScheduleInputChanged(schedule.getId()));
        return ScheduleResponse.of(schedule, LocalDateTime.now(), RECOMMENDATION_PENDING);
    }

    // ------------------------------------------------------------------
    // SCH-005 취소 (soft — 규칙 11)
    // ------------------------------------------------------------------

    @Transactional
    public void cancel(Long userId, Long scheduleId) {
        Schedule schedule = findOwnedForUpdate(userId, scheduleId);
        schedule.cancel();
        scheduleRepository.flush();
        events.publishEvent(new com.ssafy.ddubi.domain.recommendation.service.ScheduleInputChanged(scheduleId));
        log.info("일정 취소 — scheduleId={}", scheduleId);
    }

    // ------------------------------------------------------------------

    private Schedule findOwned(Long userId, Long scheduleId) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        if (!schedule.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return schedule;
    }

    /** 일정 행을 먼저 잠그는 순서를 evaluation claim/complete와 맞춰 입력 변경 사이 덮어쓰기를 막는다. */
    private Schedule findOwnedForUpdate(Long userId,Long scheduleId) {
        Schedule schedule=scheduleRepository.findForUpdate(scheduleId).orElseThrow(()->new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        if(!schedule.isOwnedBy(userId)) throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        return schedule;
    }

    private void requireRange(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "from 은 to 보다 앞선 시각이어야 합니다.");
        }
    }

    private void requireInSeoul(LocationRequest location) {
        if (!SeoulArea.contains(location.lat(), location.lng())) {
            throw new BusinessException(ErrorCode.OUT_OF_SERVICE_AREA, "서울 지역만 서비스합니다.");
        }
    }

    private void requireNotNull(Object value, String field) {
        if (value == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, field + " 는 null 일 수 없습니다.");
        }
    }

    /** 기존 출발 미입력은 허용하되 출발/도착 순서를 검증한다. 이미 지난 출발은 재평가 때 현재시각으로 평가한다. */
    private void validateDeparture(LocalDateTime departure, LocalDateTime arrival) {
        if (departure != null && !departure.isBefore(arrival)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "출발시각은 도착 목표시각보다 앞서야 합니다.");
        }
    }
}
