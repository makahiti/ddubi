package com.ssafy.ddubi.domain.schedule.service;

import com.ssafy.ddubi.domain.schedule.dto.response.HomeResponse;
import com.ssafy.ddubi.domain.schedule.dto.response.ScheduleResponse;
import com.ssafy.ddubi.domain.schedule.entity.ScheduleStatus;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * HOME-001 홈 요약.
 *
 * unreadNotificationCount 와 dataState 는 아직 엔티티가 없는 테이블
 * (notification — Phase 4, pipeline_config — Phase 5)을 읽는다. 도메인이 만들어지기
 * 전에 홈 화면이 먼저 필요해서, 여기서만 JdbcTemplate 단순 조회로 앞당겨 구현했다.
 * 각 Phase 에서 도메인이 생기면 그쪽 서비스 호출로 바꾼다.
 */
@Service
public class HomeService {

    private final ScheduleRepository scheduleRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Duration staleThreshold;

    public HomeService(ScheduleRepository scheduleRepository, JdbcTemplate jdbcTemplate,
                       @Value("${ddubi.baseline.stale-threshold}") Duration staleThreshold) {
        this.scheduleRepository = scheduleRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.staleThreshold = staleThreshold;
    }

    @Transactional(readOnly = true)
    public HomeResponse summary(Long userId, LocalDateTime at) {
        LocalDateTime now = at == null ? LocalDateTime.now() : at;

        ScheduleResponse next = scheduleRepository
                .findFirstByUserIdAndStatusAndArriveByGreaterThanEqualOrderByArriveBy(
                        userId, ScheduleStatus.UPCOMING, now)
                .map(s -> ScheduleResponse.from(s, now))
                .orElse(null);

        // 오늘 일정: 취소는 제외하고 완료는 포함한다 — "오늘 하루" 를 보여주는 화면이라
        // 이미 끝난 일정도 목록에 남아야 흐름이 보인다.
        List<ScheduleResponse> today = scheduleRepository
                .findAllByUserIdAndStatusAndArriveByBetweenOrderByArriveBy(
                        userId, ScheduleStatus.UPCOMING,
                        now.toLocalDate().atStartOfDay(), now.toLocalDate().atTime(23, 59, 59))
                .stream()
                .map(s -> ScheduleResponse.from(s, now))
                .toList();

        return new HomeResponse(next, today, unreadCount(userId), dataState(now));
    }

    /** read_at IS NULL 개수 = 안읽음 뱃지 (도메인 규칙 9). */
    private long unreadCount(Long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND read_at IS NULL",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    /**
     * 파이프라인 신선도. pipeline_config 의 마지막 활성화 시각으로 판단한다
     * (internal-api-review 3-2 계약). 이력이 아예 없으면 UNKNOWN —
     * 초기 개발·배치 미가동 상태를 STALE 경고와 구분한다.
     */
    private String dataState(LocalDateTime now) {
        List<String> values = jdbcTemplate.queryForList(
                "SELECT config_value FROM pipeline_config WHERE config_key = 'baseline_last_activated_at'",
                String.class);
        if (values.isEmpty()) {
            return "UNKNOWN";
        }
        try {
            LocalDateTime activatedAt = LocalDateTime.parse(values.get(0));
            return activatedAt.plus(staleThreshold).isBefore(now) ? "STALE" : "NORMAL";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
}
