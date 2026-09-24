package com.ssafy.ddubi.domain.recommendation.evaluation;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;

/** 감시 경계를 건너뛰거나 조기 출발 제안보다 늦게 깨우는 운영 오류를 막는다. */
class ScheduleMonitoringPolicyTest {
    final ScheduleMonitoringPolicy policy =
            new ScheduleMonitoringPolicy(
                    Duration.ofHours(1),
                    Duration.ofMinutes(30),
                    Duration.ofMinutes(10),
                    Duration.ofMinutes(10),
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(1));
    final Instant departure = Instant.parse("2026-09-10T00:00:00Z");

    @Test
    void wakesAtBoundaryAndUsesEarlierProposal() {
        assertThat(policy.nextEvaluation(departure.minusSeconds(1900), departure, null))
                .isEqualTo(departure.minusSeconds(1800));
        // 제안 출발(-10분) 기준 1시간 50분 전이라 "3시간 안" 구간: 30분 뒤에 깨운다.
        assertThat(
                        policy.nextEvaluation(
                                departure.minusSeconds(7200),
                                departure,
                                departure.minusSeconds(600)))
                .isEqualTo(departure.minusSeconds(5400));
        // 감시 창 시작(제안 출발 1시간 전)이 30분보다 가까우면 그 경계에서 깨운다.
        assertThat(
                        policy.nextEvaluation(
                                departure.minusSeconds(5000),
                                departure,
                                departure.minusSeconds(600)))
                .isEqualTo(departure.minusSeconds(4200));
    }

    @Test
    void pollingContinuesAfterDepartureUntilJourneyStateStopsIt() {
        assertThat(policy.nextEvaluation(departure, departure, null)).isAfter(departure);
    }

    /**
     * 출발이 멀수록 드물게 계산한다(2026-09-25 결정). 예전에는 결과 유효시간(10분)이 끝날 때마다 계산돼 몇 주 뒤 반복 일정까지 10분마다 계산됐다(하루 약
     * 1,300번). 그렇다고 감시 창(1시간 전)까지 아예 안 하면 전날 생긴 통제를 모른다.
     */
    @Test
    void theFartherTheDepartureTheRarerTheRefresh() {
        // 3일보다 멀면 등록·수정 때만 계산하고, 3일 전에 깨운다.
        assertThat(next(Duration.ofDays(12))).isEqualTo(departure.minus(Duration.ofDays(3)));
        assertThat(next(Duration.ofDays(5))).isEqualTo(departure.minus(Duration.ofDays(3)));
        // 1~3일 전: 하루에 한 번. 24시간 경계는 건너뛰지 않는다.
        assertThat(next(Duration.ofHours(60))).isEqualTo(departure.minus(Duration.ofHours(36)));
        assertThat(next(Duration.ofHours(30))).isEqualTo(departure.minus(Duration.ofHours(24)));
        // 당일: 1시간마다. 3시간 경계는 건너뛰지 않는다.
        assertThat(next(Duration.ofHours(20))).isEqualTo(departure.minus(Duration.ofHours(19)));
        assertThat(next(Duration.ofMinutes(200))).isEqualTo(departure.minus(Duration.ofHours(3)));
        // 3시간 안: 30분마다. 감시 창(1시간 전) 경계에서 깨운다.
        assertThat(next(Duration.ofHours(2))).isEqualTo(departure.minus(Duration.ofMinutes(90)));
        assertThat(next(Duration.ofMinutes(70))).isEqualTo(departure.minus(Duration.ofHours(1)));
        // 1시간 안은 예전 그대로: 10분·5분·1분.
        assertThat(next(Duration.ofMinutes(50))).isEqualTo(departure.minus(Duration.ofMinutes(40)));
        assertThat(next(Duration.ofMinutes(20))).isEqualTo(departure.minus(Duration.ofMinutes(15)));
        assertThat(next(Duration.ofMinutes(5))).isEqualTo(departure.minus(Duration.ofMinutes(4)));
    }

    /**
     * 감시 창 전의 다음 계산은 10분보다 멀어서, 저장할 때 "근거 만료로 당기지 않는다" 를 구분할 수 있다. 창 안에서는 다음 계산이 항상 10분 이내라 평소처럼 근거
     * 만료에 맞춰 새로 계산한다.
     */
    @Test
    void storeCanTellPreWatchRefreshFromWatchWindow() {
        for (Duration before :
                new Duration[] {
                    Duration.ofDays(12),
                    Duration.ofHours(30),
                    Duration.ofHours(20),
                    Duration.ofHours(2)
                }) {
            Instant now = departure.minus(before);
            assertThat(ScheduleEvaluationV2.waitingForWatch(now, next(before)))
                    .as("출발 %s 전", before)
                    .isTrue();
        }
        for (Duration before :
                new Duration[] {
                    Duration.ofMinutes(65),
                    Duration.ofMinutes(59),
                    Duration.ofMinutes(25),
                    Duration.ofMinutes(3)
                }) {
            Instant now = departure.minus(before);
            assertThat(ScheduleEvaluationV2.waitingForWatch(now, next(before)))
                    .as("출발 %s 전", before)
                    .isFalse();
        }
    }

    private Instant next(Duration beforeDeparture) {
        return policy.nextEvaluation(departure.minus(beforeDeparture), departure, null);
    }
}
