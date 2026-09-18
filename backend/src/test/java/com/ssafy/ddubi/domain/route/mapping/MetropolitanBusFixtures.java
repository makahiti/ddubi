package com.ssafy.ddubi.domain.route.mapping;

import java.util.*;
import com.ssafy.ddubi.domain.route.model.Coordinate;

/** 손계산 가능한 서울 버스 topology와 과거 corpus 입력의 테스트 전용 변환기다.
 * 공식 형상은 만들지 않으며 테스트 좌표와 정류장 순서만 새 Matcher에 전달한다. */
public final class MetropolitanBusFixtures {
    private MetropolitanBusFixtures() { }
    public static MetropolitanBusMaster master(List<MetropolitanBusMaster.Route> routes) {
        return new MetropolitanBusMaster(1,"TEST","a".repeat(64),"b".repeat(64),routes,List.of());
    }
    public static MetropolitanBusMaster.Stop stop(String id,String name,int seq,double x,double y) {
        return new MetropolitanBusMaster.Stop("SEOUL_BUS",id,name,seq,"FORWARD",true,x,y,null,"TEST");
    }
    public static MetropolitanBusMaster.Route route(String id,String name,MetropolitanBusMaster.Stop... stops) {
        return new MetropolitanBusMaster.Route("SEOUL_BUS",id,name,"서울",List.of(stops),Map.of());
    }
    public static MetropolitanBusMaster.Route regular() {
        return route("R1","100",stop("A","출발",1,127,37.5),
                stop("B","중간",2,127.002,37.5),stop("C","도착",3,127.004,37.5));
    }
    public static List<Coordinate> geometry() {
        return List.of(new Coordinate(127,37.5),new Coordinate(127.002,37.5),new Coordinate(127.004,37.5));
    }
    public static MetropolitanBusMaster fromLegacy(List<LegacyBusRouteFixture> routes,String version) {
        var converted=routes.stream().map(r->new MetropolitanBusMaster.Route("SEOUL_BUS",r.routeId(),r.routeName(),"서울",
                r.stops().stream().map(s->new MetropolitanBusMaster.Stop("SEOUL_BUS",s.stopId(),s.stopName(),s.sequence(),
                        "FORWARD",null,s.longitude(),s.latitude(),null,"LEGACY_FIXTURE")).toList(),Map.of())).toList();
        return new MetropolitanBusMaster(1,version,"a".repeat(64),"b".repeat(64),converted,List.of());
    }
}
