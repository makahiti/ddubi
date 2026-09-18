package com.ssafy.ddubi.domain.route.mapping;

import com.fasterxml.jackson.databind.*;
import com.ssafy.ddubi.domain.route.model.Coordinate;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** 외부 카카오 1~20.json을 실제 Java 매핑기로 평가하는 오프라인 실행 진입점.
 * DB/서버/키 없이 명시한 artifact와 검토일만 사용한다. v6 결과는 비교에만 쓰며 매핑기에
 * 전달하지 않는다. 원본 JSON은 읽기 전용, 결과는 사용자가 지정한 경로에 저장한다. */
public final class MetropolitanBusReplay {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MetropolitanBusReplay.class);
    private MetropolitanBusReplay() { }
    /** 인수: artifact.gz responses-directory v6-results.json as-of-date output.json */
    public static void main(String[] args) throws Exception {
        if(args.length<5 || args.length>6)throw new IllegalArgumentException("artifact responses baseline asOf output [negative] 인수 필요");
        ObjectMapper json=new ObjectMapper();var master=MetropolitanBusMaster.read(Path.of(args[0]));
        var matcher=new MetropolitanBusMatcher(master);LocalDate date=LocalDate.parse(args[3]);
        var replayMatcher=new MetropolitanBusMatcher(master);
        // 실제 카카오 DTO → Mapper → Resolver 경계도 함께 통과시켜 기본 BUS 연결 누락을 잡는다.
        // 지하철 마스터는 이 BUS 전용 검증 범위 밖이므로 미구성으로 두며 DB 캐시는 주입하지 않는다.
        var provider=new SeoulTransitMasterDataProvider() {
            public Optional<String> activeVersion(){return Optional.empty();}
            public List<SubwayLineMaster> loadSubwayLines(String version){return List.of();}
            public TransitAliasSnapshot loadAliases(String version){return TransitAliasSnapshot.empty();}
        };
        var clock=java.time.Clock.fixed(date.atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant(),java.time.ZoneId.of("Asia/Seoul"));
        var resolver=new com.ssafy.ddubi.domain.route.service.MasterDataTransitMappingResolver(provider,null,
                new ProviderSegmentSignatureFactory(),new CanonicalSegmentIdFactory(),new MetropolitanBusCatalog(master),null,clock);
        var mapper=new com.ssafy.ddubi.domain.route.service.KakaoRouteMapper(resolver);
        int resolverSteps=0;
        boolean negatives=args.length==6 && args[5].equals("negative");int negativeCases=0;
        List<Object> unexpected=new ArrayList<>();
        JsonNode baseline=json.readTree(Path.of(args[2]).toFile());
        Map<String,JsonNode> prior=new HashMap<>();
        for(var row:baseline)for(var alt:row.path("alternatives")) {
            prior.put(row.path("caseId").asText()+"/"+row.path("candidateIndex").asInt()+"/"+row.path("stepIndex").asInt()+"/"+alt.path("providerNames").get(0).asText(),alt);
        }
        List<Object> rows=new ArrayList<>(),changes=new ArrayList<>();Map<String,Integer> counts=new TreeMap<>();
        Map<String,String> inputHashes=new TreeMap<>();int steps=0,alternatives=0,previousPreserved=0,allSteps=0;
        long start=System.nanoTime();
        for(int i=1;i<=20;i++) {
            Path file=Path.of(args[1],i+".json");byte[] raw=Files.readAllBytes(file);
            inputHashes.put(file.getFileName().toString(),MetropolitanBusMaster.sha(raw));
            JsonNode response=json.readTree(raw);int ri=-1;
            var mapped=mapper.map(json.readValue(raw,com.ssafy.ddubi.domain.route.client.dto.KakaoPublicTransitResponse.class),clock.instant());
            for(JsonNode route:response.path("routes")) {
                ri++;int si=-1;
                for(JsonNode step:route.path("steps")) {
                    si++;if(!step.path("properties").path("type").asText().equals("BUS"))continue;
                    steps++;var input=request(step);var result=matcher.match(input,date);boolean all=true;
                    final int candidateIndex=ri,stepSequence=si+1;
                    var actual=mapped.candidates().stream().filter(c->c.providerCandidateIndex()==candidateIndex).findFirst().orElseThrow()
                            .segments().stream().filter(s->s.sequence()==stepSequence).findFirst().orElseThrow().mapping();
                    if(!actual.busAlternatives().equals(result))throw new IllegalStateException("Kakao Mapper/Resolver 결과 불일치: "+i+"/"+ri+"/"+si);
                    resolverSteps++;
                    if(!result.equals(replayMatcher.match(input,date)))throw new IllegalStateException("동일 입력 Replay 불일치");
                    if(negatives)for(String mode:List.of("REVERSE","SHIFT","UNKNOWN_ROUTE","UNKNOWN_NAMES","ZERO_LENGTH")) {
                        negativeCases++;
                        var damaged=damage(input,mode);
                        if(matcher.match(damaged,date).stream().anyMatch(a->a.geometryStatus().equals("VERIFIED")))
                            unexpected.add(Map.of("file",i,"route",ri,"step",si,"mode",mode));
                    }
                    for(var alt:result) {
                        alternatives++;counts.merge(alt.identityStatus()+"/"+alt.geometryStatus(),1,Integer::sum);
                        boolean verified=alt.geometryStatus().equals("VERIFIED");all&=verified;
                        String key=i+"/"+ri+"/"+si+"/"+alt.providerName();JsonNode before=prior.get(key);
                        if(before!=null && before.path("status").asText().equals("VERIFIED")) {
                            boolean same=verified && alt.candidates().stream().anyMatch(c->samePath(before,c));
                            if(same)previousPreserved++;else changes.add(Map.of("key",key,"beforeRouteId",before.path("officialRouteId"),"after",alt));
                        }
                    }
                    if(all)allSteps++;
                    rows.add(Map.of("caseId",Integer.toString(i),"candidateIndex",ri,"stepIndex",si,"alternatives",result));
                }
            }
            log.info(i+".json 완료: "+alternatives+" 대안");
        }
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("masterVersion",master.version());report.put("policy",MetropolitanBusMatcher.POLICY);
        report.put("reviewAsOf",date.toString());report.put("busSteps",steps);report.put("alternatives",alternatives);
        report.put("kakaoMapperResolverVerifiedSteps",resolverSteps);
        report.put("counts",counts);report.put("allAlternativesVerifiedSteps",allSteps);
        report.put("v6VerifiedPreservedWithSameIdsAndOrders",previousPreserved);report.put("changedV6Verified",changes);
        report.put("inputSha256",inputHashes);report.put("elapsedSeconds",(System.nanoTime()-start)/1e9);report.put("results",rows);
        report.put("replayIdentical",true);report.put("negativeCases",negativeCases);report.put("unexpectedNegativeVerified",unexpected);
        for(var entry:inputHashes.entrySet())if(!MetropolitanBusMaster.sha(Files.readAllBytes(Path.of(args[1],entry.getKey()))).equals(entry.getValue()))
            throw new IllegalStateException("검증 중 원본 변경: "+entry.getKey());
        report.put("inputsUnchanged",true);
        Path output=Path.of(args[4]);Files.createDirectories(output.toAbsolutePath().getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        log.info("SUMMARY "+counts+"; v6Preserved="+previousPreserved+"; changed="+changes.size());
        if(!unexpected.isEmpty())throw new IllegalStateException("손상 입력을 VERIFIED로 반환함: "+unexpected.size());
    }
    /** 운영 오탐을 유발하는 다섯 손상 입력. 원본 리스트는 불변 복사본으로 유지한다. */
    private static BusMappingRequest damage(BusMappingRequest req,String mode) {
        var names=new ArrayList<>(req.passingStopNames());var vehicles=req.providerRouteNames();
        var points=new ArrayList<>(req.geometry());
        switch(mode) {
            case "REVERSE" -> Collections.reverse(points);
            case "SHIFT" -> points.replaceAll(p->new Coordinate(p.longitude(),p.latitude()+.1));
            case "UNKNOWN_ROUTE" -> vehicles=List.of("UNREGISTERED_ROUTE_999999");
            case "UNKNOWN_NAMES" -> {for(int i=0;i<names.size();i++)names.set(i,"새이름검증"+i);}
            case "ZERO_LENGTH" -> points=new ArrayList<>(List.of(points.get(0),points.get(0)));
            default -> throw new IllegalArgumentException(mode);
        }
        return new BusMappingRequest(vehicles,names.get(0),names.get(names.size()-1),names,points);
    }
    private static boolean samePath(JsonNode before,BusAlternativeMapping.Candidate candidate) {
        if(!before.path("officialRouteId").asText().equals(candidate.routeId()))return false;
        JsonNode stops=before.path("officialStops");if(stops.size()!=candidate.stops().size())return false;
        for(int i=0;i<stops.size();i++)if(!stops.get(i).path("stopId").asText().equals(candidate.stops().get(i).stopId())
                || stops.get(i).path("sequence").asInt()!=candidate.stops().get(i).sequence())return false;
        return true;
    }
    /** 카카오 stops에는 개별 GPS가 없으므로 중간 path 점을 정류장 좌표로 추정하지 않는다. */
    public static BusMappingRequest request(JsonNode step) {
        List<String> names=new ArrayList<>(),vehicles=new ArrayList<>();List<Coordinate> points=new ArrayList<>();
        for(var s:step.path("properties").path("stops"))names.add(s.path("name").asText());
        for(var v:step.path("properties").path("vehicles"))vehicles.add(v.path("name").asText());
        for(var p:step.path("path").path("points")) {
            if(p.size()!=2 || !p.get(0).isNumber() || !p.get(1).isNumber())throw new IllegalArgumentException("카카오 경로 좌표 형식 오류");
            points.add(new Coordinate(p.get(0).asDouble(),p.get(1).asDouble()));
        }
        return new BusMappingRequest(vehicles,names.isEmpty()?"":names.get(0),names.isEmpty()?"":names.get(names.size()-1),names,points);
    }
}
