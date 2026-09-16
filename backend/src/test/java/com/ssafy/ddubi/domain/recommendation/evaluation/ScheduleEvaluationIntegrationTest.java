package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.ssafy.ddubi.domain.recommendation.entity.Confidence;
import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import com.ssafy.ddubi.domain.recommendation.service.*;
import com.ssafy.ddubi.domain.route.client.PublicTransitRouteClient;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import com.ssafy.ddubi.domain.schedule.dto.request.*;
import com.ssafy.ddubi.domain.schedule.service.ScheduleService;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
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

/** 격리 MySQL/Redis에서 등록→재평가→상태 변화→수락·취소까지 실제 저장 경계를 검증한다. */
class ScheduleEvaluationIntegrationTest extends IntegrationTestSupport {
    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleRepository schedules;
    @Autowired UserRepository users;
    @Autowired ScheduleEvaluationWorkflow workflow;
    @Autowired ScheduleEvaluationStore store;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PublicTransitRouteClient routeClient;
    @MockitoBean BusPreviewClient mlClient;
    long owner;
    long scheduleId;
    Instant now;

    @BeforeEach void fixture() {
        when(mlClient.predict(any())).thenReturn(BusPreviewClient.Estimate.unavailable("ML_NOT_CONFIGURED"));
        now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        owner = users.save(User.builder().email("evaluation@example.test").nickname("평가 테스트").build()).getId();
        var location = new LocationRequest("강남",new BigDecimal("37.4979"),new BigDecimal("127.0276"));
        var target = new LocationRequest("역삼",new BigDecimal("37.5006"),new BigDecimal("127.0364"));
        scheduleId = scheduleService.create(owner,new ScheduleCreateRequest("테스트",location,target,
                LocalDateTime.ofInstant(now.plusSeconds(1800),ZoneId.of("Asia/Seoul")),null,null,
                LocalDateTime.ofInstant(now.plusSeconds(600),ZoneId.of("Asia/Seoul")))).id();
        when(routeClient.search(any())).thenReturn(new RouteSearchResult("KAKAO",now,null,
                List.of(RouteEvidenceAssemblerTest.candidate(0,1500,30),RouteEvidenceAssemblerTest.candidate(1,600,30))));
    }

