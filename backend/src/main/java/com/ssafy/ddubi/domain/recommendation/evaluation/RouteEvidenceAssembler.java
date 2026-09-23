package com.ssafy.ddubi.domain.recommendation.evaluation;

import static com.ssafy.ddubi.domain.recommendation.evaluation.JourneyEvidence.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.*;
import java.time.Instant;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 카카오 후보와 Spark 구간 상태를 한 번의 평가 증거로 고정한다. Redis는 읽기만 하며, 미매핑·만료·형식 오류·연결 실패를 원본 fallback과 진단으로 남긴다.
 * ML 호출은 MlRoutePreview가 담당한다. 대기 포함 여부가 확정되지 않은 카카오 step에는 도착 API의 대기를 임의 추가하지 않는다.
 */
@Component
public class RouteEvidenceAssembler {
    private static final String PREFIX = "segment:status:";
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final RoadImpactEvidence road;
    private final ProvisionalRoadEvidence provisionalRoad;

    public RouteEvidenceAssembler(StringRedisTemplate redis, ObjectMapper json) {
        this(redis, json, null, null);
    }

    public RouteEvidenceAssembler(
            StringRedisTemplate redis, ObjectMapper json, RoadImpactEvidence road) {
        this(redis, json, road, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RouteEvidenceAssembler(
            StringRedisTemplate redis,
            ObjectMapper json,
            RoadImpactEvidence road,
            ProvisionalRoadEvidence provisionalRoad) {
        this.redis = redis;
        this.json = json;
        this.road = road;
        this.provisionalRoad = provisionalRoad;
    }

    /** 외부 원본을 노출하지 않는 고정 스냅샷. 검증된 상태 JSON과 오류 이유로 Replay할 수 있다. */
    public record Snapshot(
            Map<String, JsonNode> states, List<String> reasons, Map<String, JsonNode> roads) {
        public Snapshot(Map<String, JsonNode> states, List<String> reasons) {
            this(states, reasons, Map.of());
        }

        public Snapshot {
            states = Map.copyOf(states);
            reasons = List.copyOf(reasons);
            roads = roads == null ? Map.of() : Map.copyOf(roads);
        }
    }

    /** 서버 매핑 결과에서 하나로 확정된 canonical 경로만 반환한다. 여러 버스 대안을 임의로 고르지 않는다. */
    public static List<MappedTransitSegment> edges(RouteSegment segment) {
        var mapping = segment.mapping();
        if (mapping == null) return List.of();
        if (mapping.status() == TransitMappingStatus.EXACT && !mapping.segments().isEmpty())
            return mapping.segments();
        if (segment.mode() == TransportMode.BUS
                && mapping.busAlternatives().size() == 1
                && mapping.busAlternatives().get(0).candidates().size() == 1) {
            try {
                return BusCanonicalPath.select(mapping, 0, 0).edges();
            } catch (IllegalArgumentException ignored) {
                return List.of();
            }
        }
        return List.of();
    }

    /** Redis MGET 한 번으로 중복 ID를 합친다. 원본 예외에 URL/비밀정보가 있어도 응답·로그에 복제하지 않는다. */
    public Snapshot read(List<RouteCandidate> candidates, Instant asOf) {
        var ids =
                candidates.stream()
                        .flatMap(c -> c.segments().stream())
                        .flatMap(s -> edges(s).stream())
                        .map(MappedTransitSegment::segmentId)
                        .distinct()
                        .sorted()
                        .toList();
        var states = new LinkedHashMap<String, JsonNode>();
        var reasons = new ArrayList<String>();
        if (ids.isEmpty()) return new Snapshot(states, List.of("NO_VERIFIED_SEGMENT_MAPPING"));
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(ids.stream().map(id -> PREFIX + id).toList());
        } catch (RuntimeException failure) {
            return new Snapshot(states, List.of("REDIS_UNAVAILABLE"));
        }
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            try {
                String raw = values == null || values.size() <= i ? null : values.get(i);
                if (raw == null) {
                    reasons.add(id + ":MISSING");
                    continue;
                }
                JsonNode node = json.readTree(raw);
                Instant computed = Instant.parse(node.path("computedAt").asText());
                Instant until = Instant.parse(node.path("validUntil").asText());
                String state = node.path("status").asText();
                if (!id.equals(node.path("segmentId").asText())
                        || computed.isAfter(asOf)
                        || !until.isAfter(asOf)
                        || !until.isAfter(computed)
                        || !Set.of("normal", "slow", "delayed", "blocked", "unknown")
                                .contains(state)) {
                    reasons.add(id + ":STALE_OR_INVALID");
                    continue;
                }
                states.put(id, node);
            } catch (Exception invalid) {
                reasons.add(id + ":INVALID_PAYLOAD");
            }
        }
        return new Snapshot(states, reasons, roads(candidates, asOf));
    }

    /**
     * 승인된 도로 근거가 있으면 그것만 쓴다. 없을 때만 후보 대응표로 만든 근거를 쓴다 — 사고 자료를 받아 두고도 검토가 끝나기 전까지 아무것도 못 쓰는 것을 막기
     * 위해서다. 후보 근거로 나오는 원인은 "가능성" 이며 경로를 막거나 예상 시각을 바꾸지 않는다.
     */
    private Map<String, JsonNode> roads(List<RouteCandidate> candidates, Instant asOf) {
        if (road != null) {
            var approved = road.read(candidates, asOf);
            if (!approved.isEmpty()) return approved;
        }
        return provisionalRoad == null ? Map.of() : provisionalRoad.read(candidates, asOf);
    }

