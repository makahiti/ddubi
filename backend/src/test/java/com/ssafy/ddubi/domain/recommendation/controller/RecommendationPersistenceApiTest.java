package com.ssafy.ddubi.domain.recommendation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.evaluation.BusPreviewClient;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteEvidenceAssemblerTest;
import com.ssafy.ddubi.domain.route.client.PublicTransitRouteClient;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.security.JwtTokenProvider;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프론트가 실제로 밟는 순서를 HTTP로 그대로 통과시킨다 —
 * 일정 등록 → 추천 생성 → {@code recommendationId} 수신 → 최신 추천 조회 → 추천 근거 조회.
 *
 * <h3>왜 서비스 계층 테스트로 충분하지 않나</h3>
 * 같은 실수를 이미 한 번 했다. {@code /auth/oauth/link} 를 {@code PUBLIC_PATHS} 에 넣지 않아
 * 401 이 났는데, 서비스 계층 통합 테스트는 필터 체인을 건너뛰기 때문에 전부 통과했고
 * <b>프론트가 먼저 발견했다.</b> 응답 JSON 필드 이름도 마찬가지다 — 서비스가 옳은 값을
 * 반환해도 직렬화 이름이 다르면 화면은 아무것도 못 읽는다.
 */
@AutoConfigureMockMvc
class RecommendationPersistenceApiTest extends IntegrationTestSupport {

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired ScheduleRepository schedules;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PublicTransitRouteClient routeClient;
    @MockitoBean BusPreviewClient mlClient;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Instant now;
    private String bearer;
    private long scheduleId;

