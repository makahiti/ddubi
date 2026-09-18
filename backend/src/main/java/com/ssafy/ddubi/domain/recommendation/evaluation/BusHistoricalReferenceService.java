package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.ssafy.ddubi.domain.pipeline.service.BusReferenceService;
import com.ssafy.ddubi.domain.route.service.PublicBusReferenceService;
import com.ssafy.ddubi.domain.route.mapping.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 완전 적재된 불변 버전의 정확키 역사 평균만 RIDE fallback에 사용한다. 운영 baseline/실시간이 아니다. */
@Service
public class BusHistoricalReferenceService {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    public record Policy(boolean enabled,String dataVersion,long expectedRows,long expectedChunks,
            int maxAgeDays,int minSamples,int minDates,int maxFutureDays,String sampleKind) {}
    public record Estimate(Long seconds,String reason,String dataVersion,String sampleKind,List<BusReferenceService.Row> rows) {
        static Estimate missing(String reason) {return new Estimate(null,"HISTORICAL_REFERENCE_"+reason,null,null,List.of());}
    }
    public static final class Session {
        final Map<BusReferenceService.Query,Map<String,Object>> rows=new HashMap<>();
    }
    private final BusReferenceService store;
    private final PublicBusReferenceService publicReferences;
    private final MetropolitanBusMasterService masters;
    private final MetropolitanBusCatalog catalog;
    private final Policy policy;
    private volatile boolean gatePassed;
    private volatile long retryAfterNanos;
    private static final Logger log=LoggerFactory.getLogger(BusHistoricalReferenceService.class);
    // gate 관측값. 비밀값·경로는 담지 않으며 진단 조회가 대용량 COUNT를 다시 실행하지 않도록 마지막 결과만 보존한다.
    private volatile String gateState="NOT_EVALUATED";
    private volatile String gateReason;
    private volatile Instant gateCheckedAt;
    private volatile Long observedRows;
    private volatile Long observedChunks;
    @Autowired
    public BusHistoricalReferenceService(BusReferenceService store,PublicBusReferenceService publicReferences,
            MetropolitanBusMasterService masters,MetropolitanBusCatalog catalog,
            @Value("${ddubi.bus-reference.fallback.enabled:false}") boolean enabled,
            @Value("${ddubi.bus-reference.data-version:}") String version,
            @Value("${ddubi.bus-reference.fallback.expected-rows:0}") long rows,
            @Value("${ddubi.bus-reference.fallback.expected-chunks:0}") long chunks,
            @Value("${ddubi.bus-reference.fallback.max-age-days:90}") int maxAge,
            @Value("${ddubi.bus-reference.fallback.min-samples:3}") int samples,
            @Value("${ddubi.bus-reference.fallback.min-dates:3}") int dates,
            @Value("${ddubi.bus-reference.fallback.max-future-days:7}") int future,
            @Value("${ddubi.bus-reference.fallback.sample-kind:HOURLY_SOURCE_MEAN}") String kind) {
        this(store,publicReferences,masters,catalog,new Policy(enabled,version,rows,chunks,maxAge,samples,dates,future,kind));
    }
    public BusHistoricalReferenceService(BusReferenceService store,PublicBusReferenceService publicReferences,
            MetropolitanBusMasterService masters,MetropolitanBusCatalog catalog,Policy policy) {
        this.store=store;this.publicReferences=publicReferences;this.masters=masters;this.catalog=catalog;this.policy=policy;
    }
    private synchronized boolean gate() {
        if(gatePassed) return true;
        if(retryAfterNanos!=0&&System.nanoTime()<retryAfterNanos) return false;
        retryAfterNanos=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        gateCheckedAt=Instant.now();
        try {
            var status=store.status(policy.dataVersion());
            long rows=((Number)status.get("totalRows")).longValue();
            long chunks=((Number)status.get("totalChunks")).longValue();
            observedRows=rows;observedChunks=chunks;
            gatePassed=policy.dataVersion().equals(status.get("dataVersion"))
                && rows==policy.expectedRows() && chunks==policy.expectedChunks();
            gateState=gatePassed?"PASSED":"FAILED";
            gateReason=gatePassed?null:(policy.dataVersion().equals(status.get("dataVersion"))?"TOTALS_MISMATCH":"VERSION_MISMATCH");
            if(gatePassed) log.info("버스 과거 평균 gate 통과 dataVersion={} rows={} chunks={}",policy.dataVersion(),rows,chunks);
            else log.warn("버스 과거 평균 gate 불일치 dataVersion={} rows={}/{} chunks={}/{}",policy.dataVersion(),rows,policy.expectedRows(),chunks,policy.expectedChunks());
        } catch(RuntimeException unavailable) {
            gateState="FAILED";gateReason="STATUS_UNAVAILABLE:"+unavailable.getClass().getSimpleName();
            log.warn("버스 과거 평균 gate 확인 실패 dataVersion={} type={}",policy.dataVersion(),unavailable.getClass().getSimpleName());
            return false;
        }
        return gatePassed;
    }
    private boolean policyInvalid() {
        return policy.dataVersion()==null||policy.dataVersion().isBlank()||policy.expectedRows()<1||policy.expectedChunks()<1
                ||policy.maxAgeDays()<1||policy.minSamples()<1||policy.minDates()<1||policy.maxFutureDays()<0
                ||!Set.of("HOURLY_SOURCE_MEAN","ACTUAL_TRAVERSAL_MEAN").contains(policy.sampleKind());
    }
    /** 읽기 전용 진단. 설정·마지막 gate 결과·사유만 반환하며 COUNT를 실행하지 않는다. 운영 승인 값이 아니다. */
    public Map<String,Object> gateStatus() {
        var out=new LinkedHashMap<String,Object>();
        out.put("enabled",policy.enabled());
        out.put("dataVersion",policy.dataVersion()==null||policy.dataVersion().isBlank()?null:policy.dataVersion());
        out.put("expectedRows",policy.expectedRows());out.put("expectedChunks",policy.expectedChunks());
        out.put("policy",Map.of("maxAgeDays",policy.maxAgeDays(),"minSamples",policy.minSamples(),"minDates",policy.minDates(),
                "maxFutureDays",policy.maxFutureDays(),"sampleKind",String.valueOf(policy.sampleKind())));
        out.put("policyValid",!policyInvalid());
        out.put("gateState",gateState);out.put("gateReason",gateReason);out.put("gateCheckedAt",gateCheckedAt);
        out.put("observedRows",observedRows);out.put("observedChunks",observedChunks);
        out.put("productionReady",false);out.put("realtimeApplied",false);
        return out;
    }
    /** 큰 COUNT 검증은 앱 준비 시 선행해 첫 사용자 평가의 짧은 탐색 예산에서 분리한다. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmGate() {if(policy.enabled()&&policy.expectedRows()>0&&policy.expectedChunks()>0) gate();}
    private boolean masterMatches(String expected) {
        var snapshot=catalog.snapshot();
        return snapshot!=null&&expected.equals(snapshot.version())&&masters.activeVersion().filter(expected::equals).isPresent();
    }
    public Estimate estimate(List<MappedTransitSegment> edges,String masterVersion,Instant enteredAt,
            long uncertaintySeconds,Instant asOf,Session session,long deadline) {
        if(!policy.enabled()) return Estimate.missing("DISABLED");
        if(policyInvalid()) return Estimate.missing("POLICY_INVALID");
        if(masterVersion==null||edges.isEmpty()||uncertaintySeconds<0) return Estimate.missing("MAPPING_UNVERIFIED");
        if(!gate()) return Estimate.missing("DATASET_INCOMPLETE");
        try {
            if(!masterMatches(masterVersion)) return Estimate.missing("MASTER_VERSION_MISMATCH");
            long total=0;Instant cursor=enteredAt;var used=new ArrayList<BusReferenceService.Row>();
            for(int i=0;i<edges.size();i++) {
                if(System.nanoTime()>=deadline) return Estimate.missing("BUDGET_EXHAUSTED");
                var edge=edges.get(i);
                if(edge.mode()!=com.ssafy.ddubi.domain.route.model.TransportMode.BUS||edge.fromOrder()<1||edge.toOrder()!=edge.fromOrder()+1||edge.sequence()!=i+1
                        ||!edge.segmentId().equals("BUS:"+edge.routeId()+":"+edge.fromNodeId()+":"+edge.toNodeId())
                        ||(i>0&&(!edges.get(i-1).routeId().equals(edge.routeId())||!edges.get(i-1).toNodeId().equals(edge.fromNodeId())
                            ||edges.get(i-1).toOrder()!=edge.fromOrder()))) return Estimate.missing("MAPPING_UNVERIFIED");
                var time=cursor.atZone(KST);var latest=cursor.plusSeconds(uncertaintySeconds).atZone(KST);
                if(!time.toLocalDate().equals(latest.toLocalDate())||time.getHour()!=latest.getHour()) return Estimate.missing("ENTRY_HOUR_UNCERTAIN");
                LocalDate today=asOf.atZone(KST).toLocalDate();
                if(time.toLocalDate().isBefore(today)||time.toLocalDate().isAfter(today.plusDays(policy.maxFutureDays()))) return Estimate.missing("FORECAST_DATE_UNSUPPORTED");
                var query=new BusReferenceService.Query(masterVersion,"SEOUL_BUS",edge.routeId(),edge.fromNodeId(),edge.toNodeId(),edge.fromOrder(),edge.toOrder(),
                        time.getDayOfWeek().getValue()%7+1,time.getHour());
                var result=session.rows.get(query);
                if(result==null) {
                    if(session.rows.size()>=256) return Estimate.missing("LOOKUP_BUDGET_EXHAUSTED");
                    result=publicReferences.lookup(policy.dataVersion(),query);session.rows.put(query,result);
                }
                if(!"STAGED_REFERENCE_NOT_APPROVED".equals(result.get("status"))||Boolean.TRUE.equals(result.get("truncated"))) return Estimate.missing("EXACT_KEY_UNAVAILABLE");
                var candidates=new ArrayList<BusReferenceService.Row>();
                for(Object value:(List<?>)result.getOrDefault("rows",List.of())) {
                    if(!(value instanceof BusReferenceService.Row row)) return Estimate.missing("INVALID_ROW");
                    BusReferenceService.validate(row);
                    if(!row.masterVersion().equals(masterVersion)||!row.namespace().equals("SEOUL_BUS")||!row.routeId().equals(edge.routeId())
                        ||!row.fromStopId().equals(edge.fromNodeId())||!row.toStopId().equals(edge.toNodeId())||row.fromOrder()!=edge.fromOrder()||row.toOrder()!=edge.toOrder()
                        ||row.dow()!=query.dow()||row.hourSlot()!=query.hourSlot()) return Estimate.missing("INVALID_ROW");
                    if(!row.sampleKind().equals(policy.sampleKind())||row.sampleCount()<policy.minSamples()||row.observedDates()<policy.minDates()
                        ||!row.observationEnd().isBefore(today)||row.observationEnd().isBefore(today.minusDays(policy.maxAgeDays()))
                        ||row.meanSeconds()>3600) continue;
                    candidates.add(row);
                }
                if(candidates.isEmpty()) return Estimate.missing("SAMPLES_OR_FRESHNESS_INSUFFICIENT");
                LocalDate mostRecent=candidates.stream().map(BusReferenceService.Row::observationEnd).max(Comparator.naturalOrder()).orElseThrow();
                var selected=candidates.stream().filter(r->r.observationEnd().equals(mostRecent)).distinct().toList();
                if(selected.size()!=1) return Estimate.missing("AMBIGUOUS_PERIOD");
                var row=selected.get(0);long seconds=(long)Math.ceil(row.meanSeconds());
                total+=seconds;if(total>86400) return Estimate.missing("INVALID_DURATION");
                cursor=cursor.plusSeconds(seconds);used.add(row);
            }
            if(!masterMatches(masterVersion)) return Estimate.missing("MASTER_VERSION_MISMATCH");
            return new Estimate(total,"HISTORICAL_REFERENCE_APPLIED",policy.dataVersion(),policy.sampleKind(),List.copyOf(used));
        } catch(RuntimeException failure) {return Estimate.missing("LOOKUP_UNAVAILABLE");}
    }
}
