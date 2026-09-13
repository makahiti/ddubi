package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.model.*;
import com.ssafy.ddubi.domain.route.mapping.MappedTransitSegment;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 기존 카카오 구간 매핑 뒤에 버전 고정 도로 목록과 원본 사건을 결합한다.
 * SHA/방향/적용 기간이 검증된 별도 artifact만 읽고 canonical ID는 재발급하지 않는다.
 * Redis road:incidents:{namespace}:{linkId} hash는 사건 ID별 최신 응답이다. TTL과 무관하게
 * 수집시각을 재검사한다. 사건 존재는 잠재 영향이며 차로 전체 통제나 지연초를 추정하지 않는다.
 */
@Component
public class RoadImpactEvidence {
    private final ObjectMapper json;
    private final StringRedisTemplate redis;
    private final Map<String, List<JsonNode>> mappings = new HashMap<>();
    private final String masterVersion;
    private final String version;
    private final int maxAge;

    public RoadImpactEvidence(ObjectMapper json, StringRedisTemplate redis,
            @Value("${ddubi.road-impact.path:}") String path,
            @Value("${ddubi.road-impact.sha256:}") String sha,
            @Value("${ddubi.road-impact.max-age-seconds:600}") int maxAge) {
        this.json=json; this.redis=redis; this.maxAge=maxAge;
        if (maxAge < 1 || maxAge > 3600) throw new IllegalArgumentException("road freshness out of range");
        if (path == null || path.isBlank()) { masterVersion=null; version=null; return; }
        try {
            Path file=Path.of(path);
            if (Files.size(file)>32L*1024*1024) throw new IllegalArgumentException("road artifact too large");
            byte[] bytes=Files.readAllBytes(file);
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(sha))
                throw new IllegalArgumentException("road SHA mismatch");
            JsonNode root=json.readTree(bytes);
            masterVersion=root.path("masterVersion").asText(); version=root.path("version").asText();
            if (root.path("schemaVersion").asInt()!=1 || masterVersion.isBlank() || version.isBlank() || !root.path("mappings").isArray()
                    || !root.path("busCrosswalkSha256").asText().matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("road manifest invalid");
            for (JsonNode row:root.path("mappings")) {
                if (!"COMPLETE".equals(row.path("coverage").asText()) || !row.path("directionVerified").asBoolean()
                        || row.path("evidenceRef").asText().isBlank() || !row.path("roadLinks").isArray() || row.path("roadLinks").isEmpty()
                        || !Set.of("OFFICIAL_ROUTE_LINKS", "REVIEWED_ROUTE_GEOMETRY").contains(row.path("evidenceMethod").asText()))
                    throw new IllegalArgumentException("unreviewed road path");
                Instant start=Instant.parse(row.path("validFrom").asText()), end=Instant.parse(row.path("validUntil").asText());
                if (!start.isBefore(end)) throw new IllegalArgumentException("road validity invalid");
                String key=row.path("segmentId").asText()+":"+row.path("fromOrder").asInt();
                for (JsonNode prior:mappings.getOrDefault(key,List.of()))
                    if (start.isBefore(Instant.parse(prior.path("validUntil").asText())) && Instant.parse(prior.path("validFrom").asText()).isBefore(end))
                        throw new IllegalArgumentException("overlapping road paths");
                for (JsonNode link:row.path("roadLinks"))
                    if (!link.path("namespace").asText().matches("[A-Za-z0-9._-]{1,50}") || !link.path("linkId").asText().matches("[A-Za-z0-9._-]{1,50}"))
                        throw new IllegalArgumentException("road ID invalid");
                mappings.computeIfAbsent(key,k->new ArrayList<>()).add(row);
            }
        } catch(Exception invalid) { throw new IllegalArgumentException("도로 대응표 검증 실패",invalid); }
    }