    /**
     * 보강된 접근·이탈 도보와 원본 환승·탑승 step을 초 단위 fallback으로 제공한다. 끝점 도보시간 자체가 없으면 provider 전체시간 하나로 내리고,
     * 일반적인 미분류 비용은 별도 UNASSIGNED 요소로 보존한다. 미분류 값에는 대기가 이미 포함됐을 수 있어 호출자가 실시간 대기를 다시 더하지 않는다. Redis는
     * 통제 근거이며 이 단계에서 이동시간을 교체하지 않는다.
     */
    public Journey assemble(RouteCandidate candidate, Snapshot snapshot, Instant asOf) {
        boolean endpointDurationUnknown =
                candidate.segments().stream()
                        .anyMatch(
                                segment ->
                                        "EVALUATION_ENDPOINT_WALK"
                                                        .equals(segment.providerRouteKey())
                                                && segment.durationSeconds() == 0);
        if (endpointDurationUnknown) {
            var ids =
                    candidate.segments().stream()
                            .flatMap(segment -> edges(segment).stream())
                            .map(MappedTransitSegment::segmentId)
                            .distinct()
                            .toList();
            return new Journey(
                    key(candidate),
                    List.of(
                            new Leg(
                                    "provider-total",
                                    Kind.PROVIDER_TOTAL,
                                    ids,
                                    candidate.totalDurationSeconds(),
                                    null,
                                    null,
                                    List.of())));
        }

        long unassigned = 0;
        var legs = new ArrayList<Leg>();
        for (RouteSegment segment : candidate.segments()) {
            var ids = edges(segment).stream().map(MappedTransitSegment::segmentId).toList();
            // Redis는 통제/위험 근거만 제공한다. 이동시간은 ML 비교기가 별도로 계산하며
            // 관측 중앙값을 모델 예측인 것처럼 교체하지 않는다.
            legs.add(
                    new Leg(
                            "step-" + segment.sequence(),
                            segment.mode() == TransportMode.WALK ? Kind.WALK : Kind.RIDE,
                            ids,
                            segment.durationSeconds(),
                            null,
                            null,
                            List.of(),
                            unassigned));
        }
        if (candidate.unassignedDurationSeconds() > 0)
            legs.add(
                    new Leg(
                            "provider-unassigned",
                            Kind.UNASSIGNED,
                            List.of(),
                            candidate.unassignedDurationSeconds(),
                            null,
                            null,
                            List.of()));

        return new Journey(key(candidate), legs);
    }

    /** 만료되지 않은 공식 blocked 판정만 통제로 변환한다. 미래에는 validUntil 바깥까지 연장하지 않는다. */
    public List<Closure> closures(Snapshot snapshot) {
        var closures = new ArrayList<Closure>();
        snapshot.states()
                .forEach(
                        (id, state) -> {
                            if ("blocked".equals(state.path("status").asText())) {
                                Instant at = Instant.parse(state.path("computedAt").asText());
                                closures.add(
                                        new Closure(
                                                state.path("eventId").asText(id + ":" + at),
                                                List.of(id),
                                                new Window(
                                                        state.path("source")
                                                                .asText("segment.status"),
                                                        at,
                                                        at,
                                                        Instant.parse(
                                                                state.path("validUntil")
                                                                        .asText()))));
                            }
                        });
        return List.copyOf(closures);
    }

    /** 영향 구간의 현재 악화는 시간 가산 대신 근거로 공개한다. 위치 미확정/미래 이용도 확정 지연으로 바꾸지 않는다. */
    public List<String> riskReasons(RouteCandidate candidate, Snapshot snapshot) {
        return candidate.segments().stream()
                .flatMap(s -> edges(s).stream())
                .map(MappedTransitSegment::segmentId)
                .distinct()
                .filter(snapshot.states()::containsKey)
                .map(id -> id + ":" + snapshot.states().get(id).path("status").asText())
                .filter(s -> !s.endsWith(":normal"))
                .toList();
    }

    /**
     * canonical ID는 순환 노선의 같은 정류장 쌍에서 반복될 수 있으므로 공식 정류장 순번과 방향까지 포함한 occurrence 식별자를 사용한다. 실시간 ID나
     * 관측값은 경로 정체성이 아니다. 캐시 재선택과 도보 증거 연결이 동일한 기준을 사용하도록 이 함수에서만 조합한다.
     */
    public static List<List<Object>> pathIdentity(RouteSegment segment) {
        return edges(segment).stream()
                .map(
                        e ->
                                Arrays.<Object>asList(
                                        e.segmentId(),
                                        e.routeId(),
                                        e.fromOrder(),
                                        e.toOrder(),
                                        e.direction()))
                .toList();
    }

    /** 원본 후보 순번을 유지하고 선택된 버스 occurrence 열로 대안 key를 분리한다. 같은 입력은 같은 key다. */
    public static String key(RouteCandidate candidate) {
        String base = "candidate-" + candidate.providerCandidateIndex();
        if (candidate.segments().stream()
                .noneMatch(
                        s ->
                                s.mapping() != null
                                        && BusAlternativeExpansion.SELECTED.equals(
                                                s.mapping().failureReason()))) return base;
        var ids =
                candidate.segments().stream()
                        .filter(s -> !"EVALUATION_ENDPOINT_WALK".equals(s.providerRouteKey()))
                        .map(s -> s.sequence() + ":" + pathIdentity(s))
                        .toList()
                        .toString();
        return base
                + "-bus-"
                + UUID.nameUUIDFromBytes(ids.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
