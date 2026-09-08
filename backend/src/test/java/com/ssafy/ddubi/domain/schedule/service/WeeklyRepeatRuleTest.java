package com.ssafy.ddubi.domain.schedule.service;

import com.ssafy.ddubi.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RRULE ↔ CSV 변환. API 계약(rrule)과 DB 저장(CSV)의 유일한 다리라 여기가 틀리면 전부 틀린다. */
class WeeklyRepeatRuleTest {

    @Test
    @DisplayName("FREQ=WEEKLY;BYDAY 를 정렬된 CSV 로 바꾼다")
    void parseAndSort() {
        WeeklyRepeatRule rule = WeeklyRepeatRule.fromRrule("FREQ=WEEKLY;BYDAY=FR,MO,WE");

        assertThat(rule.toCsv()).isEqualTo("MON,WED,FRI");
        assertThat(rule.toRrule()).isEqualTo("FREQ=WEEKLY;BYDAY=MO,WE,FR");
    }

    @Test
    @DisplayName("중복 요일은 하나로 합쳐진다 (명세서 13장 규칙 8)")
    void deduplicates() {
        assertThat(WeeklyRepeatRule.fromRrule("FREQ=WEEKLY;BYDAY=MO,MO,MO").toCsv())
                .isEqualTo("MON");
    }

    @Test
    @DisplayName("소문자·공백이 섞여도 파싱된다")
    void lenientFormat() {
        assertThat(WeeklyRepeatRule.fromRrule("freq=weekly;byday=mo, tu").toCsv())
                .isEqualTo("MON,TUE");
    }

    @Test
    @DisplayName("INTERVAL=1 은 허용, 격주(INTERVAL=2)는 거부한다")
    void intervalPolicy() {
        assertThat(WeeklyRepeatRule.fromRrule("FREQ=WEEKLY;INTERVAL=1;BYDAY=MO").toCsv())
                .isEqualTo("MON");
        assertThatThrownBy(() -> WeeklyRepeatRule.fromRrule("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("주간 반복이 아니면 거부한다 — DB 가 표현하지 못한다")
    void rejectsNonWeekly() {
        assertThatThrownBy(() -> WeeklyRepeatRule.fromRrule("FREQ=DAILY"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> WeeklyRepeatRule.fromRrule("FREQ=MONTHLY;BYDAY=MO"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> WeeklyRepeatRule.fromRrule("아무말"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("BYDAY 가 없거나 요일이 틀리면 거부한다")
    void rejectsBadByday() {
        assertThatThrownBy(() -> WeeklyRepeatRule.fromRrule("FREQ=WEEKLY"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> WeeklyRepeatRule.fromRrule("FREQ=WEEKLY;BYDAY=XX"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("CSV 왕복 변환이 값을 보존한다")
    void csvRoundTrip() {
        assertThat(WeeklyRepeatRule.fromCsv("MON,TUE,WED,THU,FRI,SAT,SUN").toCsv())
                .isEqualTo("MON,TUE,WED,THU,FRI,SAT,SUN");
    }

    @Test
    @DisplayName("다음 발생일 — 규칙에 맞는 가장 이른 날짜, 종료일을 넘기면 null")
    void nextOccurrence() {
        WeeklyRepeatRule rule = WeeklyRepeatRule.fromRrule("FREQ=WEEKLY;BYDAY=MO");
        LocalDate wednesday = LocalDate.of(2026, 9, 2); // 수요일

        assertThat(rule.nextOccurrence(wednesday, null)).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(rule.nextOccurrence(wednesday, LocalDate.of(2026, 9, 5))).isNull();
        // 당일이 규칙에 맞으면 당일이다.
        assertThat(rule.nextOccurrence(LocalDate.of(2026, 9, 7), null)).isEqualTo(LocalDate.of(2026, 9, 7));
    }
}
