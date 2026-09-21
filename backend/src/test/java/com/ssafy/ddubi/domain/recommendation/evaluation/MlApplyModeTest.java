package com.ssafy.ddubi.domain.recommendation.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** SHADOW/TEST_APPLY/PRODUCTION 세 모드가 같은 응답을 어떻게 다르게 다루는지, 배치 선조회가 뒤 후보를 공정하게 대하는지 검증한다. */
class MlApplyModeTest {
    final Instant now = Instant.parse("2026-09-18T00:00:30Z");
    final BusPreviewClient client = mock(BusPreviewClient.class);
    final RouteEvidenceAssembler.Snapshot empty =
            new RouteEvidenceAssembler.Snapshot(Map.of(), List.of());

    JourneyEvaluationService service(MlApplyMode mode) {
        return service(mode, null);
    }

    JourneyEvaluationService service(MlApplyMode mode, BusHistoricalReferenceService historical) {
        return new JourneyEvaluationService(
                client,
                new RouteEvidenceAssembler(mock(StringRedisTemplate.class), new ObjectMapper()),
                new RouteTimelineEvaluator(),
                mock(SubwayTimingService.class),
                new BusWaitingService(mock(StringRedisTemplate.class), new ObjectMapper(), false),
                historical,
                JourneyEvaluationService.DEFAULT_ML_BUDGET_SHARE,
                mode);
    }

    /** 경기 노선은 정적 topology만 사용하고 서울 실시간/ML ID는 채우지 않는다. */
    RouteCandidate gbisRoute() {
        var edge =
                new MappedTransitSegment(
                        1,
                        "BUS:GBIS.1:GBIS.10:GBIS.11",
                        TransportMode.BUS,
                        "GBIS.1",
                        null,
                        "경기 노선",
                        "FORWARD",
                        "GBIS.10",
                        "GBIS.11",
                        null,
                        null,
                        1,
                        2);
        var mapping =
                new TransitMappingResult(
                        TransitMappingStatus.EXACT,
                        "master",
                        "policy",
                        "GBIS.1",
                        "경기 노선",
                        "GBIS.10",
                        "GBIS.11",
                        1,
                        2,
                        "FORWARD",
                        1,
                        1,
                        1,
                        null,
                        RealtimeMappingCoverage.NONE,
                        List.of(edge));
        var segment =
                new RouteSegment(
                        1,
                        TransportMode.BUS,
                        null,
                        "GBIS.1",
                        null,
                        "A",
                        "B",
                        100,
                        300,
                        List.of("A", "B"),
                        List.of(new VehicleInfo("1", "BUS")),
                        List.of(),
                        mapping);
        return new RouteCandidate(
                0, RouteType.BUS, 100, 300, 0, new FareRange(1000, 1000), 0, 0, List.of(segment));
    }

    @Test
    void seoulFallsBackFromFailedMlToObservedFormulaThenToKakao() {
        var historical = mock(BusHistoricalReferenceService.class);
        when(client.predict(any())).thenReturn(BusPreviewClient.Estimate.unavailable("ML_TIMEOUT"));
        when(historical.estimate(
                        anyList(), eq("master"), any(), anyLong(), any(), any(), anyLong()))
                .thenReturn(
                        new BusHistoricalReferenceService.Estimate(
                                42L,
                                "HISTORICAL_REFERENCE_APPLIED",
                                "seoul-v1",
                                "HOURLY_SOURCE_MEAN",
                                List.of()));
        var formula =
                service(MlApplyMode.TEST_APPLY, historical)
                        .compare(
                                List.of(MlRoutePreviewTest.route(0, "1", 300, 0, 1)),
                                empty,
                                now,
                                now,
                                now.plusSeconds(1000));
        assertThat(formula.fastest().steps().get(0).source()).isEqualTo("BUS_HISTORICAL_REFERENCE");
        assertThat(formula.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(42));
        assertThat(formula.fastest().reasons())
                .contains(
                        "step-1:ML_NOT_APPLIED:ML_TIMEOUT",
                        "step-1:HISTORICAL_REFERENCE_APPLIED",
                        "step-1:BUS_REFERENCE_VERSION:seoul-v1");

        when(historical.estimate(
                        anyList(), eq("master"), any(), anyLong(), any(), any(), anyLong()))
                .thenReturn(
                        BusHistoricalReferenceService.Estimate.missing("EXACT_KEY_UNAVAILABLE"));
        var kakao =
                service(MlApplyMode.TEST_APPLY, historical)
                        .compare(
                                List.of(MlRoutePreviewTest.route(0, "1", 300, 0, 1)),
                                empty,
                                now,
                                now,
                                now.plusSeconds(1000));
        assertThat(kakao.fastest().steps().get(0).source()).isEqualTo("FALLBACK");
        assertThat(kakao.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(300));
        assertThat(kakao.fastest().reasons())
                .contains(
                        "step-1:HISTORICAL_REFERENCE_EXACT_KEY_UNAVAILABLE", "step-1:API_FALLBACK");
    }