    @BeforeEach
    void fixture() throws Exception {
        now = Instant.now();
        // 예측 서비스는 아직 붙어 있지 않다. 지금 운영에서 실제로 나오는 상태 그대로 둔다.
        when(mlClient.predict(any())).thenReturn(BusPreviewClient.Estimate.unavailable("ML_NOT_CONFIGURED"));
        when(routeClient.search(any())).thenReturn(new RouteSearchResult("KAKAO", now, null,
                List.of(RouteEvidenceAssemblerTest.candidate(0, 1500, 30),
                        RouteEvidenceAssemblerTest.candidate(1, 600, 30))));

        long owner = users.save(User.builder().email("recommendation-api@example.test").nickname("추천 연동").build()).getId();
        bearer = "Bearer " + tokens.createAccessToken(owner);
        var body = Map.of("title", "추천 저장 연동",
                "origin", Map.of("name", "강남", "lat", 37.4979, "lng", 127.0276),
                "destination", Map.of("name", "역삼", "lat", 37.5006, "lng", 127.0364),
                "plannedDepartAt", LocalDateTime.ofInstant(now.plusSeconds(600), KST).toString(),
                "arriveBy", LocalDateTime.ofInstant(now.plusSeconds(1800), KST).toString());
        String created = http.perform(post("/api/v1/schedules").header("Authorization", bearer)
                        .contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        scheduleId = json.readTree(created).path("data").path("id").asLong();
    }

    @Test
    @DisplayName("추천 생성 → 저장 → recommendationId → 최신 추천 · 근거 조회가 HTTP로 이어진다")
    void frontendCanFollowTheWholeChainOverHttp() throws Exception {
        String evaluated = http.perform(post("/api/v1/schedules/{id}/recommendations", scheduleId)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendationId").isNumber())
                .andExpect(jsonPath("$.data.persistence.saved").value(true))
                .andExpect(jsonPath("$.data.persistence.preview").value(true))
                .andExpect(jsonPath("$.data.persistence.productionReady").value(false))
                // 배지 하나 띄우려고 추천 근거 API를 한 번 더 부르지 않아도 된다.
                .andExpect(jsonPath("$.data.persistence.confidence").value("LOW"))
                .andExpect(jsonPath("$.data.persistence.source").value("API_ONLY"))
                .andExpect(jsonPath("$.data.persistence.fallbackUsed").value(true))
                .andReturn().getResponse().getContentAsString();
        long recommendationId = json.readTree(evaluated).path("data").path("recommendationId").asLong();
        assertThat(recommendationId).isPositive();

        // 최신 추천 — 화면을 다시 그릴 때 같은 ID와 같은 표시가 나와야 한다.
        http.perform(get("/api/v1/schedules/{id}/recommendations/latest", scheduleId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("READY"))
                .andExpect(jsonPath("$.data.result.recommendationId").value(recommendationId))
                .andExpect(jsonPath("$.data.result.persistence.preview").value(true));

        // 추천 근거 — 요약과 원문이 함께 나온다.
        http.perform(get("/api/v1/recommendations/{id}/explanation", recommendationId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendationId").value(recommendationId))
                .andExpect(jsonPath("$.data.confidence").value("LOW"))
                .andExpect(jsonPath("$.data.preview").value(true))
                .andExpect(jsonPath("$.data.previewStatus").value("PREVIEW"))
                .andExpect(jsonPath("$.data.productionReady").value(false))
                .andExpect(jsonPath("$.data.source").value("API_ONLY"))
                .andExpect(jsonPath("$.data.fallbackUsed").value(true))
                .andExpect(jsonPath("$.data.reasonCodes").isArray())
                .andExpect(jsonPath("$.data.departAt").isNotEmpty())
                .andExpect(jsonPath("$.data.eta").isNotEmpty())
                .andExpect(jsonPath("$.data.reasons").isNotEmpty())
                .andExpect(jsonPath("$.data.segmentStatusSnapshot").isNotEmpty());
    }

    @Test
    @DisplayName("저장은 되어도 수락과 자동 알림은 여전히 막혀 있다")
    void storingDoesNotUnblockAcceptOrNotification() throws Exception {
        String evaluated = http.perform(post("/api/v1/schedules/{id}/recommendations", scheduleId)
                .header("Authorization", bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String evaluationId = json.readTree(evaluated).path("data").path("evaluationId").asText();

        http.perform(post("/api/v1/schedules/{id}/recommendations/accept", scheduleId).header("Authorization", bearer)
                        .contentType("application/json").content("{\"evaluationId\":\"" + evaluationId + "\"}"))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject(
                "SELECT notification_json FROM schedule_evaluation WHERE schedule_id=?", String.class, scheduleId))
                .as("검증 전 값으로 폰을 울리지 않는다").isNull();
        // 출발시각은 생 JDBC 로 읽지 않는다. hibernate.jdbc.time_zone=Asia/Seoul 이라 JPA 의
        // 쓰기/읽기는 서로 역변환이라 짝이 맞지만, 그 사이에 생 JDBC 로 끼어들면 JVM 이 UTC 인
        // CI 에서만 9시간 어긋난 값이 보인다. 애플리케이션이 실제로 읽는 경로로 확인한다.
        assertThat(schedules.findById(scheduleId).orElseThrow().getPlannedDepartAt())
                .as("수락이 막혔으니 출발시각도 그대로다")
                .isEqualTo(LocalDateTime.ofInstant(now.plusSeconds(600), KST).withNano(0));
    }

    @Test
    @DisplayName("남의 추천 근거는 볼 수 없다 — 추천 ID는 순번이라 찍어 볼 수 있다")
    void explanationIsOwnedByTheScheduleOwner() throws Exception {
        String evaluated = http.perform(post("/api/v1/schedules/{id}/recommendations", scheduleId)
                .header("Authorization", bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long recommendationId = json.readTree(evaluated).path("data").path("recommendationId").asLong();

        long stranger = users.save(User.builder().email("stranger@example.test").nickname("남").build()).getId();
        http.perform(get("/api/v1/recommendations/{id}/explanation", recommendationId)
                        .header("Authorization", "Bearer " + tokens.createAccessToken(stranger)))
                .andExpect(status().isForbidden());
        http.perform(get("/api/v1/recommendations/{id}/explanation", recommendationId))
                .andExpect(status().isUnauthorized());
    }
}
