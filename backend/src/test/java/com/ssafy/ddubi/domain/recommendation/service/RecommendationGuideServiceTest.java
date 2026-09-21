package com.ssafy.ddubi.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.client.GmsRecommendationGuideClient;
import com.ssafy.ddubi.domain.recommendation.client.RecommendationGuideProperties;
import com.ssafy.ddubi.domain.recommendation.evaluation.ScheduleEvaluationV2;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** AI가 시간·구조를 바꾸지 않고 서버 계산값 위에 표현 문구만 더하는지 검증한다. */
class RecommendationGuideServiceTest {
    @Test
    void guideKeepsCalculatedTimesAndReturnsStructuredSteps() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        ScheduleEvaluationV2 evaluation =
                json.readValue(
                        """
                        {
                          "schemaVersion":"schedule-evaluation-v2",
                          "evaluationId":"evaluation-1",
                          "proposedDepartAt":"2026-09-21T08:10:00",
                          "estimatedArrivalAt":"2026-09-21T08:52:00",
                          "durationSeconds":2520,
                          "asOf":"2026-09-20T23:00:00Z",
                          "validUntil":"2026-09-20T23:10:00Z",
                          "causes":[],
                          "segments":[
                            {"candidateKey":"candidate-0","sequence":1,"mode":"WALK","fromName":"집","toName":"강남역","durationSeconds":300},
                            {"candidateKey":"candidate-0","sequence":2,"mode":"SUBWAY","fromName":"강남역","toName":"잠실역","durationSeconds":1800}
                          ],
                          "candidateEstimates":[]
                        }
                        """,
                        ScheduleEvaluationV2.class);
        ScheduleEvaluationStore store = mock(ScheduleEvaluationStore.class);
        when(store.latestReadyV2(7L, 3L)).thenReturn(evaluation);
        GmsRecommendationGuideClient gms = mock(GmsRecommendationGuideClient.class);
        when(gms.generate(any()))
                .thenReturn(
                        Optional.of(
                                new GmsRecommendationGuideClient.GeneratedCopy(
                                        "강남역까지 걸은 뒤 잠실역까지 지하철로 이동하세요.", null)));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        var properties =
                new RecommendationGuideProperties(
                        true,
                        "https://gms.ssafy.io/gmsapi",
                        "/api.openai.com/v1/chat/completions",
                        "synthetic",
                        "gpt-5.4-nano",
                        1000);
        var service = new RecommendationGuideService(store, gms, properties, redis, json);

        var result = service.guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        assertThat(result.departureAt()).isEqualTo(LocalDateTime.parse("2026-09-21T08:10:00"));
        assertThat(result.estimatedArrivalAt())
                .isEqualTo(LocalDateTime.parse("2026-09-21T08:52:00"));
        assertThat(result.routeSteps()).hasSize(2);
        assertThat(result.routeSummary()).contains("잠실역");
        assertThat(result.delay().status()).isEqualTo("NO_CONFIRMED_DELAY");
        assertThat(result.generatedBy()).isEqualTo("GMS:gpt-5.4-nano");
    }
}
