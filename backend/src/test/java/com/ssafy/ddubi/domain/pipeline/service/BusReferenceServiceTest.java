package com.ssafy.ddubi.domain.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.global.exception.BusinessException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusReferenceServiceTest {
    @Test void verifiesRealExportWhenProvided() throws Exception {
        String fixture=System.getProperty("busReferenceFixture");
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture!=null);
        try(var stream=new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(java.nio.file.Path.of(fixture)))) {
            var request=new ObjectMapper().findAndRegisterModules().readValue(stream,BusReferenceService.Upload.class);
            assertEquals(1000,request.rows().size());
            request.rows().forEach(BusReferenceService::validate);
        }
    }
    static BusReferenceService.Row row(double seconds, int toOrder, String kind) {
        return new BusReferenceService.Row("master-v1","SEOUL_BUS","r","a","b",1,toOrder,2,8,
                kind,seconds,3,3,LocalDate.of(2026,8,1),LocalDate.of(2026,8,17),"a".repeat(64));
    }
    @Test void acceptsWeatherIndependentMean() { assertDoesNotThrow(()->BusReferenceService.validate(row(60,2,"HOURLY_SOURCE_MEAN"))); }
    @Test void rejectsNonAdjacentOccurrence() { assertThrows(BusinessException.class,()->BusReferenceService.validate(row(60,3,"HOURLY_SOURCE_MEAN"))); }
    @Test void rejectsInventedPercentileKind() { assertThrows(BusinessException.class,()->BusReferenceService.validate(row(60,2,"P95"))); }
    @Test void rejectsInvalidSeconds() { for(double v:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}) assertThrows(BusinessException.class,()->BusReferenceService.validate(row(v,2,"HOURLY_SOURCE_MEAN"))); }
    @Test void invalidUploadCannotWrite() {
        var db=mock(JdbcTemplate.class);var service=new BusReferenceService(db,new ObjectMapper().findAndRegisterModules());
        assertThrows(BusinessException.class,()->service.upload("v",0,new BusReferenceService.Upload(List.of(row(0,2,"HOURLY_SOURCE_MEAN")))));
        verifyNoInteractions(db);
    }
}
