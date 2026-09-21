package com.ssafy.ddubi.domain.recommendation.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** TTL과 freshness를 구분하고 전체 관측 교체/부분 관측 fallback/미배치 시간 보존을 검증한다. */
public class RouteEvidenceAssemblerTest {
    final ObjectMapper json = new ObjectMapper();
    final Instant now = Instant.parse("2026-09-10T00:00:00Z");

    public static RouteCandidate candidate(int index, long seconds, long residual) {
        var edge =
                new MappedTransitSegment(
                        1,
                        "BUS:1:2:3",
                        TransportMode.BUS,
                        "1",
                        "1",
                        "100",
                        "FORWARD",
                        "2",
                        "3",
                        "2",
                        "3",
                        1,
                        2);
        var mapping =
                new TransitMappingResult(
                        TransitMappingStatus.EXACT,
                        "master",
                        "policy",
                        "1",
                        "100",
                        "2",
                        "3",
                        1,
                        2,
                        "FORWARD",
                        1,
                        1,
                        1,
                        null,
                        RealtimeMappingCoverage.FULL,
                        List.of(edge));
        var leg =
                new RouteSegment(
                        1,
                        TransportMode.BUS,
                        "BUS:1:2:3",
                        "100-" + index,
                        null,
                        "A",
                        "B",
                        1000,
                        seconds,
                        List.of("A", "B"),
                        List.of(new VehicleInfo("100-" + index, "BUS")),
                        List.of(),
                        mapping);
        return new RouteCandidate(
                index,
                RouteType.BUS,
                1000,
                seconds + residual,
                0,
                new FareRange(1500, 1500),
                0,
                residual,
                List.of(leg));
    }

    @Test
    void redisObservationDoesNotReplaceMovementAndUnassignedTimeIsExcluded() throws Exception {
        var assembler = new RouteEvidenceAssembler(mock(StringRedisTemplate.class), json);
        var state =
                json.readTree(
                        """
                {"segmentId":"BUS:1:2:3","status":"delayed","observedSeconds":120,
                 "delaySeconds":60,"observationCount":3,"observedAt":"2026-09-10T00:00:00Z",
                 "computedAt":"2026-09-10T00:00:00Z","validUntil":"2026-09-10T00:05:00Z"}
                """);
        var snapshot = new RouteEvidenceAssembler.Snapshot(Map.of("BUS:1:2:3", state), List.of());
        var journey = assembler.assemble(candidate(0, 60, 30), snapshot, now);
        var result =
                new RouteTimelineEvaluator()
                        .evaluate(journey, now, now, now.plusSeconds(300), List.of());
        // 관측 120초/지연 60초로 API 이동을 교체하지 않는다. API 60초 + 잔여 30초만 보존한다.
        assertThat(result.estimatedArrivalAt()).isEqualTo(now.plusSeconds(60));
        assertThat(result.tentative()).isTrue();
        var future =
                new RouteTimelineEvaluator()
                        .evaluate(
                                journey,
                                now,
                                now.plusSeconds(600),
                                now.plusSeconds(1000),
                                List.of());
        assertThat(future.estimatedArrivalAt()).isEqualTo(now.plusSeconds(660));
    }

    @Test
    void redisOutageKeepsExplicitFallback() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("secret"));
        var snapshot =
                new RouteEvidenceAssembler(redis, json).read(List.of(candidate(0, 60, 30)), now);
        assertThat(snapshot.states()).isEmpty();
        assertThat(snapshot.reasons()).containsExactly("REDIS_UNAVAILABLE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleOrMismatchedIdCannotBecomeCurrentState() {
        var redis = mock(StringRedisTemplate.class);
        var values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(any()))
                .thenReturn(
                        List.of(
                                "{\"segmentId\":\"BUS:9:8:7\",\"status\":\"normal\",\"computedAt\":\"2026-09-10T00:00:00Z\",\"validUntil\":\"2026-09-10T00:05:00Z\"}"));
        var snapshot =
                new RouteEvidenceAssembler(redis, json).read(List.of(candidate(0, 60, 30)), now);
        assertThat(snapshot.states()).isEmpty();
        assertThat(snapshot.reasons()).containsExactly("BUS:1:2:3:STALE_OR_INVALID");
    }
}
