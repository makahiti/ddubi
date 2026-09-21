package com.ssafy.ddubi.domain.schedule.service;

import com.ssafy.ddubi.domain.schedule.dto.request.LocationRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.RepeatCreateRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.RepeatUpdateRequest;
import com.ssafy.ddubi.domain.schedule.dto.response.RepeatCreateResponse;
import com.ssafy.ddubi.domain.schedule.dto.response.RepeatResponse;
import com.ssafy.ddubi.domain.schedule.dto.response.RepeatUpdateResponse;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleRepeat;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepeatRepository;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.common.PageResponse;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.util.BusinessTime;
import com.ssafy.ddubi.global.util.SupportedServiceArea;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반복 일정 (REPEAT-001~005). 도메인 규칙 5의 2단 구조 — 이 마스터가 규칙을 들고, {@link Schedule} 인스턴스를 찍어낸다.
 *
 * <h3>인스턴스 선생성(materialization)</h3>
 *
 * 생성 시점에 오늘부터 {@code horizonDays} 일치 인스턴스를 만든다. 무기한 반복을 전부 만들 수는 없고, 조회 시점 계산은 추천·알림이 붙을 실체 행이 없어져서
 * 안 된다. 지평선 너머 연장은 Phase 4 의 일일 잡이 맡는다(그때 이 클래스의 생성 로직을 재사용).
 */
