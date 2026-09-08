package com.ssafy.ddubi.global.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code baseline_serving.dow} 값 변환.
 *
 * <h3>왜 변환이 필요한가</h3>
 * 두 체계의 시작 요일이 다르다.
 * <pre>
 *   Spark dayofweek()      일=1, 월=2, ... 토=7    ← baseline_serving.dow 가 이 기준
 *   Java  DayOfWeek        월=1, 화=2, ... 일=7
 * </pre>
 *
 * 팀 합의(2026-09-01): <b>데이터를 만드는 Spark 기준을 그대로 두고, 읽는 쪽(백엔드)이 변환</b>한다.
 * 양쪽에서 변환하면 언젠가 한쪽이 빠진다.
 *
 * <h3>틀리면 어떻게 되나</h3>
 * 하루씩 밀린 기준선을 읽는다. 월요일 출근에 일요일 값을 쓰면 소요시간이 짧게 나와
 * <b>늦게 출발하라고 추천하고 사용자를 지각시킨다.</b> 예외도 로그도 남지 않는다.
 * 그래서 변환을 이 클래스 하나에 가둔다 — 호출부에서 직접 계산하지 말 것.
 */
public final class BaselineDayOfWeek {

    private BaselineDayOfWeek() {
    }

    /** Java 요일 → baseline_serving.dow (일=1 … 토=7). */
    public static int from(DayOfWeek dayOfWeek) {
        // DayOfWeek: 월=1 … 일=7 → 일요일만 1로 접고 나머지는 +1
        return dayOfWeek == DayOfWeek.SUNDAY ? 1 : dayOfWeek.getValue() + 1;
    }

    public static int from(LocalDate date) {
        return from(date.getDayOfWeek());
    }

    /** 일정의 도착 시각처럼 날짜·시각을 함께 든 값에서 뽑을 때 쓴다. */
    public static int from(LocalDateTime dateTime) {
        return from(dateTime.getDayOfWeek());
    }

    /** baseline_serving.dow → Java 요일. 검증·디버깅용 역변환. */
    public static DayOfWeek toDayOfWeek(int dow) {
        if (dow < 1 || dow > 7) {
            throw new IllegalArgumentException("dow 는 1~7 이어야 합니다: " + dow);
        }
        return dow == 1 ? DayOfWeek.SUNDAY : DayOfWeek.of(dow - 1);
    }
}
