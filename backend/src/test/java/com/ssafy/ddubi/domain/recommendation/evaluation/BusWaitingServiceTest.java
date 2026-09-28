package com.ssafy.ddubi.domain.recommendation.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.ddubi.domain.route.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.*;

/** 실제 호출 없이 초 단위 손계산과 놓친 버스·잘못된 방향·API 단위·ML 시간대 경계를 검증한다. */
class BusWaitingServiceTest {
    final Instant now = Instant.parse("2026-09-10T23:59:30Z"); // KST 08:59:30
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final ObjectNode row = json.createObjectNode();
    final ObjectNode obs = row.putObject("observation");
    final BusPreviewClient ml = mock(BusPreviewClient.class);
    final RouteCandidate route = MlRoutePreviewTest.route(0, "1", 300, 0, 1);

    BusWaitingServiceTest() {
        row.put("eventType", "bus.arrival")
                .put("eventId", "bus-event")
                .put("occurredAt", now.toString())
                .put("collectedAt", now.toString())
                .put("validUntil", now.plusSeconds(120).toString());
        row.putObject("providerIds")
                .put("namespace", "SEOUL_BUS")
                .put("routeId", "1")
                .put("stopId", "1");
        obs.put("arrivalTimeBasis", "SEOUL_EXPS_SECONDS_V1")
                .put("stopOrder", 1)
                .put("detourCode", "00")
                .put("sourceGeneratedAt", "2026-09-11 08:59:30.0")
                .put("firstVehicleId", "101")
                .put("secondVehicleId", "102")
                .put("firstArrivalSeconds", 20)
                .put("secondArrivalSeconds", 70)
                .put("firstTerminalArrivalCode", "0")
                .put("secondTerminalArrivalCode", "0")
                .put("firstFullCode", "0")
                .put("secondFullCode", "0");
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(c -> json.writeValueAsString(row));
        when(ml.predict(any()))
                .thenReturn(
                        new BusPreviewClient.Estimate(
                                30.0, "MODEL_NOT_PRODUCTION_READY", "test", now, 0, 8, false));
    }

    BusWaitingService waiting(boolean enabled) {
        return new BusWaitingService(redis, json, enabled);
    }

    BusWaitingService.Estimate estimate(long after) {
        var service = waiting(true);
        return service.estimate(
                route.segments().get(0), now, now.plusSeconds(after), service.newSession());
    }

    MlRoutePreview.Comparison compare(
            RouteCandidate candidate, boolean enabled, Instant departure) {
        var preview =
                new MlRoutePreview(
                        ml,
                        new RouteEvidenceAssembler(redis, json),
                        new RouteTimelineEvaluator(),
                        new SubwayTimingService(new SubwayTimetable(json, ""), redis, json, false),
                        waiting(enabled));
        return preview.compare(
                List.of(candidate),
                new RouteEvidenceAssembler.Snapshot(Map.of(), List.of()),
                now,
                departure,
                now.plusSeconds(2000));
    }

    @Test
    void missedFirstBusUsesSecondAndNeverInventsThird() {
        var first = estimate(0);
        assertThat(first.seconds()).isEqualTo(20);
        var second = estimate(40);
        assertThat(second.seconds()).isEqualTo(30);
        assertThat(second.vehicleId()).isEqualTo("102");
        assertThat(estimate(71).seconds()).isNull();
        assertThat(estimate(71).reasons()).contains("BUS_WAIT_NO_BOARDABLE_VEHICLE");
    }

    @Test
    void sourceTimeIsUsedInsteadOfAddingForecastAgainAtCollectionTime() {
        row.put("occurredAt", now.minusSeconds(10).toString());
        obs.put("sourceGeneratedAt", now.minusSeconds(10).toString());
        row.put("validUntil", now.plusSeconds(110).toString());
        assertThat(estimate(0).seconds()).isEqualTo(10);
    }

