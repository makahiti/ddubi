package com.ssafy.ddubi.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.ddubi.domain.recommendation.client.GmsRecommendationGuideClient;
import com.ssafy.ddubi.domain.recommendation.client.RecommendationGuideProperties;
import com.ssafy.ddubi.domain.recommendation.evaluation.DepartureEstimate;
import com.ssafy.ddubi.domain.recommendation.evaluation.ScheduleEvaluationV2;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** AI가 시간·구조를 바꾸지 않고 서버 계산값 위에 표현 문구만 더하는지 검증한다. */
class RecommendationGuideServiceTest {
    @Test
    void rejectsGeneratedTurnByTurnDirectionsButKeepsIncidentExplanation() {
        assertThat(RecommendationGuideService.routeNarration("서울역에서 1호선 탑승 후 노량진 하차 → 버스 탑승"))
                .isTrue();
        assertThat(RecommendationGuideService.routeNarration("도로 통제를 반영해 다른 경로를 제안해요.")).isFalse();
    }

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
  "selectedRouteId":1,
  "proposedRouteId":2,
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
        when(store.latestOutcomeV2(7L, 3L))
                .thenReturn(new ScheduleEvaluationStore.LatestOutcome(evaluation, false));
        GmsRecommendationGuideClient gms = mock(GmsRecommendationGuideClient.class);
        when(gms.generate(any()))
                .thenReturn(
                        Optional.of(
                                new GmsRecommendationGuideClient.GeneratedCopy(
                                        "현재 경로를 유지해도 돼요.", null)));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        // 이 계산 번호는 처음 묻는다.
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        var properties =
                new RecommendationGuideProperties(
                        true,
                        "https://gms.ssafy.io/gmsapi",
                        "/api.openai.com/v1/responses",
                        "synthetic",
                        "gpt-5.4-nano",
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
        // 경로가 달라진 계산이라 AI 문장을 부른다. 바뀐 것이 없으면 아래 검사처럼 서버 문장만 쓴다.
        assertThat(result.changeType()).isEqualTo("ROUTE_CHANGED");
        assertThat(result.recommendationReason()).contains("유지");
        assertThat(result.delay().status()).isEqualTo("NO_CONFIRMED_DELAY");
        assertThat(result.generatedBy()).isEqualTo("GMS:gpt-5.4-nano");
    }

    // ------------------------------------------------------------------
    // 크레딧을 지키는 규칙 (2026-09-22 하루 6 만 크레딧 사고)
    // ------------------------------------------------------------------

    private static ScheduleEvaluationV2 unchanged(ObjectMapper json, String validUntil)
            throws Exception {
        return fixture(json, "evaluation-unchanged", 1, validUntil);
    }

    /** 경로가 1 → 2 로 바뀐 계산. GMS 를 부를 만한 변화다. */
    private static ScheduleEvaluationV2 changed(ObjectMapper json, String validUntil)
            throws Exception {
        return fixture(json, "evaluation-changed", 2, validUntil);
    }

    private static ScheduleEvaluationV2 fixture(
            ObjectMapper json, String id, long proposedRouteId, String validUntil)
            throws Exception {
        return json.readValue(
                """
                {
                  "schemaVersion":"schedule-evaluation-v2",
                  "evaluationId":"%s",
                  "selectedRouteId":1,
                  "proposedRouteId":%d,
                  "plannedDepartAt":"2026-09-21T08:10:00",
                  "proposedDepartAt":"2026-09-21T08:10:00",
                  "originalEstimatedArrivalAt":"2026-09-21T08:52:00",
                  "estimatedArrivalAt":"2026-09-21T08:52:00",
                  "durationSeconds":2520,
                  "asOf":"2026-09-20T23:00:00Z",
                  "validUntil":"%s",
                  "causes":[],
                  "segments":[],
                  "candidateEstimates":[]
                }
                """
                        .formatted(id, proposedRouteId, validUntil),
                ScheduleEvaluationV2.class);
    }

