package com.ssafy.ddubi.domain.recommendation.evaluation;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 지역별 수식이 문서의 손계산 결과와 일치하고 잘못된 지역·계수를 보수적으로 거절하는지 검증한다. */
class BusTravelTimeFormulaTest {
    @Test
    void seoulUsesWeightedSumWhenMlFallbackRuns() {
        var input =
                new BusTravelTimeFormula.Input(
                        BusTravelTimeFormula.SEOUL_HALF_LIFE,
                        250.0,
                        2.0,
                        null,
                        null,
                        null,
                        null,
                        null);

        assertThat(BusTravelTimeFormula.seconds("SEOUL_BUS", input)).isEqualTo(125.0);
    }

    @Test
    void metropolitanUsesLocalMeanAndFallsBackToSegmentHourFactor() {
        var observed =
                new BusTravelTimeFormula.Input(
                        BusTravelTimeFormula.METROPOLITAN_GTFS,
                        null,
                        null,
                        90.0,
                        3L,
                        100.0,
                        1.2,
                        null);
        var missingHour =
                new BusTravelTimeFormula.Input(
                        BusTravelTimeFormula.METROPOLITAN_GTFS,
                        null,
                        null,
                        null,
                        0L,
                        100.0,
                        1.2,
                        null);

        assertThat(BusTravelTimeFormula.seconds("GBIS", observed)).isEqualTo(90.0);
        assertThat(BusTravelTimeFormula.seconds("INCHEON_PUBLIC_CSV", missingHour))
                .isEqualTo(120.0);
    }

    @Test
    void busanUsesPoint25ShrinkageFormula() {
        var input =
                new BusTravelTimeFormula.Input(
                        BusTravelTimeFormula.BUSAN_GTFS, null, null, 100.0, 2L, 120.0, 1.5, 0.25);

        assertThat(BusTravelTimeFormula.seconds("BUSAN_GTFS", input))
                .isEqualTo((2 * 100.0 + 0.25 * 120.0 * 1.5) / 2.25);
    }

    @Test
    void rejectsWrongRegionAndUnapprovedBusanAlpha() {
        var metropolitan =
                new BusTravelTimeFormula.Input(
                        BusTravelTimeFormula.METROPOLITAN_GTFS,
                        null,
                        null,
                        100.0,
                        1L,
                        100.0,
                        1.0,
                        null);
        var busan =
                new BusTravelTimeFormula.Input(
                        BusTravelTimeFormula.BUSAN_GTFS, null, null, 100.0, 1L, 100.0, 1.0, 0.5);

        assertThatThrownBy(() -> BusTravelTimeFormula.seconds("SEOUL_BUS", metropolitan))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BusTravelTimeFormula.seconds("BUSAN_GTFS", busan))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
