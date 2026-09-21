package com.ssafy.ddubi.domain.recommendation.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 합성 시간표의 손계산, 놓친 환승, ID 오매칭, stale, 중복 대기 합산을 실제 Redis 없이 검증한다. */
class SubwayTimingServiceTest {
    @Test
    @EnabledIfSystemProperty(named = "transit.timetable.path", matches = ".+")
    void generatedArtifactLoadsAndCalculatesTimetableWaitAndRide() {
        var loaded = new SubwayTimetable(json, System.getProperty("transit.timetable.path"));
        assertThat(loaded.unavailableReason()).isNull();
        assertThat(loaded.data()).isNotNull();

        var selected =
                loaded.data().trips().stream()
                        .flatMap(
                                trip ->
                                        java.util.stream.IntStream.range(0, trip.calls().size() - 1)
                                                .filter(
                                                        i ->
                                                                trip.calls().get(i).departureAt()
                                                                                != null
                                                                        && trip.calls()
                                                                                        .get(i + 1)
                                                                                        .arrivalAt()
                                                                                != null)
                                                .mapToObj(i -> Map.entry(trip, i)))
                        .findFirst()
                        .orElseThrow();
        var trip = selected.getKey();
        int index = selected.getValue();
        var from = trip.calls().get(index);
        var to = trip.calls().get(index + 1);
        var edge =
                new MappedTransitSegment(
                        1,
                        "SUB:" + trip.lineId() + ":" + from.stationId() + ":" + to.stationId(),
                        TransportMode.SUBWAY,
                        trip.lineId(),
                        trip.providerLineId(),
                        trip.lineId(),
                        trip.direction(),
                        from.stationId(),
                        to.stationId(),
                        from.providerStationId(),
                        to.providerStationId(),
                        index + 1,
                        index + 2);
        var mapping =
                new TransitMappingResult(
                        TransitMappingStatus.EXACT,
                        loaded.data().masterDataVersion(),
                        "generated-artifact-smoke",
                        trip.lineId(),
                        trip.lineId(),
                        from.stationId(),
                        to.stationId(),
                        index + 1,
                        index + 2,
                        trip.direction(),
                        1,
                        1,
                        1,
                        null,
                        null,
                        List.of(edge));
        var segment =
                new RouteSegment(
                        1,
                        TransportMode.SUBWAY,
                        edge.segmentId(),
                        trip.lineId(),
                        null,
                        from.stationId(),
                        to.stationId(),
                        0,
                        0,
                        List.of(from.stationId(), to.stationId()),
                        List.of(),
                        List.of(),
                        mapping);
        Instant platformAt = from.departureAt().minusSeconds(30);
        Instant asOf =
                platformAt.isBefore(loaded.data().publishedAt())
                        ? loaded.data().publishedAt()
                        : platformAt;
        if (asOf.isAfter(platformAt)) platformAt = asOf;

        var result =
                new SubwayTimingService(loaded, redis, json, false)
                        .estimate(segment, asOf, platformAt, new SubwayTimingService.Session());

        assertThat(result.source()).isEqualTo("SUBWAY_TIMETABLE");
        assertThat(result.waitSeconds()).isNotNegative();
        assertThat(result.rideSeconds()).isPositive();
        assertThat(result.boardingAt()).isAfterOrEqualTo(platformAt);
        assertThat(result.arrivalAt()).isAfter(result.boardingAt());
        assertThat(result.reasons()).contains("SUBWAY_REALTIME_TRAIN_CROSSWALK_MISSING");
        verifyNoInteractions(redis);
    }

    @Test
    void timetableOnlyUsesCanonicalIdsWithoutInventingRealtimeTrainAndKeepsEndpointNulls() {
        var trip =
                new SubwayTimetable.Trip(
                        "static-train",
                        "L",
                        null,
                        "FORWARD",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.parse("2026-09-10"),
                        List.of(
                                new SubwayTimetable.Call(
                                        "A", null, null, now.plusSeconds(60), true),
                                new SubwayTimetable.Call(
                                        "B", null, now.plusSeconds(300), null, true)));
        var result = estimate(service(false, trip), now);
        assertThat(result.waitSeconds()).isEqualTo(60);
        assertThat(result.rideSeconds()).isEqualTo(240);
        assertThat(result.reasons()).contains("SUBWAY_REALTIME_TRAIN_CROSSWALK_MISSING");
        verifyNoInteractions(redis);
    }

