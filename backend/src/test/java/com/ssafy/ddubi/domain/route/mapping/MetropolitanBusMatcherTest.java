package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.mapping.MetropolitanBusMaster.*;
import com.ssafy.ddubi.domain.route.model.Coordinate;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 실제 오매핑 장애를 작은 손계산 가능 직선 경로로 검증한다. 거리 단위는 m이다. */
class MetropolitanBusMatcherTest {
    final LocalDate date=LocalDate.of(2026,9,10);
    final Coordinate a=new Coordinate(127,37.5),b=new Coordinate(127.002,37.5),c=new Coordinate(127.004,37.5);
    Stop stop(String id,String name,int seq,Coordinate p){return new Stop("GBIS",id,name,seq,"UP",true,p.longitude(),p.latitude(),null,"CURRENT");}
    Route route(String id,String name,List<Stop> stops){return new Route("GBIS",id,name,"경기도",stops,Map.of());}
    Route regular(){return route("1","100",List.of(stop("a","출발정류소",1,a),stop("b","중간정류소",2,b),stop("c","도착정류소",3,c)));}
    MetropolitanBusMaster master(List<Route> routes){return new MetropolitanBusMaster(1,"TEST","a".repeat(64),"b".repeat(64),routes,List.of());}
    BusMappingRequest req(List<String> vehicles,List<Coordinate> points){return new BusMappingRequest(vehicles,"출발정류소","도착정류소",List.of("출발정류소","중간정류소","도착정류소"),points);}
    @Test void eachVehicleKeepsItsOwnRouteAndUnmappedAlternative() {
        var matcher=new MetropolitanBusMatcher(master(List.of(regular(),route("2","200",regular().stops()))));
        var result=matcher.match(req(List.of("100","200","999"),List.of(a,b,c)),date);
        assertThat(result).hasSize(3);
        assertThat(result.get(0).geometryStatus()).isEqualTo("VERIFIED");
        assertThat(result.get(1).candidates().get(0).routeId()).isEqualTo("2");
        assertThat(result.get(2).identityStatus()).isEqualTo("UNRESOLVED");
        assertThat(result).allMatch(r->!r.predictionReady());
    }
    @Test void identitySurvivesEndpointConflictWithoutPredictionApproval() {
        var result=new MetropolitanBusMatcher(master(List.of(regular())))
                .match(req(List.of("100"),List.of(a,b,new Coordinate(127.005,37.5))),date).get(0);
        assertThat(result.identityStatus()).isEqualTo("CONFIRMED");
        assertThat(result.geometryStatus()).isEqualTo("CONFLICT");
        assertThat(result.reason()).isEqualTo("ENDPOINT_TOO_FAR");
        assertThat(result.candidates().get(0).endDistanceMeters()).isBetween(80.0,95.0);
    }
    @Test void reverseAndInteriorDetourAreNotVerified() {
        var matcher=new MetropolitanBusMatcher(master(List.of(regular())));
        assertThat(matcher.match(req(List.of("100"),List.of(c,b,a)),date).get(0).geometryStatus()).isNotEqualTo("VERIFIED");
        assertThat(matcher.match(req(List.of("100"),List.of(a,new Coordinate(127.002,37.51),c)),date).get(0).geometryStatus()).isNotEqualTo("VERIFIED");
    }
    @Test void sequenceHoleDoesNotBecomeAnAdjacentEdge() {
        var r=route("1","100",List.of(stop("a","출발정류소",1,a),stop("b","중간정류소",3,b),stop("c","도착정류소",4,c)));
        assertThat(new MetropolitanBusMatcher(master(List.of(r))).match(req(List.of("100"),List.of(a,b,c)),date).get(0).identityStatus()).isEqualTo("UNRESOLVED");
    }
    @Test void differentSourceIntermediateIdsRemainAmbiguous() {
        var first=new Route("GBIS","1","100","인천광역시",regular().stops(),Map.of());
        var other=new Route("INCHEON_PUBLIC_CSV","1","100","인천광역시",List.of(stop("a","출발정류소",1,a),stop("different","중간정류소",2,b),stop("c","도착정류소",3,c)),Map.of());
        assertThat(new MetropolitanBusMatcher(master(List.of(first,other))).match(req(List.of("100"),List.of(a,b,c)),date).get(0).identityStatus()).isEqualTo("AMBIGUOUS");
    }
    @Test void reviewedReservationExpiresAndDoesNotMatchChangedMiddleGeometry() {
        var reserved=route("1","100(예약)",regular().stops());var input=req(List.of("100"),List.of(a,b,c));
        var review=new Review("2026-09-09","100",MetropolitanBusMatcher.evidenceDigest(input),reserved.key(),List.of(),List.of());
        var m=new MetropolitanBusMaster(1,"TEST","a".repeat(64),"b".repeat(64),List.of(reserved),List.of(review));
        var matcher=new MetropolitanBusMatcher(m);
        assertThat(matcher.match(input,date.minusDays(1)).get(0).geometryStatus()).isEqualTo("VERIFIED");
        assertThat(matcher.match(input,date).get(0).geometryStatus()).isNotEqualTo("VERIFIED");
        var changed=req(List.of("100"),List.of(a,new Coordinate(127.002,37.50001),c));
        assertThat(matcher.match(changed,date.minusDays(1)).get(0).geometryStatus()).isNotEqualTo("VERIFIED");
    }
    @Test void duplicateSequenceAndInvalidCoordinatesFailBeforeLoading() {
        var r=route("1","100",List.of(stop("a","출발정류소",1,a),stop("b","중간정류소",1,b)));
        assertThatThrownBy(()->new MetropolitanBusMatcher(master(List.of(r)))).isInstanceOf(IllegalArgumentException.class);
    }
}
