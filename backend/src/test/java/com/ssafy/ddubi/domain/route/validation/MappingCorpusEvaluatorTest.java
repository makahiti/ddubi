package com.ssafy.ddubi.domain.route.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * 평가 도구가 미검수·오판·실시간 대응 누락을 성공 통계로 숨기지 않는지 검사한다.
 * 모든 정류장과 ID는 합성값이며, 실제 서울 정확도를 증명하는 데이터로 사용하지 않는다.
 * 실제 사용자가 준 corpus는 별도 MappingCorpusReplayTest에서 같은 운영 코드를 실행한다.
 */
class MappingCorpusEvaluatorTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode input() throws Exception {
        return (ObjectNode) json.readTree(Path.of("src/test/resources/fixtures/mapping/synthetic-corpus.json").toFile());
    }

    private MappingCorpusEvaluator.Report evaluate(ObjectNode input) throws Exception {
        return MappingCorpusEvaluator.evaluate(json.treeToValue(input, MappingCorpusEvaluator.Corpus.class));
    }

    @Test
    void user422EndpointExcerptNeverIssuesUnprovenCanonicalEdges() throws Exception {
        // 과거 150m 정책용 발췌본도 새 50m/전체 순서 정책으로 재평가한다.
        // 부분 자료만으로 EXACT를 만들지 않는 것이 회귀 기준이며 과거 상태명을 강제하지 않는다.
        var corpus = json.readValue(Path.of("src/test/resources/fixtures/mapping/user-422-endpoint-excerpt.json").toFile(),
                MappingCorpusEvaluator.Corpus.class);
        var result = MappingCorpusEvaluator.evaluate(corpus);
        var bus = result.rows().stream().filter(row -> row.mode().name().equals("BUS")).findFirst().orElseThrow();
        assertThat(bus.status().name()).isEqualTo("PARTIAL");
        assertThat(bus.failureReason()).isEqualTo("BUS_ALTERNATIVES_REQUIRE_SELECTION_OR_CANONICAL_CROSSWALK");
        assertThat(bus.actualSegmentIds()).isEmpty();
        assertThat(result.metrics().reviewedExactPrecision()).isNull();
        assertThat(result.missingCaptureTimestampCount()).isEqualTo(1);
    }

    @Test
    void reportsUnknownCaptureTimeWithoutInventingFreshness() throws Exception {
        var input = input();
        ((ObjectNode) input.at("/captures/0")).putNull("capturedAt");
        var result = evaluate(input);
        assertThat(result.missingCaptureTimestampCount()).isEqualTo(1);
        assertThat(result.metrics().exactSteps()).isEqualTo(2);
        assertThat(result).isEqualTo(evaluate(input));
    }

    @Test
    void replaysProductionMapperAndSeparatesTopologyFromRealtimeIds() throws Exception {
        var input = input();
        var result = evaluate(input);
        assertThat(result).isEqualTo(evaluate(input));
        assertThat(result.evidenceStatus()).isEqualTo("SYNTHETIC_ONLY");
        assertThat(result.metrics().transitSteps()).isEqualTo(2);
        assertThat(result.metrics().reviewedExactPrecision()).isEqualTo(1.0);
        assertThat(result.metrics().exactWithFullRealtimeIds()).isEqualTo(1);
        assertThat(result.rows().get(0).actualSegmentIds()).containsExactly("BUS:R1:A:B", "BUS:R1:B:C");
        assertThat(result.rows().get(1).realtimeCoverage().name()).isEqualTo("NONE");
        assertThat(result.allExactCandidates()).isEqualTo(1);
        Files.createDirectories(Path.of("target/mapping-validation"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/mapping-validation/synthetic-report.json").toFile(), result);
    }

    @Test
    void doesNotReportUnreviewedExactAsProvenPrecision() throws Exception {
        var input = input();
        input.put("kind", "REAL"); // 지표 분기의 단위 테스트일 뿐 파일은 합성이다.
        ((ObjectNode) input.withArray("captures").get(0)).putArray("labels");
        var result = evaluate(input);
        assertThat(result.evidenceStatus()).isEqualTo("REVIEW_INCOMPLETE");
        assertThat(result.metrics().reviewedExactPrecision()).isNull();
        assertThat(result.metrics().unreviewedExactSteps()).isEqualTo(2);
        assertThat(result.metrics().reviewCoverage()).isZero();
        assertThat(result.allReviewedCorrectCandidates()).isZero();
    }

    @Test
    void wrongIntermediateEdgeCountsAsFalseExactDespiteMatchingEndpoints() throws Exception {
        var input = input();
        var label = (ObjectNode) input.at("/captures/0/labels/0");
        label.putArray("expectedSegmentIds").add("BUS:R1:A:X").add("BUS:R1:X:C");
        var result = evaluate(input);
        assertThat(result.metrics().falseExactSteps()).isEqualTo(1);
        assertThat(result.metrics().reviewedExactPrecision()).isEqualTo(0.5);
        assertThat(result.allReviewedCorrectCandidates()).isZero();
    }

    @Test
    void missingBusCoordinatesReducesCoverageAndDoesNotHideTheStep() throws Exception {
        var input = input();
        ((ObjectNode) input.at("/captures/0/response/routes/0/steps/0")).remove("path");
        var result = evaluate(input);
        assertThat(result.metrics().transitSteps()).isEqualTo(2);
        assertThat(result.metrics().exactCoverage()).isEqualTo(0.5);
        assertThat(result.allExactCandidates()).isZero();
        assertThat(result.rows().get(0).actualSegmentIds()).isEmpty();
    }

    @Test
    void rejectsDuplicateOrNonexistentLabelPositions() throws Exception {
        var duplicate = input();
        var capture = (ObjectNode) duplicate.at("/captures/0");
        capture.withArray("labels").add(capture.withArray("labels").get(0).deepCopy());
        assertThatThrownBy(() -> evaluate(duplicate)).isInstanceOf(IllegalArgumentException.class);
        var nonexistent = input();
        ((ObjectNode) nonexistent.at("/captures/0/labels/0")).put("stepSequence", 99);
        assertThatThrownBy(() -> evaluate(nonexistent)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAbsentReviewerAndOffsetlessCaptureTime() throws Exception {
        var input = input();
        ((ObjectNode) input.at("/captures/0/labels/0")).put("reviewedBy", "");
        assertThatThrownBy(() -> evaluate(input)).isInstanceOf(IllegalArgumentException.class);
        var missingOffset = input();
        ((ObjectNode) missingOffset.at("/captures/0")).put("capturedAt", "2026-09-07T10:00:00");
        assertThatThrownBy(() -> evaluate(missingOffset)).isInstanceOf(java.time.format.DateTimeParseException.class);
    }

    @Test
    void separatesOutsideSeoulAndRejectsDuplicateCases() throws Exception {
        var input = input();
        var outside = input.withArray("captures").get(0).deepCopy();
        ((ObjectNode) outside).put("caseId", "outside-synthetic").put("region", "OUTSIDE_SEOUL");
        input.withArray("captures").add(outside);
        var result = evaluate(input);
        assertThat(result.byRegionAndMode()).containsKeys("SEOUL/BUS", "OUTSIDE_SEOUL/BUS");
        input.withArray("captures").add(outside.deepCopy());
        assertThatThrownBy(() -> evaluate(input)).isInstanceOf(IllegalArgumentException.class);
    }
}
