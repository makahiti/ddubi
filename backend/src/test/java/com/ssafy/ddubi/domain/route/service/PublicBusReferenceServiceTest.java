package com.ssafy.ddubi.domain.route.service;

import com.ssafy.ddubi.domain.pipeline.service.BusReferenceService;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.global.exception.BusinessException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicBusReferenceServiceTest {
    final BusReferenceService store=mock(BusReferenceService.class);
    final MetropolitanBusMasterService masters=mock(MetropolitanBusMasterService.class);
    final MetropolitanBusCatalog catalog=mock(MetropolitanBusCatalog.class);
    final BusReferenceService.Query query=new BusReferenceService.Query("m1","SEOUL_BUS","r","a","b",1,2,2,8);
    PublicBusReferenceService service(String version) {return new PublicBusReferenceService(store,masters,catalog,version);}
    void current() {when(masters.activeVersion()).thenReturn(Optional.of("m1"));when(catalog.snapshot()).thenReturn(new MetropolitanBusCatalog.Snapshot("m1",null));}
    @Test void configurationDoesNotClaimRowsOrReadiness() {
        var result=service("v1").status();assertEquals("STAGED_REFERENCE_CONFIGURED",result.get("status"));
        assertEquals(false,result.get("productionReady"));verifyNoInteractions(store,masters,catalog);
    }
    @Test void missingAndDifferentReferenceVersionNeverReadStore() {
        assertEquals("REFERENCE_NOT_CONFIGURED",service("").lookup("v1",query).get("status"));
        assertEquals("REFERENCE_VERSION_MISMATCH",service("v1").lookup("v2",query).get("status"));verifyNoInteractions(store,masters,catalog);
    }
    @Test void exactLookupStaysReferenceOnly() {
        current();when(store.lookup("v1",query)).thenReturn(Map.of("status","STAGED_REFERENCE_NOT_APPROVED","rows",List.of("row"),"truncated",false,"productionReady",true));
        var result=service("v1").lookup("v1",query);assertEquals(List.of("row"),result.get("rows"));assertEquals(false,result.get("productionReady"));assertEquals(false,result.get("realtimeApplied"));
    }
    @Test void liveVersionMismatchRejectsStaleSnapshot() {
        current();when(masters.activeVersion()).thenReturn(Optional.of("m2"));
        assertEquals("MASTER_VERSION_MISMATCH",service("v1").lookup("v1",query).get("status"));verifyNoInteractions(store);
    }
    @Test void switchDuringLookupDiscardsRows() {
        current();when(masters.activeVersion()).thenReturn(Optional.of("m1"),Optional.of("m2"));
        when(store.lookup("v1",query)).thenReturn(Map.of("status","STAGED_REFERENCE_NOT_APPROVED","rows",List.of("row")));
        var result=service("v1").lookup("v1",query);assertEquals("MASTER_VERSION_MISMATCH",result.get("status"));assertEquals(List.of(),result.get("rows"));
    }
    @Test void missingMasterAndDatabaseFailureAreSafe() {
        when(masters.activeVersion()).thenReturn(Optional.empty());assertEquals("MASTER_DATA_NOT_CONFIGURED",service("v1").lookup("v1",query).get("status"));
        when(masters.activeVersion()).thenThrow(new IllegalStateException("secret"));
        assertEquals("MASTER_LOOKUP_FAILED",service("v1").lookup("v1",query).get("status"));verifyNoInteractions(store);
    }
    @Test void storeFailureReturnsNoRowsOrSecrets() {
        current();when(store.lookup("v1",query)).thenThrow(new IllegalStateException("private database"));
        var result=service("v1").lookup("v1",query);assertEquals("REFERENCE_LOOKUP_FAILED",result.get("status"));assertFalse(result.toString().contains("private"));
    }
    @Test void invalidKeysRejectedBeforeMasterRead() {
        assertThrows(BusinessException.class,()->service("v1").lookup("../v1",query));
        assertThrows(BusinessException.class,()->service("v1").lookup("v1",new BusReferenceService.Query("m1","SEOUL_BUS","r","a","b",1,3,2,8)));verifyNoInteractions(store,masters,catalog);
    }
}
