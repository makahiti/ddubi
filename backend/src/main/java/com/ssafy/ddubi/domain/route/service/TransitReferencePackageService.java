package com.ssafy.ddubi.domain.route.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 체크섬 고정 참고패키지 조회. referenceId는 사용자 경로 매핑/운영승인의 증거가 아니다. */
@Service
public class TransitReferencePackageService {
    private record Package(Path root,JsonNode manifest,String status) {}
    private final ObjectMapper json;
    private final Map<String,Package> packages;
    public TransitReferencePackageService(ObjectMapper json,
            @Value("${ddubi.reference-packages.subway.path:}") String subwayPath,
            @Value("${ddubi.reference-packages.subway.sha256:}") String subwaySha,
            @Value("${ddubi.reference-packages.road.path:}") String roadPath,
            @Value("${ddubi.reference-packages.road.sha256:}") String roadSha) {
        this.json=json;
        packages=Map.of("subway",load("SUBWAY",subwayPath,subwaySha),"road",load("ROAD",roadPath,roadSha));
    }
    private static void require(boolean ok) {if(!ok) throw new IllegalArgumentException("Invalid reference package");}
    private static void referenceFlags(JsonNode node) {
        require(node.path("productionReady").isBoolean()&&!node.path("productionReady").booleanValue());
        require(node.path("realtimeApplied").isBoolean()&&!node.path("realtimeApplied").booleanValue());
    }
    private byte[] checkedRead(Path path,long limit,String expectedSha) throws Exception {
        require(expectedSha!=null&&expectedSha.matches("[a-f0-9]{64}"));
        require(Files.isRegularFile(path)&&Files.size(path)<=limit);
        byte[] bytes=Files.readAllBytes(path);require(bytes.length<=limit);
        require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expectedSha));return bytes;
    }
    private Package load(String kind,String configuredPath,String sha) {
        if((configuredPath==null||configuredPath.isBlank())&&(sha==null||sha.isBlank())) return new Package(null,null,"REFERENCE_NOT_CONFIGURED");
        try {
            Path path=Path.of(configuredPath).toRealPath();
            JsonNode manifest=json.readTree(checkedRead(path,1024*1024,sha));
            require(manifest.path("schemaVersion").asInt()==1);
            require("TRANSIT_REFERENCE_LOOKUP".equals(manifest.path("artifactType").asText()));
            require(kind.equals(manifest.path("kind").asText()));referenceFlags(manifest);
            require(manifest.path("sourceVersion").isTextual()&&!manifest.path("sourceVersion").asText().isBlank());
            require(manifest.path("sourceVersion").asText().length()<=200);
            require(manifest.path("counts").isObject()&&manifest.path("counts").size()<=100);
            manifest.path("counts").fields().forEachRemaining(e->{require(e.getKey().matches("[A-Za-z0-9_]+"));require(e.getValue().isIntegralNumber()&&e.getValue().asLong()>=0);});
            require(manifest.path("limitations").isArray()&&manifest.path("limitations").size()<=100);
            for(var limitation:manifest.path("limitations")) require(limitation.isTextual()&&limitation.asText().length()<=256);
            require("SHA256_PREFIX_2".equals(manifest.path("entriesIndex").path("algorithm").asText()));
            require(manifest.path("entriesIndex").path("shards").isObject());
            return new Package(path.getParent(),manifest,"STAGED_REFERENCE_AVAILABLE");
        } catch(Exception invalid) {return new Package(null,null,"REFERENCE_PACKAGE_INVALID");}
    }
    private Map<String,Object> base(String status) {
        var result=new LinkedHashMap<String,Object>();result.put("status",status);result.put("referenceOnly",true);
        result.put("productionReady",false);result.put("realtimeApplied",false);
        result.put("limitations",List.of("REFERENCE_ID_IS_NOT_ROUTE_MAPPING","NOT_APPROVED_FOR_RECOMMENDATION"));return result;
    }
    private Package selected(String kind) {
        Package result=packages.get(kind==null?"":kind.toLowerCase(Locale.ROOT));
        if(result==null) throw new BusinessException(ErrorCode.INVALID_REQUEST);return result;
    }
    private boolean validId(String kind,String id) {
        String pattern="subway".equalsIgnoreCase(kind)?"SUBWAY_(PATTERN|SEGMENT|HEADWAY)_[a-f0-9]{64}":"ROAD_OCCURRENCE_[a-f0-9]{64}";
        return id!=null&&id.matches(pattern);
    }
    public Map<String,Object> status(String kind) {
        Package data=selected(kind);var result=base(data.status());
        if(data.manifest()!=null) {
            result.put("sourceVersion",data.manifest().path("sourceVersion").asText());
            result.put("counts",data.manifest().path("counts"));
            result.put("limitations",data.manifest().path("limitations"));
            JsonNode examples=data.manifest().has("sampleReferenceIds")?data.manifest().path("sampleReferenceIds"):data.manifest().path("exampleReferenceIds");
            var ids=new ArrayList<String>();
            if(examples.isArray()) for(var example:examples) {
                String id=example.asText();
                if(ids.size()<3&&validId(kind,id)&&data.manifest().path("entriesIndex").path("shards").has(id.substring(id.length()-64,id.length()-62))) ids.add(id);
            }
            result.put("sampleReferenceIds",ids);
        }
        return result;
    }
    public Map<String,Object> lookup(String kind,String referenceId) {
        Package data=selected(kind);
        if(!validId(kind,referenceId)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if(data.manifest()==null) return base(data.status());
        try {
            String prefix=referenceId.substring(referenceId.length()-64,referenceId.length()-62);
            JsonNode shard=data.manifest().path("entriesIndex").path("shards").path(prefix);
            if(shard.isMissingNode()) return base("REFERENCE_MISSING");
            String relative=shard.path("path").asText();
            require(relative.matches("details/[a-f0-9]{2}\\.json"));
            Path path=data.root().resolve(relative).toRealPath();require(path.startsWith(data.root()));
            byte[] bytes=checkedRead(path,8L*1024*1024,shard.path("sha256").asText());
            require(shard.path("sizeBytes").isIntegralNumber()&&bytes.length==shard.path("sizeBytes").asLong());
            JsonNode document=json.readTree(bytes);require(document.path("schemaVersion").asInt()==1);
            require(kind.toUpperCase(Locale.ROOT).equals(document.path("kind").asText()));
            require(document.path("entries").isObject());
            JsonNode detail=document.path("entries").path(referenceId);
            if(detail.isMissingNode()) return base("REFERENCE_MISSING");
            referenceFlags(detail);require(detail.path("referenceOnly").isBoolean()&&detail.path("referenceOnly").booleanValue());
            var result=base("STAGED_REFERENCE_NOT_APPROVED");result.put("referenceId",referenceId);
            result.put("sourceVersion",data.manifest().path("sourceVersion").asText());result.put("detail",detail);return result;
        } catch(Exception invalid) {return base("REFERENCE_PACKAGE_INVALID");}
    }
}