    /** 출발만 몇 초 달라진 계산. 출발 직전엔 제안 출발이 "지금(초 단위)" 이라 1분마다 이렇게 된다. */
    private static ScheduleEvaluationV2 departureShift(
            ObjectMapper json, String id, String proposedDepartAt) throws Exception {
        return json.readValue(
                """
                {
                  "schemaVersion":"schedule-evaluation-v2",
                  "evaluationId":"%s",
                  "selectedRouteId":1,
                  "proposedRouteId":1,
                  "plannedDepartAt":"2026-09-21T08:10:00",
                  "proposedDepartAt":"%s",
                  "originalEstimatedArrivalAt":"2026-09-21T08:52:00",
                  "estimatedArrivalAt":"2026-09-21T08:52:00",
                  "durationSeconds":2520,
                  "asOf":"2026-09-20T23:00:00Z",
                  "validUntil":"2026-09-20T23:10:00Z",
                  "causes":[],
                  "segments":[],
                  "candidateEstimates":[]
                }
                """
                        .formatted(id, proposedDepartAt),
                ScheduleEvaluationV2.class);
    }

    /** 13초 차이로 "출발 시각이 13초 변경" 같은 안내를 내지 않는다(2026-09-24 제보). 1분이 넘을 때만 변경이다. */
    @Test
    void secondsLevelDepartureShiftIsNotAChange() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();