    /** Redis 조회 결과를 이번 평가 동안 고정한다. 누락/형식 오류/장애도 구간별 사유로 보존한다. */
    public Map<String, JsonNode> read(List<RouteCandidate> routes, Instant asOf) {
        var result=new LinkedHashMap<String,JsonNode>();
        var links=new HashMap<String, List<JsonNode>>();
        var failedLinks=new HashSet<String>();
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(2).toNanos();
        for (RouteCandidate route:routes) for (RouteSegment step:route.segments()) {
            if (step.mode()!=TransportMode.BUS) continue;
            for (MappedTransitSegment edge:RouteEvidenceAssembler.edges(step)) {
                String key=key(edge);
                var output=json.createObjectNode();
                output.put("segmentId",edge.segmentId()); output.put("fromOrder",edge.fromOrder());
                output.put("roadVersion",version); output.put("asOf",asOf.toString());
                var paths=output.putArray("paths");
                if (masterVersion==null || step.mapping()==null || !masterVersion.equals(step.mapping().masterDataVersion())) {
                    output.put("reason","ROAD_MASTER_UNAVAILABLE_OR_MISMATCH"); result.put(key,output); continue;
                }
                for (JsonNode path:mappings.getOrDefault(key,List.of())) {
                    // 순환 노선 occurrence와 방향을 대조한다. canonical ID만 같다고 연결하지 않는다.
                    if (!edge.routeId().equals(canonicalNode(path,"routeId")) || !edge.fromNodeId().equals(canonicalNode(path,"fromStopId")) || !edge.toNodeId().equals(canonicalNode(path,"toStopId"))
                            || edge.toOrder()!=path.path("toOrder").asInt() || !Objects.equals(edge.direction(),path.path("direction").isNull()?null:path.path("direction").asText())) continue;
                    var copy=path.deepCopy(); var events=((com.fasterxml.jackson.databind.node.ObjectNode)copy).putArray("events");
                    for (JsonNode link:path.path("roadLinks")) {
                        String linkKey="road:incidents:"+link.path("namespace").asText()+":"+link.path("linkId").asText();
                        if (!links.containsKey(linkKey)) {
                            var parsed=new ArrayList<JsonNode>();
                            try {
                                if (links.size()>=256 || System.nanoTime()>deadline) throw new IllegalStateException("road evidence budget exhausted");
                                for (Object raw:redis.opsForHash().entries(linkKey).values()) {
                                    JsonNode event=json.readTree(String.valueOf(raw));
                                    Instant collected=Instant.parse(event.path("collectedAt").asText());
                                    if (collected.isAfter(asOf) || !collected.plusSeconds(maxAge).isAfter(asOf)) continue;
                                    if (!event.path("linkNamespace").asText().equals(link.path("namespace").asText()) || !event.path("linkId").asText().equals(link.path("linkId").asText())) continue;
                                    if (event.path("conflicted").asBoolean()) throw new IllegalArgumentException("conflicting event");
                                    parsed.add(event);
                                }
                            } catch(Exception unavailable) { parsed.clear(); failedLinks.add(linkKey); }
                            links.put(linkKey,parsed);
                        }
                        if (failedLinks.contains(linkKey)) output.put("reason","ROAD_EVENTS_UNAVAILABLE");
                        if (links.get(linkKey).isEmpty()) output.put("reason","ROAD_FEED_COVERAGE_UNKNOWN");
                        links.get(linkKey).forEach(events::add);
                    }
                    paths.add(copy);
                }
                if (paths.isEmpty()) output.put("reason","ROAD_PATH_UNMAPPED");
                result.put(key,output);
            }
        }
        return Map.copyOf(result);
    }
    private static String canonicalNode(JsonNode path,String field) {
        String namespace=path.path("namespace").asText();
        return (namespace.equals("SEOUL_BUS")?"":namespace+".")+path.path(field).asText();
    }
    public static String key(MappedTransitSegment edge) { return edge.segmentId()+":"+edge.fromOrder(); }

    /** 예상 통과 구간과 사건 기간의 겹침을 확인한다. 종료 예상시각은 실제 해제 확인으로 취급하지 않는다. */
    public static List<String> reasons(RouteSegment step, Map<String,JsonNode> snapshot, Instant from, Instant to) {
        var result=new TreeSet<String>();
        if (RouteEvidenceAssembler.edges(step).isEmpty()) return List.of("ROAD_IMPACT_UNKNOWN");
        for (MappedTransitSegment edge:RouteEvidenceAssembler.edges(step)) {
            JsonNode info=snapshot.get(key(edge));
            if (info==null) { result.add("ROAD_IMPACT_UNKNOWN"); continue; }
            if (info.has("reason")) result.add(info.path("reason").asText());
            boolean valid=false;
            for (JsonNode path:info.path("paths")) {
                if (Instant.parse(path.path("validFrom").asText()).isAfter(from) || Instant.parse(path.path("validUntil").asText()).isBefore(to)) continue;
                valid=true;
                for (JsonNode event:path.path("events")) {
                    try {
                        Instant start=Instant.parse(event.path("startsAt").asText());
                        if (!start.isBefore(to)) continue;
                        String expected=event.path("expectedEndsAt").asText();
                        if (!expected.isBlank() && !Instant.parse(expected).isAfter(from)) {
                            result.add("ROAD_EXPECTED_CLEAR_UNCONFIRMED"); continue;
                        }
                        result.add("ROAD_POTENTIAL_IMPACT:"+event.path("eventId").asText());
                    } catch(Exception malformed) { result.add("ROAD_EVENT_INVALID"); }
                }
            }
            if (!valid) result.add("ROAD_PATH_UNMAPPED_OR_EXPIRED");
        }
        return List.copyOf(result);
    }
}
