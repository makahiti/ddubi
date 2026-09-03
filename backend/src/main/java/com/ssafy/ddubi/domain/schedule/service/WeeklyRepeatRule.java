package com.ssafy.ddubi.domain.schedule.service;

import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 주간 반복 규칙 — RRULE(API 계약) ↔ 요일 CSV(DB 저장) 변환.
 *
 * 명세서(REPEAT-001)는 iCalendar {@code rrule} 을 받지만 ERD 는 {@code days_of_week}
 * CSV 를 저장한다. 전체 RRULE 문법을 받으면 DB 로 표현할 수 없으므로
 * <b>FREQ=WEEKLY 단일 주기만 허용</b>하고 나머지는 400 으로 끊는다 —
 * 출퇴근 서비스라 주간 반복이면 충분하다(팀 확정 필요 시 이 클래스만 확장).
 *
 * CSV 는 서버가 정렬·중복 제거·허용값 검증을 보장한다(명세서 13장 규칙 8).
 */
public final class WeeklyRepeatRule {

    /** RRULE BYDAY 표기(MO) ↔ DB CSV 표기(MON). 월요일부터 정렬 기준으로도 쓴다. */
    private static final DayOfWeek[] ORDERED = DayOfWeek.values(); // MONDAY..SUNDAY

    private final Set<DayOfWeek> days;

    private WeeklyRepeatRule(Set<DayOfWeek> days) {
        if (days.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "반복 요일이 비어 있습니다.");
        }
        this.days = EnumSet.copyOf(days);
    }

    // ------------------------------------------------------------------
    // 파싱
    // ------------------------------------------------------------------

    /** API 입력. 예: {@code FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR} */
    public static WeeklyRepeatRule fromRrule(String rrule) {
        if (rrule == null || rrule.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "rrule 은 필수입니다.");
        }
        String freq = null;
        String byday = null;
        for (String part : rrule.trim().toUpperCase(Locale.ROOT).split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                throw invalid(rrule);
            }
            switch (kv[0]) {
                case "FREQ" -> freq = kv[1];
                case "BYDAY" -> byday = kv[1];
                case "INTERVAL" -> {
                    if (!"1".equals(kv[1])) {
                        throw new BusinessException(ErrorCode.INVALID_REQUEST,
                                "격주 이상 반복(INTERVAL>1)은 지원하지 않습니다.");
                    }
                }
                default -> throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "지원하지 않는 rrule 항목입니다: " + kv[0]);
            }
        }
        if (!"WEEKLY".equals(freq) || byday == null) {
            throw invalid(rrule);
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String code : byday.split(",")) {
            days.add(switch (code.trim()) {
                case "MO" -> DayOfWeek.MONDAY;
                case "TU" -> DayOfWeek.TUESDAY;
                case "WE" -> DayOfWeek.WEDNESDAY;
                case "TH" -> DayOfWeek.THURSDAY;
                case "FR" -> DayOfWeek.FRIDAY;
                case "SA" -> DayOfWeek.SATURDAY;
                case "SU" -> DayOfWeek.SUNDAY;
                default -> throw invalid(rrule);
            });
        }
        return new WeeklyRepeatRule(days);
    }

    /** DB 저장값. 예: {@code MON,TUE,WED} */
    public static WeeklyRepeatRule fromCsv(String csv) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String token : csv.split(",")) {
            try {
                days.add(shortNameToDay(token.trim()));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "저장된 반복 요일이 올바르지 않습니다: " + csv);
            }
        }
        return new WeeklyRepeatRule(days);
    }

    // ------------------------------------------------------------------
    // 출력
    // ------------------------------------------------------------------

    /** 정렬·중복 제거된 CSV. DB 에 이 값만 저장한다. */
    public String toCsv() {
        return java.util.Arrays.stream(ORDERED)
                .filter(days::contains)
                .map(WeeklyRepeatRule::dayToShortName)
                .collect(Collectors.joining(","));
    }

    /** 응답용 RRULE 복원. */
    public String toRrule() {
        String byday = java.util.Arrays.stream(ORDERED)
                .filter(days::contains)
                .map(d -> switch (d) {
                    case MONDAY -> "MO"; case TUESDAY -> "TU"; case WEDNESDAY -> "WE";
                    case THURSDAY -> "TH"; case FRIDAY -> "FR"; case SATURDAY -> "SA";
                    case SUNDAY -> "SU";
                })
                .collect(Collectors.joining(","));
        return "FREQ=WEEKLY;BYDAY=" + byday;
    }

    public boolean matches(LocalDate date) {
        return days.contains(date.getDayOfWeek());
    }

    /** {@code from}(포함) 이후 이 규칙에 맞는 가장 이른 날짜. {@code until} 초과면 null. */
    public LocalDate nextOccurrence(LocalDate from, LocalDate until) {
        LocalDate date = from;
        // 요일 집합이 비어 있지 않으므로 7일 안에 반드시 찾는다.
        for (int i = 0; i < 7; i++) {
            if (matches(date)) {
                return (until != null && date.isAfter(until)) ? null : date;
            }
            date = date.plusDays(1);
        }
        return null;
    }

    private static BusinessException invalid(String rrule) {
        return new BusinessException(ErrorCode.INVALID_REQUEST,
                "rrule 은 FREQ=WEEKLY;BYDAY=MO,TU,... 형식만 지원합니다: " + rrule);
    }

    private static DayOfWeek shortNameToDay(String shortName) {
        return switch (shortName) {
            case "MON" -> DayOfWeek.MONDAY;
            case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY;
            case "SAT" -> DayOfWeek.SATURDAY;
            case "SUN" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException(shortName);
        };
    }

    private static String dayToShortName(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "MON"; case TUESDAY -> "TUE"; case WEDNESDAY -> "WED";
            case THURSDAY -> "THU"; case FRIDAY -> "FRI"; case SATURDAY -> "SAT";
            case SUNDAY -> "SUN";
        };
    }
}
