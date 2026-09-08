package com.ssafy.ddubi.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 요일 변환이 하루라도 밀리면 월요일 출근에 일요일 기준선을 쓰게 되고,
 * 그 결과 늦게 출발하라고 추천해 사용자를 지각시킨다. 예외가 안 나는 종류라 테스트로 고정한다.
 */
class BaselineDayOfWeekTest {

    @ParameterizedTest(name = "{0} → dow {1}")
    @CsvSource({
            "SUNDAY,    1",
            "MONDAY,    2",
            "TUESDAY,   3",
            "WEDNESDAY, 4",
            "THURSDAY,  5",
            "FRIDAY,    6",
            "SATURDAY,  7"
    })
    @DisplayName("Spark 기준(일=1)으로 변환한다")
    void convertsToSparkConvention(DayOfWeek dayOfWeek, int expected) {
        assertThat(BaselineDayOfWeek.from(dayOfWeek)).isEqualTo(expected);
    }

    @Test
    @DisplayName("실제 날짜로 확인 — 2026-09-01 은 화요일")
    void convertsRealDate() {
        LocalDate tuesday = LocalDate.of(2026, 9, 1);

        assertThat(tuesday.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(BaselineDayOfWeek.from(tuesday)).as("화요일은 Spark 기준 3").isEqualTo(3);
    }

    @Test
    @DisplayName("Java 기준 값을 그대로 쓰면 안 된다는 것을 명시한다")
    void differsFromJavaValue() {
        // 일요일: Java 7, baseline 1 — 가장 크게 어긋나는 지점
        assertThat(DayOfWeek.SUNDAY.getValue()).isEqualTo(7);
        assertThat(BaselineDayOfWeek.from(DayOfWeek.SUNDAY)).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"1, SUNDAY", "2, MONDAY", "7, SATURDAY"})
    @DisplayName("역변환도 맞는다")
    void convertsBack(int dow, DayOfWeek expected) {
        assertThat(BaselineDayOfWeek.toDayOfWeek(dow)).isEqualTo(expected);
    }

    @Test
    @DisplayName("모든 요일이 왕복 변환에서 보존된다")
    void roundTripsForEveryDay() {
        for (DayOfWeek day : DayOfWeek.values()) {
            assertThat(BaselineDayOfWeek.toDayOfWeek(BaselineDayOfWeek.from(day))).isEqualTo(day);
        }
    }

    @ParameterizedTest
    @CsvSource({"0", "8", "-1"})
    @DisplayName("범위를 벗어난 dow 는 거부한다")
    void rejectsOutOfRange(int dow) {
        assertThatThrownBy(() -> BaselineDayOfWeek.toDayOfWeek(dow))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
