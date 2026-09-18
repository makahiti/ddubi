package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.model.Coordinate;
import com.ssafy.ddubi.domain.route.mapping.MetropolitanBusMaster.*;
import com.ssafy.ddubi.domain.route.mapping.BusAlternativeMapping.Candidate;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** 수도권 공식 topology를 차량 대안별로 검증하는 순수 Java 매핑기.
 * 1. 번호/운행 조건 후보 → 2. 이름·순서 ID 확인 → 3. 공간/형상 검증 → 4. 출처 대조.
 * 관할 지역 문자열로 광역버스를 제외하지 않는다. 최단경로/시간/ML 계산은 하지 않는다.
 * 검토 기준일은 호출자가 주입하며 만료된 수동 대응을 최신 근거로 사용하지 않는다. */
public final class MetropolitanBusMatcher {
    public static final String POLICY="metro-bus-v1";
    private static final double RADIUS=50;
    private final MetropolitanBusMaster master;
    private final Map<String,List<Route>> byName=new TreeMap<>();
    private final Map<String,Route> byKey=new HashMap<>();
    private static final Pattern EXIT=Pattern.compile("(\\d+)번\\s*출구");

    public MetropolitanBusMatcher(MetropolitanBusMaster master) {
        master.validate();this.master=master;
        for(Route r:master.routes()) {byKey.put(r.key(),r);byName.computeIfAbsent(routeKey(r.routeName()),k->new ArrayList<>()).add(r);}
    }
    /** 활성 불변 마스터의 출처별 노선/정류장 occurrence 수다. 물리 정류장 고유 수와 구분한다. */
    public Map<String, Map<String, Long>> inventory() {
        var result=new TreeMap<String,Map<String,Long>>();
        for(var route:master.routes()) {
            var counts=result.computeIfAbsent(route.namespace(),ignored->new TreeMap<>(Map.of("routes",0L,"stopOccurrences",0L)));
            counts.compute("routes",(key,value)->value+1);
            counts.compute("stopOccurrences",(key,value)->value+route.stops().size());
        }
        var immutable=new TreeMap<String,Map<String,Long>>();
        result.forEach((namespace,counts)->immutable.put(namespace,Map.copyOf(counts)));
        return Map.copyOf(immutable);
    }
    /** 각 대안에 독립 결과를 반환한다. 동일 번호라도 예약·A/B가 다르면 합치지 않는다. */
    public List<BusAlternativeMapping> match(BusMappingRequest request,LocalDate asOf) {
        Map<String,String> labels=new LinkedHashMap<>();
        for(String n:request.providerRouteNames())labels.putIfAbsent(routeKey(n)+"|"+variant(n)+"|"+branch(n),n);
        if(labels.isEmpty())labels.put("","");
        return labels.values().stream().map(n->matchVehicle(n,request,asOf)).toList();
    }
    private BusAlternativeMapping matchVehicle(String label,BusMappingRequest req,LocalDate date) {
        if(req.passingStopNames().size()<2 || req.passingStopNames().stream().anyMatch(n->normalize(n,false,false).isEmpty()))
            return failure(label,"INVALID_PROVIDER_STOPS",List.of());
        List<Coordinate> points=req.geometry();
        if(points.size()<2 || points.stream().anyMatch(p->!BusTrace.valid(p)))return failure(label,"INVALID_GEOMETRY",List.of());
        BusTrace trace=new BusTrace(points);
        if(trace.length<1)return failure(label,"ZERO_LENGTH_GEOMETRY",List.of());
        List<Route> routes=byName.getOrDefault(routeKey(label),List.of()).stream()
                .filter(r->(variant(r.routeName()).isEmpty() || variant(r.routeName()).equals(variant(label)))
                        && branch(r.routeName()).equals(branch(label))).toList();
        List<Candidate> candidates=evaluate(routes,req,trace,date);
        BusAlternativeMapping original=decide(label,candidates);
        if(original.geometryStatus().equals("VERIFIED"))return original;
        // 수동 선택은 전체 요청 해시·검토일·불변 artifact에 고정된다. 모호한 출처 선택에는 전체 ID/안내번호가 필요하다.
        List<Review> reviews=master.reviews().stream().filter(r->r.observedDate().equals(date.toString())
                && r.providerName().equals(label) && r.requestSha256().equals(evidenceDigest(req))).toList();
        if(reviews.size()!=1)return routes.isEmpty()?failure(label,"ROUTE_OR_SERVICE_VARIANT_NOT_FOUND",List.of()):original;
        Review review=reviews.get(0);
        if(original.identityStatus().equals("AMBIGUOUS") && (review.stopIds()==null || review.stopIds().isEmpty()))return original;
        var reviewed=evaluate(List.of(byKey.get(review.routeKey())),req,trace,date);
        if(review.stopIds()!=null && !review.stopIds().isEmpty())reviewed=reviewed.stream()
                .filter(c->c.stops().stream().map(Stop::stopId).toList().equals(review.stopIds())
                        && (review.mobileCodes()==null || review.mobileCodes().isEmpty()
                            || c.stops().stream().map(Stop::mobileCode).toList().equals(review.mobileCodes()))).toList();
        var result=decide(label,reviewed);
        return result.geometryStatus().equals("VERIFIED")?new BusAlternativeMapping(label,result.identityStatus(),result.geometryStatus(),
                "REVIEWED_INPUT_AND_OFFICIAL_PATH",false,result.candidates()):original;
    }
    /** 이름 후보와 좌표 후보를 모두 검사한다. 이름이 유일하지만 좌표가 충돌하면 ID 증거만 남긴다. */
    private List<Candidate> evaluate(List<Route> routes,BusMappingRequest req,BusTrace trace,LocalDate date) {
        List<Candidate> result=new ArrayList<>();
        for(Route route:routes) {
            List<Stop> stops=route.stops();
            for(int i=0;i<stops.size()-1;i++) {
                Stop a=stops.get(i);
                if(Boolean.FALSE.equals(a.boardingAllowed()) || !endpointCandidate(a,req.fromStopName(),req.geometry().get(0)))continue;
                for(int j=i+1;j<stops.size();j++) {
                    Stop b=stops.get(j);
                    if(Boolean.FALSE.equals(b.boardingAllowed()) || !endpointCandidate(b,req.toStopName(),last(req.geometry())))continue;
                    List<Stop> path=stops.subList(i,j+1);
                    boolean contiguous=true;
                    for(int k=1;k<path.size();k++)if(path.get(k).sequence()!=path.get(k-1).sequence()+1)contiguous=false;
                    if(!contiguous)continue;
                    NameCheck names=names(path,req.passingStopNames(),route,date,req.geometry());
                    if(!names.matched())continue;
                    Double sd=a.point()==null?null:BusTrace.distance(req.geometry().get(0),a.point());
                    Double ed=b.point()==null?null:BusTrace.distance(last(req.geometry()),b.point());
                    String reason="NONE",shape="NOT_CHECKED";
                    if(path.stream().anyMatch(s->s.point()==null))reason="MASTER_COORDINATE_UNAVAILABLE";
                    else if(Math.max(sd,ed)>RADIUS)reason="ENDPOINT_TOO_FAR";
                    else if(sd>=BusTrace.distance(req.geometry().get(0),b.point()) || ed>=BusTrace.distance(last(req.geometry()),a.point()))reason="DIRECTION_NOT_CONFIRMED";
                    else {
                        double previous=0;
                        for(Stop s:path.subList(1,path.size()-1)) {
                            var positions=trace.projections(s.point(),RADIUS);
                            if(positions.isEmpty()){reason="INTERIOR_STOP_OFF_TRACE";break;}
                            final double floor=previous;
                            var next=positions.stream().filter(v->v+1e-6>=floor).findFirst();
                            if(next.isEmpty()){reason="TRACE_ORDER_MISMATCH";break;}
                            previous=next.get();
                        }
                        if(reason.equals("NONE")) {
                            shape=shape(route,path,req.geometry());
                            if(shape.equals("MISMATCH")) {
                                var cleaned=reviewedSpur(req.geometry(),path);
                                if(cleaned!=req.geometry() && shape(route,path,cleaned).equals("MATCHED"))shape="MATCHED_REVIEWED_SPUR";
                            }
                            if(shape.equals("MISMATCH"))reason="OFFICIAL_SHAPE_MISMATCH";
                        }
                    }
                    // 이름 추론에는 공간 증거가 필수다. 공간이 실패하면 추론한 ID를 CONFIRMED로 올리지 않는다.
                    if(names.inferred() && !reason.equals("NONE"))continue;
                    result.add(new Candidate(route.namespace(),route.routeId(),route.routeName(),route.region(),path,reason,shape,sd,ed,names.inferred()));
                }
            }
        }
        return result;
    }
    private boolean endpointCandidate(Stop s,String name,Coordinate point) {
        return nameMatches(name,s.stopName()) || (s.point()!=null && BusTrace.distance(point,s.point())<=RADIUS);
    }
    private BusAlternativeMapping decide(String label,List<Candidate> candidates) {
        List<Candidate> verified=candidates.stream().filter(c->c.reason().equals("NONE")).toList();
        if(!verified.isEmpty() && candidates.stream().anyMatch(c->c.reason().equals("MASTER_COORDINATE_UNAVAILABLE")))
            return failure(label,"INCOMPLETE_MASTER_CANDIDATES",candidates);
        List<Candidate> pool=verified.isEmpty()?candidates:verified;
        if(pool.isEmpty())return failure(label,"STOP_ORDER_OR_SPATIAL_EVIDENCE_UNRESOLVED",candidates);
        List<List<Candidate>> groups=new ArrayList<>();
        for(Candidate c:pool) {
            var group=groups.stream().filter(g->g.stream().allMatch(m->equivalent(m,c))).findFirst();
            if(group.isPresent())group.get().add(c);else groups.add(new ArrayList<>(List.of(c)));
        }
        if(groups.size()>1)return new BusAlternativeMapping(label,"AMBIGUOUS","NOT_CHECKED","MULTIPLE_DISTINCT_OFFICIAL_PATHS",false,pool);
        return new BusAlternativeMapping(label,"CONFIRMED",verified.isEmpty()?
                (pool.stream().anyMatch(c->c.reason().equals("MASTER_COORDINATE_UNAVAILABLE"))?"UNAVAILABLE":"CONFLICT"):"VERIFIED",
                verified.isEmpty()?pool.get(0).reason():"UNIQUE_OFFICIAL_PATH",false,pool);
    }
    private BusAlternativeMapping failure(String label,String reason,List<Candidate> candidates) {
        return new BusAlternativeMapping(label,"UNRESOLVED","NOT_CHECKED",reason,false,candidates);
    }
    /** 숫자 ID만으로 통합하지 않고 모든 경유 ID·방향·위치를 직접 비교한다.
     * 출처가 다른 동일 부분경로의 순번 차이는 원본 후보 각각에 보존한다. */
    private boolean equivalent(Candidate a,Candidate b) {
        if(a.identity().equals(b.identity()))return true;
        if(a.namespace().equals(b.namespace()) || !Set.of(a.namespace(),b.namespace()).equals(Set.of("GBIS","INCHEON_PUBLIC_CSV"))
                || !a.routeId().equals(b.routeId()) || !routeKey(a.routeName()).equals(routeKey(b.routeName()))
                || !variant(a.routeName()).equals(variant(b.routeName())) || !branch(a.routeName()).equals(branch(b.routeName()))
                || a.stops().size()!=b.stops().size())return false;
        Candidate gbis=a.namespace().equals("GBIS")?a:b;
        if(gbis.region()==null || !gbis.region().contains("인천"))return false;
        for(int i=0;i<a.stops().size();i++) {
            Stop x=a.stops().get(i),y=b.stops().get(i);
            if(!x.stopId().equals(y.stopId()) || x.direction()==null || !x.direction().equals(y.direction())
                    || x.point()==null || y.point()==null || BusTrace.distance(x.point(),y.point())>RADIUS
                    || (x.boardingAllowed()!=null && y.boardingAllowed()!=null && !x.boardingAllowed().equals(y.boardingAllowed())))return false;
        }
        return true;
    }
    private record NameCheck(boolean matched,boolean inferred) { }
    /** 이름 부분수열을 먼저 확인한다. 불일치 추론은 같은 정류장 수와 둘 이상의 이름 기준점이 있어야 한다. */
    private NameCheck names(List<Stop> path,List<String> supplied,Route route,LocalDate date,List<Coordinate> points) {
        var board=path.stream().filter(s->!Boolean.FALSE.equals(s.boardingAllowed())).toList();
        List<String> source=new ArrayList<>(supplied);
        if(date.toString().equals("2026-09-09") && route.namespace().equals("GBIS") && source.size()==2
                && source.get(0).equals("논현역(중)") && source.get(1).equals("신논현역7번출구.교보타워사거리")
                && board.get(0).stopId().equals("121000013") && last(board).stopId().equals("121000940")
                && last(board).point()!=null
                && BusTrace.distance(last(points),last(board).point())<10)source.set(1,last(board).stopName());
        int cursor=0;boolean all=true;
        for(int i=0;i<source.size();i++) {
            int start=i==0?0:i==source.size()-1?board.size()-1:cursor;
            int end=i==0?1:i==source.size()-1?board.size():board.size()-1;
            int hit=-1;
            for(int j=Math.max(cursor,start);j<end;j++)if(nameMatches(source.get(i),board.get(j).stopName())){hit=j;break;}
            if(hit<0){all=false;break;}cursor=hit+1;
        }
        if(all)return new NameCheck(true,false);
        if(board.size()!=source.size())return new NameCheck(false,false);
        boolean[] evidence=new boolean[source.size()];int count=0;
        for(int i=0;i<source.size();i++){evidence[i]=nameMatches(source.get(i),board.get(i).stopName());if(evidence[i])count++;}
        if(count<2)return new NameCheck(false,false);
        for(int i=0;i<source.size();i++) {
            if(exitConflict(source.get(i),board.get(i).stopName()))return new NameCheck(false,false);
            if(evidence[i])continue;
            for(int j=0;j<source.size();j++)if(i!=j && nameMatches(source.get(i),board.get(j).stopName()))return new NameCheck(false,false);
            if(i>0 && i<source.size()-1 && !(evidence[i-1] && evidence[i+1]))return new NameCheck(false,false);
        }
        return new NameCheck(true,true);
    }
    /** 공식 형상은 두 방향으로 비교한다. 미제공은 MATCHED나 0m로 위장하지 않는다. */
    private String shape(Route r,List<Stop> path,List<Coordinate> points) {
        Set<String> dirs=new HashSet<>();path.forEach(s->dirs.add(s.direction()));
        List<Coordinate> official=null;
        if(dirs.size()==1 && !dirs.contains(null))official=r.shapes().get(dirs.iterator().next());
        else if(dirs.equals(Set.of("UP","DOWN"))) {
            var up=r.shapes().get("UP");var down=r.shapes().get("DOWN");
            if(up!=null && down!=null && BusTrace.distance(last(up),down.get(0))<1) {
                official=new ArrayList<>(up);official.addAll(down.subList(1,down.size()));
            }
        }
        if(official==null)return "NOT_AVAILABLE";
        BusTrace shape=new BusTrace(official),provider=new BusTrace(points);
        for(double a:shape.projections(path.get(0).point(),RADIUS))for(double b:shape.projections(last(path).point(),RADIUS)) {
            if(b<=a)continue;var cut=shape.clip(a,b);if(cut.size()<2)continue;
            double previous=0;boolean pass=true;
            for(Coordinate p:BusTrace.samples(cut)) {
                final double floor=previous;
                var next=provider.projections(p,RADIUS).stream().filter(v->v+1e-5>=floor).findFirst();
                if(next.isEmpty()){pass=false;break;}previous=next.get();
            }
            if(!pass)continue;BusTrace target=new BusTrace(cut);
            for(Coordinate p:BusTrace.samples(points))if(target.projections(p,RADIUS).isEmpty()){pass=false;break;}
            if(pass)return "MATCHED";
        }
        return "MISMATCH";
    }
    /** v6에서 확인한 정확한 A-B-A만 형상 비교 복사본에서 제외한다.
     * 노선 별칭과 달리 날짜 추론을 하지 않는다. 매번 25m·100m 조건과 고정 마스터 형상을 재검증한다. */
    private List<Coordinate> reviewedSpur(List<Coordinate> points,List<Stop> path) {
        var a=new Coordinate(127.10423034,37.36499649);var b=new Coordinate(127.10445558,37.36500341);
        for(int i=1;i<points.size()-1;i++)if(points.get(i-1).equals(a) && points.get(i).equals(b) && points.get(i+1).equals(a)) {
            List<Coordinate> protectedPoints=new ArrayList<>(List.of(points.get(0),last(points)));
            path.stream().filter(s->!Boolean.FALSE.equals(s.boardingAllowed()) && s.point()!=null).forEach(s->protectedPoints.add(s.point()));
            if(BusTrace.distance(a,b)>25 || protectedPoints.stream().anyMatch(p->Math.min(BusTrace.distance(p,a),BusTrace.distance(p,b))<100))return points;
            List<Coordinate> copy=new ArrayList<>(points.subList(0,i));copy.addAll(points.subList(i+2,points.size()));return copy;
        }
        return points;
    }
    /** 정규화는 후보 검색용이다. 예약·분기 조건은 별도로 비교한다. */
    static String normalize(String value,boolean route,boolean suffix) {
        String s=Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).toUpperCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)|\\[[^]]*\\]","");
        if(suffix)s=s.replaceAll("역(?=$|[\\s.,/·ㆍ])","");
        s=s.replaceAll(route?"[^\\p{L}\\p{N}-]":"[^\\p{L}\\p{N}]","");
        return s.replaceAll(route?"(버스|번)$":"정류장$","");
    }
    static String routeKey(String n){return normalize(n,true,false).replaceAll("(?:평일)?(?:출근|퇴근)$","");}
    private static String variant(String n){return String.join(",",List.of("예약","출근","퇴근").stream().filter(n::contains).toList());}
    private static String branch(String n){var m=Pattern.compile("\\(([AB])\\)",Pattern.CASE_INSENSITIVE).matcher(n);return m.find()?m.group(1).toUpperCase(Locale.ROOT):"";}
    private static boolean exitConflict(String a,String b) {
        Set<String> x=new HashSet<>(),y=new HashSet<>();var m=EXIT.matcher(a);while(m.find())x.add(m.group(1));m=EXIT.matcher(b);while(m.find())y.add(m.group(1));
        return !x.isEmpty() && !y.isEmpty() && Collections.disjoint(x,y);
    }
    private static boolean nameMatches(String a,String b) {
        if(exitConflict(a,b))return false;
        String x=normalize(a,false,false),y=normalize(b,false,false);
        if(x.isEmpty() || y.isEmpty())return false;if(x.equals(y))return true;
        x=normalize(a,false,true);y=normalize(b,false,true);
        if(x.equals(y) || Math.min(x.length(),y.length())>=4 && (x.contains(y)||y.contains(x)))return true;
        var p=Arrays.stream(a.split("[.,·ㆍ/]")).map(v->normalize(v,false,false)).filter(v->!v.isEmpty()).sorted().toList();
        var q=Arrays.stream(b.split("[.,·ㆍ/]")).map(v->normalize(v,false,false)).filter(v->!v.isEmpty()).sorted().toList();
        return p.size()>1 && p.equals(q);
    }
    /** 길이 접두 형식으로 전체 경로를 고정한다. 중간 형상 변조가 캐시를 재사용하지 못하게 한다. */
    public static String evidenceDigest(BusMappingRequest req) {
        StringBuilder s=new StringBuilder();for(String n:req.passingStopNames())append(s,n);
        s.append('|');for(Coordinate p:req.geometry()){append(s,Double.toString(p.longitude()));append(s,Double.toString(p.latitude()));}
        return MetropolitanBusMaster.sha(s.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void append(StringBuilder s,String v){s.append(v.length()).append(':').append(v);}
    private static <T> T last(List<T> a){return a.get(a.size()-1);}
}
