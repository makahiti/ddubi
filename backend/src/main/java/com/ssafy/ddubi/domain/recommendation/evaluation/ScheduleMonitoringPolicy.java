package com.ssafy.ddubi.domain.recommendation.evaluation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 일정 재평가 시각과 외부 후보 재조회를 분리하는 순수 정책이다. 수집 주기나 사용자 여유시간이 아니다.
 * 운영값은 호출자가 주입하고 Replay에서도 고정한다. 취소·완료·실제 출발 후 감시는 호출자가 중단한다.
 */
public record ScheduleMonitoringPolicy(Duration startBeforeDeparture, Duration mediumWindow,
        Duration nearWindow, Duration farInterval, Duration mediumInterval, Duration nearInterval,
        Duration candidateTtl) {
    public ScheduleMonitoringPolicy {
        for (Duration value : new Duration[]{startBeforeDeparture, mediumWindow, nearWindow,
                farInterval, mediumInterval, nearInterval, candidateTtl}) {
            Objects.requireNonNull(value);
            if (value.isNegative() || value.isZero()) throw new IllegalArgumentException("감시 주기는 양수여야 합니다.");
        }
        if (startBeforeDeparture.compareTo(mediumWindow) < 0 || mediumWindow.compareTo(nearWindow) < 0) {
            throw new IllegalArgumentException("감시 시간 구간의 순서가 잘못됐습니다.");
        }
    }

    /**
     * 조기 출발 제안이 있으면 수락 전에도 그 시각에 맞춰 재확인한다. 구간 경계 직전의 계산이
     * 다음 구간을 건너뛰지 않도록 경계시각에서 깨운다. 과거 시각을 반환해 반복 실행하지 않는다.
     */
    public Instant nextEvaluation(Instant now, Instant plannedDepartAt, Instant proposedDepartAt) {
        Instant departure = proposedDepartAt != null && proposedDepartAt.isBefore(plannedDepartAt)
                ? proposedDepartAt : plannedDepartAt;
        Instant start = departure.minus(startBeforeDeparture);
        if (now.isBefore(start)) return start;
        Instant medium = departure.minus(mediumWindow);
        Instant near = departure.minus(nearWindow);
        if (now.isBefore(medium)) return earlier(now.plus(farInterval), medium);
        if (now.isBefore(near)) return earlier(now.plus(mediumInterval), near);
        // 출발 확인이 없으면 출발했다고 추측하지 않는다. 호출자가 상태를 확인하며 한 주기 뒤 재평가한다.
        return now.isBefore(departure) ? earlier(now.plus(nearInterval), departure) : now.plus(nearInterval);
    }

    /** 정기 계산과 무관하게 후보 만료·출발지 변경·통제·대안 부족일 때만 경로 API를 재조회한다. */
    public boolean refreshCandidates(Instant now, Instant fetchedAt, boolean routeInvalidated) {
        return routeInvalidated || fetchedAt == null || fetchedAt.isAfter(now)
                || !now.isBefore(fetchedAt.plus(candidateTtl));
    }

    private Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