    @Test
    void nonSeoulSkipsMlAndUsesGtfsFormulaBeforeKakao() {
        var historical = mock(BusHistoricalReferenceService.class);
        when(historical.estimate(
                        anyList(), eq("master"), any(), anyLong(), any(), any(), anyLong()))
                .thenReturn(
                        new BusHistoricalReferenceService.Estimate(
                                55L,
                                "GTFS_FORMULA_APPLIED",
                                "gtfs-v1",
                                "GTFS_SCHEDULE_FORMULA",
                                List.of()));
        var result =
                service(MlApplyMode.TEST_APPLY, historical)
                        .compare(List.of(gbisRoute()), empty, now, now, now.plusSeconds(1000));
        verify(client, never()).predict(any());
        verify(client, never()).predictBatch(anyList(), any());
        assertThat(result.fastest().steps().get(0).source()).isEqualTo("BUS_GTFS_FORMULA");
        assertThat(result.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(55));
        assertThat(result.fastest().reasons())
                .contains(
                        "step-1:ML_NOT_APPLIED:UNSUPPORTED_PROVIDER",
                        "step-1:GTFS_FORMULA_APPLIED",
                        "step-1:BUS_REFERENCE_KIND:GTFS_SCHEDULE_FORMULA");

        // 경기·인천·부산 기준값은 아직 실측이 아니므로 결측을 임의 추정으로 메우지 않는다.
        // 정확키 조회가 실패하면 동일 BUS step의 카카오 원본 300초가 최종 안전망이어야 한다.
        when(historical.estimate(
                        anyList(), eq("master"), any(), anyLong(), any(), any(), anyLong()))
                .thenReturn(
                        BusHistoricalReferenceService.Estimate.missing("EXACT_KEY_UNAVAILABLE"));
        var kakao =
                service(MlApplyMode.TEST_APPLY, historical)
                        .compare(List.of(gbisRoute()), empty, now, now, now.plusSeconds(1000));
        verify(client, never()).predict(any());
        verify(client, never()).predictBatch(anyList(), any());
        assertThat(kakao.fastest().steps().get(0).source()).isEqualTo("FALLBACK");
        assertThat(kakao.fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(300));
        assertThat(kakao.fastest().reasons())
                .contains(
                        "step-1:HISTORICAL_REFERENCE_EXACT_KEY_UNAVAILABLE", "step-1:API_FALLBACK");
    }

    BusPreviewClient.Estimate sufficient(double seconds) {
        return new BusPreviewClient.Estimate(
                seconds, "MODEL_NOT_PRODUCTION_READY", "m1", now, 0, 8, false);
    }

