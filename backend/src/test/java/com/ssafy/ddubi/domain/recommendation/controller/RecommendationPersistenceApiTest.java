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
        // ML 미설정 시나리오를 고정한다. 실제 운영 서버의 설정 여부와는 독립적인 fixture다.
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
        String evaluated = http.perform(post("/api/v1/schedules/{id}/recommendations", scheduleId).param("version","1")
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
        http.perform(get("/api/v1/schedules/{id}/recommendations/latest", scheduleId).param("version","1").header("Authorization", bearer))
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
    @DisplayName("기본 경로 예상은 HTTP로 수락할 수 있지만 모델 승인과 자동 알림은 바뀌지 않는다")
    void providerEstimateCanBeAcceptedWithoutModelApprovalOrAutomaticNotification() throws Exception {
        // 2026-09-18 정책: 모델 승인 플래그는 안내·수락 자격과 분리한다. 409 기대를 유지하면
        // 정상적인 기본 경로 수락(200)을 CI가 실패로 오인하므로 실제 v2 안내 계약까지 검증한다.
        String evaluated = http.perform(post("/api/v1/schedules/{id}/recommendations", scheduleId).param("version","2")
                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.guidance.canDisplayEstimate").value(true))
                .andExpect(jsonPath("$.data.guidance.canAccept").value(true))
                .andExpect(jsonPath("$.data.guidance.source").value("PROVIDER_ESTIMATE"))
                .andExpect(jsonPath("$.data.quality.productionEligible").value(false))
                .andExpect(jsonPath("$.data.decision").value("KEEP_DEPARTURE"))
                .andReturn().getResponse().getContentAsString();
        var evaluation = json.readTree(evaluated).path("data");
        String evaluationId = evaluation.path("evaluationId").asText();
        long proposedRouteId = evaluation.path("proposedRouteId").asLong();
        assertThat(proposedRouteId).isPositive();

        http.perform(post("/api/v1/schedules/{id}/recommendations/accept", scheduleId).header("Authorization", bearer)
                        .contentType("application/json").content(json.writeValueAsString(Map.of("evaluationId",evaluationId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PENDING"));

        // 단순 HTTP 성공에 그치지 않고 수락된 평가와 실제 선택 경로를 저장했는지 확인한다.
        assertThat(jdbc.queryForObject("SELECT accepted_evaluation_id FROM schedule_evaluation WHERE schedule_id=?",
                String.class,scheduleId)).isEqualTo(evaluationId);
        assertThat(jdbc.queryForObject("SELECT id FROM route WHERE schedule_id=? AND is_selected=true",
                Long.class,scheduleId)).isEqualTo(proposedRouteId);

        assertThat(jdbc.queryForObject(
                "SELECT notification_json FROM schedule_evaluation WHERE schedule_id=?", String.class, scheduleId))
                .as("수동 수락이 자동 알림을 활성화하지 않는다").isNull();
        // 출발시각은 생 JDBC 로 읽지 않는다. hibernate.jdbc.time_zone=Asia/Seoul 이라 JPA 의
        // 쓰기/읽기는 서로 역변환이라 짝이 맞지만, 그 사이에 생 JDBC 로 끼어들면 JVM 이 UTC 인
        // CI 에서만 9시간 어긋난 값이 보인다. 애플리케이션이 실제로 읽는 경로로 확인한다.
        // proposedDepartAt 은 시간대 분리 이후 UTC Instant 가 아니라 KST LocalDateTime 으로 내려온다.
        // 여기서 Instant.parse 를 쓰면 "Z" 가 없어 index 19 에서 깨진다.
        assertThat(schedules.findById(scheduleId).orElseThrow().getPlannedDepartAt())
                .as("KEEP_DEPARTURE 제안의 출발시각을 그대로 수락한다")
                .isEqualTo(LocalDateTime.parse(evaluation.path("proposedDepartAt").asText()));
    }

    @Test
    @DisplayName("남의 추천 근거는 볼 수 없다 — 추천 ID는 순번이라 찍어 볼 수 있다")
    void explanationIsOwnedByTheScheduleOwner() throws Exception {
        String evaluated = http.perform(post("/api/v1/schedules/{id}/recommendations", scheduleId).param("version","1")
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
