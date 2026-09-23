package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.ddubi.domain.route.mapping.MappedTransitSegment;
import com.ssafy.ddubi.domain.route.model.RouteCandidate;
import com.ssafy.ddubi.domain.route.model.RouteSegment;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 승인 전 <b>후보</b> 도로 대응표로 만드는 도로 영향 근거. {@link RoadImpactEvidence} 와 같은 모양을 내보내므로 뒤쪽 계산·안내는 바뀌지 않는다.
 *
 * <h3>왜 따로 두나</h3>
 *
 * 정식 근거는 사람이 버스 진행 방향을 확인한 승인 파일을 요구한다(`directionVerified`). 그 검토가 끝나기 전까지는 도로 사고가 추천에 전혀 닿지 못해, 사고
 * 정보를 받아 두고도 쓰지 못했다. ML 예측을 미승인 상태로 쓰되 결과에 표시하는 것과 같은 방식으로, 후보 대응표도 <b>가능성</b>으로만 쓴다.
 *
 * <ul>
 *   <li>승인 파일이 있으면 그쪽이 이긴다. 이 클래스는 승인 근거가 없을 때만 쓰인다.
 *   <li>만들어지는 원인은 {@code POTENTIAL_IMPACT}·{@code confirmed=false} 다. 경로를 막거나 예상 시각을 바꾸지 않는다.
 *   <li>사고 자료는 이미 15 분마다 갱신되는 전체 스냅샷을 읽는다. 구간별 Redis 를 새로 만드는 Spark 잡을 띄우지 않는다.
 * </ul>
 */
@Component
public class ProvisionalRoadEvidence {
    /** 후보 대응표를 만든 발행기의 버전. 근거에 함께 실어 어느 표로 판단했는지 남긴다. */
    static final String ROAD_VERSION = "bus-road-link-provisional-v1";