        assertThat(
                        RecommendationGuideService.change(
                                        departureShift(json, "e1", "2026-09-21T08:10:13"))
                                .type())
                .isEqualTo("NO_CHANGE");
        assertThat(
                        RecommendationGuideService.change(
                                        departureShift(json, "e2", "2026-09-21T08:11:30"))
                                .type())
                .isEqualTo("DEPARTURE_TIME_CHANGED");
    }

    /**
     * 같은 상황이면 계산 번호가 바뀌어도 AI 를 다시 부르지 않는다. 출발 직전엔 1분마다 새 계산 번호가 생겨, 계산 번호만 보던 장치로는 매번 새로 불렀다
     * (2026-09-24 일정 하나에 25분 동안 4번).
     */
    @Test
    void sameSituationReusesTheCopyAcrossEvaluations() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var first = rig(json, changed(json, "2026-09-20T23:10:00Z"), false);
        java.util.Map<String, String> store = new java.util.HashMap<>();
        when(first.values().get(anyString()))
                .thenAnswer(call -> store.get(call.<String>getArgument(0)));
        org.mockito.Mockito.doAnswer(
                        call -> {
                            store.put(call.getArgument(0), call.getArgument(1));
                            return null;
                        })
                .when(first.values())
                .set(anyString(), anyString(), any(Duration.class));

        var firstResult = first.service().guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));
        assertThat(firstResult.generatedBy()).isEqualTo("GMS:gpt-5.4-nano");
        // 캐시를 비워 "다음 계산" 처럼 만든다. 상황 문장만 남는다.
        store.keySet().removeIf(key -> !key.contains(":copy:"));
        var second = first.service().guide(7L, 3L, Instant.parse("2026-09-20T23:02:00Z"));

        assertThat(second.generatedBy()).isEqualTo("GMS:gpt-5.4-nano");
        assertThat(second.recommendationReason()).isEqualTo(firstResult.recommendationReason());
        verify(first.gms(), org.mockito.Mockito.times(1)).generate(any());
    }

    /**
     * AI 가 앞당김·늦춤 방향을 설명하므로 방향이 뒤집히면 다른 상황이다. 지난 문장을 다시 쓰면 반대로 안내한다. 폭(몇 분)만 달라진 것은 같은 상황으로 본다 — 폭을
     * 지문에 넣으면 1분마다 다시 묻게 된다.
     */
    /**
     * 도로 사고 상세는 유효시각이 "사고 시작 + 10분" 이라 오래 이어지는 공사는 늘 만료로 보였다. 최근에 다시 받은 상세면 원인 설명으로 쓴다. 지하철 공지는
     * 수집기가 몇 년 치를 매번 다시 받아서 받은 시각으로 현재 여부를 알 수 없으므로 예외로 두지 않는다.
     */
    @Test
    void longRunningIncidentDetailIsUsedWhenRecentlyCollected() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Instant now = Instant.parse("2026-09-24T07:00:00Z"); // 16:00 KST
        String detail =
                """
                {"observation":{"incidentMessage":"증산로 3차로 시설물보수"},
                 "validUntil":"2025-08-25T00:10:00.000+09:00","collectedAt":"%s"}
                """;
        var recent = json.readTree(detail.formatted("2026-09-24T15:50:00+09:00"));
        var stale = json.readTree(detail.formatted("2026-09-24T12:00:00+09:00"));

        assertThat(RecommendationGuideService.usableDetail("traffic:latest:incident:", recent, now))
                .isTrue();
        assertThat(RecommendationGuideService.usableDetail("traffic:latest:incident:", stale, now))
                .isFalse();
        assertThat(
                        RecommendationGuideService.usableDetail(
                                "transit:latest:subway-alert:", recent, now))
                .isFalse();
        assertThat(
                        RecommendationGuideService.usableDetail(
                                "transit:latest:subway-alert:",
                                json.readTree("{\"validUntil\":\"2026-09-24T17:00:00+09:00\"}"),
                                now))
                .isTrue();
    }

    @Test
    void directionFlipIsANewSituationButAmountChangeIsNot() {
        java.util.function.Function<Long, String> situationFor =
                delta ->
                        RecommendationGuideService.situation(
                                new GmsRecommendationGuideClient.Facts(
                                        new GmsRecommendationGuideClient.ChangeFact(
                                                "DEPARTURE_TIME_CHANGED",
                                                "2026-09-21T08:20",
                                                "2026-09-21T09:00",
                                                delta,
                                                null,
                                                false),
                                        new GmsRecommendationGuideClient.DelayFact(
                                                "NO_CONFIRMED_DELAY", null, null, false)),
                                java.util.List.of(),
                                "gpt-5.4-nano");

        assertThat(situationFor.apply(-300L)).isNotEqualTo(situationFor.apply(300L));
        assertThat(situationFor.apply(-300L)).isEqualTo(situationFor.apply(-420L));
    }

    private record Rig(
            RecommendationGuideService service,
            GmsRecommendationGuideClient gms,
            ValueOperations<String, String> values) {}

    /** {@code alreadyAsked} 가 참이면 이 계산 번호로 이미 GMS 를 부른 뒤다 — 캐시는 비었어도 "물어봤다" 표시만 남은 상태. */
    private static Rig rig(
            ObjectMapper json, ScheduleEvaluationV2 evaluation, boolean alreadyAsked) {
        ScheduleEvaluationStore store = mock(ScheduleEvaluationStore.class);
        when(store.latestOutcomeV2(7L, 3L))
                .thenReturn(new ScheduleEvaluationStore.LatestOutcome(evaluation, false));
        GmsRecommendationGuideClient gms = mock(GmsRecommendationGuideClient.class);
        when(gms.generate(any()))
                .thenReturn(
                        Optional.of(
                                new GmsRecommendationGuideClient.GeneratedCopy("AI 사유 문장", null)));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(!alreadyAsked);
        when(values.increment(anyString())).thenReturn(1L);
        var properties =
                new RecommendationGuideProperties(
                        true,
                        "https://gms.ssafy.io/gmsapi",
                        "/api.openai.com/v1/responses",
                        "synthetic",
                        "gpt-5.4-nano",
                        1000);
        return new Rig(
                new RecommendationGuideService(store, gms, properties, redis, json), gms, values);
    }

    /** 오늘 나간 크레딧의 대부분이 "그대로 유지" 계산이었다. 그 문장은 서버가 쓰면 충분하다. */
    @Test
    void unchangedRecommendationUsesTemplateWithoutCallingGms() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var rig = rig(json, unchanged(json, "2026-09-20T23:10:00Z"), false);

        var result = rig.service().guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        assertThat(result.changeType()).isEqualTo("NO_CHANGE");
        assertThat(result.generatedBy()).isEqualTo("TEMPLATE");
        assertThat(result.recommendationReason()).contains("유지");
        verify(rig.gms(), never()).generate(any());
    }

    /** 경로가 같아도 도착 예정이 바뀌면 AI 설명 대상이다. AI 장애 때는 서버가 변경 방향을 말한다. */
    @Test
    void etaChangeReceivesExplanationAndTruthfulFallback() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        ObjectNode node = json.valueToTree(unchanged(json, "2026-09-20T23:10:00Z"));
        node.put("evaluationId", "evaluation-eta-change");
        node.put("estimatedArrivalAt", "2026-09-21T08:57:00");
        var evaluation = json.treeToValue(node, ScheduleEvaluationV2.class);
        var rig = rig(json, evaluation, false);
        when(rig.gms().generate(any())).thenReturn(Optional.empty());

        var result = rig.service().guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        assertThat(result.changeType()).isEqualTo("ETA_UPDATED");
        assertThat(result.recommendationReason()).contains("5분 늦어져");
        verify(rig.gms()).generate(any());
    }

    /**
     * 같은 계산 번호에는 캐시가 비어 있어도 다시 묻지 않는다. 시간 초과로 답을 버렸거나 Redis 캐시가 사라진 뒤의 두 번째 조회가 이 모양이다 — 예전에는 그때마다
     * 또 불렀다.
     */
    @Test
    void sameEvaluationIsNeverAskedTwiceEvenWhenCacheIsGone() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var rig = rig(json, changed(json, "2026-09-20T23:10:00Z"), true);

        var result = rig.service().guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        assertThat(result.changeType()).isEqualTo("ROUTE_CHANGED");
        assertThat(result.generatedBy()).isEqualTo("TEMPLATE");
        verify(rig.gms(), never()).generate(any());
    }

    /** 처음 묻는 계산이면 부른다. 그리고 "물어봤다" 표시는 부르기 전에 하루짜리로 남긴다 — 답이 안 와도 표시는 남아야 한다. */
    @Test
    void firstAskMarksEvaluationBeforeCallingGms() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var rig = rig(json, changed(json, "2026-09-20T23:10:00Z"), false);

        var result = rig.service().guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        assertThat(result.generatedBy()).isEqualTo("GMS:gpt-5.4-nano");
        var order = inOrder(rig.values(), rig.gms());
        order.verify(rig.values())
                .setIfAbsent(
                        eq("recommendation:guide:asked:7:evaluation-changed"),
                        anyString(),
                        eq(RecommendationGuideService.ASKED_TTL));
        order.verify(rig.gms()).generate(any());
    }

    /** 표시를 남길 수 없으면(Redis 장애) 부르지 않는다. 표시 없이 부르면 다음 조회가 또 부르고, 그때는 캐시도 없다. */
    @Test
    void gmsIsNotCalledWhenAskedMarkerCannotBeStored() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var rig = rig(json, changed(json, "2026-09-20T23:10:00Z"), false);
        when(rig.values().setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));

        var result = rig.service().guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        assertThat(result.generatedBy()).isEqualTo("TEMPLATE");
        verify(rig.gms(), never()).generate(any());
    }

    /**
     * 유효기간이 30 초 남았어도 5 분은 캐시한다. 예전에는 남은 시간이 0 이면 캐시를 건너뛰어, 출발 직전(유효기간 1 분)에는 만든 문장이 곧장 버려지고 다음 조회가
     * 또 GMS 를 불렀다.
     */
    @Test
    void resultIsCachedForAtLeastFiveMinutesEvenNearExpiry() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var rig = rig(json, unchanged(json, "2026-09-20T23:01:30Z"), false);

        rig.service().guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        verify(rig.values())
                .set(
                        eq("recommendation:guide:7:evaluation-unchanged"),
                        anyString(),
                        eq(RecommendationGuideService.MIN_CACHE_TTL));
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
        when(store.latestOutcomeV2(7L, 3L))
                .thenReturn(new ScheduleEvaluationStore.LatestOutcome(evaluation, false));
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
                                "{\"source\":\"EVENT_DETAIL\",\"contentText\":\"행사 영향"
                                        + " 가능성\",\"validUntil\":\"2026-09-20T23:10:00Z\"}",
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
                        "gpt-5.4-nano",
                        1000);
        var service = new RecommendationGuideService(store, gms, properties, redis, json);

        var result = service.guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        assertThat(result.delay().status()).isEqualTo("DISRUPTED");
        assertThat(result.delay().source()).isEqualTo("TOPIS_CONFIRMED");
        assertThat(result.delay().reason()).doesNotContain("행사 영향 가능성");
    }

    /**
     * 출발한 뒤에도 출발할 때 본 안내를 다시 볼 수 있어야 한다(2026-09-22 제보).
     *
     * <p>멈춘 일정의 마지막 결과는 유효기간이 이미 지나 있다. 그 결과는 더 갱신되지 않으므로 "지났다" 를 "틀렸다" 로 다루지 않고 그대로 문장화한다. 그리고 매번
     * GMS 를 다시 부르지 않게 고정 시간 동안 캐시한다.
     */
    @Test
    void frozenOutcomeIsServedPastValidUntilAndCachedForAFixedTime() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        ScheduleEvaluationV2 evaluation =
                json.readValue(
                        """
                        {
                          "schemaVersion":"schedule-evaluation-v2",
                          "evaluationId":"evaluation-departed",
                          "proposedDepartAt":"2026-09-21T08:10:00",
                          "estimatedArrivalAt":"2026-09-21T08:52:00",
                          "durationSeconds":2520,
                          "asOf":"2026-09-20T23:00:00Z",
                          "validUntil":"2026-09-20T23:10:00Z",
                          "causes":[],
                          "segments":[],
                          "candidateEstimates":[]
                        }
                        """,
                        ScheduleEvaluationV2.class);
        ScheduleEvaluationStore store = mock(ScheduleEvaluationStore.class);
        when(store.latestOutcomeV2(7L, 3L))
                .thenReturn(new ScheduleEvaluationStore.LatestOutcome(evaluation, true));
        GmsRecommendationGuideClient gms = mock(GmsRecommendationGuideClient.class);
        when(gms.generate(any())).thenReturn(Optional.empty());
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
                        "gpt-5.4-nano",
                        1000);
        var service = new RecommendationGuideService(store, gms, properties, redis, json);

        // 유효기간(23:10Z)이 한참 지난 시각에 물어도 거절하지 않는다.
        var result = service.guide(7L, 3L, Instant.parse("2026-09-21T02:00:00Z"));

        assertThat(result.departureAt()).isEqualTo(LocalDateTime.parse("2026-09-21T08:10:00"));
        assertThat(result.estimatedArrivalAt())
                .isEqualTo(LocalDateTime.parse("2026-09-21T08:52:00"));
        verify(values)
                .set(
                        eq("recommendation:guide:7:evaluation-departed"),
                        anyString(),
                        eq(RecommendationGuideService.FROZEN_CACHE_TTL));
    }

    @Test
    void departedGuideUsesActualDepartureEstimateInsteadOfCachedPredepartureCopy()
            throws Exception {
        // 출발 전 평가 ID는 그대로여도 실제 출발 시각으로 재계산한 ETA가 다르다.
        // 이때 이전 Redis 문장/GMS 응답을 다시 보여주지 않아야 한다.
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        ScheduleEvaluationV2 evaluation =
                json.readValue(
                        """
                        {
                          "schemaVersion":"schedule-evaluation-v2",
                          "evaluationId":"same-id",
                          "proposedDepartAt":"2026-09-23T08:10:00",
                          "estimatedArrivalAt":"2026-09-23T08:52:00",
                          "durationSeconds":2520,
                          "causes":[],"segments":[],"candidateEstimates":[]
                        }
                        """,
                        ScheduleEvaluationV2.class);
        var departure =
                new DepartureEstimate(
                        "READY",
                        Instant.parse("2026-09-22T22:51:00Z"),
                        Instant.parse("2026-09-22T23:34:00Z"),
                        2580L,
                        Instant.parse("2026-09-22T22:51:01Z"),
                        "selected",
                        null);
        ScheduleEvaluationStore store = mock(ScheduleEvaluationStore.class);
        when(store.latestOutcomeV2(7L, 3L))
                .thenReturn(new ScheduleEvaluationStore.LatestOutcome(evaluation, true, departure));
        GmsRecommendationGuideClient gms = mock(GmsRecommendationGuideClient.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        var properties =
                new RecommendationGuideProperties(
                        true,
                        "https://gms.ssafy.io/gmsapi",
                        "/api.openai.com/v1/responses",
                        "synthetic",
                        "gpt-5.4-nano",
                        1000);

        var guide =
                new RecommendationGuideService(store, gms, properties, redis, json)
                        .guide(7L, 3L, Instant.parse("2026-09-22T22:52:00Z"));

        assertThat(guide.departureAt()).isEqualTo(LocalDateTime.parse("2026-09-23T07:51:00"));
        assertThat(guide.estimatedArrivalAt())
                .isEqualTo(LocalDateTime.parse("2026-09-23T08:34:00"));
        assertThat(guide.durationSeconds()).isEqualTo(2580L);
        assertThat(guide.generatedBy()).isEqualTo("TEMPLATE:DEPARTURE");
        verify(gms, never()).generate(any());
    }
}
