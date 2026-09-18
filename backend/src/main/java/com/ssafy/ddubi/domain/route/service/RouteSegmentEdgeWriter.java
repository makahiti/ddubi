package com.ssafy.ddubi.domain.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 소유권 확인을 마친 경로 저장 서비스가 호출하는 BUS edge 저장 경계.
 * 외부 API가 아니며 raw 사용자 mapping을 신뢰하지 않는다. 전달된 서버 RouteSegment 입력을
 * 현재 Resolver로 재검증하고, 선택 경로 전체를 하나의 트랜잭션으로 교체한다. 검색에서는 호출하지 않는다. */
@Service
public class RouteSegmentEdgeWriter {
    private final JdbcTemplate jdbc;private final MasterDataTransitMappingResolver resolver;private final ObjectMapper json;
    public RouteSegmentEdgeWriter(JdbcTemplate jdbc,MasterDataTransitMappingResolver resolver,ObjectMapper json) {
        this.jdbc=jdbc;this.resolver=resolver;this.json=json;
    }
    /** 부모 route 소유권 검증 이후에만 호출한다. 같은 저장을 반복해도 PK(edge_seq) 기준 전체 목록은 동일하다.
     * 검색 당시 마스터/정책이 달라졌으면 순번으로 다른 후보를 선택하지 않고 재검색을 요구한다.
     * 삭제·삽입 실패는 이전 목록까지 모두 롤백된다. 사용자·일정 테이블은 읽거나 쓰지 않는다. */
    @Transactional(rollbackFor=Exception.class)
    public List<String> replace(long routeId,long routeSegmentId,RouteSegment serverSegment,int alternativeIndex,int candidateIndex) throws Exception {
        if(serverSegment.mode()!=TransportMode.BUS)throw new IllegalArgumentException("BUS 저장만 지원");
        // 1. 같은 부모·노선·승하차·형상인지 잠금 아래 확인한다. 같은 정류장 사이의 다른 노선을 덮어쓰지 않는다.
        var rows=jdbc.queryForList("SELECT route_id,seq,mode,provider_route_key,from_name,to_name,geometry FROM route_segment WHERE id=? FOR UPDATE",routeSegmentId);
        if(rows.size()!=1)throw new IllegalArgumentException("저장 대상 구간 없음");
        var row=rows.get(0);
        if(((Number)row.get("route_id")).longValue()!=routeId || ((Number)row.get("seq")).intValue()!=serverSegment.sequence()
                || !"BUS".equals(row.get("mode")) || !Objects.equals(row.get("provider_route_key"),serverSegment.providerRouteKey())
                || !Objects.equals(row.get("from_name"),serverSegment.fromName())
                || !Objects.equals(row.get("to_name"),serverSegment.toName()))throw new IllegalArgumentException("다른 경로 구간");
        Object stored=row.get("geometry");String geometry=stored instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):Objects.toString(stored,"null");
        if(!json.readTree(geometry).equals(json.valueToTree(serverSegment.geometry())))throw new IllegalArgumentException("저장된 경로 좌표 불일치");
        // 2. 검색 이후 마스터·검토일이 바뀌면 후보 순번의 의미도 달라질 수 있으므로 현재 판정과 전체 경로를 비교한다.
        var request=new SegmentResolveRequest(TransportMode.BUS,serverSegment.providerRouteKey(),serverSegment.fromName(),serverSegment.toName(),
                serverSegment.stopNames(),serverSegment.vehicles(),serverSegment.geometry());
        var current=resolver.resolve(request);
        if(!Objects.equals(current.masterDataVersion(),serverSegment.mapping().masterDataVersion())
                || !Objects.equals(current.mappingPolicyVersion(),serverSegment.mapping().mappingPolicyVersion()))throw new IllegalArgumentException("매핑 버전 변경: 재검색 필요");
        var selected=BusCanonicalPath.select(current,alternativeIndex,candidateIndex);
        var original=BusCanonicalPath.select(serverSegment.mapping(),alternativeIndex,candidateIndex);
        if(!selected.equals(original))throw new IllegalArgumentException("매핑 후보 변경: 재검색 필요");
        // 3. 모든 검증이 끝난 뒤에만 교체한다. Kafka/Spark는 이 서비스 테이블에 직접 쓰지 않는다.
        jdbc.update("DELETE FROM route_segment_edge WHERE route_segment_id=?",routeSegmentId);
        jdbc.batchUpdate("INSERT INTO route_segment_edge(route_segment_id,edge_seq,segment_id,mapping_version,policy_version) VALUES(?,?,?,?,?)",
                selected.edges().stream().map(e->new Object[]{routeSegmentId,e.sequence(),e.segmentId(),selected.masterVersion(),selected.policyVersion()}).toList());
        jdbc.update("UPDATE route_segment SET segment_id=? WHERE id=?",selected.edges().size()==1?selected.edges().get(0).segmentId():null,routeSegmentId);
        return selected.edges().stream().map(MappedTransitSegment::segmentId).toList();
    }
}