    static final String SNAPSHOT_KEY = "traffic:latest:incident-snapshot:all-current";
    private static final String ACTIVE_PREFIX = "ml:mapping:bus-road-provisional:active:";
    private static final String MAPPING_PREFIX = "ml:mapping:bus-road-provisional:";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,160}");

    /** 한 번의 평가에서 읽을 구간 수 상한. 후보 표는 1 만 건이 넘어 무제한으로 읽으면 평가가 느려진다. */
    private static final int MAX_SEGMENTS = 256;

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean enabled;

    public ProvisionalRoadEvidence(
            StringRedisTemplate redis,
            ObjectMapper json,
            @Value("${ddubi.road.provisional-evidence-enabled:false}") boolean enabled) {
        this.redis = redis;
        this.json = json;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** {@link RoadImpactEvidence#read} 와 같은 모양의 구간별 근거. 꺼져 있거나 자료가 없으면 빈 값이다. */
    public Map<String, JsonNode> read(List<RouteCandidate> candidates, Instant asOf) {
        if (!enabled) return Map.of();
        JsonNode snapshot = snapshot(asOf);
        if (snapshot == null) return Map.of();
        String namespace = snapshot.path("linkNamespace").asText("");
        if (!SAFE.matcher(namespace).matches()) return Map.of();

        // 링크별 사건 목록. 같은 링크를 여러 구간이 지나도 한 번만 정리한다.
        Map<String, List<JsonNode>> byLink = new LinkedHashMap<>();
        for (JsonNode incident : snapshot.path("incidents")) {
            String link = incident.path("matchedLinkId").asText("");
            String id = incident.path("providerIncidentId").asText("");
            if (!SAFE.matcher(link).matches() || !SAFE.matcher(id).matches()) continue;
            String startedAt = instant(incident.path("startedAt").asText(null));
            if (startedAt == null) continue;
            ObjectNode event = json.createObjectNode();
            event.put("eventId", id);
            event.put("source", snapshot.path("source").asText("seoul.topis"));
            event.put("startsAt", startedAt);
            String expected = instant(incident.path("expectedClearAt").asText(null));
            if (expected != null) event.put("expectedEndsAt", expected);
            byLink.computeIfAbsent(link, key -> new ArrayList<>()).add(event);
        }

        Map<String, String> activeKeys = new LinkedHashMap<>();
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (RouteCandidate route : candidates)
            for (RouteSegment step : route.segments()) {
                if (step.mode() != TransportMode.BUS || step.mapping() == null) continue;
                String masterVersion = step.mapping().masterDataVersion();
                if (masterVersion == null || !SAFE.matcher(masterVersion).matches()) continue;
                for (MappedTransitSegment edge : RouteEvidenceAssembler.edges(step)) {
                    if (result.size() >= MAX_SEGMENTS) return Map.copyOf(result);
                    String key = RoadImpactEvidence.key(edge);
                    if (result.containsKey(key)) continue;
                    result.put(
                            key,
                            evidence(
                                    edge,
                                    masterVersion,
                                    namespace,
                                    snapshot,
                                    byLink,
                                    activeKeys,
                                    asOf));
                }
            }
        return Map.copyOf(result);
    }

    private JsonNode evidence(
            MappedTransitSegment edge,
            String masterVersion,
            String namespace,
            JsonNode snapshot,
            Map<String, List<JsonNode>> byLink,
            Map<String, String> activeKeys,
            Instant asOf) {
        ObjectNode output = json.createObjectNode();
        output.put("segmentId", edge.segmentId());
        output.put("fromOrder", edge.fromOrder());
        output.put("roadVersion", ROAD_VERSION);
        output.put("asOf", asOf.toString());
        output.put("mappingStatus", "PROVISIONAL");
        var paths = output.putArray("paths");

        JsonNode mapping = mapping(edge.segmentId(), masterVersion, activeKeys);
        if (mapping == null) {
            output.put("reason", "ROAD_PATH_UNMAPPED");
            return output;
        }
        if (!namespace.equals(mapping.path("topisLinkNamespace").asText())
                || !snapshot.path("linkMasterVersion")
                        .asText()
                        .equals(mapping.path("trafficLinkMasterVersion").asText())) {
            // 사고가 가리키는 링크 체계와 대응표의 체계가 다르면 이어 붙이지 않는다.
            output.put("reason", "ROAD_LINK_NAMESPACE_MISMATCH");
            return output;
        }
        String validFrom = instant(mapping.path("roadPathValidFrom").asText(null));
        String validUntil = instant(mapping.path("roadPathValidUntil").asText(null));
        if (validFrom == null || validUntil == null) {
            output.put("reason", "ROAD_PATH_UNMAPPED_OR_EXPIRED");
            return output;
        }

        ObjectNode path = json.createObjectNode();
        path.put("validFrom", validFrom);
        path.put("validUntil", validUntil);
        var links = path.putArray("roadLinks");
        var events = path.putArray("events");
        for (JsonNode link : mapping.path("topisLinkIds")) {
            String linkId = link.asText("");
            if (!SAFE.matcher(linkId).matches()) continue;
            ObjectNode entry = links.addObject();
            entry.put("namespace", namespace);
            entry.put("linkId", linkId);
            byLink.getOrDefault(linkId, List.of()).forEach(events::add);
        }
        if (links.isEmpty()) {
            output.put("reason", "ROAD_PATH_UNMAPPED");
            return output;
        }
        paths.add(path);
        return output;
    }

    /** 활성 포인터 → 구간 행. 포인터가 후보 전용 이름이 아니면 읽지 않는다. */
    private JsonNode mapping(String segmentId, String masterVersion, Map<String, String> cache) {
        try {
            String mappingKey =
                    cache.computeIfAbsent(
                            masterVersion,
                            version -> redis.opsForValue().get(ACTIVE_PREFIX + version));
            if (mappingKey == null || !mappingKey.startsWith(MAPPING_PREFIX)) return null;
            Object raw = redis.opsForHash().get(mappingKey, segmentId);
            if (raw == null) return null;
            JsonNode node = json.readTree(String.valueOf(raw));
            if (!"PROVISIONAL".equals(node.path("mappingStatus").asText())
                    || !masterVersion.equals(node.path("masterVersion").asText())) return null;
            return node;
        } catch (Exception unavailable) {
            return null;
        }
    }

    /** 완전하고 아직 유효한 사고 스냅샷만 쓴다. 부분 수집이나 만료된 회차로 판단하지 않는다. */
    private JsonNode snapshot(Instant asOf) {
        try {
            String raw = redis.opsForValue().get(SNAPSHOT_KEY);
            if (raw == null) return null;
            JsonNode node = json.readTree(raw);
            if (!node.path("completeSnapshot").asBoolean(false)
                    || !node.path("linkMasterAvailable").asBoolean(false)) return null;
            Instant collected = parse(node.path("collectedAt").asText(null));
            Instant until = parse(node.path("validUntil").asText(null));
            if (collected == null
                    || until == null
                    || collected.isAfter(asOf)
                    || !until.isAfter(asOf)) return null;
            return node;
        } catch (Exception unavailable) {
            return null;
        }
    }

    /**
     * 시각 문자열을 {@code Instant.parse} 가 읽는 Z 표기로 맞춘다. 원본은 {@code +09:00} 오프셋으로 들어와, 그대로 실으면 뒤쪽 판정이 읽지
     * 못한다.
     */
    private static String instant(String value) {
        Instant parsed = parse(value);
        return parsed == null ? null : parsed.toString();
    }

    private static Instant parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception notOffset) {
            try {
                return Instant.parse(value);
            } catch (Exception invalid) {
                return null;
            }
        }
    }
}
