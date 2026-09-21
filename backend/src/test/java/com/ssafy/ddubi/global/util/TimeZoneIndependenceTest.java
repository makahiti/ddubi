package com.ssafy.ddubi.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시각은 JVM 기본 시간대와 무관해야 한다.
 *
 * <h3>왜 이 테스트가 있나</h3>
 *
 * 개발 PC 는 KST, CI 컨테이너는 UTC 로 뜬다. 예전에는 {@code hibernate.jdbc.time_zone: Asia/Seoul} 이 저장할 때마다 "JVM
 * 기본 시간대로 해석 → Asia/Seoul 로 다시 렌더링" 을 끼워 넣어서, UTC 로 돌리면 DB 에 +9h 밀린 값이 들어갔다. KST 개발 PC 에서는 차이가 0 이라
 * 아무도 못 봤고, CI 에서만 통합 테스트가 깨졌다 — 그것도 네 번.
 *
 * <p>여기 단언은 전부 <b>시간대에 기대지 않는 형태</b>다. 그래서 KST 로 돌리든 UTC 로 돌리든 똑같이 통과해야 한다. 한쪽만 통과하면 그게 회귀다. 재현 방법:
 *
 * <pre>./mvnw test -Dtest=TimeZoneIndependenceTest -Duser.timezone=UTC</pre>
 */
class TimeZoneIndependenceTest extends IntegrationTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private User saved() {
        return users.saveAndFlush(User.builder().email("tz@ddubi.com").nickname("시간대").build());
    }

    /**
     * 컬럼을 <b>문자열로</b> 읽는다. 다시 자바 타입으로 읽으면 저장할 때 걸린 변환이 읽을 때 되돌아와서, DB 안에서 9시간 밀려 있어도 왕복 비교로는 안 보인다.
     * 쓰기는 반드시 JPA 로 한다 — 문제의 변환은 Hibernate 가 거는 것이라 JdbcTemplate 으로 넣으면 재현되지 않는다.
     */
    @Test
    @DisplayName("DATETIME 은 JVM 시간대와 무관하게 KST 벽시계 그대로 저장된다")
    void datetimeIsStoredVerbatim() {
        User user = saved();
        user.changeHistoryConsent(true);
        users.saveAndFlush(user);

        LocalDateTime stored =
                LocalDateTime.parse(
                        jdbc.queryForObject(
                                "SELECT DATE_FORMAT(history_consented_at, '%Y-%m-%dT%H:%i:%s') FROM user WHERE id = ?",
                                String.class, user.getId()));

        assertThat(Duration.between(LocalDateTime.now(KST), stored).abs())
                .as("DB 에 적힌 글자가 KST 벽시계에서 9시간 떨어져 있으면 저장 계층이 변환을 건 것이다")
                .isLessThan(Duration.ofSeconds(10));
    }

    /**
     * TIME 컬럼이 더 위험하다. DATETIME 은 밀리기만 하지만 TIME 은 <b>자정에서 한 바퀴 돈다</b>. 22:00 이 07:00 이 되면 {@code
     * briefing_time <= now} 같은 대소 비교가 통째로 뒤집혀서, 아침 브리핑 대상 조회가 조용히 0건을 돌려준다.
     */
    @Test
    @DisplayName("TIME 은 자정을 넘겨 한 바퀴 돌지 않는다")
    void timeDoesNotWrapAroundMidnight() {
        User user = saved();
        user.changeBriefingTime(LocalTime.of(22, 0));
        users.saveAndFlush(user);

        String stored =
                jdbc.queryForObject(
                        "SELECT TIME_FORMAT(briefing_time, '%H:%i:%s') FROM user WHERE id = ?",
                        String.class, user.getId());

        assertThat(stored).as("+9h 밀리면 '07:00:00' 이 되어 대소 비교가 뒤집힌다").isEqualTo("22:00:00");
        assertThat(users.findById(user.getId()).orElseThrow().getBriefingTime())
                .isEqualTo(LocalTime.of(22, 0));
    }

    @Test
    @DisplayName("BusinessTime 은 JVM 시간대가 아니라 KST 벽시계를 본다")
    void businessTimeFollowsKstWallClock() {
        assertThat(Duration.between(LocalDateTime.now(KST), BusinessTime.now()).abs())
                .isLessThan(Duration.ofSeconds(5));
        assertThat(Duration.between(LocalDateTime.now(KST), BusinessTime.nowMicros()).abs())
                .isLessThan(Duration.ofSeconds(5));
    }

    /**
     * 감사 컬럼만 JVM 기본 시간대로 찍히면 UTC 에서 created_at 이 업무 시각보다 9시간 이르게 남는다. 둘을 비교하는 쪽(만료 판정, 낙관적 잠금)이 조용히
     * 어긋난다.
     */
    @Test
    @DisplayName("감사 컬럼도 업무 시각과 같은 시간대로 찍힌다")
    void auditColumnsUseTheSameZone() {
        User user = saved();

        assertThat(Duration.between(user.getCreatedAt(), BusinessTime.now()).abs())
                .as("9시간 차이가 나면 감사 컬럼이 JVM 시간대를 따라간 것이다")
                .isLessThan(Duration.ofSeconds(10));
    }
}
