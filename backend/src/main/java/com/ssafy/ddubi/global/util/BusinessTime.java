package com.ssafy.ddubi.global.util;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 업무 시각 생성 — <b>초 단위로 잘라서</b> 만든다.
 *
 * <h3>왜 필요한가</h3>
 *
 * 스키마 규칙상 업무 시각 컬럼(`arrive_by`, `read_at`, `sent_at` 등)은 `DATETIME` 이고 감사
 * 컬럼(`created_at`/`updated_at`)만 `DATETIME(6)` 이다. 그런데 자바 {@code now()} 는 나노초까지 만든다.
 *
 * <p>자르지 않으면 <b>"방금 저장한 엔티티의 값"과 "DB 에서 다시 읽은 값"이 달라진다.</b> MySQL 이 DATETIME 에 넣을 때 소수점 이하를 반올림하기
 * 때문이다 ({@code 11:02:19.837} → {@code 11:02:20}). 같은 요청을 두 번 보냈을 때 응답이 달라지거나, 시각을 비교하는 로직이 조용히
 * 어긋난다.
 *
 * <p>감사 컬럼 쪽 같은 문제는 {@code JpaConfig.auditingDateTimeProvider()} 가 막는다 (그쪽은 DATETIME(6) 이라 마이크로초로
 * 자른다).
 */
public final class BusinessTime {

    /**
     * 뚜비의 업무 시간대. <b>JVM 기본 시간대에 기대지 않는다.</b>
     *
     * <p>서비스가 다루는 시각은 전부 한국 기준이다 — 사용자가 정한 브리핑 시각, 일정 도착 시각, 버스·지하철 시각표. 그런데 개발 PC 는 KST, CI 컨테이너는
     * UTC 로 뜬다. {@code now()} 를 그냥 쓰면 같은 코드가 환경마다 9시간 다른 "지금" 을 본다. 이 세션에서만 그 때문에 통합 테스트가 네 번 깨졌다.
     */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private BusinessTime() {}

    /** DATETIME 컬럼에 넣어도 값이 변하지 않는 현재 시각. */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * DATETIME(6) 컬럼에 직접 넣을 현재 시각.
     *
     * <p>감사 컬럼은 JpaConfig 가 알아서 잘라 주지만, pipeline_config.updated_at 처럼 감사 대상이 아닌 DATETIME(6) 컬럼을
     * 코드에서 직접 채울 때 쓴다. 나노초를 그대로 넣으면 저장값과 읽은 값이 달라진다.
     */
    public static LocalDateTime nowMicros() {
        return LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.MICROS);
    }

    /** 외부에서 받은 시각을 DATETIME 정밀도로 맞춘다. null 은 그대로 통과시킨다. */
    public static LocalDateTime normalize(LocalDateTime time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.SECONDS);
    }

    /** TIME 컬럼(`arrive_time`)용. 클라이언트가 밀리초를 실어 보내도 저장값과 어긋나지 않게 한다. */
    public static LocalTime normalize(LocalTime time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.SECONDS);
    }
}
