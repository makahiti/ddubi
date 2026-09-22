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
    void classifiesRouteDepartureEtaAndNoChangeBeforeCallingGms() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();

        assertThat(
                        RecommendationGuideService.change(
                                        evaluation(json, 1, 2, "08:00", "07:50", "08:40", "08:35"))
                                .type())
                .isEqualTo("ROUTE_CHANGED");
        assertThat(
                        RecommendationGuideService.change(
                                        evaluation(json, 1, 1, "08:00", "07:50", "08:40", "08:35"))
                                .type())
                .isEqualTo("DEPARTURE_TIME_CHANGED");
        assertThat(
                        RecommendationGuideService.change(
                                        evaluation(json, 1, 1, "08:00", "08:00", "08:40", "08:45"))
                                .type())
                .isEqualTo("ETA_UPDATED");
        assertThat(
                        RecommendationGuideService.change(
                                        evaluation(json, 1, 1, "08:00", "08:00", "08:40", "08:40"))
                                .type())
                .isEqualTo("NO_CHANGE");
    }

    private static ScheduleEvaluationV2 evaluation(
            ObjectMapper json,
            long selected,
            long proposed,
            String planned,
            String proposedDeparture,
            String previousArrival,
            String proposedArrival)
            throws Exception {
        return json.readValue(
                """
                {
                  "selectedRouteId":%d,
                  "proposedRouteId":%d,
                  "plannedDepartAt":"2026-09-22T%s:00",
                  "proposedDepartAt":"2026-09-22T%s:00",
                  "originalEstimatedArrivalAt":"2026-09-22T%s:00",
                  "estimatedArrivalAt":"2026-09-22T%s:00",
                  "causes":[],"segments":[],"candidateEstimates":[]
                }
                """
                        .formatted(
                                selected,
                                proposed,
                                planned,
                                proposedDeparture,
                                previousArrival,
                                proposedArrival),
                ScheduleEvaluationV2.class);
    }

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
                          "fare":{"minimumWon":1550,"maximumWon":1650},
                          "asOf":"2026-09-20T23:00:00Z",
                          "validUntil":"2026-09-20T23:10:00Z",
                          "causes":[],
                          "segments":[
                            {"candidateKey":"candidate-0","sequence":1,"mode":"WALK","routeLabel":null,"fromName":"집","toName":"강남역","durationSeconds":300},
                            {"candidateKey":"candidate-0","sequence":2,"mode":"SUBWAY","routeLabel":"2호선","fromName":"강남역","toName":"잠실역","durationSeconds":1800}
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
                                        "강남역까지 걸은 뒤 잠실역까지 지하철로 이동하세요.", "현재 경로를 유지해도 돼요.", null)));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        var properties =
                new RecommendationGuideProperties(
                        true,
                        "https://gms.ssafy.io/gmsapi",
                        "/api.openai.com/v1/responses",
                        "synthetic",
                        "gpt-5.5-pro",
                        1000);
        var service = new RecommendationGuideService(store, gms, properties, redis, json);

        var result = service.guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        assertThat(result.departureAt()).isEqualTo(LocalDateTime.parse("2026-09-21T08:10:00"));
        assertThat(result.estimatedArrivalAt())
                .isEqualTo(LocalDateTime.parse("2026-09-21T08:52:00"));
        assertThat(result.routeSteps()).hasSize(2);
        assertThat(result.routeSteps().get(1).routeLabel()).isEqualTo("2호선");
        assertThat(result.fare().minimumWon()).isEqualTo(1550);
        assertThat(result.routeSummary()).contains("잠실역");
        assertThat(result.changeType()).isEqualTo("NO_CHANGE");
        assertThat(result.recommendationReason()).contains("유지");
        assertThat(result.delay().status()).isEqualTo("NO_CONFIRMED_DELAY");
        assertThat(result.generatedBy()).isEqualTo("GMS:gpt-5.5-pro");
    }

    @Test
    void guideDoesNotMixSourceFromDifferentCause() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        ScheduleEvaluationV2 evaluation =
                json.readValue(
                        """
                        {
                          "schemaVersion":"schedule-evaluation-v2",
                          "evaluationId":"evaluation-multiple-causes",
                          "proposedDepartAt":"2026-09-21T08:10:00",
                          "estimatedArrivalAt":"2026-09-21T08:52:00",
                          "durationSeconds":2520,
                          "asOf":"2026-09-20T23:00:00Z",
                          "validUntil":"2026-09-20T23:10:00Z",
                          "causes":[
                            {"eventId":"event-potential","source":"EVENT_SOURCE","segmentIds":["segment-1"],"effect":"DELAYED","confirmed":false},
                            {"eventId":"event-confirmed","source":"TOPIS_CONFIRMED","segmentIds":["segment-2"],"effect":"BLOCKED","confirmed":true}
                          ],
                          "segments":[],
                          "candidateEstimates":[]
                        }
                        """,
                        ScheduleEvaluationV2.class);
        ScheduleEvaluationStore store = mock(ScheduleEvaluationStore.class);
        when(store.latestReadyV2(7L, 3L)).thenReturn(evaluation);
        GmsRecommendationGuideClient gms = mock(GmsRecommendationGuideClient.class);
        when(gms.generate(any())).thenReturn(Optional.empty());
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.multiGet(any()))
                .thenReturn(
                        java.util.Arrays.asList(
                                "{\"source\":\"EVENT_DETAIL\",\"contentText\":\"행사 영향 가능성\",\"validUntil\":\"2026-09-20T23:10:00Z\"}",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
        var properties =
                new RecommendationGuideProperties(
                        true,
                        "https://gms.ssafy.io/gmsapi",
                        "/api.openai.com/v1/responses",
                        "synthetic",
                        "gpt-5.5-pro",
                        1000);
        var service = new RecommendationGuideService(store, gms, properties, redis, json);

        var result = service.guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        assertThat(result.delay().status()).isEqualTo("DISRUPTED");
        assertThat(result.delay().source()).isEqualTo("TOPIS_CONFIRMED");
        assertThat(result.delay().reason()).doesNotContain("행사 영향 가능성");
    }
}
