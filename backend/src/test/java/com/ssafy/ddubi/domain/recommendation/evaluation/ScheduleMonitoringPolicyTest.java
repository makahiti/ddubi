package com.ssafy.ddubi.domain.recommendation.evaluation;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 감시 경계를 건너뛰거나 조기 출발 제안보다 늦게 깨우는 운영 오류를 막는다. */
class ScheduleMonitoringPolicyTest {
    final ScheduleMonitoringPolicy policy = new ScheduleMonitoringPolicy(Duration.ofHours(1), Duration.ofMinutes(30),
            Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofMinutes(15));
    final Instant departure = Instant.parse("2026-09-10T00:00:00Z");
    @Test void wakesAtBoundaryAndUsesEarlierProposal() {
        assertThat(policy.nextEvaluation(departure.minusSeconds(1900), departure, null)).isEqualTo(departure.minusSeconds(1800));
        assertThat(policy.nextEvaluation(departure.minusSeconds(7200), departure, departure.minusSeconds(600)))
                .isEqualTo(departure.minusSeconds(4200));
    }
    @Test void pollingDoesNotForceProviderRefreshAndExpiryIsExclusive() {
        assertThat(policy.refreshCandidates(departure, departure.minusSeconds(899), false)).isFalse();
        assertThat(policy.refreshCandidates(departure, departure.minusSeconds(900), false)).isTrue();
        assertThat(policy.refreshCandidates(departure, departure, true)).isTrue();
        assertThat(policy.nextEvaluation(departure, departure, null)).isAfter(departure);
    }
}