    /** 실제 이력이 있는 PREVIEW 응답(ML 담당 조사에서 확인된 history 7·8 구간). 결측 특성명은 진단용으로 남는다. */
    BusPreviewClient.Estimate previewWithHistory(double seconds) {
        return new BusPreviewClient.Estimate(
                seconds,
                "MODEL_NOT_PRODUCTION_READY",
                "m1",
                now,
                15,
                7,
                false,
                List.of("history_recent_seconds", "passenger_recent"),
                false,
                false,
                BusPreviewClient.Contract.PREVIEW);
    }

    /** 서버가 표본 부족을 명시한 응답. */
    BusPreviewClient.Estimate insufficient(double seconds) {
        return new BusPreviewClient.Estimate(
                seconds,
                "MODEL_NOT_PRODUCTION_READY",
                "m1",
                now,
                15,
                0,
                true,
                List.of("history_recent_seconds", "passenger_recent"),
                false,
                false,
                BusPreviewClient.Contract.PREVIEW);
    }

    /** 표본은 0건인데 서버가 부족 표시를 하지 않은 응답. 학습 중앙값으로 숫자만 나온 경우다. */
    BusPreviewClient.Estimate zeroHistory(double seconds) {
        return new BusPreviewClient.Estimate(
                seconds,
                "MODEL_NOT_PRODUCTION_READY",
                "m1",
                now,
                15,
                0,
                false,
                List.of("history_recent_seconds"),
                false,
                false,
                BusPreviewClient.Contract.PREVIEW);
    }

    BusPreviewClient.Estimate production(double seconds) {
        return new BusPreviewClient.Estimate(
                seconds,
                "PRODUCTION_CONTRACT",
                "m1",
                now,
                0,
                8,
                false,
                List.of(),
                false,
                true,
                BusPreviewClient.Contract.PRODUCTION);
    }

    @Test
    void shadowRecordsNumbersButNeverChangesTravelTime() {
        when(client.predict(any())).thenReturn(sufficient(30));
        var result =
                service(MlApplyMode.SHADOW)
                        .compare(
                                List.of(MlRoutePreviewTest.route(0, "1", 300, 10, 1)),
                                empty,
                                now,
                                now,
                                now.plusSeconds(1000));
        var fastest = result.fastest();
        assertThat(fastest.steps().get(0).source()).isEqualTo("FALLBACK"); // API 값 유지
        assertThat(fastest.estimatedArrivalAt()).isEqualTo(now.plusSeconds(300));
        assertThat(fastest.reasons())
                .contains(
                        "step-1:ML_SHADOW_RECORDED",
                        "step-1:ML_NOT_APPLIED:SHADOW_MODE",
                        "step-1:API_FALLBACK");
        assertThat(result.predictions())
                .hasSize(1)
                .allMatch(p -> !p.applied() && p.estimate().seconds() == 30.0);
        assertThat(result.candidates().get(0).quality()).isEqualTo("API_ONLY");
        assertThat(result.productionReady()).isFalse();
    }

