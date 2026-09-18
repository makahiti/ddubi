package com.ssafy.ddubi.domain.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.pipeline.service.BusReferenceService;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.security.JwtTokenProvider;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@TestPropertySource(properties={"ddubi.bus-reference.data-version=public-v1","ddubi.internal.api-key=test-reference-internal"})
class PublicTransitReferenceIntegrationTest extends IntegrationTestSupport {
    @org.springframework.test.context.DynamicPropertySource
    static void packageProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        String root=System.getProperty("referencePackageRoot");
        if(root==null) return;
        for(String kind:List.of("subway","road")) {
            var path=java.nio.file.Path.of(root,kind,"manifest.json");
            registry.add("ddubi.reference-packages."+kind+".path",()->path.toString());
            registry.add("ddubi.reference-packages."+kind+".sha256",()->{
                try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(path)));}
                catch(Exception e) {throw new IllegalStateException(e);}
            });
        }
    }
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired BusReferenceService store;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired TransitReferencePackageService packages;
    @MockitoBean MetropolitanBusMasterService masters;
    @MockitoBean MetropolitanBusCatalog catalog;
    final BusReferenceService.Query query=new BusReferenceService.Query("m1","SEOUL_BUS","r","a","b",1,2,2,8);
    String bearer() {return "Bearer "+tokens.createAccessToken(users.save(User.builder().email("reference@example.test").nickname("참고 조회").build()).getId());}
    void stage() {
        store.upload("public-v1",0,new BusReferenceService.Upload(List.of(new BusReferenceService.Row("m1","SEOUL_BUS","r","a","b",1,2,2,8,
                "HOURLY_SOURCE_MEAN",60,3,3,LocalDate.of(2026,8,1),LocalDate.of(2026,8,3),"a".repeat(64)))));
        when(masters.activeVersion()).thenReturn(Optional.of("m1"));when(catalog.snapshot()).thenReturn(new MetropolitanBusCatalog.Snapshot("m1",null));
    }
    @Test void publicEndpointsRequireBearerNotInternalKey() throws Exception {
        http.perform(get("/api/v1/routes/data-status")).andExpect(status().isUnauthorized());
        http.perform(post("/api/v1/routes/bus-references/public-v1/lookup").header("X-DDUBI-API-KEY","test-reference-internal").contentType("application/json").content(json.writeValueAsBytes(query))).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/routes/reference-packages/road/ROAD_OCCURRENCE_"+"a".repeat(64))).andExpect(status().isUnauthorized());
    }
    @Test void authenticatedExactLookupAndLiveVersionChange() throws Exception {
        stage();String bearer=bearer();
        http.perform(post("/api/v1/routes/bus-references/public-v1/lookup").header("Authorization",bearer).contentType("application/json").content(json.writeValueAsBytes(query)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].meanSeconds").value(60)).andExpect(jsonPath("$.data.productionReady").value(false)).andExpect(jsonPath("$.data.realtimeApplied").value(false));
        when(masters.activeVersion()).thenReturn(Optional.of("m2"));
        http.perform(post("/api/v1/routes/bus-references/public-v1/lookup").header("Authorization",bearer).contentType("application/json").content(json.writeValueAsBytes(query)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("MASTER_VERSION_MISMATCH")).andExpect(jsonPath("$.data.rows").isEmpty());
    }
    @Test void internalReadbackIsVersionScopedAndProtected() throws Exception {
        stage();
        http.perform(get("/internal/bus-references/public-v1/status")).andExpect(status().isUnauthorized());
        http.perform(get("/internal/bus-references/public-v1/status").header("X-DDUBI-API-KEY","test-reference-internal"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalRows").value(1)).andExpect(jsonPath("$.data.totalChunks").value(1));
        http.perform(get("/internal/bus-references/other/status").header("X-DDUBI-API-KEY","test-reference-internal"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalRows").value(0));
    }
    @Test void authenticatedStatusDoesNotClaimConfigurationIsUploadCompletion() throws Exception {
        http.perform(get("/api/v1/routes/data-status").header("Authorization",bearer()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.busReference.status").value("STAGED_REFERENCE_CONFIGURED"))
            .andExpect(jsonPath("$.data.busReference.productionReady").value(false)).andExpect(jsonPath("$.data.subwayReference.status").value(System.getProperty("referencePackageRoot")==null?"REFERENCE_NOT_CONFIGURED":"STAGED_REFERENCE_AVAILABLE"));
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="referencePackageRoot",matches=".+")
    void actualPackageDetailsUseAuthenticatedReadOnlyEndpoint() throws Exception {
        String bearer=bearer();
        for(String kind:List.of("subway","road")) {
            var ids=(List<?>)packages.status(kind).get("sampleReferenceIds");
            org.junit.jupiter.api.Assertions.assertFalse(ids.isEmpty());
            http.perform(get("/api/v1/routes/reference-packages/"+kind+"/"+ids.get(0)).header("Authorization",bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("STAGED_REFERENCE_NOT_APPROVED"))
                .andExpect(jsonPath("$.data.referenceOnly").value(true)).andExpect(jsonPath("$.data.productionReady").value(false))
                .andExpect(jsonPath("$.data.realtimeApplied").value(false)).andExpect(jsonPath("$.data.detail").exists());
        }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="busReferenceFixture",matches=".+")
    void actualExportChunkRoundTripsThroughAuthenticatedPublicLookup() throws Exception {
        BusReferenceService.Upload upload;
        try(var stream=new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(java.nio.file.Path.of(System.getProperty("busReferenceFixture"))))) {
            upload=json.readValue(stream,BusReferenceService.Upload.class);
        }
        org.junit.jupiter.api.Assertions.assertEquals(1000,upload.rows().size());
        store.upload("public-v1",0,upload);
        var row=upload.rows().get(0);
        when(masters.activeVersion()).thenReturn(Optional.of(row.masterVersion()));
        when(catalog.snapshot()).thenReturn(new MetropolitanBusCatalog.Snapshot(row.masterVersion(),null));
        var key=new BusReferenceService.Query(row.masterVersion(),row.namespace(),row.routeId(),row.fromStopId(),row.toStopId(),row.fromOrder(),row.toOrder(),row.dow(),row.hourSlot());
        http.perform(post("/api/v1/routes/bus-references/public-v1/lookup").header("Authorization",bearer()).contentType("application/json").content(json.writeValueAsBytes(key)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].meanSeconds").value(row.meanSeconds()))
            .andExpect(jsonPath("$.data.rows[0].sampleKind").value(row.sampleKind())).andExpect(jsonPath("$.data.productionReady").value(false));
        http.perform(get("/internal/bus-references/public-v1/status").header("X-DDUBI-API-KEY","test-reference-internal"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalRows").value(1000)).andExpect(jsonPath("$.data.totalChunks").value(1));
    }
}
