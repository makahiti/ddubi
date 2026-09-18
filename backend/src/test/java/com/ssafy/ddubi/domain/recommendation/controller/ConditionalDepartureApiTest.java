package com.ssafy.ddubi.domain.recommendation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.evaluation.*;
import com.ssafy.ddubi.domain.route.client.PublicTransitRouteClient;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.*;
import com.ssafy.ddubi.global.security.JwtTokenProvider;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 인증/JSON 설정/Redis rate limit을 통과시켜 조회 전용 API의 DB 무변경과 오류 계약을 검증한다. */
@AutoConfigureMockMvc
class ConditionalDepartureApiTest extends IntegrationTestSupport {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider tokens;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PublicTransitRouteClient routes;
    @MockitoBean BusPreviewClient model;
    String bearer,body;
    @BeforeEach void fixture() throws Exception {
        long owner=users.save(User.builder().email("readonly@example.test").nickname("조회").build()).getId();
        bearer="Bearer "+tokens.createAccessToken(owner);
        Instant now=Instant.now();
        body=json.writeValueAsString(Map.of("origin",Map.of("longitude",127.0,"latitude",37.5),
                "destination",Map.of("longitude",127.01,"latitude",37.51),
                "plannedDepartAt",now.plusSeconds(600).toString(),"arriveBy",now.plusSeconds(3600).toString()));
        when(routes.search(any())).thenReturn(new RouteSearchResult("KAKAO",now,null,List.of(RouteEvidenceAssemblerTest.candidate(0,600,0))));
        when(model.predict(any())).thenReturn(BusPreviewClient.Estimate.unavailable("ML_NOT_CONFIGURED"));
    }
    @Test void readonlyKeepsRequiredNullFieldsAndDoesNotWriteServiceRows() throws Exception {
        String response=http.perform(post("/api/v1/routes/evaluate").header("Authorization",bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("KEEP_DEPARTURE"))
                .andReturn().getResponse().getContentAsString();
        var value=json.readTree(response).path("data");
        assertThat(value.has("scheduleId")).isTrue(); assertThat(value.path("scheduleId").isNull()).isTrue();
        assertThat(value.has("bestKnownDecision")).isTrue(); assertThat(value.path("appliedBufferSeconds").asInt()).isZero();
        for(String table:List.of("schedule","route_request","route","route_segment","route_segment_edge","recommendation","schedule_evaluation","evaluation_notification_outbox"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).as(table).isZero();
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        json.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/conditional-departure-http.json").toFile(),value);
    }
    @Test void authenticationAndInvalidOrderDoNotCallProvider() throws Exception {
        http.perform(post("/api/v1/routes/evaluate").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        var invalid=json.readTree(body); ((com.fasterxml.jackson.databind.node.ObjectNode)invalid).put("plannedDepartAt",invalid.path("arriveBy").asText());
        http.perform(post("/api/v1/routes/evaluate").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(routes);
    }
    @Test void seventhRequestIsRateLimitedAndProviderFailureIs503() throws Exception {
        when(routes.search(any())).thenThrow(new BusinessException(ErrorCode.REALTIME_DATA_UNAVAILABLE));
        for(int i=0;i<6;i++) http.perform(post("/api/v1/routes/evaluate").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable());
        http.perform(post("/api/v1/routes/evaluate").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());
        verify(routes,times(6)).search(any());
    }
}
