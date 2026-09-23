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
    void redisObservationDoesNotReplaceMovementAndUnassignedTimeUsesProviderTotal()
            throws Exception {
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
        // 관측 120초/지연 60초로 API 이동을 교체하지 않는다. 분해되지 않은 30초를 버리지 않고
        // 카카오 전체시간 90초로 fallback해 도보·대기 누락으로 사용자를 늦게 보내지 않는다.
        assertThat(result.estimatedArrivalAt()).isEqualTo(now.plusSeconds(90));
        assertThat(result.tentative()).isTrue();
        assertThat(result.steps())
                .extracting(RouteTimelineEvaluator.Step::key)
                .containsExactly("step-1", "provider-unassigned");
        var future =
                new RouteTimelineEvaluator()
                        .evaluate(
                                journey,
                                now,
                                now.plusSeconds(600),
                                now.plusSeconds(1000),
                                List.of());
        assertThat(future.estimatedArrivalAt()).isEqualTo(now.plusSeconds(690));
    }

    /**
     * 승인 파일이 설정되지 않은 승인 근거 부품은 구간마다 "설정 없음" 값을 돌려준다. 그것을 승인 근거로 읽으면 후보 근거가 한 번도 쓰이지 않는다 (2026-09-23
     * 운영에서 실제로 그랬다). 설정이 없으면 후보 근거로 넘어가고, 설정이 있으면 승인 근거가 이긴다.
     */
    @Test
    void unconfiguredApprovedEvidenceDoesNotShadowProvisionalEvidence() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenReturn(java.util.Arrays.asList((String) null));
        var unconfigured = new RoadImpactEvidence(json, redis, "", "", 600);
        var provisional = mock(ProvisionalRoadEvidence.class);
        var marker =
                json.readTree("{\"segmentId\":\"BUS:1:2:3\",\"mappingStatus\":\"PROVISIONAL\"}");
        when(provisional.enabled()).thenReturn(true);
        when(provisional.read(anyList(), any())).thenReturn(Map.of("BUS:1:2:3:1", marker));

        var snapshot =
                new RouteEvidenceAssembler(redis, json, unconfigured, provisional)
                        .read(List.of(candidate(0, 60, 30)), now);

        assertThat(unconfigured.configured()).isFalse();
        assertThat(snapshot.roads()).containsOnlyKeys("BUS:1:2:3:1");
        assertThat(snapshot.roads().get("BUS:1:2:3:1").path("mappingStatus").asText())
                .isEqualTo("PROVISIONAL");

        // 후보 근거가 꺼져 있으면 예전처럼 승인 부품의 "설정 없음" 사유를 그대로 남긴다.
        when(provisional.enabled()).thenReturn(false);
        var fallback =
                new RouteEvidenceAssembler(redis, json, unconfigured, provisional)
                        .read(List.of(candidate(0, 60, 30)), now);
        assertThat(fallback.roads().values())
                .allMatch(
                        node ->
                                "ROAD_MASTER_UNAVAILABLE_OR_MISMATCH"
                                        .equals(node.path("reason").asText()));
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
