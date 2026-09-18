package com.ssafy.ddubi.domain.recommendation.evaluation;

import java.time.*;
import java.util.*;

/**
 * FE 공통 schedule-evaluation-v2 DTO. 조회 전용 variant에서는 scheduleId/inputVersion/DB routeId가 null이다.
 * source가 미확보된 버전은 추측하지 않고 missingInputs에 보존한다. preview는 저장 가능하지만 수락 불가다.
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record ScheduleEvaluationV2(String schemaVersion, String variant, String evaluationId, Long scheduleId,
        Long inputVersion, long eventRevision, String state, String decision, String triggerReason,
        Instant userInputDepartAt, Instant plannedDepartAt, Instant effectiveDepartAt, Instant arriveBy,
        Instant originalEstimatedArrivalAt, Instant targetArriveAt, int appliedBufferSeconds,
        Instant proposedDepartAt, Instant estimatedArrivalAt, Long durationSeconds, Long latenessSeconds,
        Boolean arrivesBeforeSchedule, Boolean meetsAdjustedTarget, boolean departureMissed,
        Long selectedRouteId, Long proposedRouteId, Quality quality, ConditionalDepartureEvaluator.Search search,
        String bestKnownDecision, List<Cause> causes, Instant computedAt, Instant asOf, Instant validUntil,
        Instant nextEvaluationAt, Map<String,String> versions,
        List<Segment> segments) {
    /** 최종 타임라인에 적용된 시간만 표시한다. 전체시간 fallback에서는 구간 시간을 분배하지 않는다. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Segment(String candidateKey,int sequence,String mode,String fromName,String toName,String legRole,
            Long durationSeconds,String durationSource,com.ssafy.ddubi.domain.route.model.EndpointEvidence endpointEvidence) { }
    /** 사건 자체와 경로 영향이 확인된 경우만 confirmed=true다. 단순 위험 신호를 지연 원인으로 단정하지 않는다. */
    public record Cause(String eventId,String source,List<String> segmentIds,String effect,boolean confirmed) { }
    public record Quality(boolean preview, boolean productionEligible, boolean realtimeApplied, boolean walkingApiApplied,
            String walkingApiCoverage, String costCoverage, List<String> missingInputs, List<String> fallbackReasons) { }

    /** 응답/DB/outbox가 동일한 증거 만료를 공유한다. 원천 TTL을 결과 기본 60초로 연장하지 않는다. */
    public static Instant validity(ConditionalDepartureEvaluator.Evaluation e,RouteEvidenceAssembler.Snapshot snapshot,
                                   Instant asOf,Instant nextAt) {
        Instant until=asOf.plusSeconds(60);
        if(snapshot!=null) for(var state:snapshot.states().values()) {
            try { Instant expires=Instant.parse(state.path("validUntil").asText()); if(expires.isBefore(until)) until=expires; }
            catch(RuntimeException invalid) { until=asOf; }
        }
        if(e!=null && e.walking()!=null && e.walking().validUntil()!=null && e.walking().validUntil().isBefore(until)) until=e.walking().validUntil();
        // P에서의 판단과 제안시각에서의 ETA가 사용한 증거를 함께 제한한다.
        // 조회만 했거나 fallback으로 폐기한 예측은 만료 근거가 아니다. 구형 저장 JSON에
        // 적용 표시만 있고 만료가 없으면 이번 시각에 만료시켜 60초로 임의 연장하지 않는다.
        if(e!=null) for(var comparison:List.of(e.originalComparison(),e.proposedComparison())) {
            for(var wait:comparison.waitPredictions()) if(wait.applied())
                until=earlier(until,wait.validUntil()==null?asOf:wait.validUntil());
            for(var subway:comparison.subwayPredictions()) if(subway.applied())
                until=earlier(until,subway.estimate().validUntil()==null?asOf:subway.estimate().validUntil());
        }
        if(nextAt!=null && nextAt.isBefore(until)) until=nextAt;
        return until;
    }

    private static Instant earlier(Instant left,Instant right) { return left.isBefore(right)?left:right; }

    /** 읽기 전용 평가에는 사용자 선택 경로가 없으므로 기존 호출 계약을 유지한다. */
    public static ScheduleEvaluationV2 from(ConditionalDepartureEvaluator.Evaluation e, String id, Long schedule,
            Long revision, long eventRevision, String trigger, Instant userInput, Instant planned, Instant deadline,
            Instant now, Instant validUntil, Instant nextAt, Long selectedRouteId, Long proposedRouteId,
            List<String> missing,RouteEvidenceAssembler.Snapshot snapshot, List<com.ssafy.ddubi.domain.route.model.RouteCandidate> routes) {
        return from(e,id,schedule,revision,eventRevision,trigger,userInput,planned,deadline,now,validUntil,nextAt,
                selectedRouteId,proposedRouteId,missing,snapshot,routes,null);
    }

    /** 평가 근거에서 품질·원인을 만든다. selected는 DB ID가 아닌 실제 선택 후보의 평가 결과이며,
     * 통제로 최선 후보에서 제외돼도 원인을 보존한다. 관련 없는 다른 후보의 사건은 포함하지 않는다. */
    public static ScheduleEvaluationV2 from(ConditionalDepartureEvaluator.Evaluation e, String id, Long schedule,
            Long revision, long eventRevision, String trigger, Instant userInput, Instant planned, Instant deadline,
            Instant now, Instant validUntil, Instant nextAt, Long selectedRouteId, Long proposedRouteId,
            List<String> missing,RouteEvidenceAssembler.Snapshot snapshot, List<com.ssafy.ddubi.domain.route.model.RouteCandidate> routes,
            RouteTimelineEvaluator.Result selected) {
        var p=e.proposed();
        Instant eta=p==null?null:p.estimatedArrivalAt();
        var comparison=e.proposedComparison();
        var absent=new ArrayList<String>(missing);
        absent.add("CROSSWALK_VERSION_NOT_REPORTED"); absent.add("FEATURE_SNAPSHOT_VERSION_NOT_REPORTED");
        var reasons=new ArrayList<String>(missing);
        if(p!=null) reasons.addAll(p.reasons());
        if(p!=null && p.steps().stream().anyMatch(s->s.key().equals("provider-total"))) reasons.add("PROVIDER_TOTAL_FALLBACK");
        if(e.walking()!=null) reasons.addAll(p==null || e.walking().candidateFallbackReasons().isEmpty()?e.walking().fallbackReasons():
                e.walking().candidateFallbackReasons().getOrDefault(p.journeyKey(),List.of()));
        var legs=segments(e,p,routes);
        boolean access=eta!=null && legs.stream().anyMatch(l->l.legRole().equals("ACCESS_WALK")&&appliedWalk(l,p));
        boolean egress=eta!=null && legs.stream().anyMatch(l->l.legRole().equals("EGRESS_WALK")&&appliedWalk(l,p));
        String costCoverage=p==null || p.steps().stream().anyMatch(x->x.key().equals("provider-total"))?"UNKNOWN":
                routes!=null && routes.stream().filter(r->RouteEvidenceAssembler.key(r).equals(p.journeyKey()))
                        .anyMatch(r->r.unassignedDurationSeconds()==0 && r.totalDurationSeconds()==r.segments().stream().mapToLong(com.ssafy.ddubi.domain.route.model.RouteSegment::durationSeconds).sum())?"COMPLETE":"PARTIAL";
        boolean realtime=p!=null && eta!=null && (comparison.waitPredictions().stream().anyMatch(w -> w.candidateKey().equals(p.journeyKey()) && w.applied() && w.eventId()!=null)
                || comparison.subwayPredictions().stream().anyMatch(s -> s.candidateKey().equals(p.journeyKey()) && s.applied() && s.estimate().arrivalEventId()!=null));
        var causes=new ArrayList<Cause>();
        var checked=java.util.stream.Stream.concat(e.originalComparison().candidates().stream(),e.proposedComparison().candidates().stream()).map(MlRoutePreview.Candidate::result)
                .filter(r->p==null || r.journeyKey().equals(p.journeyKey())
                        || e.original()!=null && r.journeyKey().equals(e.original().journeyKey())
                        || selected!=null && r.journeyKey().equals(selected.journeyKey())).toList();
        var impacted=new HashSet<String>();
        checked.forEach(r->r.reasons().stream().filter(x->x.startsWith("CLOSURE:")).forEach(x->impacted.add(x.substring(8))));
        if(snapshot!=null) snapshot.states().forEach((segment,node)->{
            String event=node.path("eventId").asText();
            if(!event.isBlank()&&impacted.contains(event)&&!node.path("source").asText().isBlank())
                causes.add(new Cause(event,node.path("source").asText(),List.of(segment),"BLOCKED",true));
            // segment.status 정식 계약은 원본 사건을 causes[]로 제공한다. 내부 closure 그룹 ID를
            // 공식 사건 ID처럼 노출하지 않고, 판정에 실제 연결된 원본 출처만 전달한다.
            if(impacted.contains(event.isBlank()?segment+":"+Instant.parse(node.path("computedAt").asText()):event))
                for(var cause:node.path("causes")) {
                    String originalId=cause.path("eventId").asText(),source=cause.path("source").asText();
                    if(!originalId.isBlank() && !source.isBlank()) causes.add(new Cause(originalId,source,List.of(segment),"BLOCKED",true));
                }
        });
        if(snapshot!=null) for(var info:snapshot.roads().values()) for(var path:info.path("paths")) for(var event:path.path("events")) {
            String idValue=event.path("eventId").asText();
            if(!idValue.isBlank()&&checked.stream().anyMatch(r->r.reasons().contains("ROAD_POTENTIAL_IMPACT:"+idValue))
                    && causes.stream().noneMatch(c->c.eventId().equals(idValue)))
                causes.add(new Cause(idValue,event.path("source").asText("UNKNOWN"),List.of(info.path("segmentId").asText()),"POTENTIAL_IMPACT",false));
        }
        var groupedCauses=new LinkedHashMap<String,Cause>();
        for(var cause:causes) {
            String key=cause.eventId()+":"+cause.source()+":"+cause.effect();
            Cause previous=groupedCauses.get(key);
            var segments=new TreeSet<String>(cause.segmentIds()); if(previous!=null) segments.addAll(previous.segmentIds());
            groupedCauses.put(key,new Cause(cause.eventId(),cause.source(),List.copyOf(segments),cause.effect(),cause.confirmed()));
        }
        var versions=new LinkedHashMap<String,String>(); versions.put("policy",ConditionalDeparturePolicy.VERSION);
        if(e.walking()!=null) {
            versions.put("walkingEndpointPolicy",com.ssafy.ddubi.domain.route.model.EndpointEvidence.POLICY_VERSION);
            if(access||egress) versions.put("walkingApi","kakaomap-walk-v2");
        }
        if(routes!=null) routes.stream().flatMap(r->r.segments().stream()).map(x->x.mapping()).filter(Objects::nonNull)
                .map(x->x.masterDataVersion()).filter(Objects::nonNull).distinct().reduce((a,b)->a.equals(b)?a:"MULTIPLE")
                .ifPresent(v->versions.put("master",v));
        if(!versions.containsKey("master")) absent.add("MASTER_VERSION_UNAVAILABLE");
        comparison.predictions().stream().map(x->x.estimate().modelVersion()).filter(Objects::nonNull).distinct()
                .reduce((a,b)->a.equals(b)?a:"MIXED_REJECTED").ifPresent(v->versions.put("model",v));
        comparison.subwayPredictions().stream().map(x->x.estimate().timetableVersion()).filter(Objects::nonNull).distinct()
                .reduce((a,b)->a.equals(b)?a:"MIXED_REJECTED").ifPresent(v->versions.put("timetable",v));
        return new ScheduleEvaluationV2("schedule-evaluation-v2",schedule==null?"READ_ONLY":"SCHEDULE",id,schedule,
                revision,eventRevision,"READY",e.decision().name(),trigger,userInput,planned,planned.isBefore(now)?now:planned,
                deadline,e.original()==null?null:e.original().estimatedArrivalAt(),e.targetArriveAt()==null?deadline:e.targetArriveAt(),e.appliedBufferSeconds(),
                p==null?null:p.departureAt(),eta,eta==null?null:Duration.between(p.departureAt(),eta).getSeconds(),
                eta==null?null:Math.max(0,Duration.between(deadline,eta).getSeconds()),eta==null?null:eta.isBefore(deadline),
                e.targetArriveAt()==null||eta==null?null:!eta.isAfter(e.targetArriveAt()),e.departureMissed(),selectedRouteId,proposedRouteId,
                new Quality(true,false,realtime,access||egress,access&&egress?"BOTH":access?"ACCESS_ONLY":egress?"EGRESS_ONLY":"NONE",costCoverage,List.copyOf(absent),reasons.stream().distinct().toList()),
                e.search(),e.bestKnownDecision()==null?null:e.bestKnownDecision().name(),List.copyOf(groupedCauses.values()),now,now,validUntil,nextAt,Map.copyOf(versions),legs);
    }
    /** 호출 성공만으로 applied를 만들지 않는다. 최종 타임라인에 해당 WALK와 교체 시간이 실제 남아 있어야 한다. */
    private static boolean appliedWalk(Segment leg,
                                       RouteTimelineEvaluator.Result result) {
        return leg.durationSeconds()!=null && leg.durationSource().equals("KAKAO_WALKING_API") && result.steps().stream()
                .anyMatch(step->step.key().equals("step-"+leg.sequence()) && step.exitedAt()!=null
                        && Duration.between(step.enteredAt(),step.exitedAt()).getSeconds()==leg.durationSeconds());
    }

    private static List<Segment> segments(ConditionalDepartureEvaluator.Evaluation evaluation,RouteTimelineEvaluator.Result result,
            List<com.ssafy.ddubi.domain.route.model.RouteCandidate> routes) {
        if(evaluation.walking()==null || result==null) return List.of();
        boolean total=result.steps().stream().anyMatch(s->s.key().equals("provider-total"));
        var output=new ArrayList<Segment>();
        for(var leg:evaluation.walking().legs()) {
            if(!leg.candidateKey().equals(result.journeyKey())) continue;
            var step=result.steps().stream().filter(s->s.key().equals("step-"+leg.sequence())).findFirst().orElse(null);
            Long seconds=total||step==null||step.exitedAt()==null?null:Duration.between(step.enteredAt(),step.exitedAt()).getSeconds();
            String source=total?"PROVIDER_TOTAL_FALLBACK":leg.durationSource();
            if(!total && step!=null && "ML_PREVIEW".equals(step.source())) source="ML_PREVIEW";
            else if(!total && step!=null && "BUS_HISTORICAL_REFERENCE".equals(step.source())) source="BUS_HISTORICAL_REFERENCE";
            else if(!total && evaluation.proposedComparison().subwayPredictions().stream().anyMatch(s->s.applied()
                    && s.candidateKey().equals(leg.candidateKey()) && s.stepKey().equals("step-"+leg.sequence()))) source="TIMETABLE";
            var original=routes==null?null:routes.stream().filter(r->RouteEvidenceAssembler.key(r).equals(leg.candidateKey()))
                    .flatMap(r->r.segments().stream()).filter(s->s.sequence()==leg.sequence()).findFirst().orElse(null);
            output.add(new Segment(leg.candidateKey(),leg.sequence(),original==null?null:original.mode().name(),
                    original==null?null:original.fromName(),original==null?null:original.toName(),leg.legRole(),seconds,source,leg.endpointEvidence()));
        }
        return List.copyOf(output);
    }
}
