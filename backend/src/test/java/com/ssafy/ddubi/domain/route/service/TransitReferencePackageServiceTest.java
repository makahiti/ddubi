package com.ssafy.ddubi.domain.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.global.exception.BusinessException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TransitReferencePackageServiceTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    final String id="ROAD_OCCURRENCE_"+"a".repeat(64);
    String sha(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    TransitReferencePackageService fixture(String relative,boolean ready) throws Exception {
        Files.createDirectories(root.resolve("details"));
        byte[] shard=json.writeValueAsBytes(Map.of("schemaVersion",1,"kind","ROAD","entries",Map.of(id,Map.of("productionReady",ready,"realtimeApplied",false,"referenceOnly",true,"limitations",List.of("UNMAPPED")))));
        Files.write(root.resolve("details/aa.json"),shard);
        byte[] manifest=json.writeValueAsBytes(Map.of("schemaVersion",1,"kind","ROAD","artifactType","TRANSIT_REFERENCE_LOOKUP","productionReady",false,"realtimeApplied",false,
                "sourceVersion","v1","counts",Map.of("occurrences",1),"limitations",List.of("NOT_APPROVED"),
                "entriesIndex",Map.of("algorithm","SHA256_PREFIX_2","shards",Map.of("aa",Map.of("path",relative,"sha256",sha(shard),"sizeBytes",shard.length)))));
        Path file=root.resolve("manifest.json");Files.write(file,manifest);
        return new TransitReferencePackageService(json,"","",file.toString(),sha(manifest));
    }
    @Test void exactLookupAndAbsentIdAreSeparate() throws Exception {
        var service=fixture("details/aa.json",false);
        assertEquals("STAGED_REFERENCE_AVAILABLE",service.status("road").get("status"));
        var result=service.lookup("road",id);assertEquals("STAGED_REFERENCE_NOT_APPROVED",result.get("status"));assertEquals(false,result.get("productionReady"));
        assertEquals("REFERENCE_MISSING",service.lookup("road","ROAD_OCCURRENCE_"+"b".repeat(64)).get("status"));
    }
    @Test void invalidIdAndTraversalCannotReadOutsidePackage() throws Exception {
        var service=fixture("../secret.json",false);
        assertEquals("REFERENCE_PACKAGE_INVALID",service.lookup("road",id).get("status"));
        assertThrows(BusinessException.class,()->service.lookup("road","../secret"));
    }
    @Test void changedShardAndProductionFlagAreRejected() throws Exception {
        var service=fixture("details/aa.json",false);Files.writeString(root.resolve("details/aa.json"),"{}");
        assertEquals("REFERENCE_PACKAGE_INVALID",service.lookup("road",id).get("status"));
        assertEquals("REFERENCE_PACKAGE_INVALID",fixture("details/aa.json",true).lookup("road",id).get("status"));
    }
    @Test void configurationAbsenceAndHashFailureDoNotLeakPaths() {
        var absent=new TransitReferencePackageService(json,"","","","");assertEquals("REFERENCE_NOT_CONFIGURED",absent.status("road").get("status"));
        var broken=new TransitReferencePackageService(json,"","",root.resolve("private.json").toString(),"bad");
        assertEquals("REFERENCE_PACKAGE_INVALID",broken.status("road").get("status"));assertFalse(broken.status("road").toString().contains("private"));
    }
}
