package com.ssafy.ddubi.domain.route.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.ssafy.ddubi.domain.route.mapping.CanonicalSegmentIdFactory;
import com.ssafy.ddubi.domain.recommendation.evaluation.RouteEvidenceAssembler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** DT !148의 transit-coordinate-v1을 소비한다. 이름·거리만으로 노선/방향/순번을 추정하지 않는다. */
public final class WalkingCoordinateArtifact {
    private final JsonNode root;

    public WalkingCoordinateArtifact(JsonNode root) {
        if (!"transit-coordinate-v1".equals(root.path("schemaVersion").asText())
                || !"WGS84".equals(root.path("coordinateSystem").asText())
                || root.path("artifactVersion").asText().isBlank() || !root.path("records").isArray())
            throw new IllegalArgumentException("도보 좌표 artifact 계약 불일치");
        Instant.parse(root.path("generatedAt").asText());
        this.root = root.deepCopy();
    }

    /** sourceRef의 공식 노선 순번까지 확인한다. occurrence만으로 전체 노선의 순번을 만들지 않는다. */
    public List<WalkingEndpointResolver.MasterPoint> matching(RouteSegment transit, boolean boarding, Instant asOf) {
        var edges = RouteEvidenceAssembler.edges(transit);
        if (edges.isEmpty() || transit.mapping() == null
                || Instant.parse(root.path("generatedAt").asText()).isAfter(asOf)) return List.of();
        var edge = boarding ? edges.get(0) : edges.get(edges.size() - 1);
        String nodeId = boarding ? edge.fromNodeId() : edge.toNodeId();
        int order = boarding ? edge.fromOrder() : edge.toOrder();
        String direction = edge.direction();
        if (transit.mode() == TransportMode.BUS && "SEQUENCE_FORWARD".equals(direction)) {
            // canonical edge의 순방향 표시는 원천 상·하행 코드가 아니다. 검증된 단일 대안의 원본을 대조한다.
            var alternatives = transit.mapping().busAlternatives();
            if (alternatives.size() != 1 || alternatives.get(0).candidates().size() != 1) return List.of();
            var stops = alternatives.get(0).candidates().get(0).stops();
            var endpoint = stops.stream().filter(s -> s.sequence() == order).toList();
            if (endpoint.size() != 1) return List.of();
            direction = endpoint.get(0).direction();
        }
        var points = new ArrayList<WalkingEndpointResolver.MasterPoint>();
        for (var row : root.path("records")) {
            if (!transit.mode().name().equals(row.path("mode").asText())
                    || !"EXACT".equals(row.path("mappingStatus").asText())
                    || !Objects.equals(transit.mapping().masterDataVersion(), row.path("masterVersion").asText())
                    || direction == null || !direction.equals(row.path("direction").asText())
                    || row.path("occurrence").asInt() < 1 || row.path("source").asText().isBlank()
                    || !row.path("longitude").isNumber() || !row.path("latitude").isNumber()
                    || !row.path("sourceRef").asText().matches(".*(?:^|;)sequence=" + order + "(?:;.*)?$")
                    || !validAt(row, asOf)) continue;
            String canonical, route;
            if (transit.mode() == TransportMode.BUS) {
                String namespace = row.path("providerNamespace").asText();
                String stop = row.path("providerStopId").asText();
                if (!row.path("canonicalNodeId").asText().equals("BUS_STOP:" + namespace + ":" + stop)) continue;
                try {
                    var ids = new CanonicalSegmentIdFactory();
                    canonical = ids.busComponent(namespace, stop);
                    route = ids.busComponent(namespace, row.path("providerRouteId").asText());
                } catch (IllegalArgumentException invalid) { continue; }
                if (!Objects.equals(edge.routeId(), route)) continue;
            } else {
                if (!"METROPOLITAN_SUBWAY".equals(row.path("providerNamespace").asText())
                        || !row.path("canonicalNodeId").asText().startsWith("SUBWAY_STATION:")
                        || !Objects.equals(edge.realtimeRouteId(), row.path("providerLineId").asText())) continue;
                canonical = row.path("canonicalNodeId").asText().substring("SUBWAY_STATION:".length());
            }
            if (!Objects.equals(nodeId, canonical)) continue;
            points.add(new WalkingEndpointResolver.MasterPoint(canonical, row.path("masterVersion").asText(),
                    new Coordinate(row.path("longitude").doubleValue(), row.path("latitude").doubleValue()),
                    row.path("coordinateType").asText(), row.path("source").asText(),
                    row.path("validUntil").isTextual() ? Instant.parse(row.path("validUntil").asText()) : null));
        }
        return List.copyOf(points);
    }

    private boolean validAt(JsonNode row, Instant asOf) {
        try {
            return (!row.path("validFrom").isTextual() || !Instant.parse(row.path("validFrom").asText()).isAfter(asOf))
                    && (!row.path("validUntil").isTextual() || Instant.parse(row.path("validUntil").asText()).isAfter(asOf));
        } catch (RuntimeException invalid) { return false; }
    }
}
