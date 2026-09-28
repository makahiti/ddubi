package com.ssafy.ddubi.domain.recommendation.evaluation;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.client.*;
import com.ssafy.ddubi.domain.route.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** T27~T34: 도보 방향·EXACT 좌표·교체 비용·장애 fallback을 합성 마스터로 재현한다. */
class WalkingEnrichmentTest {
    final Instant now = Instant.parse("2026-09-16T00:00:00Z");
    final Coordinate origin = new Coordinate(127, 37.5),
            board = new Coordinate(127.001, 37.501),
            alight = new Coordinate(127.002, 37.502),
            destination = new Coordinate(127.003, 37.503);

    RouteSegment walk(int sequence) {
        return new RouteSegment(
                sequence,
                TransportMode.WALK,
                null,
                null,
                null,
                "A",
                "B",
                50,
                60,
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    RouteCandidate route(long residual) {
        var original = RouteEvidenceAssemblerTest.candidate(0, 600, 0).segments().get(0);
        var ride =
                new RouteSegment(
                        2,
                        original.mode(),
                        original.segmentId(),
                        original.providerRouteKey(),
                        null,
                        "A",
                        "B",
                        1000,
                        600,
                        original.stopNames(),
                        original.vehicles(),
                        List.of(board, alight),
                        original.mapping());
        return new RouteCandidate(
                0,
                RouteType.BUS,
                1100,
                720 + residual,
                0,
                null,
                0,
                residual,
                List.of(walk(1), ride, walk(3)));
    }

    List<WalkingEndpointResolver.MasterPoint> points(String meaning) {
        return List.of(
                new WalkingEndpointResolver.MasterPoint(
                        "2", "master", board, meaning, "OFFICIAL_FIXTURE"),
                new WalkingEndpointResolver.MasterPoint(
                        "3", "master", alight, meaning, "OFFICIAL_FIXTURE"));
    }

    WalkingEnrichmentService service(WalkingRouteClient client) {
        return new WalkingEnrichmentService(client, new ObjectMapper(), true, 20, "");
    }

    @Test
    void accessAndEgressHaveCorrectDirectionAndReplaceInsteadOfAdding() {
        var seen = new ArrayList<WalkingRouteClient.Request>();
        var output =
                service(
                                r -> {
                                    seen.add(r);
                                    return new WalkingRouteClient.Evidence(
                                            120L, now.plusSeconds(60), "fixture", null);
                                })
                        .prepare(List.of(route(0)), origin, destination, now, points("BUS_STOP"));
        assertThat(seen).hasSize(2);
        assertThat(seen.get(0).origin()).isEqualTo(origin);
        assertThat(seen.get(0).destination()).isEqualTo(board);
        assertThat(seen.get(1).origin()).isEqualTo(alight);
        assertThat(seen.get(1).destination()).isEqualTo(destination);
        assertThat(output.candidates().get(0).totalDurationSeconds()).isEqualTo(840);
        assertThat(output.evidence().legs())
                .extracting(WalkingEnrichmentService.Leg::legRole)
                .containsExactly("ACCESS_WALK", "RIDE", "EGRESS_WALK");
    }

    @Test
    void walkingPathReplacesSegmentGeometryForTheMap() {
        var line = List.of(new Coordinate(127.0005, 37.5005), new Coordinate(127.001, 37.501));
        var output =
                service(
                                r ->
                                        new WalkingRouteClient.Evidence(
                                                120L, now.plusSeconds(60), "fixture", null, line))
                        .prepare(List.of(route(0)), origin, destination, now, points("BUS_STOP"));
        var segments = output.candidates().get(0).segments();
        // 도보 구간은 실제 걷는 길 좌표를 갖고, 대중교통 구간의 좌표는 그대로다.
        assertThat(segments.get(0).geometry()).isEqualTo(line);
        assertThat(segments.get(2).geometry()).isEqualTo(line);
        assertThat(segments.get(1).geometry()).containsExactly(board, alight);
    }

    @Test
    void walkingWithoutPathKeepsProviderGeometry() {
        var output =
                service(
                                r ->
                                        new WalkingRouteClient.Evidence(
                                                120L, now.plusSeconds(60), "fixture", null))
                        .prepare(List.of(route(0)), origin, destination, now, points("BUS_STOP"));
        // 좌표가 없으면 원본 좌표를 그대로 둔다. 시간만 도보 API 값으로 바뀐다.
        assertThat(output.candidates().get(0).segments().get(0).geometry()).isEmpty();
        assertThat(output.evidence().legs().get(0).durationSeconds()).isEqualTo(120);
    }

    @Test
    void existingWalksReplaceTheirOwnCostsWithoutResidual() {
        var output =
                service(
                                r ->
                                        new WalkingRouteClient.Evidence(
                                                120L, now.plusSeconds(60), "fixture", null))
                        .prepare(List.of(route(30)), origin, destination, now, points("BUS_STOP"));
        assertThat(output.evidence().apiCalls()).isEqualTo(2);
        assertThat(output.candidates().get(0).totalDurationSeconds()).isEqualTo(840);
        assertThat(output.candidates().get(0).unassignedDurationSeconds()).isZero();
    }

    @Test
    void stationCenterIsNotAnEntrance() {
        var output =
                service(
                                r -> {
                                    throw new AssertionError("호출 금지");
                                })
                        .prepare(
                                List.of(route(0)),
                                origin,
                                destination,
                                now,
                                points("STATION_CENTER"));
        assertThat(output.evidence().fallbackReasons()).contains("STATION_CENTER_ONLY");
    }

    @Test
    void missingWalksAreSummedIndependentlyOfProviderTotal() {
        // 실응답과 같은 양 끝 도보 누락·5~40분 잔여시간, 도보가 잔여보다 긴 경우도 재현한다.
        for (long residual : List.of(1L, 300L, 2400L)) {
            var ride = route(0).segments().get(1);
            var input =
                    new RouteCandidate(
                            0,
                            RouteType.BUS,
                            1000,
                            600 + residual,
                            0,
                            null,
                            0,
                            residual,
                            List.of(ride));
            var output =
                    service(
                                    r ->
                                            new WalkingRouteClient.Evidence(
                                                    120L, now.plusSeconds(60), "fixture", null))
                            .prepare(List.of(input), origin, destination, now, points("BUS_STOP"));
            var actual = output.candidates().get(0);
            assertThat(output.evidence().apiCalls()).isEqualTo(2);
            long providerTotal = 600 + residual;
            long expectedTotal = Math.max(840, providerTotal);
            assertThat(actual.totalDurationSeconds()).isEqualTo(expectedTotal);
            assertThat(actual.unassignedDurationSeconds()).isEqualTo(expectedTotal - 840);
            assertThat(actual.segments()).hasSize(3);
            // 합성 도보도 route_segment에 저장되므로 양 끝 이름이 DB NOT NULL 계약을 만족해야 한다.
            assertThat(actual.segments().get(0).fromName()).isEqualTo("출발지");
            assertThat(actual.segments().get(0).toName()).isEqualTo("A");
            assertThat(actual.segments().get(2).fromName()).isEqualTo("B");
            assertThat(actual.segments().get(2).toName()).isEqualTo("도착지");
            assertThat(actual.totalDurationSeconds())
                    .isEqualTo(
                            actual.unassignedDurationSeconds()
                                    + actual.segments().stream()
                                            .mapToLong(RouteSegment::durationSeconds)
                                            .sum());
        }
    }

    @Test
    void oneEndpointFailurePreservesUnknownCostWithoutInventingZeroWalk() {
        var ride = route(0).segments().get(1);
        var input = new RouteCandidate(0, RouteType.BUS, 1000, 900, 0, null, 0, 300, List.of(ride));
        var output =
                service(
                                r ->
                                        r.origin().equals(origin)
                                                ? new WalkingRouteClient.Evidence(
                                                        120L, now.plusSeconds(60), "fixture", null)
                                                : new WalkingRouteClient.Evidence(
                                                        null, null, null, "WALK_TIMEOUT"))
                        .prepare(List.of(input), origin, destination, now, points("BUS_STOP"));
        assertThat(output.candidates().get(0).totalDurationSeconds()).isEqualTo(900);
        assertThat(output.candidates().get(0).unassignedDurationSeconds()).isEqualTo(180);
        assertThat(output.candidates().get(0).segments()).hasSize(3);
        assertThat(output.candidates().get(0).segments())
                .anyMatch(
                        segment ->
                                "EVALUATION_ENDPOINT_WALK".equals(segment.providerRouteKey())
                                        && segment.durationSeconds() == 0);
        assertThat(output.evidence().fallbackReasons())
                .contains("WALK_TIMEOUT", "WALK_ENDPOINT_DURATION_UNAVAILABLE");
    }

    @Test
    void provider403429TimeoutRemainCandidateLocalFallback() {
        for (String reason : List.of("WALK_HTTP_403", "WALK_HTTP_429", "WALK_TIMEOUT")) {
            var output =
                    service(r -> new WalkingRouteClient.Evidence(null, null, null, reason))
                            .prepare(
                                    List.of(route(0)),
                                    origin,
                                    destination,
                                    now,
                                    points("BUS_STOP"));
            assertThat(output.candidates().get(0).totalDurationSeconds()).isEqualTo(720);
            assertThat(output.evidence().fallbackReasons()).contains(reason);
        }
    }

    @Test
    void conflictingCoordinateSkipsProvider() {
        var points =
                List.of(
                        new WalkingEndpointResolver.MasterPoint(
                                "2", "master", destination, "BUS_STOP", "fixture"));
        var output =
                service(
                                r -> {
                                    throw new AssertionError("호출 금지");
                                })
                        .prepare(List.of(route(0)), origin, destination, now, points);
        assertThat(output.evidence().fallbackReasons()).contains("WALK_ENDPOINT_UNVERIFIED");
    }

    @Test
    void middleWalkRemainsTransfer() {
        var steps =
                List.of(
                        route(0).segments().get(1),
                        walk(4),
                        new RouteSegment(
                                5,
                                TransportMode.SUBWAY,
                                null,
                                null,
                                null,
                                "B",
                                "C",
                                100,
                                60,
                                List.of(),
                                List.of(),
                                List.of(),
                                null));
        assertThat(new RouteLegRoleClassifier().classify(steps).get(4))
                .isEqualTo(RouteLegRoleClassifier.Role.TRANSFER_WALK);
    }

    @Test
    void walkingEvidenceExpiryLimitsResultAndStaleResponseFallsBack() {
        var good =
                service(
                                r ->
                                        new WalkingRouteClient.Evidence(
                                                120L, now.plusSeconds(12), "fixture", null))
                        .prepare(List.of(route(0)), origin, destination, now, points("BUS_STOP"));
        var f = new ConditionalDepartureEvaluatorTest();
        var evaluated =
                f.engine(256)
                        .evaluate(
                                1,
                                now,
                                f.p,
                                f.d,
                                List.of(),
                                at -> f.comparison(at, at.plusSeconds(840)))
                        .withWalking(good.evidence());
        assertThat(ScheduleEvaluationV2.validity(evaluated, null, now, null))
                .isEqualTo(now.plusSeconds(12));
        var stale =
                service(r -> new WalkingRouteClient.Evidence(120L, now, "fixture", null))
                        .prepare(List.of(route(0)), origin, destination, now, points("BUS_STOP"));
        assertThat(stale.candidates().get(0).totalDurationSeconds()).isEqualTo(720);
        assertThat(stale.evidence().validUntil()).isNull();
    }

    @Test
    void scheduledWalkingResultRemainsValidUntilScheduleEnds() {
        var service =
                service(
                        r ->
                                new WalkingRouteClient.Evidence(
                                        120L, now.plusSeconds(60), "fixture", null));
        var prepared =
                service.prepare(List.of(route(0)), origin, destination, now, points("BUS_STOP"));
        Instant scheduleEndsAt = now.plus(Duration.ofHours(24));

        var retained = service.retainUntil(prepared, scheduleEndsAt);

        assertThat(retained.evidence().validUntil()).isEqualTo(scheduleEndsAt);
        assertThat(retained.candidates()).isEqualTo(prepared.candidates());
    }

    @Test
    void missingCoordinateMeaningCannotCrashOrCallProvider() {
        var output =
                service(
                                r -> {
                                    throw new AssertionError("호출 금지");
                                })
                        .prepare(List.of(route(0)), origin, destination, now, points(null));
        assertThat(output.evidence().fallbackReasons()).contains("WALK_ENDPOINT_UNVERIFIED");
    }

    @Test
    void walkingOnlyNormalizesRoleWithoutInventingBoardingEndpoint() {
        var route = new RouteCandidate(0, RouteType.BUS, 50, 60, 0, null, 0, 0, List.of(walk(1)));
        var output =
                service(
                                r -> {
                                    throw new AssertionError("호출 금지");
                                })
                        .prepare(List.of(route), origin, destination, now, List.of());
        assertThat(output.evidence().legs().get(0).legRole()).isEqualTo("ACCESS_WALK");
        assertThat(output.evidence().fallbackReasons()).contains("WALK_NO_TRANSIT_ENDPOINT");
    }

    @Test
    void verifiedEndpointRemainsStructuredWhenHttpFails() {
        var output =
                service(r -> new WalkingRouteClient.Evidence(null, null, null, "WALK_HTTP_403"))
                        .prepare(List.of(route(0)), origin, destination, now, points("BUS_STOP"));
        var leg = output.evidence().legs().get(0);
        assertThat(leg.durationSource()).isEqualTo("PUBLIC_TRANSIT_STEP");
        assertThat(leg.endpointEvidence().verified()).isTrue();
        assertThat(leg.endpointEvidence().source()).isEqualTo("OFFICIAL_FIXTURE");
        assertThat(leg.endpointEvidence().mappingVersion()).isEqualTo("policy");
        assertThat(output.evidence().fallbackReasons()).contains("WALK_HTTP_403");
    }

    @Test
    void candidateRefreshCachesResponsesWithoutCallLimit() {
        var seen = new ArrayList<WalkingRouteClient.Request>();
        var service =
                new WalkingEnrichmentService(
                        r -> {
                            seen.add(r);
                            return new WalkingRouteClient.Evidence(
                                    90L, now.plusSeconds(60), "fixture", null);
                        },
                        new ObjectMapper(),
                        true,
                        2,
                        20,
                        "");
        var session = new WalkingEnrichmentService.Session();
        service.prepare(List.of(route(0)), origin, destination, now, points("BUS_STOP"), session);
        var repeated =
                service.prepare(
                        List.of(route(0)), origin, destination, now, points("BUS_STOP"), session);
        assertThat(seen).hasSize(2);
        assertThat(repeated.candidates().get(0).totalDurationSeconds()).isEqualTo(780);
        var changed =
                service.prepare(
                        List.of(route(0)), destination, origin, now, points("BUS_STOP"), session);
        assertThat(seen).hasSize(4);
        assertThat(changed.evidence().fallbackReasons()).isEmpty();
        assertThat(changed.evidence().apiCalls()).isEqualTo(4);
    }

    @Test
    void dtCoordinateArtifactIsConsumedWithoutGuessingMissingEvidence(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var root =
                json.readTree(
                        java.nio.file.Path.of(
                                        "src/test/resources/conditional-departure-dt/transit-coordinates.json")
                                .toFile());
        var artifact = new WalkingCoordinateArtifact(root);
        assertThat(artifact.matching(route(0).segments().get(1), true, now)).isEmpty();
        // 계약 fixture의 ID/순번을 현재 합성 노선에 맞춘다. 운영 데이터나 매핑을 생성하는 코드가 아니다.
        var rows = ((com.fasterxml.jackson.databind.node.ObjectNode) root).putArray("records");
        for (int i = 0; i < 2; i++) {
            var point = i == 0 ? board : alight;
            rows.addObject()
                    .put("mode", "BUS")
                    .put("providerNamespace", "SEOUL_BUS")
                    .put("providerRouteId", "1")
                    .put("providerStopId", String.valueOf(i + 2))
                    .put("canonicalNodeId", "BUS_STOP:SEOUL_BUS:" + (i + 2))
                    .put("masterVersion", "master")
                    .put("mappingStatus", "EXACT")
                    .put("occurrence", 1)
                    .put("direction", "FORWARD")
                    .put("coordinateType", "STOP_POINT")
                    .put("source", "SYNTHETIC_FIXTURE")
                    .put("sourceRef", "route=1;sequence=" + (i + 1))
                    .put("longitude", point.longitude())
                    .put("latitude", point.latitude());
        }
        var file = dir.resolve("coordinates.json");
        json.writeValue(file.toFile(), root);
        var service =
                new WalkingEnrichmentService(
                        r ->
                                new WalkingRouteClient.Evidence(
                                        90L, now.plusSeconds(60), "fixture", null),
                        json,
                        true,
                        4,
                        20,
                        file.toString());
        assertThat(
                        service.prepare(List.of(route(0)), origin, destination, now)
                                .candidates()
                                .get(0)
                                .totalDurationSeconds())
                .isEqualTo(780);
        var row = (com.fasterxml.jackson.databind.node.ObjectNode) rows.get(0);
        row.put("direction", "REVERSE");
        assertThat(
                        new WalkingCoordinateArtifact(root)
                                .matching(route(0).segments().get(1), true, now))
                .isEmpty();
        row.put("direction", "FORWARD").put("validUntil", now.toString());
        assertThat(
                        new WalkingCoordinateArtifact(root)
                                .matching(route(0).segments().get(1), true, now))
                .isEmpty();
    }

    @Test
    void legacyStoredEvidenceRemainsReadableButDoesNotClaimNewVerification() throws Exception {
        var leg =
                new ObjectMapper()
                        .readValue(
                                """
                {"candidateKey":"candidate-0","sequence":1,"legRole":"WALK_ONLY","durationSeconds":60,
                 "durationSource":"KAKAO_TRANSIT","endpointEvidence":"EXACT:source:master:BUS_STOP"}
                """,
                                WalkingEnrichmentService.Leg.class);
        assertThat(leg.legRole()).isEqualTo("ACCESS_WALK");
        assertThat(leg.durationSource()).isEqualTo("PUBLIC_TRANSIT_STEP");
        assertThat(leg.endpointEvidence().verified()).isFalse();
        assertThat(leg.endpointEvidence().reason()).isEqualTo("LEGACY_ENDPOINT_EVIDENCE");
    }
}
