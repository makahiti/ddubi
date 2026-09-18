package com.ssafy.ddubi.domain.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** 명시적으로 제공된 실제 전체 산출물의 공개 계약을 검증한다. CI 기본 실행에는 로컬 자료를 요구하지 않는다. */
@EnabledIfSystemProperty(named="referencePackageRoot",matches=".+")
class TransitReferencePackageRealArtifactTest {
    @Test void actualSubwayAndRoadPackagesExposeOnlyReferenceDetails() throws Exception {
        Path root=Path.of(System.getProperty("referencePackageRoot"));
        Path subway=root.resolve("subway/manifest.json"),road=root.resolve("road/manifest.json");
        String subwaySha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(subway)));
        String roadSha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(road)));
        var service=new TransitReferencePackageService(new ObjectMapper(),subway.toString(),subwaySha,road.toString(),roadSha);
        for(String kind:List.of("subway","road")) {
            var status=service.status(kind);assertEquals("STAGED_REFERENCE_AVAILABLE",status.get("status"));
            var ids=(List<?>)status.get("sampleReferenceIds");assertFalse(ids.isEmpty());assertTrue(ids.size()<=3);
            for(Object id:ids) {
                var detail=service.lookup(kind,id.toString());assertEquals("STAGED_REFERENCE_NOT_APPROVED",detail.get("status"));
                assertEquals(false,detail.get("productionReady"));assertEquals(false,detail.get("realtimeApplied"));assertEquals(true,detail.get("referenceOnly"));
            }
        }
    }
}
