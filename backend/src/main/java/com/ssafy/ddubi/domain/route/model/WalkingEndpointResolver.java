package com.ssafy.ddubi.domain.route.model;

import java.util.*;

/**
 * DT가 넘긴 공식 좌표와 EXACT canonical endpoint를 검증한다. 정류장 이름으로 임의 매칭하지 않는다.
 * 역 중심은 출입구로 승격하지 않는다. 거리 임계값은 corpus 검증 후 호출자가 주입한다.
 */
public final class WalkingEndpointResolver {
    public record MasterPoint(String nodeId, String masterVersion, Coordinate coordinate, String meaning, String source,
                              java.time.Instant validUntil) {
        public MasterPoint(String nodeId,String masterVersion,Coordinate coordinate,String meaning,String source) {
            this(nodeId,masterVersion,coordinate,meaning,source,null);
        }
    }
    public record Endpoint(Coordinate coordinate, EndpointEvidence evidence, boolean verified) { }
    public Endpoint resolve(RouteSegment transit, boolean boarding, Collection<MasterPoint> points, double maxMeters) {
        var mapping=transit.mapping();
        var edges=com.ssafy.ddubi.domain.recommendation.evaluation.RouteEvidenceAssembler.edges(transit);
        if (mapping==null || mapping.status()!=com.ssafy.ddubi.domain.route.mapping.TransitMappingStatus.EXACT || edges.isEmpty() || transit.geometry().isEmpty()
                || mapping.candidateCount()!=1 || mapping.masterDataVersion()==null
                || !Double.isFinite(maxMeters) || maxMeters<=0) return unavailable("WALK_ENDPOINT_UNVERIFIED");
        // EXACT 문자열만 믿지 않는다. provider step의 인접 edge가 같은 노선·방향으로 이어지는지 재확인한다.
        for(int i=0;i<edges.size();i++) {
            var edge=edges.get(i);
            if(edge.sequence()!=i+1 || edge.mode()!=transit.mode() || edge.toOrder()!=edge.fromOrder()+1
                    || !Objects.equals(edge.direction(),mapping.direction())
                    || i>0 && (!Objects.equals(edges.get(i-1).routeId(),edge.routeId())
                    || !Objects.equals(edges.get(i-1).toNodeId(),edge.fromNodeId()) || edges.get(i-1).toOrder()!=edge.fromOrder()))
                return unavailable("WALK_ENDPOINT_UNVERIFIED");
        }
        String id=boarding?edges.get(0).fromNodeId():edges.get(edges.size()-1).toNodeId();
        var matches=points.stream().filter(p -> Objects.equals(p.nodeId(),id)
                && Objects.equals(p.masterVersion(),mapping.masterDataVersion())).toList();
        if(matches.size()!=1) return unavailable("WALK_ENDPOINT_UNVERIFIED");
        var point=matches.get(0);
        Coordinate path=boarding?transit.geometry().get(0):transit.geometry().get(transit.geometry().size()-1);
        Double meters=valid(path)&&valid(point.coordinate())?distance(path,point.coordinate()):null;
        String reason="STATION_CENTER".equals(point.meaning())?"STATION_CENTER_ONLY":null;
        if(point.source()==null || point.source().isBlank() || point.meaning()==null
                || !(transit.mode()==TransportMode.BUS?Set.of("BUS_STOP","STOP_POINT"):Set.of("STATION_ENTRANCE","ENTRANCE")).contains(point.meaning())
                || meters==null || meters>maxMeters) {
            if(reason==null) reason="WALK_ENDPOINT_UNVERIFIED";
        }
        var evidence=new EndpointEvidence(reason==null,point.source(),point.masterVersion(),mapping.mappingPolicyVersion(),
                EndpointEvidence.POLICY_VERSION,meters,point.meaning(),reason==null?"EXACT":"UNVERIFIED",reason);
        return new Endpoint(reason==null?point.coordinate():null,evidence,reason==null);
    }
    private Endpoint unavailable(String reason) { return new Endpoint(null,EndpointEvidence.unavailable(reason),false); }
    private boolean valid(Coordinate p) { return p!=null && Double.isFinite(p.latitude()) && Double.isFinite(p.longitude())
            && Math.abs(p.latitude())<=90 && Math.abs(p.longitude())<=180; }
    private double distance(Coordinate a,Coordinate b) {
        double lat=Math.toRadians(b.latitude()-a.latitude()),lon=Math.toRadians(b.longitude()-a.longitude());
        double h=Math.pow(Math.sin(lat/2),2)+Math.cos(Math.toRadians(a.latitude()))*Math.cos(Math.toRadians(b.latitude()))*Math.pow(Math.sin(lon/2),2);
        return 6371000*2*Math.asin(Math.sqrt(Math.min(1,h)));
    }
}