    @Test
    void testApplyFallsBackWholeBusStepWhenHistorySamplesAreMissing() {
        // 1) 서버가 표본 부족을 명시한 경우.
        when(client.predict(any())).thenReturn(insufficient(30));
        var declared =
                service(MlApplyMode.TEST_APPLY)
                        .compare(
                                List.of(MlRoutePreviewTest.route(0, "1", 300, 10, 1)),
                                empty,
                                now,
                                now,
                                now.plusSeconds(1000));
        assertThat(declared.fastest().steps().get(0).source()).isEqualTo("FALLBACK");
        assertThat(declared.fastest().estimatedArrivalAt())
                .isEqualTo(now.plusSeconds(300)); // API 원본 300+10초 유지
        assertThat(declared.fastest().reasons())
                .contains(
                        "step-1:ML_HISTORY_INSUFFICIENT",
                        "step-1:ML_NOT_APPLIED:ML_HISTORY_INSUFFICIENT",
                        "step-1:API_FALLBACK");
        assertThat(declared.fastest().reasons()).noneMatch(r -> r.contains("ML_TEST_APPLIED"));
        assertThat(declared.predictions())
                .allMatch(p -> !p.applied() && p.estimate().seconds() == 30.0); // 수치는 진단에 남는다

        // 2) 표본 0건인데 부족 표시가 없는 경우(학습 중앙값으로 숫자만 나온 응답)도 같게 처리한다.
        when(client.predict(any())).thenReturn(zeroHistory(30));
        var silent =
                service(MlApplyMode.TEST_APPLY)
                        .compare(
                                List.of(MlRoutePreviewTest.route(0, "1", 300, 10, 1)),
                                empty,
                                now,
                                now,
                                now.plusSeconds(1000));
        assertThat(silent.fastest().steps().get(0).source()).isEqualTo("FALLBACK");
        assertThat(silent.fastest().reasons())
                .contains("step-1:ML_NOT_APPLIED:ML_HISTORY_INSUFFICIENT", "step-1:API_FALLBACK");

        // 3) 한 edge라도 이력이 없으면 step 전체를 API 값으로 되돌린다(일부만 ML로 채우지 않는다).
        when(client.predict(any())).thenReturn(previewWithHistory(30), zeroHistory(30));
        var partial =
                service(MlApplyMode.TEST_APPLY)
                        .compare(
                                List.of(MlRoutePreviewTest.route(0, "1", 300, 10, 2)),
                                empty,
                                now,
                                now,
                                now.plusSeconds(1000));
        assertThat(partial.fastest().steps().get(0).source()).isEqualTo("FALLBACK");
        assertThat(partial.fastest().reasons())
                .contains("step-1:ML_NOT_APPLIED:ML_HISTORY_INSUFFICIENT", "step-1:API_FALLBACK");
        assertThat(partial.predictions()).allMatch(p -> !p.applied());
    }

    @Test
    void testApplyUsesPreviewNumbersOnlyWhenRealHistoryExistsAndMarksTheTrial() {
        when(client.predict(any())).thenReturn(previewWithHistory(30));
        var result =
                service(MlApplyMode.TEST_APPLY)
                        .compare(
                                List.of(MlRoutePreviewTest.route(0, "1", 300, 10, 1)),
                                empty,
                                now,
                                now,
                                now.plusSeconds(1000));
        var fastest = result.fastest();
        assertThat(fastest.steps().get(0).source()).isEqualTo("ML_PREVIEW");
        assertThat(fastest.estimatedArrivalAt())
                .isEqualTo(now.plusSeconds(30)); // 30초 ML, 미분류 시간 제외
        assertThat(fastest.reasons())
                .contains(
                        "step-1:ML_TEST_APPLIED",
                        "step-1:ML_MODEL_VERSION:m1",
                        "step-1:ML_MIN_HISTORY_SAMPLES:7",
                        "step-1:ML_MAX_MISSING_FEATURES:15");
        assertThat(fastest.reasons()).doesNotContain("step-1:API_FALLBACK");
        // 시험 적용은 이력이 있는 구간에서만 하므로 "이력 부족인데 적용" 예외 표시는 더 이상 없다.
        assertThat(fastest.reasons()).noneMatch(r -> r.contains("ML_HISTORY_INSUFFICIENT"));
        assertThat(result.predictions()).allMatch(MlRoutePreview.Prediction::applied);
        assertThat(result.predictions().get(0).estimate().missingFeatures())
                .containsExactly("history_recent_seconds", "passenger_recent");
        assertThat(result.productionReady()).isFalse(); // 시험 적용은 운영 승인이 아니다
        // 숫자 자체가 없는 edge가 섞이면 이전과 같이 step 전체를 되돌린다.
        when(client.predict(any()))
                .thenReturn(
                        previewWithHistory(30),
                        BusPreviewClient.Estimate.unavailable("ML_TIMEOUT"));
        var partial =
                service(MlApplyMode.TEST_APPLY)
                        .compare(
                                List.of(MlRoutePreviewTest.route(0, "1", 300, 10, 2)),
                                empty,
                                now,
                                now,
                                now.plusSeconds(1000));
        assertThat(partial.fastest().steps().get(0).source()).isEqualTo("FALLBACK");
        assertThat(partial.fastest().reasons())
                .contains("step-1:ML_NOT_APPLIED:ML_TIMEOUT", "step-1:API_FALLBACK");
        assertThat(partial.predictions()).allMatch(p -> !p.applied());
    }

