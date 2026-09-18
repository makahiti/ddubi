package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;

/** 명시한 로컬 v12 서버에서 독립 golden 표본을 대조한다. 실제 사용자 경로 매핑 정확도를 뜻하지 않는다. */
@EnabledIfSystemProperty(named="ddubi.ml.smoke-url", matches="http://127\\.0\\.0\\.1:[0-9]+")
class BusPreviewLiveModelTest {
    @Test void actualPythonModelMatchesTwoPublishedGoldenPredictions() {
        var client = new BusPreviewClient(new ObjectMapper(), System.getProperty("ddubi.ml.smoke-url"), 5000);
        var first = client.predict(new BusPreviewClient.Input("100100001","100000004","101000001",23,LocalDate.parse("2026-09-10"),9));
        var second = client.predict(new BusPreviewClient.Input("100100001","100000023","100000415",16,LocalDate.parse("2026-09-10"),9));
        assertThat(first.reason()).isEqualTo("MODEL_NOT_PRODUCTION_READY");
        assertThat(first.seconds()).isCloseTo(145.1247447449537,within(0.000001));
        assertThat(second.seconds()).isCloseTo(340.31162658466326,within(0.000001));
        assertThat(first.modelVersion()).isEqualTo("20260908-v12");
    }
}
