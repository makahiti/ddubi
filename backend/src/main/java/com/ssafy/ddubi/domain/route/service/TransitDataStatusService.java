package com.ssafy.ddubi.domain.route.service;

import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.recommendation.evaluation.BusHistoricalReferenceService;
import com.ssafy.ddubi.domain.recommendation.evaluation.SubwayTimetable;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Git 파일 보유와 실제 활성 데이터를 구분하는 읽기 전용 진단이다. BUS는 현재 메모리 스냅샷,
 * SUBWAY는 활성 provider 버전, 시간표는 시작 시 검증된 Data를 확인한다. 비밀 URL·경로와
 * 사용자 경로는 반환하지 않는다. DB 조회 실패는 UNKNOWN이며 API fallback 자체를 막지 않는다.
 */
@Service
public class TransitDataStatusService {
    private final MetropolitanBusCatalog bus;
    private final SeoulTransitMasterDataProvider subway;
    private final SubwayTimetable timetable;
    private final boolean mlConfigured;
    private final boolean subwayDecompositionVerified;
    private final boolean busDecompositionVerified;
    private final PublicBusReferenceService busReferences;
    private final TransitReferencePackageService referencePackages;
    private final BusHistoricalReferenceService historical;
    public TransitDataStatusService(MetropolitanBusCatalog bus, SeoulTransitMasterDataProvider subway,
            SubwayTimetable timetable,
            @Value("${ddubi.recommendation.ml-preview-base-url:}") String mlUrl,
            @Value("${ddubi.subway-timing.decomposition-verified:false}") boolean subwayVerified,
            @Value("${ddubi.bus-waiting.decomposition-verified:false}") boolean busVerified,
            PublicBusReferenceService busReferences,TransitReferencePackageService referencePackages,
            BusHistoricalReferenceService historical) {
        this.bus=bus;this.subway=subway;this.timetable=timetable;this.mlConfigured=mlUrl!=null&&!mlUrl.isBlank();
        this.subwayDecompositionVerified=subwayVerified;this.busDecompositionVerified=busVerified;
        this.busReferences=busReferences;this.referencePackages=referencePackages;this.historical=historical;
    }
    /** 각 준비 단계와 지원 범위를 독립 반환한다. ML 주소 설정을 서버 연결 성공/운영 승인으로 표시하지 않는다. */
    public Map<String,Object> status(Instant now) {
        var result=new LinkedHashMap<String,Object>();result.put("checkedAt",now);
        var snapshot=bus.snapshot();
        result.put("bus",snapshot==null?Map.of("status","MASTER_DATA_NOT_CONFIGURED"):
                Map.of("status","LOADED","activeVersion",snapshot.version(),"providers",snapshot.matcher().inventory(),
                        "mappingPolicy",MetropolitanBusMatcher.POLICY,"segmentIdPolicy",CanonicalSegmentIdFactory.BUS_POLICY));
        String subwayVersion=null;
        try {
            subwayVersion=subway.activeVersion().orElse(null);
            if(subwayVersion==null) result.put("subway",Map.of("status","MASTER_DATA_NOT_CONFIGURED"));
            else {
                var lines=subway.loadSubwayLines(subwayVersion);
                long stations=lines.stream().flatMap(l->l.stations().stream()).map(SubwayStationMaster::stationId).distinct().count();
                long realtimeStations=lines.stream().flatMap(l->l.stations().stream()).filter(SubwayStationMaster::hasRealtimeStationId)
                        .map(SubwayStationMaster::stationId).distinct().count();
                result.put("subway",Map.of("status",lines.isEmpty()?"MASTER_DATA_EMPTY":"LOADED","activeVersion",subwayVersion,
                        "lines",lines.stream().map(SubwayLineMaster::subwayId).distinct().count(),"patterns",lines.size(),
                        "staticStations",stations,"realtimeMappedStations",realtimeStations));
            }
        } catch(RuntimeException failure) { result.put("subway",Map.of("status","MASTER_LOOKUP_FAILED"));subwayVersion=null; }
        var data=timetable.data();
        if(data==null) result.put("subwayTimetable",Map.of("status",timetable.unavailableReason()));
        else {
            String state=!Objects.equals(data.masterDataVersion(),subwayVersion)?"MASTER_VERSION_MISMATCH":
                    now.isBefore(data.publishedAt())||now.isBefore(data.validFrom())?"NOT_YET_APPLICABLE":
                    !now.isBefore(data.validUntil())?"EXPIRED":"LOADED";
            result.put("subwayTimetable",Map.of("status",state,"version",data.version(),"masterVersion",data.masterDataVersion(),
                    "validFrom",data.validFrom(),"validUntil",data.validUntil(),"trips",data.trips().size(),
                    "realtimeVerifiedTrips",data.trips().stream().filter(SubwayTimetable.Trip::realtimeVerified).count(),
                    "limitations",data.limitations(),"decompositionVerified",subwayDecompositionVerified));
        }
        result.put("busMl",Map.of("status",mlConfigured?"CONFIGURED_NOT_PROBED":"ML_NOT_CONFIGURED",
                "supportedNamespaces",List.of("SEOUL_BUS"),"productionReady",false,"waitingDecompositionVerified",busDecompositionVerified));
        result.put("busReference",busReferences.status());
        // 설정 활성화, gate 통과, 실제 적용은 서로 다른 상태다. 여기서는 설정과 마지막 gate 결과만 보여 준다.
        result.put("busHistoricalFallback",historical.gateStatus());
        result.put("subwayReference",referencePackages.status("subway"));
        result.put("roadReference",referencePackages.status("road"));
        return result;
    }
}
