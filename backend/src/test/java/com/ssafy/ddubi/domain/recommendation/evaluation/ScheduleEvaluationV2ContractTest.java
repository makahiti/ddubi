package com.ssafy.ddubi.domain.recommendation.evaluation;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 고정시각 실제 DTO 직렬화 산출물을 FE용 JSON Schema 검증 단계에 전달한다. */
class ScheduleEvaluationV2ContractTest {
    @Test
    void allDecisionsProduceDeterministicConsumerFixtures() throws Exception {
        var f = new ConditionalDepartureEvaluatorTest();
        var scenarios = new LinkedHashMap<String, ConditionalDepartureEvaluator.Evaluation>();
        scenarios.put(
                "KEEP_DEPARTURE",
                f.engine(256)
                        .evaluate(
                                1,
                                f.now,
                                f.p,
                                f.d,
                                List.of(),
                                at -> f.comparison(at, f.d.minusSeconds(1))));
        scenarios.put(
                "ADVANCE_DEPARTURE",
                f.engine(256)
                        .evaluate(
                                1,
                                f.now,
                                f.p,
                                f.d,
                                List.of(),
                                at -> f.comparison(at, at.plusSeconds(1800))));
        scenarios.put(
                "MARGIN_UNAVAILABLE",
                f.engine(256)
                        .evaluate(
                                1,
                                f.now,
                                f.p,
                                f.d,
                                List.of(),
                                at ->
                                        f.comparison(
                                                at, at.equals(f.p) ? f.d : f.d.minusSeconds(1))));
        scenarios.put(
                "LATE_EXPECTED",
                f.engine(256)
                        .evaluate(
                                1,
                                f.now,
                                f.p,
                                f.d,
                                List.of(),
                                at -> f.comparison(at, at.plusSeconds(4000))));
        scenarios.put(
                "DATA_INSUFFICIENT",
                f.engine(256)
                        .evaluate(1, f.now, f.p, f.d, List.of(), at -> f.comparison(at, null)));
        scenarios.put(
                "SEARCH_INCOMPLETE",
                f.engine(2)
                        .evaluate(
                                1,
                                f.now,
                                f.p,
                                f.d,
                                List.of(),
                                at -> f.comparison(at, f.d.plusSeconds(20))));
        scenarios.put(
                "DEPART_NOW",
                f.engine(256)
                        .evaluate(
                                1,
                                f.now,
                                f.now.minusSeconds(60),
                                f.d,
                                List.of(),
                                at -> f.comparison(at, at.plusSeconds(600))));
        scenarios.put(
                "ROUTE_UNAVAILABLE",
                f.engine(256)
                        .evaluate(
                                0,
                                f.now,
                                f.p,
                                f.d,
                                List.of(),
                                at ->
                                        new MlRoutePreview.Comparison(
                                                "PREVIEW", false, null, List.of(), List.of(), false,
                                                List.of(), List.of())));
        var json =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Path directory = Path.of("target/conditional-departure-fixtures");
        Files.createDirectories(directory);
        for (var scenario : scenarios.entrySet()) {
            assertThat(scenario.getValue().decision().name()).isEqualTo(scenario.getKey());
            Instant planned = scenario.getKey().equals("DEPART_NOW") ? f.now.minusSeconds(60) : f.p;
            var dto =
                    ScheduleEvaluationV2.from(
                            scenario.getValue(),
                            "synthetic-" + scenario.getKey(),
                            null,
                            null,
                            0,
                            "READ_ONLY",
                            planned,
                            planned,
                            f.d,
                            f.now,
                            f.now.plusSeconds(60),
                            null,
                            null,
                            null,
                            List.of("SYNTHETIC_FIXTURE"),
                            null,
                            List.of());
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(
                            directory.resolve(scenario.getKey().toLowerCase() + ".json").toFile(),
                            dto);
        }
        for (String state :
                List.of("PENDING", "RUNNING", "FAILED", "STOPPED", "DEPARTURE_REQUIRED"))
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(
                            directory.resolve(state.toLowerCase() + ".json").toFile(),
                            Map.of("schemaVersion", "schedule-evaluation-v2", "state", state));
    }

