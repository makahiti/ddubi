package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 실제 로컬 HTTP 서버로 Python 계약 직렬화·응답 상관키·실패 처리를 검증한다. 실제 모델 정확도 시험은 아니다. */
class BusPreviewClientTest {
    final ObjectMapper json = new ObjectMapper();
    final BusPreviewClient.Input input = new BusPreviewClient.Input("100","200","300",1,LocalDate.now(ZoneId.of("Asia/Seoul")),9);

    @Test void sendsProviderIdsAndReadsPreviewMetadata() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/bus/predictions:batch", exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            assertThat(request.path("segments").get(0).path("provider_route_id").asText()).isEqualTo("100");
            var response = new LinkedHashMap<String,Object>();
            response.put("request_id",request.path("request_id").asText());
            response.put("model_version","test-v12"); response.put("model_type","lightgbm");
            response.put("target","hourly_mean_bus_segment_seconds"); response.put("production_ready",false);
            response.put("computed_at",Instant.now().toString()); response.put("supported_from",input.date().toString());
            response.put("supported_until",input.date().toString());
            response.put("results",List.of(Map.of("segment_key","edge","status","PREVIEW","reason","MODEL_NOT_PRODUCTION_READY",
                    "prediction",Map.of("mean_seconds",37.5,"missing_feature_count",2,"history_sample_count",4,
                            "data_insufficient",false,"training_ids_verified",false))));
            byte[] bytes = json.writeValueAsBytes(response);
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var value = new BusPreviewClient(json,"http://127.0.0.1:"+server.getAddress().getPort(),1500).predict(input);
            assertThat(value.seconds()).isEqualTo(37.5); assertThat(value.modelVersion()).isEqualTo("test-v12");
            assertThat(value.missingFeatureCount()).isEqualTo(2);
        } finally { server.stop(0); }
    }

    @Test void mismatchedResponseAndHttpFailureAreNotPredictions() throws Exception {
        for (int status : List.of(200,503)) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/v1/bus/predictions:batch", exchange -> {
                byte[] body = "{\"request_id\":\"wrong-request\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,body.length); exchange.getResponseBody().write(body); exchange.close();
            }); server.start();
            try {
                var value = new BusPreviewClient(json,"http://127.0.0.1:"+server.getAddress().getPort(),1500).predict(input);
                assertThat(value.seconds()).isNull();
                assertThat(value.reason()).isEqualTo(status==200?"ML_INVALID_RESPONSE":"ML_HTTP_UNAVAILABLE");
            } finally { server.stop(0); }
        }
        assertThat(new BusPreviewClient(json,"",1500).predict(input).reason()).isEqualTo("ML_NOT_CONFIGURED");
    }
}
