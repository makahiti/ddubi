package com.ssafy.ddubi.domain.route.prediction;

import com.ssafy.ddubi.domain.recommendation.evaluation.BusPreviewClient;
import com.ssafy.ddubi.domain.route.mapping.BusCanonicalPath;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 장애였던 미등록 HTTP 연결, 시간대 이동, 이력 부족과 PREVIEW 승인 경계를 검증한다. */
class BusPreviewPredictionAdapterTest {
    @Test void callsEveryAdjacentEdgeAtItsEntryHourAndSharesOnlyRequestCache() {
        var fixture=new BusJourneyEvaluatorTest();
        var client=mock(BusPreviewClient.class);
        Instant at=Instant.parse("2026-09-17T00:59:40Z");
        when(client.predict(any())).thenReturn(new BusPreviewClient.Estimate(30d,"MODEL_NOT_PRODUCTION_READY",
                "v12",at,0,8,false));
        var adapter=new BusPreviewPredictionAdapter(client);
        var evaluator=new BusJourneyEvaluator(adapter).forRequest();
        var first=evaluator.evaluate(fixture.segment(false),at).get(0);
        evaluator.evaluate(fixture.segment(false),at);
        assertThat(first.predictionReady()).isFalse();
        assertThat(first.estimatedSeconds()).isEqualTo(100);
        assertThat(first.previewEstimatedSeconds()).isEqualTo(60);
        assertThat(first.reason()).isEqualTo("MODEL_NOT_APPROVED");
        assertThat(first.featureVersion()).isNull();
        var requests=ArgumentCaptor.forClass(BusPreviewClient.Input.class);
        verify(client,times(2)).predict(requests.capture());
        assertThat(requests.getAllValues()).extracting(BusPreviewClient.Input::hour).containsExactly(9,10);
        new BusJourneyEvaluator(adapter).forRequest().evaluate(fixture.segment(false),at);
        verify(client,times(4)).predict(any());
    }

    @Test void insufficientHistoryNeverBecomesSuccessfulNumericPrediction() {
        var fixture=new BusJourneyEvaluatorTest(); var client=mock(BusPreviewClient.class);
        when(client.predict(any())).thenReturn(new BusPreviewClient.Estimate(160d,"MODEL_NOT_PRODUCTION_READY",
                "v12",fixture.now,17,0,true));
        var result=new BusJourneyEvaluator(new BusPreviewPredictionAdapter(client)).forRequest()
                .evaluate(fixture.segment(false),fixture.now).get(0);
        assertThat(result.reason()).isEqualTo("ML_HISTORY_INSUFFICIENT");
        assertThat(result.previewEstimatedSeconds()).isNull();
        verify(client,times(1)).predict(any());
    }

    @Test void adapterDoesNotInventFeatureAvailabilityOrValidityForPreview() {
        var fixture=new BusJourneyEvaluatorTest(); var client=mock(BusPreviewClient.class);
        when(client.predict(any())).thenReturn(new BusPreviewClient.Estimate(30d,"MODEL_NOT_PRODUCTION_READY",
                "v12",fixture.now,0,8,false));
        var path=BusCanonicalPath.select(fixture.segment(false).mapping(),0,0);
        var adapter=new BusPreviewPredictionAdapter(client);
        var input=new BusJourneyEvaluator(adapter).input(path,fixture.now);
        var batch=adapter.predict(input).orElseThrow();
        assertThat(batch.featureVersion()).isNull();
        assertThat(batch.featuresAsOf()).isNull();
        assertThat(batch.validUntil()).isNull();
        assertThat(batch.productionReady()).isFalse();
        var falselyApproved=new BusPredictionPort.Batch(batch.modelVersion(),null,batch.inputDigest(),batch.target(),
                true,true,null,batch.computedAt(),null,batch.edges());
        var rejected=new BusJourneyEvaluator(i->java.util.Optional.of(falselyApproved)).evaluate(fixture.segment(false),fixture.now).get(0);
        assertThat(rejected.reason()).isEqualTo("MODEL_RESULT_CONTRACT_MISMATCH");
        assertThat(rejected.previewEstimatedSeconds()).isNull();
    }
}