    /** 저장된 근거 JSON의 persistence 필드 하나를 꺼낸다. MySQL이 공백을 정규화하므로 문자열 비교는 쓰지 않는다. */
    private String storedReason(long recommendationId, String field) {
        return jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(reasons,'$.persistence." + field + "')) "
                + "FROM recommendation WHERE id=?", String.class, recommendationId);
    }

    @Test void inputIsStoredAndPeriodicEvaluationReusesCandidates() {
        var first = workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        assertThat(first.selected().assessment()).isEqualTo(RouteTimelineEvaluator.Assessment.LATE_ESTIMATE);
        assertThat(first.suggestion().proposed().journeyKey()).isEqualTo("candidate-1");
        // 비교 계약은 JPA가 반환한 버전과 JDBC 저장/조회 버전의 일치다.
        // Hibernate의 Timestamp 변환이 개입하는 원본 DB 컬럼끼리 직접 비교하지 않는다.
        LocalDateTime persistedVersion = jdbc.queryForObject(
                "SELECT input_updated_at FROM schedule_evaluation WHERE schedule_id=?",
                (rs, rowNum) -> rs.getObject(1, LocalDateTime.class), scheduleId);
        assertThat(persistedVersion).isEqualTo(schedules.findById(scheduleId).orElseThrow().getUpdatedAt());
        assertThat(store.latest(scheduleId,owner).get("state")).isEqualTo("READY");
        workflow.evaluate(scheduleId,owner,TriggerType.REFRESH,now.plusSeconds(30));
        verify(routeClient,times(1)).search(any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM route WHERE schedule_id=? AND is_selected=true",Integer.class,scheduleId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM route_segment_edge",Integer.class)).isEqualTo(2);
        assertThat(schedules.findById(scheduleId).orElseThrow().getPlannedDepartAt())
                .isEqualTo(LocalDateTime.ofInstant(now.plusSeconds(600),ZoneId.of("Asia/Seoul")));
    }

    /**
     * 저장은 하되 수락·알림은 막는다(2026-09-14).
     *
     * <p>예전에는 PREVIEW면 추천 행 자체를 만들지 않았다. 그런데 모델이 미승인이라 <b>모든</b>
     * 결과가 PREVIEW라, 추천이 하나도 안 생기고 프론트의 최신 추천·근거 화면이 계속 빈손이었다.
     * 이제 기록은 남기되 <b>사용자를 실제로 움직이게 하는 두 가지</b>는 그대로 막는다 —
     * 일정 출발시각을 바꾸는 수락과, 폰을 울리는 자동 알림이다.
     */
    @Test void previewIsStoredWithItsMarkingButCannotBeAcceptedOrNotified() {
        var result = workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        assertThat(result.preview().status()).isEqualTo("PREVIEW");

        // 저장됐고, 검증용이라는 표시가 함께 남는다.
        assertThat(result.recommendationId()).isNotNull();
        assertThat(result.persistence().saved()).isTrue();
        assertThat(result.persistence().reason()).isNull();
        assertThat(result.persistence().preview()).isTrue();
        assertThat(result.persistence().status()).isEqualTo("PREVIEW");
        assertThat(result.persistence().productionReady()).isFalse();
        assertThat(result.persistence().confidence()).isEqualTo(Confidence.LOW);
        assertThat(result.persistence().source()).isEqualTo("API_ONLY");
        assertThat(result.persistence().fallbackUsed()).isTrue();
        assertThat(result.persistence().reasons()).contains("VALIDATION_ONLY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT confidence FROM recommendation WHERE id=?",String.class,result.recommendationId()))
                .as("검증 전 값이라는 사실을 행에도 남긴다").isEqualTo("LOW");
        // 행에 저장된 근거에도 검증용 표시가 들어 있어야 근거만 꺼내 봐도 출처를 알 수 있다.
        assertThat(storedReason(result.recommendationId(),"preview")).isEqualTo("true");
        assertThat(storedReason(result.recommendationId(),"productionReady")).isEqualTo("false");
        assertThat(storedReason(result.recommendationId(),"source")).isEqualTo("API_ONLY");

        // 여기부터는 막힌 채로 남는다.
        assertThatThrownBy(() -> store.accept(scheduleId,owner+1000,result.evaluationId(),now)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> store.accept(scheduleId,owner,result.evaluationId(),now)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT notification_json FROM schedule_evaluation WHERE schedule_id=?",String.class,scheduleId)).isNull();
        assertThat(schedules.findById(scheduleId).orElseThrow().getPlannedDepartAt())
                .as("수락이 막혔으니 일정 출발시각은 그대로다")
                .isEqualTo(LocalDateTime.ofInstant(now.plusSeconds(600),ZoneId.of("Asia/Seoul")));
        assertThat(store.latest(scheduleId,owner).get("state")).isEqualTo("READY");
    }

    /**
     * ETA를 계산하지 못하면 저장하지 않고 <b>사유를 남긴다.</b>
     *
     * <p>추천이 비어 있는 화면에서 "아직 계산 중"과 "계산은 했는데 근거가 없다"는 전혀 다른
     * 상황이다. recommendationId가 null이라는 사실만으로는 프론트가 둘을 구분할 수 없다.
     */
    @Test void resultWithoutEtaIsNotStoredAndSaysWhy() {
        var noEta = result("candidate-0",null,List.of("step-1:ML_NOT_CONFIGURED","step-1:BUS_WAIT_REQUIRED_NOT_APPLIED"));

        var completed = completeWithChosen(noEta);

        assertThat(completed.recommendationId()).isNull();
        assertThat(completed.persistence().saved()).isFalse();
        assertThat(completed.persistence().reason()).isEqualTo("ETA_UNAVAILABLE");
        assertThat(completed.persistence().reasons()).contains("step-1:BUS_WAIT_REQUIRED_NOT_APPLIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation",Integer.class))
                .as("첫 평가가 남긴 1건뿐이고 이번 평가는 추가하지 않는다").isEqualTo(1);
        // 사유는 DB 왕복 뒤에도 남아야 프론트가 latest만 보고 판단할 수 있다.
        var stored = (ScheduleEvaluationWorkflow.Outcome) store.latest(scheduleId,owner).get("result");
        assertThat(stored.persistence().reason()).isEqualTo("ETA_UNAVAILABLE");
    }

    /**
     * 계산 가능한 후보가 하나도 없을 때. 지금 workflow에서는 selected가 항상 채워져 여기까지
     * 오지 않지만, 저장 판정이 null을 만나면 NPE가 아니라 사유로 드러나야 한다.
     */
    @Test void resultWithoutAnyCandidateIsNotStoredAndSaysWhy() {
        var completed = completeWithChosen(null);

        assertThat(completed.recommendationId()).isNull();
        assertThat(completed.persistence().reason()).isEqualTo("NO_CANDIDATE");
        assertThat(completed.persistence().reasons()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation",Integer.class)).isEqualTo(1);
    }

    /** 캐시에 없는 후보를 고른 경우. getReferenceById(null)로 죽는 대신 사유가 남아야 한다. */
    @Test void chosenCandidateMissingFromCacheIsNotStoredAndSaysWhy() {
        var unknown = result("candidate-does-not-exist",now.plusSeconds(1200),List.of("VALIDATION_ONLY"));

        var completed = completeWithChosen(unknown);

        assertThat(completed.recommendationId()).isNull();
        assertThat(completed.persistence().reason()).isEqualTo("ROUTE_NOT_STORED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation",Integer.class)).isEqualTo(1);
    }

    private RouteTimelineEvaluator.Result result(String key, Instant eta, List<String> reasons) {
        return new RouteTimelineEvaluator.Result(key,now.plusSeconds(600),eta,
                eta == null ? RouteTimelineEvaluator.Assessment.INSUFFICIENT_DATA : RouteTimelineEvaluator.Assessment.ON_TIME_ESTIMATE,
                true,List.of(),reasons,"timeline-v1");
    }

    /**
     * 첫 평가로 후보 캐시를 만든 뒤, 선택 결과만 바꿔치기한 평가를 완료시킨다.
     * 저장 판정은 store의 책임이라 workflow를 거치지 않고 경계에서 직접 검증한다.
     */
    private ScheduleEvaluationWorkflow.Outcome completeWithChosen(RouteTimelineEvaluator.Result chosen) {
        workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        var job = store.claim(scheduleId,owner,now.plusSeconds(10));
        var previous = store.read(job.previousJson(),ScheduleEvaluationWorkflow.Outcome.class);
        var outcome = new ScheduleEvaluationWorkflow.Outcome(job.token(),scheduleId,null,job.departure(),job.arriveBy(),
                now.plusSeconds(10),"candidate-0",chosen,
                new DepartureSuggestionService.Suggestion(chosen == null ? List.of() : List.of(chosen),null,null,1,false),
                previous.candidates(),previous.evidence(),TriggerType.REFRESH,"ml-prev-v1",job.eventRevision(),
                previous.preview(),null);
        return store.complete(job,job.cache().search(),false,outcome,now.plusSeconds(600),null,null,now.plusSeconds(11));
    }

    @Test void storedEvaluationUsesMlAndReturnsFastestCandidateAtUserDeparture() {
        when(routeClient.search(any())).thenReturn(new RouteSearchResult("KAKAO",now,null,
                List.of(MlRoutePreviewTest.route(0,"1",10,0,1),MlRoutePreviewTest.route(1,"2",900,0,1))));
        when(mlClient.predict(any())).thenAnswer(call -> new BusPreviewClient.Estimate(
                ((BusPreviewClient.Input)call.getArgument(0)).routeId().equals("1") ? 200.0 : 30.0,
                "MODEL_NOT_PRODUCTION_READY","test-model",now,0,8,false));
        var result = workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        assertThat(result.preview().fastest().journeyKey()).isEqualTo("candidate-1");
        assertThat(result.preview().fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(630));
        assertThat(result.preview().fastest().departureAt()).isEqualTo(now.plusSeconds(600));
        var stored = (ScheduleEvaluationWorkflow.Outcome) store.latest(scheduleId,owner).get("result");
        assertThat(stored.preview().predictions()).hasSize(2);
        assertThat(stored.preview().waitPredictions()).hasSize(2);
        assertThat(stored.preview().waitPredictions()).allMatch(w -> w.seconds() == null && !w.applied());
        assertThat(stored.preview().candidates().get(1).quality()).isEqualTo("ML_PREVIEW");
        // 예측을 실제로 쓴 후보가 선택됐으면 출처도 그대로 남는다. LOW는 유지된다 — 미승인 모델이다.
        assertThat(stored.recommendationId()).isNotNull();
        assertThat(stored.persistence().source()).isEqualTo("ML_PREVIEW");
        assertThat(stored.persistence().mlSteps()).isEqualTo(1);
        assertThat(stored.persistence().productionReady()).isFalse();
        assertThat(jdbc.queryForObject("SELECT confidence FROM recommendation WHERE id=?",String.class,stored.recommendationId()))
                .isEqualTo("LOW");
    }

    @Test void subwayMissingTimetableReasonSurvivesStoredPreviewRoundTrip() {
        // 새 JSON 필드가 MySQL 저장/역직렬화에서 사라지거나 시간표 미설정을 ML 성공으로 표시하지 않는다.
        var segment = SubwayTimingServiceTest.segment(1,"A","B");
        var route = new com.ssafy.ddubi.domain.route.model.RouteCandidate(0,
                com.ssafy.ddubi.domain.route.model.RouteType.SUBWAY,100,999,0,
                new com.ssafy.ddubi.domain.route.model.FareRange(0,0),0,0,List.of(segment));
        when(routeClient.search(any())).thenReturn(new RouteSearchResult("KAKAO",now,null,List.of(route)));
        workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        var stored = (ScheduleEvaluationWorkflow.Outcome)store.latest(scheduleId,owner).get("result");
        assertThat(stored.preview().subwayPredictions()).hasSize(1);
        assertThat(stored.preview().waitPredictions()).hasSize(1);
        assertThat(stored.preview().waitPredictions().get(0).seconds()).isNull();
        assertThat(stored.preview().subwayPredictions().get(0).estimate().reasons()).contains("SUBWAY_TIMETABLE_NOT_CONFIGURED");
        assertThat(stored.preview().subwayPredictions().get(0).applied()).isFalse();
        assertThat(stored.preview().candidates().get(0).subwaySteps()).isZero();
    }

    @Test void canceledOrEditedScheduleCannotBeOverwrittenByInFlightEvaluation() {
        var job = store.claim(scheduleId,owner,now);
        assertThatThrownBy(() -> store.claim(scheduleId,owner,now)).isInstanceOf(BusinessException.class);
        scheduleService.cancel(owner,scheduleId);
        assertThat(store.due(now.plusSeconds(121))).doesNotContain(scheduleId);
        assertThatThrownBy(() -> store.complete(job,null,false,null,now,null,null,now)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation",Integer.class)).isZero();
    }

    @Test void eventTargetsOnlyRelatedSelectedEdgesAndInvalidatesProposalAcceptance() {
        var result = workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        store.wakeSegments(List.of("BUS:9:8:7"),now);
        assertThat(jdbc.queryForObject("SELECT event_revision FROM schedule_evaluation WHERE schedule_id=?",Long.class,scheduleId)).isZero();
        store.wakeSegments(List.of("BUS:1:2:3"),now);
        assertThat(jdbc.queryForObject("SELECT event_revision FROM schedule_evaluation WHERE schedule_id=?",Long.class,scheduleId)).isEqualTo(1);
        assertThatThrownBy(() -> store.accept(scheduleId,owner,result.evaluationId(),now)).isInstanceOf(BusinessException.class);
    }

    @Test void missingDepartureAndProviderFailureRemainExplicit() {
        when(routeClient.search(any())).thenThrow(new IllegalStateException("private URL"));
        assertThatThrownBy(() -> workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now)).isInstanceOf(IllegalStateException.class);
        assertThat(store.due(now.plusSeconds(1))).doesNotContain(scheduleId);
        assertThat(store.due(now.plusSeconds(61))).contains(scheduleId);
        assertThat(jdbc.queryForObject("SELECT last_error FROM schedule_evaluation WHERE schedule_id=?",String.class,scheduleId))
                .isEqualTo("EVALUATION_FAILED");
    }
    @Test void concurrentClaimsHaveExactlyOneLeaseOwner() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> claim = () -> {
                ready.countDown();
                if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                try { store.claim(scheduleId, owner, now); return true; }
                catch (BusinessException busy) { return false; }
            };
            var first = pool.submit(claim); var second = pool.submit(claim);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void eventDuringCalculationSurvivesCompletionAndStop() throws Exception {
        workflow.evaluate(scheduleId, owner, TriggerType.INITIAL, now);
        var job = store.claim(scheduleId, owner, now.plusSeconds(10));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            // 다른 스레드에서 이벤트를 갱신해 계산 시작 때의 revision보다 최신이 되게 한다.
            pool.submit(() -> store.wakeSegments(List.of("BUS:1:2:3"), now.plusSeconds(11)))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            var previous = store.read(job.previousJson(), ScheduleEvaluationWorkflow.Outcome.class);
            store.complete(job, job.cache().search(), false, previous, now.plusSeconds(600), null, null, now.plusSeconds(12));
            assertThat(store.due(now.plusSeconds(12))).contains(scheduleId);
            var stop = pool.submit(() -> store.stop(scheduleId, owner));
            var event = pool.submit(() -> store.wakeSegments(List.of("BUS:1:2:3"), now.plusSeconds(13)));
            stop.get(10, java.util.concurrent.TimeUnit.SECONDS); event.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(store.due(now.plusSeconds(14))).doesNotContain(scheduleId);
            assertThat(jdbc.queryForObject("SELECT stopped FROM schedule_evaluation WHERE schedule_id=?", Boolean.class, scheduleId)).isTrue();
        } finally { pool.shutdownNow(); }
    }
}