    @Test
    void productionModeOnlyAppliesProductionContractEstimates() {
        var route = MlRoutePreviewTest.route(0, "1", 300, 10, 1);
        // production_ready=false 응답(= PREVIEW 계약으로 해석된 값)은 이력이 충분해도 운영 모드에서 적용하지 않는다.
        // HTTP 봉투 단계의 거절은 BusPreviewClientTest 의 계약 테스트가 따로 고정한다.
        assertThat(sufficient(30).contract()).isEqualTo(BusPreviewClient.Contract.PREVIEW);
        assertThat(sufficient(30).productionEligible()).isFalse();
        when(client.predictBatch(anyList(), eq(BusPreviewClient.Contract.PRODUCTION)))
                .thenAnswer(
                        call -> {
                            var out =
                                    new LinkedHashMap<
                                            BusPreviewClient.Input, BusPreviewClient.Estimate>();
                            for (BusPreviewClient.Input i :
                                    (List<BusPreviewClient.Input>) call.getArgument(0))
                                out.put(i, sufficient(30));
                            return out;
                        });
        var rejected =
                service(MlApplyMode.PRODUCTION)
                        .compare(List.of(route), empty, now, now, now.plusSeconds(1000));
        assertThat(rejected.fastest().steps().get(0).source()).isEqualTo("FALLBACK");
        assertThat(rejected.fastest().estimatedArrivalAt())
                .isEqualTo(now.plusSeconds(300)); // API 원본 유지
        assertThat(rejected.fastest().reasons())
                .contains(
                        "step-1:ML_NOT_APPLIED:ML_PRODUCTION_CONTRACT_NOT_MET",
                        "step-1:API_FALLBACK");
        when(client.predictBatch(anyList(), eq(BusPreviewClient.Contract.PRODUCTION)))
                .thenAnswer(
                        call -> {
                            var out =
                                    new LinkedHashMap<
                                            BusPreviewClient.Input, BusPreviewClient.Estimate>();
                            for (BusPreviewClient.Input i :
                                    (List<BusPreviewClient.Input>) call.getArgument(0))
                                out.put(i, production(30));
                            return out;
                        });
        var applied =
                service(MlApplyMode.PRODUCTION)
                        .compare(List.of(route), empty, now, now, now.plusSeconds(1000));
        assertThat(applied.fastest().steps().get(0).source()).isEqualTo("ML_PRODUCTION");
        assertThat(applied.fastest().reasons()).contains("step-1:ML_APPLIED");
        assertThat(applied.productionReady()).isFalse(); // 승인 정책 전환은 별도 결정
        verify(client, never()).predict(any());
    }

