package com.ssafy.ddubi.domain.recommendation.evaluation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 저장된 경로 후보를 대상으로 일정 재평가 시각만 결정하는 순수 정책이다. 수집 주기나 사용자 여유시간이 아니다. 외부 경로 후보는 일정 입력이 바뀔 때 저장소 캐시가 폐기된
 * 경우에만 Workflow가 다시 조회한다. 운영값은 호출자가 주입하고 Replay에서도 고정한다. 취소·완료·실제 출발 후 감시는 호출자가 중단한다.
 *
 * <p>감시 창(출발 {@code startBeforeDeparture} 전)이 열리기 전에는 {@link PreWatch} 가 정한 간격으로만 계산한다. 출발이 멀수록 드물게
 * 계산한다(2026-09-25 결정).
 */
public record ScheduleMonitoringPolicy(
        Duration startBeforeDeparture,
        Duration mediumWindow,
        Duration nearWindow,
        Duration farInterval,
        Duration mediumInterval,
        Duration nearInterval,
        PreWatch preWatch) {
    public ScheduleMonitoringPolicy {
        for (Duration value :
                new Duration[] {
                    startBeforeDeparture,
                    mediumWindow,
                    nearWindow,
                    farInterval,
                    mediumInterval,
                    nearInterval
                }) {
            Objects.requireNonNull(value);
            if (value.isNegative() || value.isZero())
                throw new IllegalArgumentException("감시 주기는 양수여야 합니다.");
        }
        Objects.requireNonNull(preWatch);
        if (startBeforeDeparture.compareTo(mediumWindow) < 0
                || mediumWindow.compareTo(nearWindow) < 0
                || preWatch.halfHourlyWindow().compareTo(startBeforeDeparture) < 0) {
            throw new IllegalArgumentException("감시 시간 구간의 순서가 잘못됐습니다.");
        }
    }

    /** 감시 창 전 간격은 기본값({@link PreWatch#DEFAULT})을 쓴다. */
    public ScheduleMonitoringPolicy(
            Duration startBeforeDeparture,
            Duration mediumWindow,
            Duration nearWindow,
            Duration farInterval,
            Duration mediumInterval,
            Duration nearInterval) {
        this(
                startBeforeDeparture,
                mediumWindow,
                nearWindow,
                farInterval,
                mediumInterval,
                nearInterval,
                PreWatch.DEFAULT);
    }

    /**
     * 감시 창이 열리기 전의 정기 계산 간격.
     *
     * <p>예전에는 창이 열릴 때까지 기다리게 되어 있었지만, 결과의 기본 유효시간(10분)이 끝날 때마다 다시 계산돼 몇 주 뒤 반복 일정까지 10분마다 계산됐다(하루 약
     * 1,300번, DB 하루 1GB 넘게 증가). 그렇다고 창이 열릴 때까지 아예 계산하지 않으면 전날 생긴 통제 같은 변화를 하루 종일 모른다. 사고가 나면 일정을 바로
     * 깨우는 장치는 그 신호를 만드는 작업이 운영에서 빠져 지금은 동작하지 않으므로, 정기 계산이 유일한 반영 경로다. 그래서 가까울수록 자주 계산한다.
     *
     * <ul>
     *   <li>출발 {@code dailyWindow}(3일)보다 멀면: 정기 계산 없음. 등록·수정할 때만 계산하고 3일 전에 깨운다.
     *   <li>3일~{@code hourlyWindow}(24시간) 전: {@code dailyInterval}(하루)마다.
     *   <li>24시간~{@code halfHourlyWindow}(3시간) 전: {@code hourlyInterval}(1시간)마다.
     *   <li>3시간~감시 창(1시간) 전: {@code halfHourlyInterval}(30분)마다.
     * </ul>
     */
    public record PreWatch(
            Duration dailyWindow,
            Duration dailyInterval,
            Duration hourlyWindow,
            Duration hourlyInterval,
            Duration halfHourlyWindow,
            Duration halfHourlyInterval) {
        public static final PreWatch DEFAULT =
                new PreWatch(
                        Duration.ofDays(3),
                        Duration.ofDays(1),
                        Duration.ofHours(24),
                        Duration.ofHours(1),
                        Duration.ofHours(3),
                        Duration.ofMinutes(30));

        public PreWatch {
            for (Duration value :
                    new Duration[] {
                        dailyWindow,
                        dailyInterval,
                        hourlyWindow,
                        hourlyInterval,
                        halfHourlyWindow,
                        halfHourlyInterval
                    }) {
                Objects.requireNonNull(value);
                if (value.isNegative() || value.isZero())
                    throw new IllegalArgumentException("감시 주기는 양수여야 합니다.");
            }
            if (dailyWindow.compareTo(hourlyWindow) < 0
                    || hourlyWindow.compareTo(halfHourlyWindow) < 0) {
                throw new IllegalArgumentException("감시 시간 구간의 순서가 잘못됐습니다.");
            }
        }

        /** 감시 창 시작({@code watchStart}) 전의 다음 계산 시각. 구간 경계를 건너뛰지 않도록 경계시각에서 깨운다. */
        Instant next(Instant now, Instant departure, Instant watchStart) {
            Instant daily = departure.minus(dailyWindow);
            if (now.isBefore(daily)) return daily;
            Instant hourly = departure.minus(hourlyWindow);
            if (now.isBefore(hourly)) return earlier(now.plus(dailyInterval), hourly);
            Instant halfHourly = departure.minus(halfHourlyWindow);
            if (now.isBefore(halfHourly)) return earlier(now.plus(hourlyInterval), halfHourly);
            return earlier(now.plus(halfHourlyInterval), watchStart);
        }
    }

    /**
     * 조기 출발 제안이 있으면 수락 전에도 그 시각에 맞춰 재확인한다. 구간 경계 직전의 계산이 다음 구간을 건너뛰지 않도록 경계시각에서 깨운다. 과거 시각을 반환해 반복
     * 실행하지 않는다.
     */
    public Instant nextEvaluation(Instant now, Instant plannedDepartAt, Instant proposedDepartAt) {
        Instant departure =
                proposedDepartAt != null && proposedDepartAt.isBefore(plannedDepartAt)
                        ? proposedDepartAt
                        : plannedDepartAt;
        Instant start = departure.minus(startBeforeDeparture);
        if (now.isBefore(start)) return preWatch.next(now, departure, start);
        Instant medium = departure.minus(mediumWindow);
        Instant near = departure.minus(nearWindow);
        if (now.isBefore(medium)) return earlier(now.plus(farInterval), medium);
        if (now.isBefore(near)) return earlier(now.plus(mediumInterval), near);
        // 출발 확인이 없으면 출발했다고 추측하지 않는다. 호출자가 상태를 확인하며 한 주기 뒤 재평가한다.
        return now.isBefore(departure)
                ? earlier(now.plus(nearInterval), departure)
                : now.plus(nearInterval);
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
