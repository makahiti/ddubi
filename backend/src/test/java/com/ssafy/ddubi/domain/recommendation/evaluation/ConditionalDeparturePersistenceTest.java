package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import com.ssafy.ddubi.domain.recommendation.service.*;
import com.ssafy.ddubi.domain.route.client.PublicTransitRouteClient;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import com.ssafy.ddubi.domain.schedule.dto.request.*;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.schedule.service.ScheduleService;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 MySQL migration·revision·lease·v2 직렬화·출발 멱등성의 교차 경계를 검증한다. */
class ConditionalDeparturePersistenceTest extends IntegrationTestSupport {
    @Autowired ScheduleService schedules;
    @Autowired ScheduleRepository repository;
    @Autowired UserRepository users;
    @Autowired ScheduleEvaluationStore store;
    @Autowired ScheduleEvaluationWorkflow workflow;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @MockitoBean PublicTransitRouteClient routes;
    @MockitoBean BusPreviewClient model;
    Instant now; long owner,id;
    @BeforeEach void setup() {
        now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        owner=users.save(User.builder().email("conditional@test.invalid").nickname("fixture").build()).getId();
        var point=new LocationRequest("강남",new BigDecimal("37.5"),new BigDecimal("127.0"));
        id=schedules.create(owner,new ScheduleCreateRequest("조건부",point,point,local(now.plusSeconds(3600)),null,false,local(now.plusSeconds(1800)))).id();
        when(model.predict(any())).thenReturn(BusPreviewClient.Estimate.unavailable("ML_NOT_CONFIGURED"));
        when(routes.search(any())).thenReturn(new RouteSearchResult("KAKAO",now,null,List.of(RouteEvidenceAssemblerTest.candidate(0,1800,0))));
    }
    LocalDateTime local(Instant t) { return LocalDateTime.ofInstant(t,ZoneId.of("Asia/Seoul")); }
    @Test void exactDeadlineActuallySearchesInWorkflowAndPersistsBuffer() {
        var outcome=workflow.evaluate(id,owner,TriggerType.INITIAL,now);
        assertThat(outcome.conditional().decision()).isEqualTo(ConditionalDeparturePolicy.Decision.ADVANCE_DEPARTURE);
        assertThat(outcome.suggestion().proposed().departureAt()).isEqualTo(now.plusSeconds(1200));
        assertThat(jdbc.queryForObject("SELECT buffer_min FROM recommendation WHERE id=?",Integer.class,outcome.recommendationId())).isEqualTo(10);
        var response=store.response(outcome,owner);
        assertThat(response.schemaVersion()).isEqualTo("schedule-evaluation-v2");
        assertThat(response.meetsAdjustedTarget()).isTrue();assertThat(response.quality().productionEligible()).isFalse();
        assertThat(repository.findById(id).orElseThrow().getPlannedDepartAt()).isEqualTo(local(now.plusSeconds(1800)));
        assertThatThrownBy(()->store.accept(id,owner,outcome.evaluationId(),now)).isInstanceOf(BusinessException.class);
    }
    @Test void sameTimestampInputChangeCannotCompleteOldLease() {
        var job=store.claim(id,owner,now);
        jdbc.update("UPDATE schedule SET input_revision=input_revision+1 WHERE id=?",id);
        assertThatThrownBy(()->store.complete(job,null,false,null,now,null,null,now)).isInstanceOf(BusinessException.class);
    }
    @Test void expiredLeaseAndChangedEventBothRejectLateWriter() {
        var job=store.claim(id,owner,now);
        assertThatThrownBy(()->store.complete(job,null,false,null,now,null,null,now.plusSeconds(121))).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE schedule_evaluation SET event_revision=event_revision+1 WHERE schedule_id=?",id);
        assertThatThrownBy(()->store.complete(job,null,false,null,now,null,null,now)).isInstanceOf(BusinessException.class);
    }
    @Test void departurePreservesFirstFactAndStopsMonitoring() {
        store.stop(id,owner);
        var first=repository.findById(id).orElseThrow().getActualDepartAt();
        store.stop(id,owner);
        var saved=repository.findById(id).orElseThrow();
        assertThat(saved.getActualDepartAt()).isEqualTo(first);assertThat(saved.getJourneyState()).isEqualTo("IN_PROGRESS");
        assertThat(store.due(now.plusSeconds(5))).doesNotContain(id);
    }
    @Test void repeatedProviderFailuresStopAutomaticRetryUntilInputChanges() {
        for(int i=0;i<5;i++) {
            var job=store.claim(id,owner,now);
            store.fail(job,"EVALUATION_UNAVAILABLE",now);
        }
        assertThat(store.due(now.plusSeconds(1000))).doesNotContain(id);
        assertThat(jdbc.queryForObject("SELECT failure_count FROM schedule_evaluation WHERE schedule_id=?",Integer.class,id)).isEqualTo(5);
        jdbc.update("UPDATE schedule SET input_revision=input_revision+1 WHERE id=?",id);
        assertThat(store.due(now.plusSeconds(1000))).contains(id);
    }
    @Test void legacyStoredJsonRemainsReadableWithoutPretendingToBeV2() throws Exception {
        var outcome=workflow.evaluate(id,owner,TriggerType.INITIAL,now);
        var old=json.valueToTree(outcome); ((com.fasterxml.jackson.databind.node.ObjectNode)old).remove("conditional");
        jdbc.update("UPDATE schedule_evaluation SET result_json=? WHERE schedule_id=?",json.writeValueAsString(old),id);
        assertThat(((ScheduleEvaluationWorkflow.Outcome)store.latest(id,owner).get("result")).conditional()).isNull();
        var v2=json.valueToTree(store.latestV2(id,owner));
        assertThat(v2.path("state").asText()).isEqualTo("PENDING");assertThat(v2.has("result")).isFalse();
    }
}
