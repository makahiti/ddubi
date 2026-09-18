package com.ssafy.ddubi.domain.route.service;

import com.ssafy.ddubi.domain.pipeline.service.BusReferenceService;
import com.ssafy.ddubi.domain.route.mapping.MetropolitanBusCatalog;
import com.ssafy.ddubi.domain.route.mapping.MetropolitanBusMasterService;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 인증 사용자용 정확키 참고 조회. 추천 계산·운영 활성화와 분리한다. */
@Service
public class PublicBusReferenceService {
    private static final List<String> LIMITATIONS=List.of("WEATHER_NOT_JOINED","SOURCE_AVAILABILITY_UNKNOWN",
            "MASTER_EFFECTIVE_DATE_UNVERIFIED","CONFIGURATION_DOES_NOT_PROVE_DATASET_UPLOAD_COMPLETE");
    private final BusReferenceService references;
    private final MetropolitanBusMasterService masters;
    private final MetropolitanBusCatalog catalog;
    private final String dataVersion;

    public PublicBusReferenceService(BusReferenceService references, MetropolitanBusMasterService masters,
            MetropolitanBusCatalog catalog, @Value("${ddubi.bus-reference.data-version:}") String dataVersion) {
        this.references=references;this.masters=masters;this.catalog=catalog;
        this.dataVersion=dataVersion==null?"":dataVersion.strip();
        if(!this.dataVersion.isEmpty()&&!validVersion(this.dataVersion)) throw new IllegalArgumentException("Invalid reference version configuration");
    }
    private boolean validVersion(String version) {return version!=null&&version.length()<=80&&version.matches("[A-Za-z0-9._-]+");}
    public Map<String,Object> status() {
        var out=base(dataVersion.isEmpty()?"REFERENCE_NOT_CONFIGURED":"STAGED_REFERENCE_CONFIGURED");
        if(!dataVersion.isEmpty()) out.put("dataVersion",dataVersion);
        return out;
    }
    private LinkedHashMap<String,Object> base(String status) {
        var out=new LinkedHashMap<String,Object>();out.put("status",status);out.put("productionReady",false);
        out.put("realtimeApplied",false);out.put("referenceOnly",true);out.put("limitations",LIMITATIONS);return out;
    }
    private Map<String,Object> unavailable(String status) {
        var out=base(status);out.put("rows",List.of());out.put("truncated",false);return out;
    }
    private String masterFailure(String expected) {
        String live=masters.activeVersion().orElse(null);
        var snapshot=catalog.snapshot();
        if(live==null||snapshot==null) return "MASTER_DATA_NOT_CONFIGURED";
        return expected.equals(live)&&expected.equals(snapshot.version())?null:"MASTER_VERSION_MISMATCH";
    }
    public Map<String,Object> lookup(String requestedVersion,BusReferenceService.Query query) {
        if(!validVersion(requestedVersion)) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        BusReferenceService.validate(query);
        if(dataVersion.isEmpty()) return unavailable("REFERENCE_NOT_CONFIGURED");
        if(!dataVersion.equals(requestedVersion)) return unavailable("REFERENCE_VERSION_MISMATCH");
        try {
            String state=masterFailure(query.masterVersion());
            if(state!=null) return unavailable(state);
        } catch(RuntimeException failure) {return unavailable("MASTER_LOOKUP_FAILED");}
        final Map<String,Object> found;
        try {found=references.lookup(dataVersion,query);}
        catch(RuntimeException failure) {return unavailable("REFERENCE_LOOKUP_FAILED");}
        // DB 조회 중 활성 포인터가 바뀌면 이전 마스터 참고행을 공개하지 않는다.
        try {
            String state=masterFailure(query.masterVersion());
            if(state!=null) return unavailable(state);
        } catch(RuntimeException failure) {return unavailable("MASTER_LOOKUP_FAILED");}
        var out=base(Objects.toString(found.get("status"),"REFERENCE_LOOKUP_FAILED"));
        out.put("dataVersion",dataVersion);out.put("masterVersion",query.masterVersion());
        out.put("rows",found.getOrDefault("rows",List.of()));out.put("truncated",Boolean.TRUE.equals(found.get("truncated")));
        return out;
    }
}
