package com.ssafy.ddubi.domain.recommendation.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 실제 GMS를 호출하지 않고 인증·구조화 응답·비활성 fallback 계약을 검증한다. */
class GmsRecommendationGuideClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void configuredClientReturnsOnlyStructuredCopy() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var properties =
                new RecommendationGuideProperties(
                        true,
                        "https://gms.ssafy.io/gmsapi",
                        "/api.openai.com/v1/responses",
                        "synthetic-key",
                        "gpt-5.4-nano",
                        1000);
        String content =
                json.writeValueAsString(
                        java.util.Map.of(
                                "routeSummary",
                                "집에서 역까지 걸은 뒤 지하철을 이용하세요.",
                                "recommendationReason",
                                "통제로 다른 경로를 제안해요.",
                                "delayExplanation",
                                "공식 사고 영향이 확인됐어요."));
        String response =
                json.writeValueAsString(
                        java.util.Map.of(
                                "output",
                                List.of(
                                        java.util.Map.of(
                                                "type",
                                                "message",
                                                "content",
                                                List.of(
                                                        java.util.Map.of(
                                                                "type",
                                                                "output_text",
                                                                "text",
                                                                content))))));
        server.expect(requestTo("https://gms.ssafy.io/gmsapi/api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer synthetic-key"))
                .andExpect(jsonPath("$.model").value("gpt-5.4-nano"))
                .andExpect(jsonPath("$.input").isString())
                .andExpect(jsonPath("$.messages").doesNotExist())
                .andExpect(jsonPath("$.text.format.type").value("json_schema"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        var client =
                new GmsRecommendationGuideClient(
                        builder.baseUrl(properties.baseUrl()).build(), properties, json);
        var result = client.generate(facts(true));
        assertThat(result).isPresent();
        assertThat(result.orElseThrow().routeSummary()).contains("지하철");
        assertThat(result.orElseThrow().recommendationReason()).contains("다른 경로");
        assertThat(result.orElseThrow().delayExplanation()).contains("사고");
        server.verify();
    }

    @Test
    void disabledClientDoesNotCallGmsAndUnconfirmedCauseCannotReceiveAiExplanation() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var disabled =
                new RecommendationGuideProperties(
                        false,
                        "https://gms.ssafy.io/gmsapi",
                        "/api.openai.com/v1/responses",
                        "",
                        "gpt-5.4-nano",
                        1000);
        assertThat(
                        new GmsRecommendationGuideClient(
                                        builder.baseUrl(disabled.baseUrl()).build(), disabled, json)
                                .generate(facts(false)))
                .isEmpty();
        server.verify();
    }

    private static GmsRecommendationGuideClient.Facts facts(boolean confirmed) {
        return new GmsRecommendationGuideClient.Facts(
                "2026-09-21T08:10",
                "2026-09-21T08:52",
                List.of(
                        new GmsRecommendationGuideClient.StepFact(
                                1, "WALK", null, "집", "강남역", 300L)),
                2520L,
                new com.ssafy.ddubi.domain.route.model.FareRange(1550, 1650),
                new GmsRecommendationGuideClient.ChangeFact(
                        "ROUTE_CHANGED",
                        "2026-09-21T08:20",
                        "2026-09-21T09:00",
                        -600L,
                        -480L,
                        true),
                new GmsRecommendationGuideClient.DelayFact(
                        confirmed ? "DELAYED" : "POSSIBLE",
                        confirmed ? "공식 사고 영향" : "영향 가능성",
                        "TOPIS",
                        confirmed));
    }
}