    @Test
    void timetableOnlyFindsLaterLapAfterFirstLapWasMissed() {
        var trip =
                new SubwayTimetable.Trip(
                        "loop",
                        "L",
                        null,
                        "FORWARD",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.parse("2026-09-10"),
                        List.of(
                                call("A", 60, true),
                                call("B", 120, true),
                                call("A", 180, true),
                                call("B", 240, true)));
        var result = estimate(service(false, trip), now.plusSeconds(61));
        assertThat(result.boardingAt()).isEqualTo(now.plusSeconds(180));
        assertThat(result.arrivalAt()).isEqualTo(now.plusSeconds(240));
        verifyNoInteractions(redis);
    }

    @Test
    void partialTrainCrosswalkAndMissingIntermediateTimeAreRejected() {
        assertThatThrownBy(
                        () ->
                                new SubwayTimetable(
                                        data(
                                                new SubwayTimetable.Trip(
                                                        "bad",
                                                        "L",
                                                        "1002",
                                                        "FORWARD",
                                                        null,
                                                        "내선",
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        LocalDate.parse("2026-09-10"),
                                                        List.of(
                                                                call("A", 60, true),
                                                                call("B", 120, true))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SubwayTimetable(
                                        data(
                                                trip(
                                                        "T1",
                                                        call("A", 60, true),
                                                        new SubwayTimetable.Call(
                                                                "B",
                                                                "PB",
                                                                null,
                                                                now.plusSeconds(100),
                                                                true),
                                                        call("C", 120, true)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    final Instant now = Instant.parse("2026-09-10T00:00:00Z");
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final Map<String, String> rows = new HashMap<>();

    SubwayTimingServiceTest() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(any()))
                .thenAnswer(
                        c ->
                                ((Collection<String>) c.getArgument(0))
                                        .stream().map(rows::get).toList());
    }

    SubwayTimetable.Call call(String station, long second, boolean stops) {
        return new SubwayTimetable.Call(
                station, "P" + station, now.plusSeconds(second), now.plusSeconds(second), stops);
    }

    SubwayTimetable.Trip trip(String id, SubwayTimetable.Call... calls) {
        return new SubwayTimetable.Trip(
                id,
                "L",
                "1002",
                "FORWARD",
                id,
                "내선",
                "0",
                "종점",
                "종점",
                "일반",
                LocalDate.parse("2026-09-10"),
                List.of(calls));
    }

    SubwayTimetable.Data data(SubwayTimetable.Trip... trips) {
        return new SubwayTimetable.Data(
                "test-v1",
                "master",
                "synthetic-test-only",
                now.minusSeconds(86400),
                now.minusSeconds(3600),
                now.plusSeconds(86400),
                List.of(trips));
    }

    SubwayTimingService service(boolean apply, SubwayTimetable.Trip... trips) {
        return new SubwayTimingService(new SubwayTimetable(data(trips)), redis, json, apply);
    }

    static RouteSegment segment(int sequence, String from, String to) {
        var edge =
                new MappedTransitSegment(
                        1,
                        "SUB:L:" + from + ":" + to,
                        TransportMode.SUBWAY,
                        "L",
                        "1002",
                        "L",
                        "FORWARD",
                        from,
                        to,
                        "P" + from,
                        "P" + to,
                        1,
                        2);
        var mapping =
                new TransitMappingResult(
                        TransitMappingStatus.EXACT,
                        "master",
                        "policy",
                        "L",
                        "L",
                        from,
                        to,
                        1,
                        2,
                        "FORWARD",
                        1,
                        1,
                        1,
                        null,
                        RealtimeMappingCoverage.FULL,
                        List.of(edge));
        return new RouteSegment(
                sequence,
                TransportMode.SUBWAY,
                null,
                "L",
                null,
                from,
                to,
                100,
                999,
                List.of(from, to),
                List.of(),
                List.of(),
                mapping);
    }

    void arrival(String id, Instant at, Object seconds, String direction) throws Exception {
        rows.put(
                "transit:latest:subway-arrival:1002:PA:" + id,
                json.writeValueAsString(
                        Map.of(
                                "eventType",
                                "subway.arrival",
                                "providerIds",
                                Map.of("lineId", "1002", "stationId", "PA", "trainId", id),
                                "eventId",
                                "arrival-" + id,
                                "occurredAt",
                                at.toString(),
                                "collectedAt",
                                at.toString(),
                                "validUntil",
                                at.plusSeconds(120).toString(),
                                "observation",
                                Map.of(
                                        "arrivalDirection",
                                        direction,
                                        "arrivalDestination",
                                        "종점",
                                        "arrivalTrainType",
                                        "일반",
                                        "arrivalSeconds",
                                        seconds,
                                        "sourceGeneratedAt",
                                        at.toString()))));
    }

    SubwayTimingService.Estimate estimate(SubwayTimingService s, Instant platform) {
        return s.estimate(segment(1, "A", "B"), now, platform, s.newSession());
    }

    @Test
    void timetableWaitAndRideAreSeparateAndMissedTrainUsesNext() {
        var s =
                service(
                        true,
                        trip("T1", call("A", 60, true), call("B", 300, true)),
                        trip("T2", call("A", 180, true), call("B", 420, true)));
        var first = estimate(s, now);
        assertThat(first.waitSeconds()).isEqualTo(60);
        assertThat(first.rideSeconds()).isEqualTo(240);
        assertThat(first.arrivalAt()).isEqualTo(now.plusSeconds(300));
        assertThat(first.source()).isEqualTo("SUBWAY_TIMETABLE");
        var missed = estimate(s, now.plusSeconds(61));
        assertThat(missed.tripKey()).isEqualTo("T2");
        assertThat(missed.waitSeconds()).isEqualTo(119);
    }

    @Test
    void arrivalUsesGenerationTimeInsteadOfAddingSecondsToQueryTime() throws Exception {
        var s = service(true, trip("T1", call("A", 60, true), call("B", 300, true)));
        arrival("T1", now.minusSeconds(10), 80, "내선");
        var result = estimate(s, now);
        // 10초 전의 80초 후 도착 = 지금부터 70초. 80초를 다시 더하지 않는다.
        assertThat(result.waitSeconds()).isEqualTo(70);
        assertThat(result.rideSeconds()).isEqualTo(240);
        assertThat(result.arrivalAt()).isEqualTo(now.plusSeconds(310));
        assertThat(result.source()).isEqualTo("SUBWAY_REALTIME_ARRIVAL_AND_TIMETABLE");
        assertThat(result.arrivalEventId()).isEqualTo("arrival-T1");
        var key = "transit:latest:subway-arrival:1002:PA:T1";
        var local = json.readTree(rows.get(key));
        ((com.fasterxml.jackson.databind.node.ObjectNode) local.path("observation"))
                .put("sourceGeneratedAt", "2026-09-10 08:59:50");
        rows.put(key, json.writeValueAsString(local));
        assertThat(estimate(s, now)).isEqualTo(result);
    }

    @Test
    void staleAndMissingGeneratedTimeFallBackExplicitly() throws Exception {
        var s = service(true, trip("T1", call("A", 60, true), call("B", 300, true)));
        arrival("T1", now.minusSeconds(121), 180, "내선");
        assertThat(estimate(s, now).reasons()).contains("ARRIVAL:STALE_OR_INVALID");
        arrival("T1", now, 80, "내선");
        var key = "transit:latest:subway-arrival:1002:PA:T1";
        var node = json.readTree(rows.get(key));
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("observation"))
                .remove("sourceGeneratedAt");
        rows.put(key, json.writeValueAsString(node));
        var missing = estimate(s, now);
        assertThat(missing.source()).isEqualTo("SUBWAY_TIMETABLE");
        assertThat(missing.reasons()).contains("ARRIVAL:INVALID_PAYLOAD");
        arrival("T1", now, 80, "내선");
        node = json.readTree(rows.get(key));
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("observation"))
                .remove("arrivalTrainType");
        rows.put(key, json.writeValueAsString(node));
        assertThat(estimate(s, now).source()).isEqualTo("SUBWAY_TIMETABLE");
        assertThat(estimate(s, now).reasons()).contains("ARRIVAL:MISSING_TRAIN_TYPE");
    }

    @Test
    void directionConflictAndZeroArrivalDoNotClaimImmediateBoarding() throws Exception {
        var s = service(true, trip("T1", call("A", 60, true), call("B", 300, true)));
        arrival("T1", now, 60, "외선");
        assertThat(estimate(s, now).reasons())
                .contains("SUBWAY_ARRIVAL_IDENTITY_CONFLICT", "SUBWAY_NO_BOARDABLE_TRAIN");
        arrival("T1", now, 0, "내선");
        assertThat(estimate(s, now).arrivalAt()).isNull();
        assertThat(estimate(s, now).reasons()).contains("SUBWAY_ARRIVAL_SECONDS_UNUSABLE");
    }

    @Test
    void freshPositionAfterBoardingStationExcludesTrain() throws Exception {
        var s = service(true, trip("T1", call("A", 60, true), call("B", 300, true)));
        rows.put(
                "transit:latest:subway-position:1002:T1",
                json.writeValueAsString(
                        Map.of(
                                "eventType",
                                "subway.position",
                                "providerIds",
                                Map.of("lineId", "1002", "trainId", "T1", "stationId", "PB"),
                                "eventId",
                                "position-T1",
                                "occurredAt",
                                now.toString(),
                                "collectedAt",
                                now.toString(),
                                "validUntil",
                                now.plusSeconds(120).toString(),
                                "observation",
                                Map.of(
                                        "direction",
                                        "0",
                                        "destinationStation",
                                        "종점",
                                        "sourceGeneratedAt",
                                        now.toString()))));
        assertThat(estimate(s, now).reasons()).contains("SUBWAY_TRAIN_ALREADY_PASSED");
        assertThat(estimate(s, now).arrivalAt()).isNull();
    }

    @Test
    void expressSkippingDestinationAndShortTurnAreNotMatched() {
        var s =
                service(
                        true,
                        trip(
                                "EXP",
                                call("A", 60, true),
                                call("B", 100, false),
                                call("C", 200, true)),
                        trip("SHORT", call("X", 10, true), call("A", 60, true)));
        assertThat(estimate(s, now).reasons()).contains("SUBWAY_NO_MATCHING_TRIP");
    }

    @Test
    void redisFailureFallsBackAndSessionDoesNotRetryOrChangeSnapshot() throws Exception {
        var s = service(true, trip("T1", call("A", 60, true), call("B", 300, true)));
        var session = s.newSession();
        doThrow(new IllegalStateException("test failure")).when(values).multiGet(any());
        var first = s.estimate(segment(1, "A", "B"), now, now, session);
        var replay = s.estimate(segment(1, "A", "B"), now, now, session);
        assertThat(first).isEqualTo(replay);
        assertThat(first.reasons()).contains("SUBWAY_REDIS_UNAVAILABLE");
        assertThat(first.source()).isEqualTo("SUBWAY_TIMETABLE");
        verify(values, times(1)).multiGet(any());
    }

    @Test
    void missingOrInvalidTimetableAndMasterMismatchAreExplicit() throws Exception {
        var missing = new SubwayTimingService(new SubwayTimetable(json, ""), redis, json, true);
        assertThat(estimate(missing, now).reasons()).contains("SUBWAY_TIMETABLE_NOT_CONFIGURED");
        var s = service(true, trip("T1", call("A", 60, true), call("B", 300, true)));
        var e = segment(1, "A", "B");
        var m = e.mapping();
        var different =
                new TransitMappingResult(
                        m.status(),
                        "other",
                        m.mappingPolicyVersion(),
                        m.officialRouteId(),
                        m.officialRouteName(),
                        m.fromNodeId(),
                        m.toNodeId(),
                        m.startOrder(),
                        m.endOrder(),
                        m.direction(),
                        1,
                        1,
                        1,
                        null,
                        m.realtimeCoverage(),
                        m.segments());
        var changed =
                new RouteSegment(
                        e.sequence(),
                        e.mode(),
                        e.segmentId(),
                        e.providerRouteKey(),
                        e.guidance(),
                        e.fromName(),
                        e.toName(),
                        e.distanceMeters(),
                        e.durationSeconds(),
                        e.stopNames(),
                        e.vehicles(),
                        e.geometry(),
                        different);
        assertThat(s.estimate(changed, now, now, s.newSession()).reasons())
                .contains("SUBWAY_MASTER_VERSION_MISMATCH");
        assertThatThrownBy(
                        () ->
                                new SubwayTimetable(
                                        data(
                                                trip(
                                                        "T1",
                                                        call("A", 100, true),
                                                        call("B", 90, true)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void providerResidualPreventsDuplicatedTimetableWaitAndKeepsProviderTotal() {
        var trip = trip("T1", call("A", 60, true), call("B", 300, true));
        var bus = mock(BusPreviewClient.class);
        var evidence = new RouteEvidenceAssembler(redis, json);
        var snapshot = new RouteEvidenceAssembler.Snapshot(Map.of(), List.of());
        for (boolean verified : List.of(false, true))
            for (long residual : List.of(0L, 30L)) {
                var preview =
                        new MlRoutePreview(
                                bus,
                                evidence,
                                new RouteTimelineEvaluator(),
                                service(verified, trip),
                                new BusWaitingService(redis, json, false));
                var route =
                        new RouteCandidate(
                                0,
                                RouteType.SUBWAY,
                                100,
                                999 + residual,
                                0,
                                new FareRange(0, 0),
                                0,
                                residual,
                                List.of(segment(1, "A", "B")));
                var result =
                        preview.compare(List.of(route), snapshot, now, now, now.plusSeconds(2000));
                boolean applied = residual == 0;
                if (applied) assertThat(result.subwayPredictions().get(0).applied()).isTrue();
                else assertThat(result.subwayPredictions()).isEmpty();
                assertThat(result.candidates().get(0).subwaySteps()).isEqualTo(applied ? 1 : 0);
                assertThat(result.fastest().estimatedArrivalAt())
                        .isEqualTo(now.plusSeconds(applied ? 300 : 999 + residual));
                if (!applied)
                    assertThat(result.fastest().reasons())
                            .contains("step-1:WAIT_TIME_COVERED_BY_PROVIDER_TOTAL");
            }
        verifyNoInteractions(bus);
    }

    @Test
    void transferWalkUpdatesPlatformTimeAndMissedConnectionUsesNextTrain() {
        var s =
                service(
                        true,
                        trip("T1", call("A", 60, true), call("B", 200, true), call("C", 400, true)),
                        trip("T2", call("B", 500, true), call("C", 600, true)));
        var walk =
                new RouteSegment(
                        2,
                        TransportMode.WALK,
                        null,
                        null,
                        null,
                        "B1",
                        "B2",
                        50,
                        45,
                        List.of(),
                        List.of(),
                        List.of(),
                        null);
        var route =
                new RouteCandidate(
                        0,
                        RouteType.SUBWAY,
                        250,
                        2043,
                        1,
                        new FareRange(0, 0),
                        0,
                        0,
                        List.of(segment(1, "A", "B"), walk, segment(3, "B", "C")));
        var preview =
                new MlRoutePreview(
                        mock(BusPreviewClient.class),
                        new RouteEvidenceAssembler(redis, json),
                        new RouteTimelineEvaluator(),
                        s,
                        new BusWaitingService(redis, json, false));
        var result =
                preview.compare(
                        List.of(route),
                        new RouteEvidenceAssembler.Snapshot(Map.of(), List.of()),
                        now,
                        now,
                        now.plusSeconds(2000));
        assertThat(result.subwayPredictions().get(1).platformAt()).isEqualTo(now.plusSeconds(245));
        assertThat(result.subwayPredictions().get(1).estimate().tripKey()).isEqualTo("T2");
        assertThat(result.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(600));
        assertThat(result.waitPredictions()).hasSize(2);
        assertThat(result.waitPredictions().get(1).seconds()).isEqualTo(255);
        assertThat(result.waitPredictions())
                .allMatch(w -> w.applied() && w.mode() == TransportMode.SUBWAY);
    }

    @Test
    void afterMidnightUsesExplicitServiceDateAndDoesNotInventLastTrain() {
        var s = service(true, trip("NIGHT", call("A", 54010, true), call("B", 54130, true)));
        var result = estimate(s, now.plusSeconds(53990)); // KST 23:59:50 → 다음날 00:00:10 탑승
        assertThat(result.waitSeconds()).isEqualTo(20);
        assertThat(result.arrivalAt()).isEqualTo(now.plusSeconds(54130));
        assertThat(estimate(s, now.plusSeconds(54200)).reasons())
                .contains("SUBWAY_NO_BOARDABLE_TRAIN");
    }

    @Test
    void fileLoaderValidatesVersionedArtifactAndRejectsMalformedFile(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("timetable.json");
        var input = data(trip("T1", call("A", 60, true), call("B", 300, true)));
        var writer =
                json.writer()
                        .without(
                                com.fasterxml.jackson.databind.SerializationFeature
                                        .WRITE_DATES_AS_TIMESTAMPS);
        Files.writeString(file, writer.writeValueAsString(input));
        assertThat(new SubwayTimetable(json, file.toString()).data()).isEqualTo(input);
        Files.writeString(file, "{}");
        assertThat(new SubwayTimetable(json, file.toString()).unavailableReason())
                .isEqualTo("SUBWAY_TIMETABLE_INVALID");
        var future =
                new SubwayTimetable.Data(
                        input.version(),
                        input.masterDataVersion(),
                        input.source(),
                        now.plusSeconds(1),
                        input.validFrom(),
                        input.validUntil(),
                        input.trips());
        var s = new SubwayTimingService(new SubwayTimetable(future), redis, json, true);
        assertThat(estimate(s, now).reasons()).contains("SUBWAY_TIMETABLE_OUTSIDE_VALIDITY");
    }

    @Test
    void subwayReplacementParticipatesInFastestCandidateSelection() {
        var s = service(true, trip("T1", call("A", 60, true), call("B", 300, true)));
        var bus = mock(BusPreviewClient.class);
        when(bus.predict(any()))
                .thenReturn(BusPreviewClient.Estimate.unavailable("ML_NOT_CONFIGURED"));
        var subway =
                new RouteCandidate(
                        0,
                        RouteType.SUBWAY,
                        100,
                        999,
                        0,
                        new FareRange(0, 0),
                        0,
                        0,
                        List.of(segment(1, "A", "B")));
        var preview =
                new MlRoutePreview(
                        bus,
                        new RouteEvidenceAssembler(redis, json),
                        new RouteTimelineEvaluator(),
                        s,
                        new BusWaitingService(redis, json, false));
        var result =
                preview.compare(
                        List.of(subway, MlRoutePreviewTest.route(1, "1", 500, 0, 1)),
                        new RouteEvidenceAssembler.Snapshot(Map.of(), List.of()),
                        now,
                        now,
                        now.plusSeconds(1000));
        assertThat(result.fastest().journeyKey()).isEqualTo("candidate-0");
        assertThat(result.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(300));
    }

    @Test
    void trackClosureEndingWhileWaitingDoesNotBlockLaterRide() throws Exception {
        var s = service(true, trip("T1", call("A", 60, true), call("B", 300, true)));
        var subway =
                new RouteCandidate(
                        0,
                        RouteType.SUBWAY,
                        100,
                        999,
                        0,
                        new FareRange(0, 0),
                        0,
                        0,
                        List.of(segment(1, "A", "B")));
        var state =
                json.readTree(
                        "{\"status\":\"blocked\",\"computedAt\":\"2026-09-10T00:00:00Z\",\"validUntil\":\"2026-09-10T00:00:30Z\"}");
        var preview =
                new MlRoutePreview(
                        mock(BusPreviewClient.class),
                        new RouteEvidenceAssembler(redis, json),
                        new RouteTimelineEvaluator(),
                        s,
                        new BusWaitingService(redis, json, false));
        var result =
                preview.compare(
                        List.of(subway),
                        new RouteEvidenceAssembler.Snapshot(Map.of("SUB:L:A:B", state), List.of()),
                        now,
                        now,
                        now.plusSeconds(1000));
        assertThat(result.fastest()).isNotNull();
        assertThat(result.fastest().steps().get(0).vehicleId()).isEqualTo("T1");
        assertThat(result.fastest().steps().get(1).enteredAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    void missingTrainUsesProviderRideAndOmitsUnavailableWait() {
        var s = service(true, trip("T1", call("A", 60, true), call("B", 300, true)));
        var route =
                new RouteCandidate(
                        0,
                        RouteType.SUBWAY,
                        100,
                        999,
                        0,
                        new FareRange(0, 0),
                        0,
                        0,
                        List.of(segment(1, "A", "B")));
        var preview =
                new MlRoutePreview(
                        mock(BusPreviewClient.class),
                        new RouteEvidenceAssembler(redis, json),
                        new RouteTimelineEvaluator(),
                        s,
                        new BusWaitingService(redis, json, false));
        var result =
                preview.compare(
                        List.of(route),
                        new RouteEvidenceAssembler.Snapshot(Map.of(), List.of()),
                        now,
                        now.plusSeconds(400),
                        now.plusSeconds(2000));
        assertThat(result.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(400 + 999));
        assertThat(result.fastest().reasons()).contains("step-1:WAIT_TIME_EXCLUDED");
        assertThat(result.waitPredictions().get(0).seconds()).isNull();
        assertThat(result.candidates().get(0).result().assessment())
                .isEqualTo(RouteTimelineEvaluator.Assessment.ON_TIME_ESTIMATE);
    }

    /** Redis 도착·위치 증거의 더 짧은 TTL을 최종 평가까지 전달한다. 시간표 fallback에는 도착 TTL을 쓰지 않는다. */
    @Test
    void appliedSubwayEvidenceCapsFinalValidityAtEarliestSourceExpiry() throws Exception {
        arrival("T1", now, 10, "내선");
        var key = "transit:latest:subway-arrival:1002:PA:T1";
        var row = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(rows.get(key));
        row.put("validUntil", now.plusSeconds(20).toString());
        rows.put(key, json.writeValueAsString(row));
        rows.put(
                "transit:latest:subway-position:1002:T1",
                json.writeValueAsString(
                        Map.of(
                                "eventType",
                                "subway.position",
                                "providerIds",
                                Map.of("lineId", "1002", "trainId", "T1", "stationId", "PA"),
                                "eventId",
                                "position-T1",
                                "occurredAt",
                                now.toString(),
                                "collectedAt",
                                now.toString(),
                                "validUntil",
                                now.plusSeconds(15).toString(),
                                "observation",
                                Map.of(
                                        "direction",
                                        "0",
                                        "destinationStation",
                                        "종점",
                                        "sourceGeneratedAt",
                                        now.toString()))));
        var route =
                new RouteCandidate(
                        0,
                        RouteType.SUBWAY,
                        100,
                        999,
                        0,
                        new FareRange(0, 0),
                        0,
                        0,
                        List.of(segment(1, "A", "B")));
        var preview =
                new MlRoutePreview(
                        mock(BusPreviewClient.class),
                        new RouteEvidenceAssembler(redis, json),
                        new RouteTimelineEvaluator(),
                        service(true, trip("T1", call("A", 60, true), call("B", 300, true))),
                        new BusWaitingService(redis, json, false));
        var snapshot = new RouteEvidenceAssembler.Snapshot(Map.of(), List.of());
        var evaluation =
                new ConditionalDepartureEvaluator(null, null, 256, 60, 5000)
                        .evaluate(
                                1,
                                now,
                                now,
                                now.plusSeconds(2000),
                                List.of(),
                                at ->
                                        preview.compare(
                                                List.of(route),
                                                snapshot,
                                                now,
                                                at,
                                                now.plusSeconds(2000)));
        var stored =
                json.readValue(
                        json.writeValueAsString(evaluation),
                        ConditionalDepartureEvaluator.Evaluation.class);
        assertThat(
                        stored.proposedComparison()
                                .subwayPredictions()
                                .get(0)
                                .estimate()
                                .arrivalEventId())
                .isEqualTo("arrival-T1");
        assertThat(ScheduleEvaluationV2.validity(stored, snapshot, now, null))
                .isEqualTo(now.plusSeconds(15));
        rows.remove("transit:latest:subway-position:1002:T1");
        assertThat(
                        estimate(
                                        service(
                                                true,
                                                trip(
                                                        "T1",
                                                        call("A", 60, true),
                                                        call("B", 300, true))),
                                        now)
                                .validUntil())
                .isEqualTo(now.plusSeconds(20));
        rows.clear();
        assertThat(
                        estimate(
                                        service(
                                                true,
                                                trip(
                                                        "T1",
                                                        call("A", 60, true),
                                                        call("B", 300, true))),
                                        now)
                                .validUntil())
                .isEqualTo(now.plusSeconds(86400));
    }
}