@Service
public class ScheduleRepeatService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRepeatService.class);

    private final ScheduleRepeatRepository repeatRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final int horizonDays;

    public ScheduleRepeatService(
            ScheduleRepeatRepository repeatRepository,
            ScheduleRepository scheduleRepository,
            UserRepository userRepository,
            @Value("${ddubi.schedule.repeat-horizon-days}") int horizonDays) {
        this.repeatRepository = repeatRepository;
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository;
        this.horizonDays = horizonDays;
    }

    // ------------------------------------------------------------------
    // REPEAT-001 생성
    // ------------------------------------------------------------------

    @Transactional
    public RepeatCreateResponse create(Long userId, RepeatCreateRequest request) {
        requireInServiceArea(request.origin());
        requireInServiceArea(request.destination());
        WeeklyRepeatRule rule = WeeklyRepeatRule.fromRrule(request.rrule());
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "endDate 는 startDate 이후여야 합니다.");
        }

        ScheduleRepeat repeat =
                repeatRepository.save(
                        ScheduleRepeat.builder()
                                .user(userRepository.getReferenceById(userId))
                                .title(request.title())
                                .daysOfWeek(rule.toCsv())
                                .arriveTime(BusinessTime.normalize(request.localTime()))
                                .departTime(BusinessTime.normalize(request.departTime()))
                                .startDate(request.startDate())
                                .untilDate(request.endDate())
                                .origin(request.origin().toSnapshot())
                                .destination(request.destination().toSnapshot())
                                .importance(request.importance())
                                .considerLastTrip(Boolean.TRUE.equals(request.considerLastTrip()))
                                .build());

        int generated = materialize(repeat, BusinessTime.now());
        log.info("반복 일정 생성 — repeatId={}, 인스턴스 {}건", repeat.getId(), generated);
        return new RepeatCreateResponse(repeat.getId(), generated, nextOccurrenceAt(repeat));
    }

    // ------------------------------------------------------------------
    // REPEAT-002 목록 / REPEAT-003 상세
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<RepeatResponse> list(Long userId, boolean activeOnly, Pageable pageable) {
        var page =
                activeOnly
                        ? repeatRepository.findAllByUserIdAndActiveTrue(userId, pageable)
                        : repeatRepository.findAllByUserId(userId, pageable);
        return PageResponse.of(page, r -> RepeatResponse.of(r, nextOccurrenceAt(r)));
    }

    @Transactional(readOnly = true)
    public RepeatResponse get(Long userId, Long repeatId) {
        ScheduleRepeat repeat = findOwned(userId, repeatId);
        return RepeatResponse.of(repeat, nextOccurrenceAt(repeat));
    }

    // ------------------------------------------------------------------
    // REPEAT-004 수정 — 미래 미수정(customized=false) 인스턴스를 지우고 다시 만든다
    // ------------------------------------------------------------------

    @Transactional
    public RepeatUpdateResponse update(Long userId, Long repeatId, RepeatUpdateRequest request) {
        if (request.hasNoChanges()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "수정할 필드가 없습니다.");
        }
        ScheduleRepeat repeat = findOwned(userId, repeatId);
        if (!repeat.isActive()) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "중지된 반복 일정은 수정할 수 없습니다.");
        }

        if (request.isTitlePresent()) {
            requireNotNull(request.getTitle(), "title");
            repeat.changeTitle(request.getTitle());
        }
        if (request.isOriginPresent()) {
            requireNotNull(request.getOrigin(), "origin");
            requireInServiceArea(request.getOrigin());
            repeat.changeOrigin(request.getOrigin().toSnapshot());
        }
        if (request.isDestinationPresent()) {
            requireNotNull(request.getDestination(), "destination");
            requireInServiceArea(request.getDestination());
            repeat.changeDestination(request.getDestination().toSnapshot());
        }
        if (request.isImportancePresent()) {
            requireNotNull(request.getImportance(), "importance");
            repeat.changeImportance(request.getImportance());
        }
        if (request.isConsiderLastTripPresent()) {
            requireNotNull(request.getConsiderLastTrip(), "considerLastTrip");
            repeat.changeConsiderLastTrip(request.getConsiderLastTrip());
        }

        // 규칙 관련 필드는 묶어서 반영한다 — 일부만 오면 기존 값과 조합.
        WeeklyRepeatRule rule =
                request.isRrulePresent()
                        ? WeeklyRepeatRule.fromRrule(request.getRrule())
                        : WeeklyRepeatRule.fromCsv(repeat.getDaysOfWeek());
        var arriveTime =
                request.isLocalTimePresent()
                        ? BusinessTime.normalize(request.getLocalTime())
                        : repeat.getArriveTime();
        // 출발시각 도입 이전 규칙은 departTime 이 null 이다. 안 보내면 null 그대로 두고,
        // 보내면 null 은 거부한다 — "출발시각을 지운다" 는 계산을 못 하게 만드는 것이라
        // 사용자가 의도할 동작이 아니다.
        var departTime =
                request.isDepartTimePresent()
                        ? BusinessTime.normalize(request.getDepartTime())
                        : repeat.getDepartTime();
        var startDate =
                request.isStartDatePresent() ? request.getStartDate() : repeat.getStartDate();
        // endDate 는 명시적 null = "무기한으로 변경" 이라 present 면 null 도 그대로 반영한다.
        var untilDate = request.isEndDatePresent() ? request.getEndDate() : repeat.getUntilDate();
        requireNotNull(arriveTime, "localTime");
        requireNotNull(startDate, "startDate");
        if (request.isDepartTimePresent()) {
            requireNotNull(departTime, "departTime");
        }
        if (untilDate != null && untilDate.isBefore(startDate)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "endDate 는 startDate 이후여야 합니다.");
        }
        repeat.changeRule(rule.toCsv(), arriveTime, departTime, startDate, untilDate);

        int regenerated = 0;
        if (request.affectsInstances()) {
            LocalDateTime now = BusinessTime.now();
            // 규칙 5: customized=true 인스턴스는 사용자가 손댄 것 — 지우지도 다시 만들지도 않는다.
            int deleted = scheduleRepository.deleteRegenerableInstances(repeatId, now);
            regenerated = materialize(repeat, now);
            log.info("반복 일정 수정 — repeatId={}, 삭제 {}건 → 재생성 {}건", repeatId, deleted, regenerated);
        }
        return new RepeatUpdateResponse(
                RepeatResponse.of(repeat, nextOccurrenceAt(repeat)), regenerated);
    }

    // ------------------------------------------------------------------
    // REPEAT-005 중지 — 행 삭제가 아니라 비활성화. 기존 인스턴스는 유지(규칙 5)
    // ------------------------------------------------------------------

    @Transactional
    public void deactivate(Long userId, Long repeatId) {
        ScheduleRepeat repeat = findOwned(userId, repeatId);
        repeat.deactivate();
        log.info("반복 일정 중지 — repeatId={}", repeatId);
    }

    // ------------------------------------------------------------------
    // 인스턴스 선생성 — Phase 4 연장 잡도 이 메서드를 쓴다
    // ------------------------------------------------------------------

    /**
     * @return 새로 만든 인스턴스 수
     */
    @Transactional
    public int materialize(ScheduleRepeat repeat, LocalDateTime now) {
        WeeklyRepeatRule rule = WeeklyRepeatRule.fromCsv(repeat.getDaysOfWeek());
        LocalDate first =
                repeat.getStartDate().isAfter(now.toLocalDate())
                        ? repeat.getStartDate()
                        : now.toLocalDate();
        LocalDate last = now.toLocalDate().plusDays(horizonDays);
        if (repeat.getUntilDate() != null && repeat.getUntilDate().isBefore(last)) {
            last = repeat.getUntilDate();
        }

        // UNIQUE(repeat_id, arrive_by) 가 최후 방어선이지만, 위반 전에 걸러야
        // 배치 삽입이 통째로 구르지 않는다.
        Set<LocalDateTime> existing =
                new HashSet<>(scheduleRepository.findArriveBysByRepeatId(repeat.getId()));

        int created = 0;
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            if (!rule.matches(date)) {
                continue;
            }
            LocalDateTime arriveBy = date.atTime(repeat.getArriveTime());
            // 오늘 날짜라도 도착 시각이 이미 지났으면 만들지 않는다.
            if (!arriveBy.isAfter(now) || existing.contains(arriveBy)) {
                continue;
            }
            Schedule instance =
                    scheduleRepository.save(
                            Schedule.builder()
                                    .user(repeat.getUser())
                                    .repeat(repeat)
                                    .title(repeat.getTitle())
                                    .origin(repeat.getOrigin())
                                    .destination(repeat.getDestination())
                                    .arriveBy(arriveBy)
                                    .importance(repeat.getImportance())
                                    .considerLastTrip(repeat.isConsiderLastTrip())
                                    .build());
            // 빌더로 plannedDepartAt 을 직접 넣지 않는다. 그러면 plan_source 가
            // LEGACY_UNKNOWN 으로, user_input_depart_at 이 NULL 로 남아 출처를 속인다.
            // 이 값은 사용자가 반복 규칙에 직접 넣은 것이므로 USER_INPUT 이 맞다.
            LocalDateTime departAt = repeat.departAtOn(date);
            if (departAt != null) {
                instance.changePlannedDepartAt(BusinessTime.normalize(departAt));
            }
            created++;
        }
        return created;
    }

    /** 다음 발생 시각 — 지평선과 무관하게 규칙으로 계산한다(무기한이면 항상 존재). */
    private LocalDateTime nextOccurrenceAt(ScheduleRepeat repeat) {
        if (!repeat.isActive()) {
            return null;
        }
        LocalDateTime now = BusinessTime.now();
        WeeklyRepeatRule rule = WeeklyRepeatRule.fromCsv(repeat.getDaysOfWeek());
        LocalDate from =
                repeat.getStartDate().isAfter(now.toLocalDate())
                        ? repeat.getStartDate()
                        : now.toLocalDate();
        // 오늘인데 도착 시각이 지났으면 다음 날부터 찾는다.
        if (from.equals(now.toLocalDate()) && !now.toLocalTime().isBefore(repeat.getArriveTime())) {
            from = from.plusDays(1);
        }
        LocalDate next = rule.nextOccurrence(from, repeat.getUntilDate());
        return next == null ? null : next.atTime(repeat.getArriveTime());
    }

    private ScheduleRepeat findOwned(Long userId, Long repeatId) {
        ScheduleRepeat repeat =
                repeatRepository
                        .findById(repeatId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.SCHEDULE_REPEAT_NOT_FOUND));
        if (!repeat.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return repeat;
    }

    private void requireInServiceArea(LocationRequest location) {
        if (!SupportedServiceArea.contains(location.lat(), location.lng())) {
            throw new BusinessException(ErrorCode.OUT_OF_SERVICE_AREA, "수도권과 부산 지역만 서비스합니다.");
        }
    }

    private void requireNotNull(Object value, String field) {
        if (value == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, field + " 는 null 일 수 없습니다.");
        }
    }
}