    @Test
    void batchPrefetchServesAllCandidatesWithOneRequestAndDeduplicatesInputs() {
        var seen = new ArrayList<List<BusPreviewClient.Input>>();
        when(client.predictBatch(anyList(), eq(BusPreviewClient.Contract.PREVIEW)))
                .thenAnswer(
                        call -> {
                            List<BusPreviewClient.Input> inputs = call.getArgument(0);
                            seen.add(List.copyOf(inputs));
                            var out =
                                    new LinkedHashMap<
                                            BusPreviewClient.Input, BusPreviewClient.Estimate>();
                            for (BusPreviewClient.Input i : inputs)
                                out.put(i, sufficient("1".equals(i.routeId()) ? 30 : 60));
                            return out;
                        });
        // 후보 0과 1은 같은 노선·edge(같은 ML 입력), 후보 2는 다른 노선이다. 33개 후보 순차 호출 대신 한 배치로 조회한다.
        var routes =
                List.of(
                        MlRoutePreviewTest.route(0, "1", 300, 0, 1),
                        MlRoutePreviewTest.route(1, "1", 500, 0, 1),
                        MlRoutePreviewTest.route(2, "2", 900, 0, 1));
        var result =
                service(MlApplyMode.TEST_APPLY)
                        .compare(routes, empty, now, now, now.plusSeconds(2000));
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).hasSize(2); // 중복 입력 제거
        verify(client, never()).predict(any()); // 개별 호출 없음
        assertThat(result.candidates()).allMatch(c -> c.mlSteps() == 1); // 뒤 후보도 예산을 잃지 않는다
        assertThat(result.candidates().get(2).result().estimatedArrivalAt())
                .isEqualTo(now.plusSeconds(60));
        assertThat(result.budgetExhausted()).isFalse();
    }

    @Test
    void exhaustedMlBudgetIsReportedAsBudgetNotAsMissingDataAndIndividualCallsAreCapped() {
        var service = service(MlApplyMode.TEST_APPLY);
        when(client.predict(any())).thenReturn(sufficient(30));
        long nowNanos = System.nanoTime();
        var exhausted =
                service.compare(
                        List.of(MlRoutePreviewTest.route(0, "1", 300, 0, 1)),
                        empty,
                        now,
                        now,
                        now.plusSeconds(1000),
                        JourneyEvaluationService.Session.withDeadlines(
                                nowNanos + Duration.ofSeconds(20).toNanos(), nowNanos - 1));
        assertThat(exhausted.fastest().reasons())
                .contains(
                        "step-1:ML_BUDGET_EXHAUSTED",
                        "step-1:ML_NOT_APPLIED:ML_BUDGET_EXHAUSTED",
                        "step-1:API_FALLBACK");
        assertThat(exhausted.budgetExhausted()).isFalse(); // 보조 ML 예산 부족이어도 전체 API 후보 계산은 완료됐다.
        verify(client, never()).predict(any());
        // 선조회가 비어 있고(mock) edge마다 개별 호출이 필요할 때 INDIVIDUAL_CALL_LIMIT까지만 부른다.
        var many = new ArrayList<com.ssafy.ddubi.domain.route.model.RouteCandidate>();
        for (int i = 0; i < JourneyEvaluationService.INDIVIDUAL_CALL_LIMIT + 3; i++)
            many.add(MlRoutePreviewTest.route(i, String.valueOf(100 + i), 300, 0, 1));
        var capped = service.compare(many, empty, now, now, now.plusSeconds(1000));
        verify(client, times(JourneyEvaluationService.INDIVIDUAL_CALL_LIMIT)).predict(any());
        assertThat(capped.candidates().stream().filter(c -> c.mlSteps() == 1).count())
                .isEqualTo(JourneyEvaluationService.INDIVIDUAL_CALL_LIMIT);
        assertThat(capped.budgetExhausted()).isFalse();
    }

    @Test
    void mlCallIsNotStartedWhenTheRemainingBudgetCannotCoverOneCall() {
        // 수동 재계산 예산은 10초, 호출 한 건 제한은 7초다. 남은 시간이 7초보다 적으면 호출을 시작하지 않고
        // 기존 fallback 으로 간다. 시작해 버리면 한 건이 전체 예산을 넘겨 과거 평균 계산 시간이 사라진다.
        when(client.timeoutMillis()).thenReturn(7000L);
        when(client.predict(any())).thenReturn(sufficient(30));
        long nowNanos = System.nanoTime();
        var session =
                JourneyEvaluationService.Session.withDeadlines(
                        nowNanos + Duration.ofSeconds(3).toNanos(),
                        nowNanos + Duration.ofSeconds(2).toNanos());
        var result =
                service(MlApplyMode.TEST_APPLY)
                        .compare(
                                List.of(MlRoutePreviewTest.route(0, "1", 300, 10, 1)),
                                empty,
                                now,
                                now,
                                now.plusSeconds(1000),
                                session);

        verify(client, never()).predict(any());
        verify(client, never()).predictBatch(anyList(), any());
        assertThat(result.fastest().steps().get(0).source()).isEqualTo("FALLBACK");
        assertThat(result.fastest().reasons())
                .contains("step-1:ML_BUDGET_EXHAUSTED", "step-1:API_FALLBACK");
        assertThat(result.budgetExhausted()).isFalse();

        // 같은 마감이라도 호출 제한이 짧으면(여유가 충분하면) 호출을 시작한다.
        when(client.timeoutMillis()).thenReturn(500L);
        var roomy =
                JourneyEvaluationService.Session.withDeadlines(
                        System.nanoTime() + Duration.ofSeconds(3).toNanos(),
                        System.nanoTime() + Duration.ofSeconds(2).toNanos());
        service(MlApplyMode.TEST_APPLY)
                .compare(
                        List.of(MlRoutePreviewTest.route(0, "1", 300, 10, 1)),
                        empty,
                        now,
                        now,
                        now.plusSeconds(1000),
                        roomy);
        verify(client, atLeastOnce()).predict(any());
    }

    @Test
    void invalidModeConfigurationFailsFastAndDefaultIsShadow() {
        assertThat(MlApplyMode.parse(null)).isEqualTo(MlApplyMode.SHADOW);
        assertThat(MlApplyMode.parse(" test_apply ")).isEqualTo(MlApplyMode.TEST_APPLY);
        assertThatThrownBy(() -> MlApplyMode.parse("APPLY_EVERYTHING"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testApplyRequiresExplicitTestEnvironmentPermission() {
        // 운영 compose는 허용 값을 전달하지 않는다. 환경변수 하나로 TEST_APPLY가 켜지면 기동이 실패해야 한다.
        assertThatThrownBy(() -> MlApplyMode.resolve("TEST_APPLY", false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MlApplyMode.resolve("TEST_APPLY", true)).isEqualTo(MlApplyMode.TEST_APPLY);
        assertThat(MlApplyMode.resolve(null, false)).isEqualTo(MlApplyMode.SHADOW);
        assertThat(MlApplyMode.resolve("PRODUCTION", false)).isEqualTo(MlApplyMode.PRODUCTION);
    }

    @Test
    void productionNeverAppliesEstimatesWithoutHistorySamples() {
        // history_sample_count=0 이면 data_insufficient 표시가 없어도 운영 계약으로 시간을 바꾸지 않는다.
        var noHistory =
                new BusPreviewClient.Estimate(
                        30.0,
                        "PRODUCTION_CONTRACT",
                        "m1",
                        now,
                        0,
                        0,
                        false,
                        List.of(),
                        false,
                        true,
                        BusPreviewClient.Contract.PRODUCTION);
        assertThat(noHistory.applicationFailure()).isEqualTo("ML_HISTORY_INSUFFICIENT");
        when(client.predictBatch(anyList(), eq(BusPreviewClient.Contract.PRODUCTION)))
                .thenAnswer(
                        call -> {
                            var out =
                                    new LinkedHashMap<
                                            BusPreviewClient.Input, BusPreviewClient.Estimate>();
                            for (BusPreviewClient.Input i :
                                    (List<BusPreviewClient.Input>) call.getArgument(0))
                                out.put(i, noHistory);
                            return out;
                        });
        var result =
                service(MlApplyMode.PRODUCTION)
                        .compare(
                                List.of(MlRoutePreviewTest.route(0, "1", 300, 10, 1)),
                                empty,
                                now,
                                now,
                                now.plusSeconds(1000));
        assertThat(result.fastest().steps().get(0).source()).isEqualTo("FALLBACK");
        assertThat(result.fastest().reasons())
                .contains("step-1:ML_NOT_APPLIED:ML_HISTORY_INSUFFICIENT", "step-1:API_FALLBACK");
        assertThat(result.predictions()).allMatch(p -> !p.applied());
    }
}
