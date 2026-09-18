package com.ssafy.ddubi.domain.route.validation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * -Dmapping.corpus로 지정한 로컬 JSON을 Replay하고 target에 결과를 남기는 실행 진입점이다.
 * 일반 CI에서는 명시적으로 skip한다. API 키·DB·Redis·네트워크가 필요 없고 서비스 상태를
 * 변경하지 않는다. SHA-256으로 정확한 입력을 고정하며 오판이 있어도 보고서를 먼저 보존한다.
 * 빌드 성공은 운영 승인 의미가 아니다. 미검수 범위와 표본 대표성은 별도 검토해야 한다.
 */
@EnabledIfSystemProperty(named = "mapping.corpus", matches = ".+")
class MappingCorpusReplayTest {
    @Test
    void evaluateProvidedCorpus() throws Exception {
        var json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        byte[] bytes = Files.readAllBytes(Path.of(System.getProperty("mapping.corpus")));
        var corpus = json.readValue(bytes, MappingCorpusEvaluator.Corpus.class);
        var report = MappingCorpusEvaluator.evaluate(corpus);
        var output = json.valueToTree(report);
        ((com.fasterxml.jackson.databind.node.ObjectNode) output).put("corpusSha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        Path target = Path.of("target/mapping-validation/corpus-report.json");
        Files.createDirectories(target.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), output);
        // 오판을 성공 종료로 숨기지 않지만, 미검수 EXACT는 틀렸다고도 맞았다고도 단정하지 않는다.
        assertThat(report.rows()).as("정답과 다른 판정 또는 edge: %s", target)
                .noneMatch(row -> Boolean.FALSE.equals(row.correct()));
    }
}