    @Test
    void dtPublishedFixtureWithMissingStopOrderRemainsExplicitFallback() throws Exception {
        var fixture =
                json.readTree(
                        java.nio.file.Path.of(
                                        "src/test/resources/conditional-departure-dt/redis-bus-arrival.json")
                                .toFile());
        when(values.get(anyString())).thenReturn(json.writeValueAsString(fixture.path("value")));
        assertThat(estimate(0).seconds()).isNull();
        assertThat(estimate(0).reasons()).contains("BUS_WAIT_IDENTITY_OR_UNIT_UNVERIFIED");
        assertThat(fixture.path("ttlSeconds").asInt())
                .isGreaterThan(fixture.path("consumerFreshnessSeconds").asInt());
    }

    @Test
    void publicSegmentReportsAppliedMlDurationInsteadOfOriginalProviderStep() {
        var walking =
                new com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService(
                        r -> {
                            throw new AssertionError("호출 금지");
                        },
                        json,
                        false,
                        4,
                        0,
                        "");
        var prepared =
                walking.prepare(
                        List.of(route),
                        new Coordinate(127, 37.5),
                        new Coordinate(127.1, 37.6),
                        now);
        var result =
                new ConditionalDepartureEvaluator(null, null, 256, 60, 5000)
                        .evaluate(
                                1,
                                now,
                                now,
                                now.plusSeconds(2000),
                                List.of(),
                                at -> compare(route, false, at))
                        .withWalking(prepared.evidence());
        var dto =
                ScheduleEvaluationV2.from(
                        result,
                        "ml-fixture",
                        null,
                        null,
                        0,
                        "READ_ONLY",
                        now,
                        now,
                        now.plusSeconds(2000),
                        now,
                        now.plusSeconds(60),
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(route));
        assertThat(dto.segments())
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.durationSource()).isEqualTo("ML_PREVIEW");
                            assertThat(s.durationSeconds()).isEqualTo(30);
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"stopOrder", "arrivalTimeBasis", "sourceGeneratedAt", "detourCode"})
    void missingRequiredEvidenceIsNotZeroWaiting(String field) {
        obs.remove(field);
        assertThat(estimate(0).seconds()).isNull();
    }

    @Test
    void wrongOccurrenceAndStaleOrFutureObservationAreRejected() {
        obs.put("stopOrder", 99);
        assertThat(estimate(0).reasons()).contains("BUS_WAIT_IDENTITY_OR_UNIT_UNVERIFIED");
        obs.put("stopOrder", 1);
        row.put("validUntil", now.toString());
        assertThat(estimate(0).reasons()).contains("BUS_WAIT_STALE_OR_OUTSIDE_HORIZON");
        row.put("validUntil", now.plusSeconds(120).toString())
                .put("collectedAt", now.plusSeconds(1).toString());
        assertThat(estimate(0).seconds()).isNull();
    }

    @Test
    void fullOrTerminalBusIsSkippedAndInvalidZeroIsNotImmediateArrival() {
        obs.put("firstFullCode", "1");
        assertThat(estimate(0).vehicleId()).isEqualTo("102");
        obs.put("firstFullCode", "0").put("firstTerminalArrivalCode", "1");
        assertThat(estimate(0).vehicleId()).isEqualTo("102");
        obs.put("firstTerminalArrivalCode", "0").put("firstArrivalSeconds", 0);
        assertThat(estimate(0).vehicleId()).isEqualTo("102");
        obs.put("secondArrivalSeconds", -1);
        assertThat(estimate(0).seconds()).isNull();
    }

    @Test
    void redisFailureAndRepeatedReadsRemainStableInsideEvaluation() {
        var s = waiting(true);
        var session = s.newSession();
        var first = s.estimate(route.segments().get(0), now, now, session);
        obs.put("firstArrivalSeconds", 110);
        assertThat(s.estimate(route.segments().get(0), now, now, session)).isEqualTo(first);
        verify(values, times(1)).get(anyString());
        doThrow(new IllegalStateException("unavailable")).when(values).get(anyString());
        var failedSession = s.newSession();
        assertThat(s.estimate(route.segments().get(0), now, now, failedSession).reasons())
                .contains("BUS_WAIT_REDIS_UNAVAILABLE");
        assertThat(
                        s.estimate(
                                        MlRoutePreviewTest.route(0, "2", 300, 0, 1)
                                                .segments()
                                                .get(0),
                                        now,
                                        now,
                                        failedSession)
                                .reasons())
                .contains("BUS_WAIT_REDIS_UNAVAILABLE");
        verify(values, times(2)).get(anyString()); // 성공 1회 + 장애 1회, 다른 노선에서 장애를 반복 호출하지 않는다.
    }

    @Test
    void waitMovesMlInputToBoardingHourAndAppearsAsSeparateStep() {
        var result = compare(route, true, now.plusSeconds(40));
        assertThat(result.predictions().get(0).input().hour()).isEqualTo(9);
        assertThat(result.predictions().get(0).enteredAt()).isEqualTo(now.plusSeconds(70));
        assertThat(result.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(100));
        assertThat(result.fastest().steps().get(0).vehicleId()).isEqualTo("102");
        assertThat(result.waitPredictions().get(0).seconds()).isEqualTo(30);
        assertThat(result.waitPredictions().get(0).applied()).isTrue();
        assertThat(result.candidates().get(0).busWaitSteps()).isEqualTo(1);
        assertThat(result.candidates().get(0).quality()).isEqualTo("MIXED_PREVIEW");
    }

    @Test
    void failedMlUsesProviderRideAndKeepsCalculatedWait() {
        when(ml.predict(any()))
                .thenReturn(
                        new BusPreviewClient.Estimate(
                                30.0, "MODEL_NOT_PRODUCTION_READY", "test", now, 0, 8, false),
                        BusPreviewClient.Estimate.unavailable("ML_TIMEOUT"));
        var result = compare(MlRoutePreviewTest.route(0, "1", 300, 0, 2), true, now);
        assertThat(result.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(320));
        assertThat(result.waitPredictions().get(0).applied()).isTrue();
        assertThat(result.predictions()).allMatch(p -> !p.applied());
    }

    @Test
    void providerResidualIsPreservedWithoutAddingPossiblyDuplicatedWait() {
        var result = compare(MlRoutePreviewTest.route(0, "1", 300, 10, 1), true, now);
        // ML 이동 30초 + provider 미분류 10초다. 미분류 값에 대기가 포함됐을 수 있어 20초를 다시 더하지 않는다.
        assertThat(result.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(40));
        assertThat(result.waitPredictions().get(0).applied()).isFalse();
        assertThat(result.waitPredictions().get(0).reasons())
                .contains("WAIT_TIME_COVERED_BY_PROVIDER_TOTAL");
        assertThat(compare(route, false, now).waitPredictions().get(0).applied()).isTrue();
    }

    @Test
    void missingWaitIsExcludedWithoutDiscardingValidMlRide() {
        var result = compare(route, true, now.plusSeconds(71));
        assertThat(result.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(101));
        assertThat(result.waitPredictions().get(0).seconds()).isNull();
        assertThat(result.predictions()).allMatch(MlRoutePreview.Prediction::applied);
        assertThat(result.fastest().reasons()).contains("step-1:WAIT_TIME_EXCLUDED");
    }

    @Test
    void busWaitRideAndTransferFeedSubwayWaitingInOrder() {
        var calls =
                List.of(
                        new SubwayTimetable.Call(
                                "A", "PA", now.plusSeconds(100), now.plusSeconds(100), true),
                        new SubwayTimetable.Call(
                                "B", "PB", now.plusSeconds(250), now.plusSeconds(250), true));
        var trip =
                new SubwayTimetable.Trip(
                        "subway-trip",
                        "L",
                        "1002",
                        "FORWARD",
                        "T1",
                        "내선",
                        "0",
                        "종점",
                        "종점",
                        "일반",
                        LocalDate.of(2026, 9, 11),
                        calls);
        var data =
                new SubwayTimetable.Data(
                        "test",
                        "master",
                        "synthetic",
                        now.minusSeconds(1),
                        now.minusSeconds(1),
                        now.plusSeconds(3600),
                        List.of(trip));
        var subway = new SubwayTimingService(new SubwayTimetable(data), redis, json, true);
        var walk =
                new RouteSegment(
                        2,
                        TransportMode.WALK,
                        null,
                        null,
                        null,
                        "버스역",
                        "승강장",
                        10,
                        20,
                        List.of(),
                        List.of(),
                        List.of(),
                        null);
        var mixed =
                new RouteCandidate(
                        0,
                        RouteType.BUS_AND_SUBWAY,
                        100,
                        1319,
                        1,
                        new FareRange(0, 0),
                        0,
                        0,
                        List.of(
                                route.segments().get(0),
                                walk,
                                SubwayTimingServiceTest.segment(3, "A", "B")));
        var preview =
                new MlRoutePreview(
                        ml,
                        new RouteEvidenceAssembler(redis, json),
                        new RouteTimelineEvaluator(),
                        subway,
                        waiting(true));
        var result =
                preview.compare(
                        List.of(mixed),
                        new RouteEvidenceAssembler.Snapshot(Map.of(), List.of()),
                        now,
                        now,
                        now.plusSeconds(1000));
        // 버스 대기20 + ML이동30 + 환승20 + 지하철 대기30 + 이동150 = 250초.
        assertThat(result.waitPredictions()).hasSize(2);
        assertThat(result.waitPredictions().get(0).seconds()).isEqualTo(20);
        assertThat(result.waitPredictions().get(1).stationAt()).isEqualTo(now.plusSeconds(70));
        assertThat(result.waitPredictions().get(1).seconds()).isEqualTo(30);
        assertThat(result.waitPredictions()).allMatch(MlRoutePreview.WaitPrediction::applied);
        assertThat(result.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(250));
    }

    /** 선택 차량은 10초 뒤 도착하지만 관측은 20초 뒤 만료된다. ETA가 더 늦어도 TTL을 늘리지 않는다. */
    @Test
    void appliedBusObservationExpiresFinalEvaluationAndSurvivesStoredJson() throws Exception {
        row.put("validUntil", now.plusSeconds(20).toString());
        obs.put("firstArrivalSeconds", 10);
        var evaluation =
                new ConditionalDepartureEvaluator(null, null, 256, 60, 5000)
                        .evaluate(
                                1,
                                now,
                                now,
                                now.plusSeconds(2000),
                                List.of(),
                                at -> compare(route, true, at));
        var stored =
                json.readValue(
                        json.writeValueAsString(evaluation),
                        ConditionalDepartureEvaluator.Evaluation.class);
        assertThat(stored.proposedComparison().waitPredictions().get(0).validUntil())
                .isEqualTo(now.plusSeconds(20));
        assertThat(ScheduleEvaluationV2.validity(stored, null, now, null))
                .isEqualTo(now.plusSeconds(20));
        assertThat(ScheduleEvaluationV2.validity(stored, null, now, now.plusSeconds(5)))
                .isEqualTo(now.plusSeconds(5));
        // 이전 저장 JSON에는 만료 필드가 없다. 적용된 관측을 무기한 유효한 것으로 복원하지 않는다.
        var legacy = json.valueToTree(evaluation);
        for (String field : List.of("originalComparison", "proposedComparison"))
            ((ObjectNode) legacy.path(field).path("waitPredictions").get(0)).remove("validUntil");
        var restored = json.treeToValue(legacy, ConditionalDepartureEvaluator.Evaluation.class);
        assertThat(ScheduleEvaluationV2.validity(restored, null, now, null)).isEqualTo(now);
    }

    @Test
    void appliedWaitExpiresEvenWithLegacyFlagDisabled() {
        row.put("validUntil", now.plusSeconds(20).toString());
        obs.put("firstArrivalSeconds", 10);
        var evaluation =
                new ConditionalDepartureEvaluator(null, null, 256, 60, 5000)
                        .evaluate(
                                1,
                                now,
                                now,
                                now.plusSeconds(2000),
                                List.of(),
                                at -> compare(route, false, at));
        assertThat(evaluation.proposedComparison().waitPredictions())
                .allMatch(MlRoutePreview.WaitPrediction::applied);
        assertThat(ScheduleEvaluationV2.validity(evaluation, null, now, null))
                .isEqualTo(now.plusSeconds(20));
    }
}
