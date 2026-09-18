package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 실제 로컬 HTTP 서버로 Python 계약 직렬화·응답 상관키·실패 처리를 검증한다. 실제 모델 정확도 시험은 아니다. */
class BusPreviewClientTest {
    @Test void numericPredictionWithMissingHistoryIsNotAnApplicableEstimate() {
        // 실제 모델이 숫자를 반환해도 과거 관측이 부족하면 승인 상태와 다른 실패 이유를 보존한다.
        var estimate = new BusPreviewClient.Estimate(160.74, "MODEL_NOT_PRODUCTION_READY",
                "v12", Instant.now(), 17, 0, true);
        assertThat(estimate.applicationFailure()).isEqualTo("ML_HISTORY_INSUFFICIENT");
        assertThat(estimate.seconds()).isEqualTo(160.74);
        assertThat(estimate.numeric()).isTrue();
        assertThat(estimate.productionEligible()).isFalse();
        assertThat(estimate.contract()).isEqualTo(BusPreviewClient.Contract.PREVIEW);
        assertThat(estimate.missingFeatures()).isNull();
        assertThat(BusPreviewClient.Estimate.unavailable("REFERENCE_DATE_UNSUPPORTED").applicationFailure())
                .isEqualTo("REFERENCE_DATE_UNSUPPORTED");
    }
    final ObjectMapper json = new ObjectMapper();
    final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    final BusPreviewClient.Input input = new BusPreviewClient.Input("100","200","300",1,today,9);

