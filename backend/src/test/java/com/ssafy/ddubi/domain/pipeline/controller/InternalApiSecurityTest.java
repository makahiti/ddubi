package com.ssafy.ddubi.domain.pipeline.controller;

import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /internal/**} 의 문지기가 실제로 동작하는지 HTTP 레벨에서 고정한다.
 *
 * <p>서비스 계층 테스트로는 이걸 못 잡는다. 필터 순서가 어긋나거나 SecurityConfig 에서
 * {@code hasRole("INTERNAL")} 이 빠지면 <b>아무 검사 없이 통과</b>하는데, 그건 기준선을
 * 누구나 갈아치울 수 있다는 뜻이다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "ddubi.internal.api-key=test-internal-key")
class InternalApiSecurityTest extends IntegrationTestSupport {

    private static final String URL = "/internal/baselines/segment/versions/2026-09-01/rows";
    private static final String BODY = """
            {"rows":[{"segmentId":"BUS:100100609:123456:123789","dow":3,"hourSlot":8,
              "weatherBucket":"DRY","p50":1200,"p80":1260,"p95":1320,"sampleCount":42}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("키가 없으면 401 — JWT 로그인 여부와 무관하게 막힌다")
    void rejectsMissingKey() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("키가 틀리면 401")
    void rejectsWrongKey() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-DDUBI-API-KEY", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("키가 맞으면 통과하고 적재된다")
    void acceptsCorrectKey() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-DDUBI-API-KEY", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.received").value(1))
                .andExpect(jsonPath("$.data.dataVersion").value("2026-09-01"));
    }

    @Test
    @DisplayName("지원하지 않는 baselineType 은 400")
    void rejectsUnknownType() throws Exception {
        mockMvc.perform(post("/internal/baselines/unknown/versions/2026-09-01/rows")
                        .header("X-DDUBI-API-KEY", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("잘못된 segmentId 형식은 400 — 채번 규칙을 벗어난 값이 기준선에 섞이면 매칭이 안 된다")
    void rejectsMalformedSegmentId() throws Exception {
        String bad = BODY.replace("BUS:100100609:123456:123789", "강남역-광화문");
        mockMvc.perform(post(URL)
                        .header("X-DDUBI-API-KEY", "test-internal-key")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }
}
