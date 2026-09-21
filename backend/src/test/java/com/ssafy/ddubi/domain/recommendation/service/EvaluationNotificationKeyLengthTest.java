package com.ssafy.ddubi.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 알림 식별 키가 DB 칸을 넘지 않는지 지킨다.
 *
 * <p>{@code schedule_evaluation.notification_key} 와 {@code notification.suppress_key} 는 둘 다
 * {@code varchar(64)} 다. 알림이 한 번도 발송되지 않던 동안에는 이 경로가 실행되지 않아 넘침이
 * 드러나지 않았고, 설정으로 알림을 켜자마자 모든 평가가 ER_DATA_TOO_LONG 으로 실패했다
 * (2026-09-21 운영 확인).
 */
class EvaluationNotificationKeyLengthTest {

    /** 운영에서 실제로 관측한 journeyKey 다. 53자. */
    private static final String JOURNEY_KEY = "candidate-13-bus-fd4bf30e-4aa2-34fc-a5ad-87e5dbab638b";

    private static final int COLUMN_LIMIT = 64;

    @Test
    @DisplayName("경로 키 두 개를 이어 붙여도 키는 64자를 넘지 않는다")
    void routeChangeKeyFitsTheColumn() {
        String key =
                EvaluationNotificationPlanner.shortKey(
                        "ROUTE_CHANGE", JOURNEY_KEY + ">" + JOURNEY_KEY);

        assertThat(key).hasSizeLessThanOrEqualTo(COLUMN_LIMIT);
        assertThat(key).startsWith("ROUTE_CHANGE:");
    }

    @Test
    @DisplayName("나머지 알림 종류도 64자 안에 들어간다")
    void everyKindFitsTheColumn() {
        assertThat(EvaluationNotificationPlanner.shortKey("ON_TIME_UNLIKELY", JOURNEY_KEY))
                .hasSizeLessThanOrEqualTo(COLUMN_LIMIT);
        assertThat(
                        EvaluationNotificationPlanner.shortKey(
                                "EARLY_DEPART", JOURNEY_KEY + ":" + 5_555_555_555L))
                .hasSizeLessThanOrEqualTo(COLUMN_LIMIT);
    }

    @Test
    @DisplayName("같은 상황이면 같은 키다 — 중복 발송을 막는 근거")
    void sameSituationGivesSameKey() {
        assertThat(EvaluationNotificationPlanner.shortKey("ROUTE_CHANGE", JOURNEY_KEY))
                .isEqualTo(EvaluationNotificationPlanner.shortKey("ROUTE_CHANGE", JOURNEY_KEY));
    }

    @Test
    @DisplayName("경로가 다르면 키도 다르다 — 서로 다른 알림이 뭉개지지 않는다")
    void differentRoutesGiveDifferentKeys() {
        String other = "candidate-11-bus-f2f5cc2f-c4cb-3650-8d65-3d5cd7ba0636";

        assertThat(EvaluationNotificationPlanner.shortKey("ROUTE_CHANGE", JOURNEY_KEY))
                .isNotEqualTo(EvaluationNotificationPlanner.shortKey("ROUTE_CHANGE", other));
    }
}