    /** 요청 본문(원시 바이트)과 파싱된 요청을 받아 응답 객체를 만드는 stub 서버. */
    HttpServer serve(BiFunction<byte[], JsonNode, Object> responder, long delayMillis) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/bus/predictions:batch", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            try { if (delayMillis > 0) Thread.sleep(delayMillis); } catch (InterruptedException ignored) { }
            Object response = responder.apply(raw, json.readTree(raw));
            byte[] bytes = response instanceof byte[] b ? b : json.writeValueAsBytes(response);
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        return server;
    }
    BusPreviewClient client(HttpServer server, int timeoutMs) { return new BusPreviewClient(json,"http://127.0.0.1:"+server.getAddress().getPort(),timeoutMs); }

    Map<String,Object> envelope(JsonNode request, boolean productionReady, List<Map<String,Object>> results) {
        var response = new LinkedHashMap<String,Object>();
        response.put("request_id",request.path("request_id").asText());
        response.put("model_version","test-v12"); response.put("model_type","lightgbm");
        response.put("target","hourly_mean_bus_segment_seconds"); response.put("production_ready",productionReady);
        response.put("computed_at",Instant.now().toString()); response.put("supported_from",today.toString());
        response.put("supported_until",today.toString()); response.put("results",results);
        return response;
    }
    Map<String,Object> preview(String key, double seconds, Map<String,Object> extra) {
        var prediction = new LinkedHashMap<String,Object>(Map.of("mean_seconds",seconds,"missing_feature_count",2,"history_sample_count",4,
                "data_insufficient",false,"training_ids_verified",false,"missing_features",List.of("history_recent_seconds","passenger_recent")));
        prediction.putAll(extra);
        return Map.of("segment_key",key,"status","PREVIEW","reason","MODEL_NOT_PRODUCTION_READY","prediction",prediction);
    }

    @Test void sendsProviderIdsAndReadsPreviewMetadataIncludingMissingFeatureNames() throws Exception {
        var server = serve((raw, request) -> {
            assertThat(request.path("segments").get(0).path("provider_route_id").asText()).isEqualTo("100");
            assertThat(request.path("segments").get(0).path("segment_key").asText()).isEqualTo("e0");
            return envelope(request,false,List.of(preview("e0",37.5,Map.of())));
        }, 0);
        try {
            var value = client(server,1500).predict(input);
            assertThat(value.seconds()).isEqualTo(37.5); assertThat(value.modelVersion()).isEqualTo("test-v12");
            assertThat(value.missingFeatureCount()).isEqualTo(2);
            assertThat(value.missingFeatures()).containsExactly("history_recent_seconds","passenger_recent");
            assertThat(value.missingFeaturesTruncated()).isFalse();
            assertThat(value.trainingIdsVerified()).isFalse();
            assertThat(value.contract()).isEqualTo(BusPreviewClient.Contract.PREVIEW);
            assertThat(value.applicationFailure()).isNull();
        } finally { server.stop(0); }
    }

    @Test void missingFeatureNamesAreBoundedAndAbsentFieldIsDistinctFromEmptyArray() throws Exception {
        var many = new ArrayList<String>(); for (int i = 0; i < 40; i++) many.add("feature_" + i);
        var cases = new ArrayList<Map<String,Object>>();
        cases.add(Map.of("missing_feature_count",40,"missing_features",many));          // 32개로 자르고 truncated 표시
        cases.add(Map.of("missing_feature_count",0,"missing_features",List.of()));       // 빈 배열
        cases.add(Map.of("missing_feature_count",3,"missing_features",List.of("a","b",7)));// 문자열 아님 → 잘못된 응답
        cases.add(Map.of("missing_feature_count",1,"missing_features",List.of("a","b"))); // 개수 불일치 → 잘못된 응답
        for (int i = 0; i < cases.size(); i++) {
            var extra = cases.get(i);
            var server = serve((raw, request) -> envelope(request,false,List.of(preview("e0",20,extra))), 0);
            try {
                var value = client(server,1500).predict(input);
                switch (i) {
                    case 0 -> { assertThat(value.missingFeatures()).hasSize(32); assertThat(value.missingFeaturesTruncated()).isTrue(); assertThat(value.missingFeatureCount()).isEqualTo(40); }
                    case 1 -> { assertThat(value.missingFeatures()).isEmpty(); assertThat(value.missingFeaturesTruncated()).isFalse(); }
                    default -> { assertThat(value.seconds()).isNull(); assertThat(value.reason()).isEqualTo("ML_INVALID_RESPONSE"); }
                }
            } finally { server.stop(0); }
        }
        // 필드 자체가 없으면 null(알 수 없음)로 남긴다. 빈 배열과 구분된다.
        var server = serve((raw, request) -> {
            var prediction = new LinkedHashMap<String,Object>(Map.of("mean_seconds",20.0,"missing_feature_count",5,"history_sample_count",4,"data_insufficient",false,"training_ids_verified",false));
            return envelope(request,false,List.of(Map.of("segment_key","e0","status","PREVIEW","reason","MODEL_NOT_PRODUCTION_READY","prediction",prediction)));
        }, 0);
        try {
            var value = client(server,1500).predict(input);
            assertThat(value.seconds()).isEqualTo(20.0); assertThat(value.missingFeatures()).isNull(); assertThat(value.missingFeaturesTruncated()).isNull();
        } finally { server.stop(0); }
    }

    @Test void batchMapsResultsBySegmentKeyDeduplicatesInputsAndKeepsPartialFailures() throws Exception {
        var a = new BusPreviewClient.Input("100","200","300",1,today,9);
        var b = new BusPreviewClient.Input("100","300","400",2,today,9);
        var c = new BusPreviewClient.Input("200","1","2",1,today,10);
        var calls = new ArrayList<Integer>();
        var server = serve((raw, request) -> {
            calls.add(request.path("segments").size());
            // 응답 순서를 뒤집고 하나는 UNAVAILABLE로 준다. 배열 순서가 아니라 segment_key로 연결해야 한다.
            return envelope(request,false,List.of(preview("e2",50,Map.of()),
                    Map.of("segment_key","e1","status","UNAVAILABLE","reason","UNSEEN_PROVIDER_ID"),
                    preview("e0",30,Map.of())));
        }, 0);
        try {
            var result = client(server,1500).predictBatch(List.of(a,b,c,a), BusPreviewClient.Contract.PREVIEW);
            assertThat(calls).containsExactly(3);               // 중복 입력 a는 한 번만 보낸다
            assertThat(result).hasSize(3);
            assertThat(result.get(a).seconds()).isEqualTo(30.0);
            assertThat(result.get(b).seconds()).isNull(); assertThat(result.get(b).reason()).isEqualTo("UNSEEN_PROVIDER_ID");
            assertThat(result.get(c).seconds()).isEqualTo(50.0);
        } finally { server.stop(0); }
    }

    @Test void batchCountOrKeyMismatchInvalidatesTheWholeChunkAndChunksLargeBatches() throws Exception {
        var inputs = List.of(new BusPreviewClient.Input("100","200","300",1,today,9), new BusPreviewClient.Input("100","300","400",2,today,9),
                new BusPreviewClient.Input("200","1","2",1,today,10));
        var fewer = serve((raw, request) -> envelope(request,false,List.of(preview("e0",30,Map.of()),preview("e1",40,Map.of()))), 0);
        try {
            var result = client(fewer,1500).predictBatch(inputs, BusPreviewClient.Contract.PREVIEW);
            assertThat(result.values()).allMatch(e -> e.seconds()==null && "ML_INVALID_RESPONSE".equals(e.reason()));
        } finally { fewer.stop(0); }
        var duplicateKeys = serve((raw, request) -> envelope(request,false,List.of(preview("e0",30,Map.of()),preview("e0",40,Map.of()),preview("e2",50,Map.of()))), 0);
        try {
            assertThat(client(duplicateKeys,1500).predictBatch(inputs, BusPreviewClient.Contract.PREVIEW).values()).allMatch(e -> "ML_INVALID_RESPONSE".equals(e.reason()));
        } finally { duplicateKeys.stop(0); }
        // 배치 상한 2개: 3개 입력은 요청 두 번으로 나뉘고 키는 요청마다 e0부터 다시 시작한다.
        var sizes = new ArrayList<Integer>();
        var chunked = serve((raw, request) -> {
            sizes.add(request.path("segments").size());
            var results = new ArrayList<Map<String,Object>>();
            for (int i = 0; i < request.path("segments").size(); i++) results.add(preview("e"+i, 10+i, Map.of()));
            return envelope(request,false,results);
        }, 0);
        try {
            var small = new BusPreviewClient(json,"http://127.0.0.1:"+chunked.getAddress().getPort(),1500,2);
            var result = small.predictBatch(inputs, BusPreviewClient.Contract.PREVIEW);
            assertThat(sizes).containsExactly(2,1);
            assertThat(result.values()).allMatch(e -> e.seconds()!=null);
        } finally { chunked.stop(0); }
    }

    @Test void requestTimeoutIsReportedForEveryInputAndDistinctFromHttpFailure() throws Exception {
        var slow = serve((raw, request) -> envelope(request,false,List.of(preview("e0",30,Map.of()))), 700);
        try {
            var result = client(slow,200).predictBatch(List.of(input), BusPreviewClient.Contract.PREVIEW);
            assertThat(result.get(input).reason()).isEqualTo("ML_TIMEOUT");
        } finally { slow.stop(0); }
        assertThat(new BusPreviewClient(json,"",1500).predict(input).reason()).isEqualTo("ML_NOT_CONFIGURED");
    }

    @Test void mismatchedResponseAndHttpFailureAreNotPredictions() throws Exception {
        for (int status : List.of(200,503)) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/v1/bus/predictions:batch", exchange -> {
                byte[] body = "{\"request_id\":\"wrong-request\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,body.length); exchange.getResponseBody().write(body); exchange.close();
            }); server.start();
            try {
                var value = client(server,1500).predict(input);
                assertThat(value.seconds()).isNull();
                assertThat(value.reason()).isEqualTo(status==200?"ML_INVALID_RESPONSE":"ML_HTTP_UNAVAILABLE");
            } finally { server.stop(0); }
        }
    }

    @Test void slowButWithinTimeoutResponseSucceedsAndBoundsAreEnforced() throws Exception {
        // 운영 설정은 7초다. ml-serving 단독 응답이 약 4초라 4.5초 응답은 성공해야 한다.
        var slow = serve((raw, request) -> envelope(request,false,List.of(preview("e0",41.0,Map.of()))), 4500);
        try {
            var value = client(slow,7000).predict(input);
            assertThat(value.seconds()).isEqualTo(41.0);
            assertThat(value.reason()).isEqualTo("MODEL_NOT_PRODUCTION_READY");
        } finally { slow.stop(0); }
        // 설정 상한은 10초다. 무제한은 허용하지 않는다.
        assertThat(BusPreviewClient.MAX_TIMEOUT_MS).isEqualTo(10000);
        assertThatCode(() -> new BusPreviewClient(json, "http://127.0.0.1:1", BusPreviewClient.MAX_TIMEOUT_MS, 200)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new BusPreviewClient(json, "http://127.0.0.1:1", BusPreviewClient.MAX_TIMEOUT_MS + 1, 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusPreviewClient(json, "http://127.0.0.1:1", 0, 200))
                .isInstanceOf(IllegalArgumentException.class);
        // 설정한 제한을 평가 예산 판단에 그대로 노출한다.
        assertThat(new BusPreviewClient(json, "http://127.0.0.1:1", 7000, 200).timeoutMillis()).isEqualTo(7000L);
    }

    @Test void responseSlowerThanTheConfiguredTimeoutIsReportedAsTimeout() throws Exception {
        // 제한을 넘긴 응답은 수치로 쓰지 않고 시간초과로 남긴다(같은 배치의 모든 입력에 적용).
        var tooSlow = serve((raw, request) -> envelope(request,false,List.of(preview("e0",41.0,Map.of()))), 1200);
        try {
            var value = client(tooSlow,600).predict(input);
            assertThat(value.seconds()).isNull();
            assertThat(value.reason()).isEqualTo("ML_TIMEOUT");
            assertThat(value.applicationFailure()).isEqualTo("ML_TIMEOUT");
        } finally { tooSlow.stop(0); }
    }

    @Test void previewContractRejectsProductionReadyEnvelopeAndProductionContractIsCheckedSeparately() throws Exception {
        // PREVIEW 계약: production_ready=true는 계약 위반이다(승인된 모델을 미리보기 경로로 쓰지 않는다).
        var readyAsPreview = serve((raw, request) -> envelope(request,true,List.of(preview("e0",30,Map.of()))), 0);
        try { assertThat(client(readyAsPreview,1500).predict(input).reason()).isEqualTo("ML_INVALID_RESPONSE"); } finally { readyAsPreview.stop(0); }

        BiFunction<byte[], JsonNode, Object> production = (raw, request) -> {
            var response = envelope(request,true,List.of(Map.of("segment_key","e0","status","PRODUCTION","reason","PRODUCTION_CONTRACT",
                    "prediction",Map.of("mean_seconds",42.0,"missing_feature_count",0,"missing_features",List.of(),"history_sample_count",8,
                            "data_insufficient",false,"training_ids_verified",true))));
            response.put("feature_version","fv-2026-09"); response.put("features_as_of",Instant.now().minusSeconds(60).toString());
            response.put("valid_until",Instant.now().plusSeconds(3600).toString());
            response.put("input_digest",BusPreviewClient.sha256(new String(raw,StandardCharsets.UTF_8)));
            return response;
        };
        var productionServer = serve(production, 0);
        try {
            var asPreview = client(productionServer,1500).predictBatch(List.of(input), BusPreviewClient.Contract.PREVIEW).get(input);
            assertThat(asPreview.reason()).isEqualTo("ML_INVALID_RESPONSE");           // 미리보기 계약으로는 거절
            var asProduction = client(productionServer,1500).predictBatch(List.of(input), BusPreviewClient.Contract.PRODUCTION).get(input);
            assertThat(asProduction.seconds()).isEqualTo(42.0);
            assertThat(asProduction.contract()).isEqualTo(BusPreviewClient.Contract.PRODUCTION);
            assertThat(asProduction.trainingIdsVerified()).isTrue();
            assertThat(asProduction.productionEligible()).isTrue();
        } finally { productionServer.stop(0); }
        // 운영 계약인데 input_digest가 빠지면 통과시키지 않는다. 현재 PREVIEW 응답도 운영 계약으로는 거절된다.
        var missingDigest = serve((raw, request) -> { var r = (Map<String,Object>) production.apply(raw,request); r.remove("input_digest"); return r; }, 0);
        try { assertThat(client(missingDigest,1500).predictBatch(List.of(input), BusPreviewClient.Contract.PRODUCTION).get(input).reason()).isEqualTo("ML_INVALID_RESPONSE"); } finally { missingDigest.stop(0); }
        var previewAsProduction = serve((raw, request) -> envelope(request,false,List.of(preview("e0",30,Map.of()))), 0);
        try { assertThat(client(previewAsProduction,1500).predictBatch(List.of(input), BusPreviewClient.Contract.PRODUCTION).get(input).reason()).isEqualTo("ML_INVALID_RESPONSE"); } finally { previewAsProduction.stop(0); }
    }
}