    @Test
    void readonlyVariantPreservesNullIdsAndNoBuffer() throws Exception {
        var fixture = new ConditionalDepartureEvaluatorTest();
        var result =
                fixture.engine(256)
                        .evaluate(
                                1,
                                fixture.now,
                                fixture.p,
                                fixture.d,
                                List.of(),
                                at -> fixture.comparison(at, fixture.d.minusSeconds(1)));
        var dto =
                ScheduleEvaluationV2.from(
                        result,
                        "fixture-evaluation",
                        null,
                        null,
                        0,
                        "READ_ONLY",
                        fixture.p,
                        fixture.p,
                        fixture.d,
                        fixture.now,
                        fixture.now.plusSeconds(60),
                        null,
                        null,
                        null,
                        List.of("NO_VERIFIED_SEGMENT_MAPPING"),
                        null,
                        List.of());
        ObjectMapper json =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var value = json.valueToTree(dto);
        assertThat(value.path("schemaVersion").asText()).isEqualTo("schedule-evaluation-v2");
        assertThat(value.path("decision").asText()).isEqualTo("KEEP_DEPARTURE");
        assertThat(value.path("scheduleId").isNull()).isTrue();
        assertThat(value.path("inputVersion").isNull()).isTrue();
        assertThat(value.path("targetArriveAt").asText()).isEqualTo("2026-09-16T10:00:00");
        assertThat(value.path("appliedBufferSeconds").asInt()).isZero();
        assertThat(value.path("quality").path("productionEligible").asBoolean()).isFalse();
        Path file = Path.of("target/conditional-departure-readonly.json");
        Files.createDirectories(file.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), dto);
    }

    @Test
    void userFacingClockFieldsUseKoreanLocalTimeWhileTechnicalTimestampsStayUtc() {
        var fixture = new ConditionalDepartureEvaluatorTest();
        var result =
                fixture.engine(256)
                        .evaluate(
                                1,
                                fixture.now,
                                fixture.p,
                                fixture.d,
                                List.of(),
                                at -> fixture.comparison(at, fixture.d.minusSeconds(1)));
        var dto =
                ScheduleEvaluationV2.from(
                        result,
                        "kst-contract",
                        null,
                        null,
                        0,
                        "READ_ONLY",
                        fixture.p,
                        fixture.p,
                        fixture.d,
                        fixture.now,
                        fixture.now.plusSeconds(60),
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        List.of());
        ObjectMapper json =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        JsonNode value = json.valueToTree(dto);

        // 일정·추천 시각은 ScheduleResponse와 동일한 KST LocalDateTime 계약이다. UTC Z가 섞이면
        // 홈 요약처럼 시각만 읽는 소비자가 9시간 이른 값으로 표시할 수 있다.
        assertThat(value.path("plannedDepartAt").asText()).doesNotEndWith("Z");
        assertThat(value.path("arriveBy").asText()).doesNotEndWith("Z");
        assertThat(value.path("proposedDepartAt").asText()).doesNotEndWith("Z");
        assertThat(value.path("estimatedArrivalAt").asText()).doesNotEndWith("Z");
        assertThat(value.path("candidateEstimates").get(0).path("departAt").asText())
                .doesNotEndWith("Z");

        // 만료·재평가 시각은 순간을 나타내는 운영 필드라 UTC Instant 계약을 유지한다.
        assertThat(value.path("computedAt").asText()).endsWith("Z");
        assertThat(value.path("validUntil").asText()).endsWith("Z");
    }

    @Test
    void proposedRouteKeepsKakaoFareForGuideConsumers() {
        var fixture = new ConditionalDepartureEvaluatorTest();
        var result =
                fixture.engine(256)
                        .evaluate(
                                1,
                                fixture.now,
                                fixture.p,
                                fixture.d,
                                List.of(),
                                at -> fixture.comparison(at, fixture.d.minusSeconds(1)));
        var route =
                new com.ssafy.ddubi.domain.route.model.RouteCandidate(
                        0,
                        com.ssafy.ddubi.domain.route.model.RouteType.SUBWAY,
                        1000,
                        1200,
                        0,
                        new com.ssafy.ddubi.domain.route.model.FareRange(1550, 1650),
                        0,
                        0,
                        List.of());

        var dto =
                ScheduleEvaluationV2.from(
                        result,
                        "fare-contract",
                        null,
                        null,
                        0,
                        "READ_ONLY",
                        fixture.p,
                        fixture.p,
                        fixture.d,
                        fixture.now,
                        fixture.now.plusSeconds(60),
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(route));

        assertThat(dto.fare().minimumWon()).isEqualTo(1550);
        assertThat(dto.fare().maximumWon()).isEqualTo(1650);
    }
}
