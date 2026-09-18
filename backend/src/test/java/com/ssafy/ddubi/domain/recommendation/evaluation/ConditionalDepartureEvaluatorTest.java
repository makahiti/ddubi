package com.ssafy.ddubi.domain.recommendation.evaluation;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.*;
import static com.ssafy.ddubi.domain.recommendation.evaluation.ConditionalDeparturePolicy.Decision.*;

/** T01~T08: 시간 경계·미탐색·예산 종료를 고정시각으로 검증한다. 실제 HTTP/DB가 없어도 정책을 재현한다. */
class ConditionalDepartureEvaluatorTest {
    final Instant now=Instant.parse("2026-09-16T00:00:00Z"),p=now.plusSeconds(1800),d=now.plusSeconds(3600);
    ConditionalDepartureEvaluator engine(int budget) { return new ConditionalDepartureEvaluator(null,null,budget,60,5000); }
    MlRoutePreview.Comparison comparison(Instant departure,Instant eta) {
        var r=new Result("candidate-0",departure,eta,eta==null?Assessment.INSUFFICIENT_DATA:
                eta.isAfter(d)?Assessment.LATE_ESTIMATE:Assessment.ON_TIME_ESTIMATE,true,List.of(),List.of(),"fixture");
        return new MlRoutePreview.Comparison("PREVIEW",false,eta==null?null:r,List.of(new MlRoutePreview.Candidate(r,"API_ONLY",0,1,0,0)),List.of(),false,List.of(),List.of());
    }
    @Test void oneSecondBeforeDeadlineKeepsPWithoutSearchOrBuffer() {
        var calls=new AtomicInteger();
        var result=engine(256).evaluate(1,now,p,d,List.of(),at->{calls.incrementAndGet();return comparison(at,d.minusSeconds(1));});
        assertThat(result.decision()).isEqualTo(KEEP_DEPARTURE);assertThat(calls).hasValue(1);
        assertThat(result.appliedBufferSeconds()).isZero();assertThat(result.targetArriveAt()).isNull();
    }
    @Test void exactDeadlineTriggersSearchAndExactTargetSucceeds() {
        var result=engine(256).evaluate(1,now,p,d,List.of(),at->comparison(at,at.plusSeconds(1800)));
        assertThat(result.decision()).isEqualTo(ADVANCE_DEPARTURE);
        assertThat(result.proposed().departureAt()).isEqualTo(p.minusSeconds(600));
        assertThat(result.proposed().estimatedArrivalAt()).isEqualTo(d.minusSeconds(600));
        assertThat(result.appliedBufferSeconds()).isEqualTo(600);
    }
    @Test void noTenMinuteMarginStillDistinguishesArrivalBeforeD() {
        var result=engine(256).evaluate(1,now,p,d,List.of(),at->comparison(at,at.equals(p)?d:d.minusSeconds(1)));
        assertThat(result.decision()).isEqualTo(MARGIN_UNAVAILABLE);
    }
    @Test void missingEvidenceIsNotRouteUnavailableOrZeroDuration() {
        var result=engine(256).evaluate(1,now,p,d,List.of(),at->comparison(at,null));
        assertThat(result.decision()).isEqualTo(DATA_INSUFFICIENT);assertThat(result.proposed()).isNull();
    }
    @Test void budgetEndsWithBestKnownDecisionInsteadOfClaimingImpossible() {
        var result=engine(2).evaluate(1,now,p,d,List.of(),at->comparison(at,d.plusSeconds(20)));
        assertThat(result.decision()).isEqualTo(SEARCH_INCOMPLETE);assertThat(result.bestKnownDecision()).isEqualTo(LATE_EXPECTED);
        assertThat(result.search().complete()).isFalse();assertThat(result.search().evaluatedCount()).isEqualTo(2);
    }
    @Test void pastPUsesNowAndNeverRecommendsPastDeparture() {
        var result=engine(256).evaluate(1,now,now.minusSeconds(600),d,List.of(),at->comparison(at,at.plusSeconds(600)));
        assertThat(result.decision()).isEqualTo(DEPART_NOW);assertThat(result.departureMissed()).isTrue();
        assertThat(result.proposed().departureAt()).isEqualTo(now);
    }
    @Test void nonMonotonicTimetableIsReevaluatedForEveryDeparture() {
        var seen=new ArrayList<Instant>();
        var result=engine(256).evaluate(1,now,p,d,List.of(p.minusSeconds(90)),at->{
            seen.add(at);return comparison(at,at.equals(p.minusSeconds(90))?d.minusSeconds(600):d.plusSeconds(10));
        });
        assertThat(result.proposed().departureAt()).isEqualTo(p.minusSeconds(90));
        assertThat(seen).containsExactly(p,p.minusSeconds(60),p.minusSeconds(90));
    }
    @Test void denseSourceBoundariesCannotDisplaceLaterGridDepartures() {
        var boundaries=new ArrayList<Instant>();
        for(int i=1;i<20;i++) boundaries.add(now.plusSeconds(i));
        var seen=new ArrayList<Instant>();
        var result=engine(4).evaluate(1,now,p,d,boundaries,at->{seen.add(at);return comparison(at,at.equals(p.minusSeconds(60))?d.minusSeconds(600):d);});
        assertThat(seen).containsExactly(p,p.minusSeconds(60));
        assertThat(result.proposed().departureAt()).isEqualTo(p.minusSeconds(60));
        assertThat(result.decision()).isEqualTo(ADVANCE_DEPARTURE);
        assertThat(result.search().complete()).isTrue();
    }
    /** 수시간 뒤 일정도 최신 해까지 모두 검사했다면 버린 과거 후보 때문에 미완료로 바꾸지 않는다. */
    @Test void distantScheduleCompletesWhenLatestFeasibleDepartureIsFoundWithinBudget() {
        Instant planned=now.plusSeconds(21600),deadline=planned.plusSeconds(3600);
        var result=engine(256).evaluate(1,now,planned,deadline,List.of(),at->comparison(at,at.plusSeconds(3600)));
        assertThat(result.decision()).isEqualTo(ADVANCE_DEPARTURE);
        assertThat(result.proposed().departureAt()).isEqualTo(planned.minusSeconds(600));
        assertThat(result.search().complete()).isTrue();
        assertThat(result.search().evaluatedCount()).isEqualTo(11);
        assertThat(result.bestKnownDecision()).isNull();
    }

    @Test void truncatedDomainWithoutFeasibleDepartureStillRemainsIncomplete() {
        var result=engine(4).evaluate(1,now,now.plusSeconds(21600),now.plusSeconds(25200),List.of(),
                at->comparison(at,now.plusSeconds(30000)));
        assertThat(result.decision()).isEqualTo(SEARCH_INCOMPLETE);
        assertThat(result.search().complete()).isFalse();
    }
    @Test void feasibleResultFromExhaustedBatchDoesNotClaimCompletedSearch() {
        var result=engine(256).evaluate(1,now,p,d,List.of(),at->{
            var base=comparison(at,at.equals(p)?d:d.minusSeconds(600));
            return new MlRoutePreview.Comparison(base.status(),false,base.fastest(),base.candidates(),base.predictions(),
                    !at.equals(p),base.subwayPredictions(),base.waitPredictions());
        });
        assertThat(result.decision()).isEqualTo(SEARCH_INCOMPLETE);
        assertThat(result.bestKnownDecision()).isEqualTo(ADVANCE_DEPARTURE);
    }
}
