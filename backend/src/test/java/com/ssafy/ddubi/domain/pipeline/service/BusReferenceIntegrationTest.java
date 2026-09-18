package com.ssafy.ddubi.domain.pipeline.service;

import com.ssafy.ddubi.support.IntegrationTestSupport;
import com.ssafy.ddubi.global.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties="ddubi.internal.api-key=test-internal-key")
class BusReferenceIntegrationTest extends IntegrationTestSupport {
    @Autowired BusReferenceService service;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Test void failedMultiValuesInsertRollsBackChunkAndEveryRow() {
        jdbc.execute("ALTER TABLE bus_reference_row ADD CONSTRAINT reference_test_failure CHECK (data_version <> 'rollback-v1' OR row_index <> 1)");
        try {
            var row=BusReferenceServiceTest.row(60,2,"HOURLY_SOURCE_MEAN");
            assertThrows(RuntimeException.class,()->service.upload("rollback-v1",0,new BusReferenceService.Upload(List.of(row,row))));
            assertEquals(0L,service.status("rollback-v1").get("totalRows"));
            assertEquals(0L,service.status("rollback-v1").get("totalChunks"));
        } finally {jdbc.execute("ALTER TABLE bus_reference_row DROP CHECK reference_test_failure");}
    }
    @Test void authenticatedUploadAndLookup() throws Exception {
        var request=new BusReferenceService.Upload(List.of(BusReferenceServiceTest.row(60,2,"HOURLY_SOURCE_MEAN")));
        mvc.perform(post("/internal/bus-references/http-v1/chunks/0")
                .header("X-DDUBI-API-KEY","test-internal-key")
                .contentType("application/json").content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.receivedRows").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.productionReady").value(false));
        var query=new BusReferenceService.Query("master-v1","SEOUL_BUS","r","a","b",1,2,2,8);
        mvc.perform(post("/internal/bus-references/http-v1/lookup")
                .header("X-DDUBI-API-KEY","test-internal-key")
                .contentType("application/json").content(json.writeValueAsBytes(query)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.rows[0].meanSeconds").value(60))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.realtimeApplied").value(false));
    }
    @Test void immutableRetryAndExactLookup() {
        var request=new BusReferenceService.Upload(List.of(BusReferenceServiceTest.row(60,2,"HOURLY_SOURCE_MEAN")));
        assertEquals(false,service.upload("test-v1",0,request).get("reused"));
        assertEquals(true,service.upload("test-v1",0,request).get("reused"));
        assertThrows(BusinessException.class,()->service.upload("test-v1",0,new BusReferenceService.Upload(List.of(BusReferenceServiceTest.row(70,2,"HOURLY_SOURCE_MEAN")))));
        var result=service.lookup("test-v1",new BusReferenceService.Query("master-v1","SEOUL_BUS","r","a","b",1,2,2,8));
        assertEquals(false,result.get("productionReady"));
        assertEquals(1,((List<?>)result.get("rows")).size());
        assertEquals("REFERENCE_MISSING",service.lookup("test-v1",new BusReferenceService.Query("wrong-master","SEOUL_BUS","r","a","b",1,2,2,8)).get("status"));
    }
    @Test void internalReferenceRejectsMissingKey() throws Exception {
        mvc.perform(post("/internal/bus-references/test-v1/lookup").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
