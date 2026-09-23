package com.ssafy.ddubi.domain.recommendation.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 승인 전 후보 대응표로 만드는 도로 근거.
 *
 * <p>정식 근거는 사람이 방향을 확인한 파일을 요구해서, 그 검토가 끝나기 전에는 사고 정보를 받아 두고도 추천에 닿지 못했다. 여기서는 후보 표와 이미 도는 사고 스냅샷으로
 * <b>가능성</b>만 만든다. 만들어진 원인이 경로를 막거나 예상 시각을 바꾸지 않는 것은 뒤쪽 계약이 보장한다.
 */
class ProvisionalRoadEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-09-23T04:00:00Z");
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);

    private static final String MAPPING_KEY = "ml:mapping:bus-road-provisional:v1:master:sha";

    /** 사고가 링크 L1 에 있고, 대응표가 그 구간을 L1 로 잇는 정상 상태. */
    private ProvisionalRoadEvidence rig(boolean enabled) {
        return rig(enabled, snapshot("L1"), mapping("L1"));
    }

    private ProvisionalRoadEvidence rig(boolean enabled, String snapshot, String mapping) {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hashes);
        when(values.get(ProvisionalRoadEvidence.SNAPSHOT_KEY)).thenReturn(snapshot);
        when(values.get("ml:mapping:bus-road-provisional:active:master")).thenReturn(MAPPING_KEY);
        when(hashes.get(anyString(), anyString())).thenReturn(mapping);
        return new ProvisionalRoadEvidence(redis, json, enabled);
    }

    private static String snapshot(String linkId) {
        return """
               {"completeSnapshot":true,"linkMasterAvailable":true,
                "linkNamespace":"SEOUL_ACCINFO_LINK","linkMappingStatus":"PROVISIONAL",
                "linkMasterVersion":"provisional-accinfo-input-v1",
                "collectedAt":"2026-09-23T12:55:00+09:00","validUntil":"2026-09-23T13:25:00+09:00",
                "incidents":[{"providerIncidentId":"I1","matchedLinkId":"%s",
                              "startedAt":"2026-09-23T11:00:00+09:00",
                              "expectedClearAt":"2026-09-23T18:00:00+09:00"}]}
               """
                .formatted(linkId);
    }

    private static String mapping(String linkId) {
        return """
               {"segmentId":"BUS:1:1:2","masterVersion":"master","mappingStatus":"PROVISIONAL",
                "topisLinkNamespace":"SEOUL_ACCINFO_LINK","topisLinkIds":["%s"],
                "trafficLinkMasterVersion":"provisional-accinfo-input-v1",
                "roadPathValidFrom":"2026-09-23T00:00:00+09:00",
                "roadPathValidUntil":"2026-09-30T00:00:00+09:00"}
               """
                .formatted(linkId);
    }

    @Test
    void 후보표와사고스냅샷으로가능성원인을만든다() {
        var route = MlRoutePreviewTest.route(0, "1", 60, 0, 1);

        var snapshot = rig(true).read(List.of(route), now);

        assertThat(snapshot).isNotEmpty();
        var reasons =
                RoadImpactEvidence.reasons(
                        route.segments().get(0), snapshot, now, now.plusSeconds(600));
        assertThat(reasons).contains("ROAD_POTENTIAL_IMPACT:I1");
        assertThat(snapshot.values().iterator().next().path("mappingStatus").asText())
                .isEqualTo("PROVISIONAL");
    }

    /** 꺼져 있으면 아무것도 만들지 않는다. 기본값이 꺼짐이라 설정 없이 동작이 바뀌지 않는다. */
    @Test
    void 꺼져있으면빈값이다() {
        assertThat(rig(false).read(List.of(MlRoutePreviewTest.route(0, "1", 60, 0, 1)), now))
                .isEmpty();
    }

    /** 사고가 다른 도로에 있으면 이 구간의 원인이 되지 않는다. 링크가 겹칠 때만 잇는다. */
    @Test
    void 다른도로의사고는이구간원인이아니다() {
        var route = MlRoutePreviewTest.route(0, "1", 60, 0, 1);

        var snapshot = rig(true, snapshot("L9"), mapping("L1")).read(List.of(route), now);

        assertThat(
                        RoadImpactEvidence.reasons(
                                route.segments().get(0), snapshot, now, now.plusSeconds(600)))
                .noneMatch(reason -> reason.startsWith("ROAD_POTENTIAL_IMPACT"));
    }

    /** 부분 수집이거나 만료된 회차로는 판단하지 않는다. 사고 없음으로 오해하지 않게 근거 자체를 비운다. */
    @Test
    void 불완전하거나만료된스냅샷은쓰지않는다() {
        var route = List.of(MlRoutePreviewTest.route(0, "1", 60, 0, 1));
        String partial =
                snapshot("L1").replace("\"completeSnapshot\":true", "\"completeSnapshot\":false");
        String expired =
                snapshot("L1")
                        .replace(
                                "\"validUntil\":\"2026-09-23T13:25:00+09:00\"",
                                "\"validUntil\":\"2026-09-23T12:00:00+09:00\"");

        assertThat(rig(true, partial, mapping("L1")).read(route, now)).isEmpty();
        assertThat(rig(true, expired, mapping("L1")).read(route, now)).isEmpty();
    }

    /** 대응표가 없는 구간은 "모른다" 로 남는다. 사고가 없다는 뜻이 아니다. */
    @Test
    void 대응표가없으면경로미연결로남긴다() {
        var route = MlRoutePreviewTest.route(0, "1", 60, 0, 1);

        var snapshot = rig(true, snapshot("L1"), null).read(List.of(route), now);

        assertThat(snapshot.values().iterator().next().path("reason").asText())
                .isEqualTo("ROAD_PATH_UNMAPPED");
        assertThat(
                        RoadImpactEvidence.reasons(
                                route.segments().get(0), snapshot, now, now.plusSeconds(600)))
                .contains("ROAD_PATH_UNMAPPED");
    }

    /** 사고 링크 체계와 대응표 체계가 다르면 잇지 않는다. 숫자가 같다고 같은 도로로 보지 않는다. */
    @Test
    void 링크체계가다르면잇지않는다() {
        var route = MlRoutePreviewTest.route(0, "1", 60, 0, 1);
        String other =
                mapping("L1").replace("\"provisional-accinfo-input-v1\"", "\"other-link-master\"");

        var snapshot = rig(true, snapshot("L1"), other).read(List.of(route), now);

        assertThat(snapshot.values().iterator().next().path("reason").asText())
                .isEqualTo("ROAD_LINK_NAMESPACE_MISMATCH");
    }
}
