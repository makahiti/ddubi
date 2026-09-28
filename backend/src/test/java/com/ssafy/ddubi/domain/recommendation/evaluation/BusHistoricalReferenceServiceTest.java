package com.ssafy.ddubi.domain.recommendation.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.pipeline.service.BusReferenceService;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.service.PublicBusReferenceService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class BusHistoricalReferenceServiceTest {
    final Instant now = Instant.parse("2026-09-17T00:00:00Z");
    final BusReferenceService store = mock(BusReferenceService.class);
    final PublicBusReferenceService publicReferences = mock(PublicBusReferenceService.class);
    final MetropolitanBusMasterService masters = mock(MetropolitanBusMasterService.class);
    final MetropolitanBusCatalog catalog = mock(MetropolitanBusCatalog.class);
    final BusHistoricalReferenceService.Policy policy =
            new BusHistoricalReferenceService.Policy(
                    true, "v1", 1000, 1, 90, 3, 3, 7, "HOURLY_SOURCE_MEAN");

    BusHistoricalReferenceService service() {
        when(store.status("v1"))
                .thenReturn(Map.of("dataVersion", "v1", "totalRows", 1000L, "totalChunks", 1L));
        when(masters.activeVersion()).thenReturn(Optional.of("master"));
        when(catalog.snapshot()).thenReturn(new MetropolitanBusCatalog.Snapshot("master", null));
        return new BusHistoricalReferenceService(store, publicReferences, masters, catalog, policy);
    }

    List<MappedTransitSegment> edges(int count) {
        return MlRoutePreviewTest.route(0, "1", 300, 0, count)
                .segments()
                .get(0)
                .mapping()
                .segments();
    }

    /** 비서울 ID는 canonical component에 출처가 포함되며 실시간 ID를 서울 값으로 꾸미지 않는다. */
    List<MappedTransitSegment> gbisEdges() {
        return List.of(
                new MappedTransitSegment(
                        1,
                        "BUS:GBIS.1:GBIS.10:GBIS.11",
                        com.ssafy.ddubi.domain.route.model.TransportMode.BUS,
                        "GBIS.1",
                        null,
                        "경기 노선",
                        "FORWARD",
                        "GBIS.10",
                        "GBIS.11",
                        null,
                        null,
                        1,
                        2));
    }

    BusReferenceService.Row row(
            BusReferenceService.Query q, double mean, int dates, String kind, LocalDate end) {
        return new BusReferenceService.Row(
                q.masterVersion(),
                q.namespace(),
                q.routeId(),
                q.fromStopId(),
                q.toStopId(),
                q.fromOrder(),
                q.toOrder(),
                q.dow(),
                q.hourSlot(),
                kind,
                mean,
                dates,
                dates,
                end.minusDays(14),
                end,
                "a".repeat(64));
    }

    void normal(double seconds) {
        when(publicReferences.lookup(eq("v1"), any()))
                .thenAnswer(
                        call ->
                                Map.of(
                                        "status",
                                        "STAGED_REFERENCE_NOT_APPROVED",
                                        "rows",
                                        List.of(
                                                row(
                                                        call.getArgument(1),
                                                        seconds,
                                                        3,
                                                        "HOURLY_SOURCE_MEAN",
                                                        LocalDate.of(2026, 8, 27))),
                                        "truncated",
                                        false));
    }

    @Test
    void gtfsFormulaUsesProviderNamespaceAndFeedValidityWithoutInventingObservedDates() {
        var s = service();
        when(publicReferences.lookup(eq("v1"), any()))
                .thenAnswer(
                        call -> {
                            var q = (BusReferenceService.Query) call.getArgument(1);
                            assertEquals("GBIS", q.namespace());
                            assertEquals(1, q.dow()); // 실제 요일이 아니라 GTFS 템플릿 키다.
                            var row =
                                    new BusReferenceService.Row(
                                            q.masterVersion(),
                                            q.namespace(),
                                            q.routeId(),
                                            q.fromStopId(),
                                            q.toStopId(),
                                            q.fromOrder(),
                                            q.toOrder(),
                                            q.dow(),
                                            q.hourSlot(),
                                            "GTFS_SCHEDULE_FORMULA",
                                            71.2,
                                            5,
                                            0,
                                            LocalDate.of(2025, 3, 1),
                                            LocalDate.of(2030, 12, 31),
                                            "b".repeat(64));
                            return Map.of(
                                    "status",
                                    "STAGED_REFERENCE_NOT_APPROVED",
                                    "rows",
                                    List.of(row),
                                    "truncated",
                                    false);
                        });
        var result =
                s.estimate(
                        gbisEdges(),
                        "master",
                        now,
                        0,
                        now,
                        new BusHistoricalReferenceService.Session(),
                        System.nanoTime() + Duration.ofSeconds(20).toNanos());
        assertEquals(72, result.seconds());
        assertEquals("GTFS_FORMULA_APPLIED", result.reason());
        assertEquals("GTFS_SCHEDULE_FORMULA", result.sampleKind());
    }

    @Test
    void formulaInputIsRecalculatedByBackendInsteadOfTrustingCachedMean() {
        var s = service();
        when(publicReferences.lookup(eq("v1"), any()))
                .thenAnswer(
                        call -> {
                            var q = (BusReferenceService.Query) call.getArgument(1);
                            var formula =
                                    new BusTravelTimeFormula.Input(
                                            BusTravelTimeFormula.METROPOLITAN_GTFS,
                                            null,
                                            null,
                                            null,
                                            0L,
                                            80.00032,
                                            1.25,
                                            null);
                            var row =
                                    new BusReferenceService.Row(
                                            q.masterVersion(),
                                            q.namespace(),
                                            q.routeId(),
                                            q.fromStopId(),
                                            q.toStopId(),
                                            q.fromOrder(),
                                            q.toOrder(),
                                            q.dow(),
                                            q.hourSlot(),
                                            "GTFS_SCHEDULE_FORMULA",
                                            100.0,
                                            5,
                                            0,
                                            LocalDate.of(2025, 3, 1),
                                            LocalDate.of(2030, 12, 31),
                                            "b".repeat(64),
                                            formula);
                            return Map.of(
                                    "status",
                                    "STAGED_REFERENCE_NOT_APPROVED",
                                    "rows",
                                    List.of(row),
                                    "truncated",
                                    false);
                        });

        var result =
                s.estimate(
                        gbisEdges(),
                        "master",
                        now,
                        0,
                        now,
                        new BusHistoricalReferenceService.Session(),
                        System.nanoTime() + Duration.ofSeconds(20).toNanos());

        // 계약상 진단용 meanSeconds와 수식 결과의 차이는 0.001초 이내여야 한다.
        // 수식값 100.0004초와 저장값 100.0초는 유효하지만 올림 결과가 달라 재계산 여부를 검증한다.
        assertEquals(101, result.seconds());
        assertEquals("GTFS_FORMULA_APPLIED", result.reason());
    }

    BusHistoricalReferenceService.Estimate estimate(
            BusHistoricalReferenceService s, Instant entered, int count, long uncertainty) {
        return s.estimate(
                edges(count),
                "master",
                entered,
                uncertainty,
                now,
                new BusHistoricalReferenceService.Session(),
                System.nanoTime() + Duration.ofSeconds(20).toNanos());
    }

    @Test
    void completedDatasetGateIsLifetimeCachedAndExactMeanApplied() {
        var s = service();
        s.warmGate();
        normal(45.1);
        assertEquals(92, estimate(s, now, 2, 0).seconds());
        assertEquals(46, estimate(s, now, 1, 0).seconds());
        verify(store, times(1)).status("v1");
    }

    @Test
    void incompleteGateAndDisabledPolicyCannotQueryRows() {
        var s = service();
        when(store.status("v1"))
                .thenReturn(Map.of("dataVersion", "v1", "totalRows", 999L, "totalChunks", 1L));
        assertEquals("HISTORICAL_REFERENCE_DATASET_INCOMPLETE", estimate(s, now, 1, 0).reason());
        estimate(s, now, 1, 0);
        verify(store, times(1)).status("v1");
        verifyNoInteractions(publicReferences);
        var disabled =
                new BusHistoricalReferenceService(
                        store,
                        publicReferences,
                        masters,
                        catalog,
                        new BusHistoricalReferenceService.Policy(
                                false, "v1", 1000, 1, 90, 3, 3, 7, "HOURLY_SOURCE_MEAN"));
        assertEquals("HISTORICAL_REFERENCE_DISABLED", estimate(disabled, now, 1, 0).reason());
    }

    @Test
    void gateStatusExposesResultAndReasonWithoutSecrets() {
        var s = service();
        assertEquals("NOT_EVALUATED", s.gateStatus().get("gateState"));
        assertNull(s.gateStatus().get("gateCheckedAt"));
        s.warmGate();
        var passed = s.gateStatus();
        assertEquals("PASSED", passed.get("gateState"));
        assertNull(passed.get("gateReason"));
        assertNotNull(passed.get("gateCheckedAt"));
        assertEquals(1000L, passed.get("observedRows"));
        assertEquals(1L, passed.get("observedChunks"));
        assertEquals("v1", passed.get("dataVersion"));
        assertEquals(true, passed.get("policyValid"));
        assertEquals(false, passed.get("productionReady"));
        when(store.status("v1"))
                .thenReturn(Map.of("dataVersion", "v1", "totalRows", 999L, "totalChunks", 1L));
        var mismatch =
                new BusHistoricalReferenceService(
                        store, publicReferences, masters, catalog, policy);
        assertEquals(
                "HISTORICAL_REFERENCE_DATASET_INCOMPLETE", estimate(mismatch, now, 1, 0).reason());
        assertEquals("FAILED", mismatch.gateStatus().get("gateState"));
        assertEquals("TOTALS_MISMATCH", mismatch.gateStatus().get("gateReason"));
        assertEquals(999L, mismatch.gateStatus().get("observedRows"));
        when(store.status("v1")).thenThrow(new IllegalStateException("jdbc://secret-host/ddubi"));
        var broken =
                new BusHistoricalReferenceService(
                        store, publicReferences, masters, catalog, policy);
        assertEquals(
                "HISTORICAL_REFERENCE_DATASET_INCOMPLETE", estimate(broken, now, 1, 0).reason());
        assertEquals(
                "STATUS_UNAVAILABLE:IllegalStateException", broken.gateStatus().get("gateReason"));
        assertFalse(broken.gateStatus().toString().contains("secret-host"));
        var invalid =
                new BusHistoricalReferenceService(
                        store,
                        publicReferences,
                        masters,
                        catalog,
                        new BusHistoricalReferenceService.Policy(
                                true, "v1", 0, 0, 90, 3, 3, 7, "HOURLY_SOURCE_MEAN"));
        assertEquals(false, invalid.gateStatus().get("policyValid"));
        assertEquals("HISTORICAL_REFERENCE_POLICY_INVALID", estimate(invalid, now, 1, 0).reason());
    }

    @Test
    void staleSparseWrongKindAndFutureObservedRowsAreNotUsed() {
        var s = service();
        for (var rowCase : List.of("stale", "sparse", "kind", "future")) {
            when(publicReferences.lookup(eq("v1"), any()))
                    .thenAnswer(
                            call ->
                                    Map.of(
                                            "status",
                                            "STAGED_REFERENCE_NOT_APPROVED",
                                            "rows",
                                            List.of(
                                                    row(
                                                            call.getArgument(1),
                                                            60,
                                                            rowCase.equals("sparse") ? 1 : 3,
                                                            rowCase.equals("kind")
                                                                    ? "ACTUAL_TRAVERSAL_MEAN"
                                                                    : "HOURLY_SOURCE_MEAN",
                                                            rowCase.equals("stale")
                                                                    ? LocalDate.of(2026, 5, 1)
                                                                    : rowCase.equals("future")
                                                                            ? LocalDate.of(
                                                                                    2026, 9, 18)
                                                                            : LocalDate.of(
                                                                                    2026, 8,
                                                                                    27)))));
            assertEquals(
                    "HISTORICAL_REFERENCE_SAMPLES_OR_FRESHNESS_INSUFFICIENT",
                    estimate(s, now, 1, 0).reason());
        }
    }

    @Test
    void overlappingPeriodsAreNotAveragedAndAmbiguousLatestIsRejected() {
        var s = service();
        when(publicReferences.lookup(eq("v1"), any()))
                .thenAnswer(
                        call -> {
                            var q = (BusReferenceService.Query) call.getArgument(1);
                            return Map.of(
                                    "status",
                                    "STAGED_REFERENCE_NOT_APPROVED",
                                    "rows",
                                    List.of(
                                            row(
                                                    q,
                                                    30,
                                                    3,
                                                    "HOURLY_SOURCE_MEAN",
                                                    LocalDate.of(2026, 8, 27)),
                                            row(
                                                    q,
                                                    60,
                                                    3,
                                                    "HOURLY_SOURCE_MEAN",
                                                    LocalDate.of(2026, 8, 27))));
                        });
        assertEquals("HISTORICAL_REFERENCE_AMBIGUOUS_PERIOD", estimate(s, now, 1, 0).reason());
    }

    @Test
    void kstHourAdvancesAndUncertainHourOrForecastRangeFallback() {
        var s = service();
        normal(45);
        Instant start = Instant.parse("2026-09-17T00:59:30Z");
        assertEquals(90, estimate(s, start, 2, 0).seconds());
        var capture = org.mockito.ArgumentCaptor.forClass(BusReferenceService.Query.class);
        verify(publicReferences, times(2)).lookup(eq("v1"), capture.capture());
        assertEquals(
                List.of(9, 10),
                capture.getAllValues().stream().map(BusReferenceService.Query::hourSlot).toList());
        assertEquals(
                "HISTORICAL_REFERENCE_ENTRY_HOUR_UNCERTAIN", estimate(s, start, 1, 60).reason());
        assertEquals(
                "HISTORICAL_REFERENCE_FORECAST_DATE_UNSUPPORTED",
                estimate(s, now.plus(Duration.ofDays(8)), 1, 0).reason());
    }

    @Test
    void changedMasterRejectsWithoutReferenceLookup() {
        var s = service();
        when(masters.activeVersion()).thenReturn(Optional.of("other"));
        assertEquals(
                "HISTORICAL_REFERENCE_MASTER_VERSION_MISMATCH", estimate(s, now, 1, 0).reason());
        verifyNoInteractions(publicReferences);
    }

    @Test
    void actualJourneyUsesHistoricalMeanAfterMlFailure() {
        var history = service();
        normal(45);
        var ml = mock(BusPreviewClient.class);
        when(ml.predict(any()))
                .thenReturn(BusPreviewClient.Estimate.unavailable("ML_NOT_CONFIGURED"));
        var evaluator =
                new JourneyEvaluationService(
                        ml,
                        new RouteEvidenceAssembler(
                                mock(StringRedisTemplate.class), new ObjectMapper()),
                        new RouteTimelineEvaluator(),
                        mock(SubwayTimingService.class),
                        new BusWaitingService(
                                mock(StringRedisTemplate.class), new ObjectMapper(), false),
                        history);
        var route = MlRoutePreviewTest.route(0, "1", 300, 10, 2);
        var result =
                evaluator.compare(
                        List.of(route),
                        new RouteEvidenceAssembler.Snapshot(Map.of(), List.of()),
                        now,
                        now,
                        now.plusSeconds(1000));
        // 두 edge의 역사 기준값 45초씩에 provider가 step으로 분해하지 못한 10초를 보존한다.
        assertEquals(now.plusSeconds(100), result.fastest().estimatedArrivalAt());
        assertEquals("MIXED_PREVIEW", result.candidates().get(0).quality());
        assertEquals(0, result.candidates().get(0).mlSteps());
        assertEquals("BUS_HISTORICAL_REFERENCE", result.fastest().steps().get(0).source());
        assertTrue(result.fastest().reasons().contains("step-1:HISTORICAL_REFERENCE_APPLIED"));
        assertFalse(result.productionReady());
        assertTrue(result.predictions().stream().noneMatch(MlRoutePreview.Prediction::applied));
    }

    @Test
    void mlBudgetExhaustionUsesHistoricalFormulaBeforeProviderStep() {
        var history = service();
        normal(45);
        var ml = mock(BusPreviewClient.class);
        when(ml.predict(any()))
                .thenReturn(new BusPreviewClient.Estimate(50.0, null, "v12", now, 0, 10, false));
        var evaluator =
                new JourneyEvaluationService(
                        ml,
                        new RouteEvidenceAssembler(
                                mock(StringRedisTemplate.class), new ObjectMapper()),
                        new RouteTimelineEvaluator(),
                        mock(SubwayTimingService.class),
                        new BusWaitingService(
                                mock(StringRedisTemplate.class), new ObjectMapper(), false),
                        history);
        var route = MlRoutePreviewTest.route(0, "1", 300, 10, 2);
        // ML 예산이 소진돼도 남은 전체 예산으로 서울 실측 기준값을 조회한다.
        long nowNanos = System.nanoTime();
        var session =
                JourneyEvaluationService.Session.withDeadlines(
                        nowNanos + Duration.ofSeconds(20).toNanos(), nowNanos - 1);
        var result =
                evaluator.compare(
                        List.of(route),
                        new RouteEvidenceAssembler.Snapshot(Map.of(), List.of()),
                        now,
                        now,
                        now.plusSeconds(1000),
                        session);
        verify(ml, never()).predict(any());
        assertEquals("BUS_HISTORICAL_REFERENCE", result.fastest().steps().get(0).source());
        // ML 예산 소진 fallback에서도 provider 미분류 10초를 버리지 않는다.
        assertEquals(now.plusSeconds(100), result.fastest().estimatedArrivalAt());
        assertTrue(result.fastest().reasons().contains("step-1:ML_BUDGET_EXHAUSTED"));
        assertTrue(result.fastest().reasons().contains("step-1:HISTORICAL_REFERENCE_APPLIED"));
        assertFalse(result.budgetExhausted()); // 보조 ML 실패와 전체 후보 탐색 실패를 구별한다.
        // 기본 세션은 ML 마감이 전체 마감보다 앞선다. 전체 예산은 그대로다.
        var normal = evaluator.newSession(5000);
        assertTrue(normal.mlDeadline < normal.deadline);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new JourneyEvaluationService(
                                ml,
                                new RouteEvidenceAssembler(
                                        mock(StringRedisTemplate.class), new ObjectMapper()),
                                new RouteTimelineEvaluator(),
                                mock(SubwayTimingService.class),
                                new BusWaitingService(
                                        mock(StringRedisTemplate.class), new ObjectMapper(), false),
                                history,
                                1.5));
    }

    @Test
    void referenceServiceDoesNotOverrideProviderFallback() {
        var history = service();
        var ml = mock(BusPreviewClient.class);
        when(ml.predict(any()))
                .thenReturn(BusPreviewClient.Estimate.unavailable("ML_NOT_CONFIGURED"));
        var evaluator =
                new JourneyEvaluationService(
                        ml,
                        new RouteEvidenceAssembler(
                                mock(StringRedisTemplate.class), new ObjectMapper()),
                        new RouteTimelineEvaluator(),
                        mock(SubwayTimingService.class),
                        new BusWaitingService(
                                mock(StringRedisTemplate.class), new ObjectMapper(), false),
                        history);
        var route = MlRoutePreviewTest.route(0, "1", 300, 10, 1);
        var snapshot = new RouteEvidenceAssembler.Snapshot(Map.of(), List.of());
        // 데이터 없음: 정확키 조회가 비어 있다(REFERENCE_MISSING). 예산 문제가 아니다.
        when(publicReferences.lookup(eq("v1"), any()))
                .thenReturn(
                        Map.of(
                                "status",
                                "REFERENCE_MISSING",
                                "rows",
                                List.of(),
                                "truncated",
                                false));
        var missing = evaluator.compare(List.of(route), snapshot, now, now, now.plusSeconds(1000));
        assertTrue(missing.fastest().reasons().contains("step-1:ML_NOT_APPLIED:ML_NOT_CONFIGURED"));
        assertTrue(missing.fastest().reasons().contains("step-1:API_FALLBACK"));
        assertFalse(
                missing.fastest().reasons().stream().anyMatch(r -> r.contains("BUDGET_EXHAUSTED")));
        // 예산 소진: 자료는 있지만 마감이 지났다. 데이터 없음으로 표시하지 않는다.
        normal(45);
        long nowNanos = System.nanoTime();
        var exhausted =
                evaluator.compare(
                        List.of(route),
                        snapshot,
                        now,
                        now,
                        now.plusSeconds(1000),
                        JourneyEvaluationService.Session.withDeadlines(nowNanos - 1, nowNanos - 1));
        assertFalse(
                exhausted.fastest().reasons().stream()
                        .anyMatch(r -> r.contains("EXACT_KEY_UNAVAILABLE")));
        assertEquals("FALLBACK", exhausted.fastest().steps().get(0).source());
    }

    @Test
    void partialReferenceFailureRestoresEntireApiRide() {
        var history = service();
        normal(45);
        when(publicReferences.lookup(eq("v1"), argThat(q -> q.fromOrder() == 2)))
                .thenReturn(Map.of("status", "REFERENCE_MISSING", "rows", List.of()));
        var ml = mock(BusPreviewClient.class);
        when(ml.predict(any()))
                .thenReturn(BusPreviewClient.Estimate.unavailable("ML_NOT_CONFIGURED"));
        var evaluator =
                new JourneyEvaluationService(
                        ml,
                        new RouteEvidenceAssembler(
                                mock(StringRedisTemplate.class), new ObjectMapper()),
                        new RouteTimelineEvaluator(),
                        mock(SubwayTimingService.class),
                        new BusWaitingService(
                                mock(StringRedisTemplate.class), new ObjectMapper(), false),
                        history);
        var result =
                evaluator.compare(
                        List.of(MlRoutePreviewTest.route(0, "1", 300, 0, 2)),
                        new RouteEvidenceAssembler.Snapshot(Map.of(), List.of()),
                        now,
                        now,
                        now.plusSeconds(1000));
        assertEquals(now.plusSeconds(300), result.fastest().estimatedArrivalAt());
        assertEquals("API_ONLY", result.candidates().get(0).quality());
    }
}
