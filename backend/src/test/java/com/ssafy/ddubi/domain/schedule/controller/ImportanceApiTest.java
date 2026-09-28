package com.ssafy.ddubi.domain.schedule.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.security.JwtTokenProvider;
import com.ssafy.ddubi.global.util.BusinessTime;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 중요도 2단계 — <b>HTTP 경계</b> (B7).
 *
 * <h3>왜 서비스 계층 테스트로 충분하지 않나</h3>
 *
 * {@code ImportanceTwoLevelsIntegrationTest} 는 {@code Importance.CRITICAL} 을 자바 값으로 직접 넘긴다. 그러면
 * <b>JSON 역직렬화와 컨트롤러를 건너뛴다.</b> B7 의 핵심은 "경계에서 흡수한다" 인데, 정작 그 경계를 지나지 않는 셈이다.
 *
 * <p>구체적으로 이런 회귀를 못 잡는다 — 나중에 누가 enum 에서 {@code CRITICAL} 을 지우면 문자열 {@code "CRITICAL"} 이 역직렬화 단계에서
 * 400 으로 터진다. 앱은 아직 그 값을 보내고 있는데도 서비스 계층 테스트는 초록불로 남는다.
 *
 * <p>같은 실수를 팀이 이미 한 번 했다 — {@code /auth/oauth/link} 가 필터에 막혀 401 이 났는데 서비스 계층 테스트는 전부 통과했고 프론트가 먼저
 * 발견했다.
 */
@AutoConfigureMockMvc
class ImportanceApiTest extends IntegrationTestSupport {

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;

    private String bearer;

    @BeforeEach
    void fixture() {
        long owner =
                users.save(
                                User.builder()
                                        .email("importance-api@example.test")
                                        .nickname("중요도 연동")
                                        .build())
                        .getId();
        bearer = "Bearer " + tokens.createAccessToken(owner);
    }

    /** importance 키를 아예 빼거나 명시적 null 로 보내려고 HashMap 을 쓴다 — Map.of 는 null 을 못 담는다. */
    private Map<String, Object> scheduleBody(Object importance, boolean includeKey) {
        LocalDateTime arriveBy = BusinessTime.now().plusDays(1);
        Map<String, Object> body = new HashMap<>();
        body.put("title", "회의");
        body.put("origin", Map.of("name", "강남", "lat", 37.4979, "lng", 127.0276));
        body.put("destination", Map.of("name", "역삼", "lat", 37.5006, "lng", 127.0364));
        body.put("arriveBy", arriveBy.toString());
        body.put("plannedDepartAt", arriveBy.minusHours(1).toString());
        if (includeKey) {
            body.put("importance", importance);
        }
        return body;
    }

    private long create(Map<String, Object> body) throws Exception {
        String created =
                http.perform(
                                post("/api/v1/schedules")
                                        .header("Authorization", bearer)
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(created).path("data").path("id").asLong();
    }

    private String stored(long scheduleId) {
        return jdbc.queryForObject(
                "SELECT importance FROM schedule WHERE id = ?", String.class, scheduleId);
    }

    /** 앱은 아직 "CRITICAL" 을 보낸다. 400 으로 거절하면 앱이 업데이트될 때까지 일정 등록이 막힌다. 역직렬화도 통과하고, 저장은 HIGH 여야 한다. */
    @Test
    @DisplayName("JSON 으로 \"CRITICAL\" 을 보내도 400 이 아니라 HIGH 로 저장된다")
    void criticalStringIsAcceptedOverHttp() throws Exception {
        long scheduleId = create(scheduleBody("CRITICAL", true));

        assertThat(stored(scheduleId)).as("새 CRITICAL 행이 생기면 안 된다").isEqualTo("HIGH");
        http.perform(get("/api/v1/schedules/{id}", scheduleId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.importance").value("HIGH"));
    }

    @Test
    @DisplayName("JSON 으로 \"HIGH\"·\"NORMAL\" 은 그대로 저장된다")
    void twoLevelsRoundTripOverHttp() throws Exception {
        assertThat(stored(create(scheduleBody("HIGH", true)))).isEqualTo("HIGH");
        assertThat(stored(create(scheduleBody("NORMAL", true)))).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("importance 를 명시적 null 로 보내면 NORMAL 로 저장된다")
    void explicitNullBecomesNormal() throws Exception {
        long scheduleId = create(scheduleBody(null, true));

        assertThat(stored(scheduleId)).isEqualTo("NORMAL");
        http.perform(get("/api/v1/schedules/{id}", scheduleId).header("Authorization", bearer))
                .andExpect(jsonPath("$.data.importance").value("NORMAL"));
    }

    @Test
    @DisplayName("importance 키를 아예 빼도 NORMAL 로 저장된다")
    void missingKeyBecomesNormal() throws Exception {
        assertThat(stored(create(scheduleBody(null, false)))).isEqualTo("NORMAL");
    }

    /** 도입 이전 CRITICAL 행은 DB 에 남아 있다. 그 행을 HTTP 로 읽을 때 500 이 나거나 앱이 모르는 값을 받으면 안 된다. */
    @Test
    @DisplayName("이미 저장된 CRITICAL 행도 HTTP 응답에서는 HIGH 로 보인다")
    void legacyCriticalRowIsExposedAsHighOverHttp() throws Exception {
        long scheduleId = create(scheduleBody("HIGH", true));
        jdbc.update("UPDATE schedule SET importance = 'CRITICAL' WHERE id = ?", scheduleId);

        assertThat(stored(scheduleId)).as("DB 값은 그대로 둔다").isEqualTo("CRITICAL");
        http.perform(get("/api/v1/schedules/{id}", scheduleId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.importance").value("HIGH"));
    }

    /** 오타는 받아 주지 않는다 — 흡수는 CRITICAL 에만 해당하는 한시적 조치다. */
    @Test
    @DisplayName("없는 값을 보내면 400 이다")
    void unknownValueIsRejected() throws Exception {
        http.perform(
                        post("/api/v1/schedules")
                                .header("Authorization", bearer)
                                .contentType("application/json")
                                .content(json.writeValueAsString(scheduleBody("URGENT", true))))
                .andExpect(status().isBadRequest());
    }
}
